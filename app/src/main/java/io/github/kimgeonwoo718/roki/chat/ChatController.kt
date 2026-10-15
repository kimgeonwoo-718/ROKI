package io.github.kimgeonwoo718.roki.chat

import android.os.SystemClock
import com.google.ai.edge.litertlm.Message
import io.github.kimgeonwoo718.roki.llm.BackendKind
import io.github.kimgeonwoo718.roki.llm.ConversationSettings
import io.github.kimgeonwoo718.roki.llm.EngineState
import io.github.kimgeonwoo718.roki.llm.GenerationEvent
import io.github.kimgeonwoo718.roki.llm.LlmEngineManager
import io.github.kimgeonwoo718.roki.llm.LlmError
import io.github.kimgeonwoo718.roki.model.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TurnStatus { WAITING, STREAMING, DONE, STOPPED, ERROR }

data class ChatTurn(
    val id: Long,
    val prompt: String,
    val response: String = "",
    val status: TurnStatus = TurnStatus.WAITING,
    val error: LlmError? = null,
    val errorDetail: String? = null,
    val ttftMillis: Long? = null,
    /** Approximate: counts streamed chunks, which can undercount tokens for Korean text. */
    val tokensPerSecond: Double? = null,
    val backend: BackendKind? = null,
    /** The answer ended because the context (KV cache) was full, not because the model finished. */
    val truncated: Boolean = false,
) {
    val isActive: Boolean
        get() = status == TurnStatus.WAITING || status == TurnStatus.STREAMING
}

data class ChatState(val turns: List<ChatTurn> = emptyList()) {
    val isGenerating: Boolean
        get() = turns.lastOrNull()?.isActive == true
}

/**
 * Runs generations in the application scope so they survive rotation and leaving the screen.
 * The UI only observes [state].
 */
class ChatController(
    private val engine: LlmEngineManager,
    private val appScope: CoroutineScope,
    private val spec: ModelSpec,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var job: Job? = null
    private var nextId = 1L

    // Settings screen arrives in stage 4; until then use the model defaults.
    private val settings =
        ConversationSettings(
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            temperature = spec.defaultTemperature,
            topK = spec.defaultTopK,
            topP = spec.defaultTopP,
        )

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isGenerating) return
        val turnId = nextId++
        val backend = (engine.state.value as? EngineState.Ready)?.backend
        _state.update { it.copy(turns = it.turns + ChatTurn(id = turnId, prompt = prompt, backend = backend)) }
        job = appScope.launch { runTurn(turnId, prompt) }
    }

    private suspend fun runTurn(turnId: Long, prompt: String) {
        // Not enough room left in the live KV cache for this prompt plus an answer: rebuild trimmed
        // up front instead of letting the answer be cut off when the cache fills.
        engine.tokenCount()?.let { used ->
            if (used + estimateTokens(prompt) + OUTPUT_RESERVE_TOKENS > spec.maxNumTokens) engine.resetConversation()
        }
        // A context overflow before any output is retried once on a fresh, trimmed Conversation.
        var outcome = generateOnce(turnId, prompt)
        if (outcome is GenerationEvent.Error && outcome.error == LlmError.CONTEXT_FULL && !hasOutput(turnId)) {
            engine.resetConversation()
            outcome = generateOnce(turnId, prompt)
        }
        when (outcome) {
            GenerationEvent.Done -> afterDone(turnId)
            GenerationEvent.Cancelled -> updateTurn(turnId) { it.copy(status = TurnStatus.STOPPED) }
            is GenerationEvent.Error ->
                updateTurn(turnId) {
                    if (it.status == TurnStatus.STOPPED) it
                    else it.copy(status = TurnStatus.ERROR, error = outcome.error, errorDetail = outcome.detail)
                }
            // The stream ended without a terminal event: never leave the turn spinning.
            else -> updateTurn(turnId) {
                if (it.isActive) it.copy(status = TurnStatus.ERROR, error = LlmError.GENERATION_FAILED) else it
            }
        }
    }

    /** Streams one attempt into the turn and returns how it ended (Done, Cancelled or Error). */
    private suspend fun generateOnce(turnId: Long, prompt: String): GenerationEvent? {
        val history = historyMessages(prompt)
        val sentAt = SystemClock.elapsedRealtimeNanos()
        var firstChunkAt = 0L
        var chunkCount = 0
        var outcome: GenerationEvent? = null
        // No output cap yet (the max-length setting arrives in stage 4): an answer ends when the
        // model finishes or the KV cache fills, and the latter is flagged as truncated.
        engine.generate(prompt, settings, history, maxOutputTokens = null).collect { event ->
            when (event) {
                is GenerationEvent.Chunk -> {
                    if (firstChunkAt == 0L) firstChunkAt = SystemClock.elapsedRealtimeNanos()
                    chunkCount++
                    updateTurn(turnId) { turn ->
                        // A stopped turn ignores chunks that were already in flight.
                        if (!turn.isActive) turn
                        else turn.copy(
                            response = turn.response + event.text,
                            status = TurnStatus.STREAMING,
                            ttftMillis = turn.ttftMillis ?: ((firstChunkAt - sentAt) / 1_000_000),
                        )
                    }
                }
                GenerationEvent.Done -> {
                    val doneAt = SystemClock.elapsedRealtimeNanos()
                    val rate =
                        if (chunkCount > 1 && doneAt > firstChunkAt) (chunkCount - 1) / ((doneAt - firstChunkAt) / 1e9)
                        else null
                    updateTurn(turnId) { turn ->
                        if (!turn.isActive) turn else turn.copy(status = TurnStatus.DONE, tokensPerSecond = rate)
                    }
                    outcome = event
                }
                else -> outcome = event
            }
        }
        return outcome
    }

    /** Detects a silently cut-off answer and starts the next turn on a trimmed Conversation if needed. */
    private suspend fun afterDone(turnId: Long) {
        val used = engine.tokenCount() ?: return
        if (used >= spec.maxNumTokens - 1) updateTurn(turnId) { it.copy(truncated = true) }
        if (used >= spec.maxNumTokens * CONTEXT_FILL_RATIO) engine.resetConversation()
    }

    /** Stops generation immediately. The partial answer stays on screen and in the context. */
    fun stop() {
        val last = _state.value.turns.lastOrNull() ?: return
        if (!last.isActive) return
        updateTurn(last.id) { it.copy(status = TurnStatus.STOPPED) }
        job?.cancel()
        engine.stop()
    }

    fun newChat() {
        stop()
        job?.cancel()
        job = null
        _state.value = ChatState()
        engine.resetConversation()
    }

    /**
     * Earlier turns replayed into a rebuilt Conversation (after a stop, an error or a reset),
     * newest first until the token budget runs out. The system prompt is always kept separately.
     */
    private fun historyMessages(prompt: String): List<Message> {
        var budget =
            (spec.maxNumTokens * CONTEXT_FILL_RATIO).toInt() - OUTPUT_RESERVE_TOKENS -
                estimateTokens(DEFAULT_SYSTEM_PROMPT) - estimateTokens(prompt)
        val kept = ArrayDeque<ChatTurn>()
        val completed =
            _state.value.turns.filter {
                (it.status == TurnStatus.DONE || it.status == TurnStatus.STOPPED) && it.response.isNotBlank()
            }
        for (turn in completed.asReversed()) {
            val cost = estimateTokens(turn.prompt) + estimateTokens(turn.response)
            if (cost > budget) break
            budget -= cost
            kept.addFirst(turn)
        }
        return kept.flatMap { listOf(Message.user(it.prompt), Message.model(it.response)) }
    }

    private fun hasOutput(turnId: Long): Boolean =
        _state.value.turns.firstOrNull { it.id == turnId }?.response?.isNotEmpty() == true

    private fun updateTurn(id: Long, transform: (ChatTurn) -> ChatTurn) {
        _state.update { state -> state.copy(turns = state.turns.map { if (it.id == id) transform(it) else it }) }
    }

    companion object {
        /** Room kept free for an answer when deciding how much history fits. */
        const val OUTPUT_RESERVE_TOKENS = 1024

        /** Rebuild the Conversation once the KV cache passes this share (same ratio as AI Edge Gallery). */
        const val CONTEXT_FILL_RATIO = 0.75

        const val DEFAULT_SYSTEM_PROMPT =
            "당신은 ROKI입니다. 사용자의 휴대폰 안에서 인터넷 없이 동작하는 친절한 AI 어시스턴트입니다. " +
                "사용자가 한국어로 말하면 자연스러운 한국어로 답하세요."

        /** Deliberately pessimistic (about one token per character) since there is no tokenizer API. */
        private fun estimateTokens(text: String): Int = text.length + 8
    }
}
