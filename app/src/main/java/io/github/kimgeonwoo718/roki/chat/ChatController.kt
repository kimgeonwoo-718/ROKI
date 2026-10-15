package io.github.kimgeonwoo718.roki.chat

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.kimgeonwoo718.roki.llm.BackendKind
import io.github.kimgeonwoo718.roki.llm.ConversationSettings
import io.github.kimgeonwoo718.roki.llm.EngineState
import io.github.kimgeonwoo718.roki.llm.GenerationEvent
import io.github.kimgeonwoo718.roki.llm.LlmEngineManager
import io.github.kimgeonwoo718.roki.llm.LlmError
import io.github.kimgeonwoo718.roki.llm.ReasoningLevel
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
    /** The model's reasoning for this turn (shown collapsible; never replayed into the context). */
    val thinking: String = "",
    val status: TurnStatus = TurnStatus.WAITING,
    val error: LlmError? = null,
    val errorDetail: String? = null,
    /** Time from sending to the first streamed token (thought or answer). */
    val ttftMillis: Long? = null,
    val thinkingMillis: Long? = null,
    /** Approximate: counts streamed chunks, which can undercount tokens for Korean text. */
    val tokensPerSecond: Double? = null,
    val backend: BackendKind? = null,
    val reasoningLevel: ReasoningLevel? = null,
    /** The answer ended because the context (KV cache) was full, not because the model finished. */
    val truncated: Boolean = false,
    /** The answer contains letters from scripts the prompt did not use (e.g. stray Japanese/Arabic). */
    val garbled: Boolean = false,
    internal val thinkingStartedAt: Long? = null,
    internal val thoughtChunks: Int = 0,
) {
    val isActive: Boolean
        get() = status == TurnStatus.WAITING || status == TurnStatus.STREAMING

    val isThinking: Boolean
        get() = isActive && response.isEmpty() && thinking.isNotEmpty()
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
    private val prefs: SharedPreferences,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _reasoningLevel = MutableStateFlow(ReasoningLevel.fromName(prefs.getString(KEY_REASONING, null)))
    val reasoningLevel: StateFlow<ReasoningLevel> = _reasoningLevel.asStateFlow()

    private var job: Job? = null
    private var nextId = 1L

    /** Thought chunks of the last finished turn: still in the KV cache until the next send strips them. */
    private var lastTurnThoughtChunks = 0

    // Settings screen arrives in stage 4; until then use the model defaults.
    private val settings =
        ConversationSettings(
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            temperature = spec.defaultTemperature,
            topK = spec.defaultTopK,
            topP = spec.defaultTopP,
            thinkingEnabled = spec.supportsThinking,
        )

    /** Takes effect from the next message; no Conversation rebuild is needed. */
    fun setReasoningLevel(level: ReasoningLevel) {
        _reasoningLevel.value = level
        prefs.edit().putString(KEY_REASONING, level.name).apply()
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isGenerating) return
        val turnId = nextId++
        val backend = (engine.state.value as? EngineState.Ready)?.backend
        val level = if (spec.supportsThinking) _reasoningLevel.value else null
        _state.update {
            it.copy(turns = it.turns + ChatTurn(id = turnId, prompt = prompt, backend = backend, reasoningLevel = level))
        }
        job = appScope.launch { runTurn(turnId, prompt, level) }
    }

    private suspend fun runTurn(turnId: Long, prompt: String, level: ReasoningLevel?) {
        // Not enough room left in the live KV cache for this prompt plus thinking and an answer:
        // rebuild trimmed up front instead of letting the answer be cut off when the cache fills.
        engine.tokenCount()?.let { used ->
            val effective = used - lastTurnThoughtChunks
            if (effective + estimateTokens(prompt) + reserveTokens(level) > spec.maxNumTokens) {
                engine.resetConversation()
            }
        }
        // A context overflow before any output is retried once on a fresh, trimmed Conversation.
        var outcome = generateOnce(turnId, prompt, level)
        if (outcome is GenerationEvent.Error && outcome.error == LlmError.CONTEXT_FULL && !hasOutput(turnId)) {
            engine.resetConversation()
            updateTurn(turnId) { it.copy(thinking = "", thinkingStartedAt = null, thoughtChunks = 0, ttftMillis = null) }
            outcome = generateOnce(turnId, prompt, level)
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
    private suspend fun generateOnce(turnId: Long, prompt: String, level: ReasoningLevel?): GenerationEvent? {
        val history = historyMessages(prompt, level)
        val sentAt = SystemClock.elapsedRealtimeNanos()
        var firstChunkAt = 0L
        var chunkCount = 0
        var outcome: GenerationEvent? = null
        val thinking = level?.let { thinkingFor(it, prompt) }
        // No answer-length cap yet (the setting arrives in stage 4): an answer ends when the model
        // finishes or the KV cache fills, and the latter is flagged as truncated.
        engine.generate(prompt, settings, history, maxOutputTokens = null, thinking = thinking).collect { event ->
            when (event) {
                is GenerationEvent.Thought, is GenerationEvent.Chunk -> {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (firstChunkAt == 0L) firstChunkAt = now
                    chunkCount++
                    updateTurn(turnId) { turn ->
                        // A stopped turn ignores chunks that were already in flight.
                        if (!turn.isActive) return@updateTurn turn
                        val ttft = turn.ttftMillis ?: ((firstChunkAt - sentAt) / 1_000_000)
                        if (event is GenerationEvent.Thought) {
                            turn.copy(
                                thinking = turn.thinking + event.text,
                                thinkingStartedAt = turn.thinkingStartedAt ?: now,
                                thoughtChunks = turn.thoughtChunks + 1,
                                status = TurnStatus.STREAMING,
                                ttftMillis = ttft,
                            )
                        } else {
                            turn.copy(
                                response = turn.response + (event as GenerationEvent.Chunk).text,
                                status = TurnStatus.STREAMING,
                                ttftMillis = ttft,
                                thinkingMillis = turn.thinkingMillis
                                    ?: turn.thinkingStartedAt?.let { (now - it) / 1_000_000 },
                            )
                        }
                    }
                }
                GenerationEvent.Done -> {
                    val doneAt = SystemClock.elapsedRealtimeNanos()
                    val rate =
                        if (chunkCount > 1 && doneAt > firstChunkAt) (chunkCount - 1) / ((doneAt - firstChunkAt) / 1e9)
                        else null
                    updateTurn(turnId) { turn ->
                        if (!turn.isActive) turn
                        else turn.copy(
                            status = TurnStatus.DONE,
                            tokensPerSecond = rate,
                            thinkingMillis = turn.thinkingMillis
                                ?: turn.thinkingStartedAt?.let { (doneAt - it) / 1_000_000 },
                        )
                    }
                    outcome = event
                }
                else -> outcome = event
            }
        }
        return outcome
    }

    /**
     * Flags a cut-off or garbled answer, and starts the next turn on a trimmed Conversation when the
     * KV cache is filling up or holds garbled text.
     */
    private suspend fun afterDone(turnId: Long) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        lastTurnThoughtChunks = turn.thoughtChunks
        val garbled = looksGarbled(turn)
        if (garbled) {
            Log.w(TAG, "Garbled answer on ${turn.backend}: foreign letters in the response")
            updateTurn(turnId) { it.copy(garbled = true) }
            // Do not let the broken text steer later answers.
            engine.resetConversation()
        }
        val used = engine.tokenCount() ?: return
        // Thinking used up the room (or the cap) before any answer came out.
        val noAnswer = turn.response.isBlank() && turn.thinking.isNotBlank()
        if (used >= spec.maxNumTokens - 1 || noAnswer) updateTurn(turnId) { it.copy(truncated = true) }
        if (!garbled && used - turn.thoughtChunks >= spec.maxNumTokens * CONTEXT_FILL_RATIO) engine.resetConversation()
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
        lastTurnThoughtChunks = 0
        _state.value = ChatState()
        engine.resetConversation()
    }

    /**
     * Earlier turns replayed into a rebuilt Conversation (after a stop, an error or a reset),
     * newest first until the token budget runs out. Only answers are replayed (never thoughts,
     * as the Gemma 4 model card requires), and garbled answers are left out.
     */
    private fun historyMessages(prompt: String, level: ReasoningLevel?): List<Message> {
        // Below both reset thresholds (75% after a turn, reserve before a send) with an answer's
        // worth of margin, so a rebuilt Conversation does not trigger another reset right away.
        var budget =
            minOf((spec.maxNumTokens * CONTEXT_FILL_RATIO).toInt(), spec.maxNumTokens - reserveTokens(level)) -
                OUTPUT_RESERVE_TOKENS - estimateTokens(DEFAULT_SYSTEM_PROMPT) - estimateTokens(prompt)
        val kept = ArrayDeque<ChatTurn>()
        val completed =
            _state.value.turns.filter {
                (it.status == TurnStatus.DONE || it.status == TurnStatus.STOPPED) &&
                    it.response.isNotBlank() && !it.garbled
            }
        for (turn in completed.asReversed()) {
            val cost = estimateTokens(turn.prompt) + estimateTokens(turn.response)
            if (cost > budget) break
            budget -= cost
            kept.addFirst(turn)
        }
        return kept.flatMap { listOf(Message.user(it.prompt), Message.model(it.response)) }
    }

    /**
     * The level's thinking budget, capped by the room a very long prompt leaves, so thinking can
     * never fill the KV cache and leave no answer. Never 0 (that means "uncapped" in 0.18.0).
     */
    private fun thinkingFor(level: ReasoningLevel, prompt: String): ThinkingConfig {
        val room = spec.maxNumTokens - estimateTokens(DEFAULT_SYSTEM_PROMPT) - estimateTokens(prompt) -
            OUTPUT_RESERVE_TOKENS - 16
        val budget = level.budget(spec.maxNumTokens).coerceAtMost(room).coerceAtLeast(MIN_THINKING_BUDGET)
        return ThinkingConfig(enableThinking = true, thinkingTokenBudget = budget)
    }

    /** Room kept free for this turn's thinking and answer. */
    private fun reserveTokens(level: ReasoningLevel?): Int =
        OUTPUT_RESERVE_TOKENS + (level?.reserveTokens(spec.maxNumTokens) ?: 0)

    private fun hasOutput(turnId: Long): Boolean =
        _state.value.turns.firstOrNull { it.id == turnId }?.let { it.response.isNotEmpty() || it.thinking.isNotEmpty() } == true

    private fun updateTurn(id: Long, transform: (ChatTurn) -> ChatTurn) {
        _state.update { state -> state.copy(turns = state.turns.map { if (it.id == id) transform(it) else it }) }
    }

    companion object {
        private const val TAG = "ChatController"
        private const val KEY_REASONING = "reasoning_level"

        /** Room kept free for an answer when deciding how much history fits. */
        const val OUTPUT_RESERVE_TOKENS = 1024

        private const val MIN_THINKING_BUDGET = 128

        /** Rebuild the Conversation once the KV cache passes this share (same ratio as AI Edge Gallery). */
        const val CONTEXT_FILL_RATIO = 0.75

        // Positive wording on purpose: naming the unwanted languages can prime a small model.
        const val DEFAULT_SYSTEM_PROMPT =
            "당신은 ROKI입니다. 사용자의 휴대폰 안에서 인터넷 없이 동작하는 친절한 AI 어시스턴트입니다. " +
                "모든 답변은 자연스러운 한국어로 작성하세요. 사용자가 다른 언어로 답해 달라고 직접 요청할 때만 그 언어를 쓰세요. " +
                "코드, 고유명사, 널리 쓰이는 영어 약어는 원래 표기대로 써도 됩니다."

        /** Deliberately pessimistic (about one token per character) since there is no tokenizer API. */
        private fun estimateTokens(text: String): Int = text.length + 8

        // Greek is allowed for math (α, θ, π). Han stays "foreign" except inside a short gloss.
        private val ALLOWED_SCRIPTS =
            setOf(
                Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.LATIN,
                Character.UnicodeScript.COMMON,
                Character.UnicodeScript.INHERITED,
                Character.UnicodeScript.GREEK,
            )

        /** Prompts that legitimately ask for other scripts (translation, Hanja, idioms, "~어로"). */
        private val FOREIGN_REQUEST =
            Regex(
                "번역|통역|외국어|한자|한문|성어|일본어|중국어|러시아어|아랍어|태국어|힌디어|히브리어|그리스어|베트남어|[가-힣]{1,4}어로|translat",
                RegexOption.IGNORE_CASE,
            )

        /** A short parenthesised gloss such as 고진감래(苦盡甘來) or 学校(がっこう). */
        private val GLOSS = Regex("[(（][^)）]{1,20}[)）]")

        private fun foreignLetters(text: String): Int {
            var count = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) !in ALLOWED_SCRIPTS) count++
                i += Character.charCount(cp)
            }
            return count
        }

        /**
         * Kana/Arabic/Cyrillic/Han/... letters in an answer to a prompt that neither used nor asked
         * for another script. Glosses in parentheses are ignored.
         */
        private fun looksGarbled(turn: ChatTurn): Boolean {
            if (foreignLetters(turn.prompt) > 0 || FOREIGN_REQUEST.containsMatchIn(turn.prompt)) return false
            return foreignLetters(turn.response.replace(GLOSS, "")) >= 3
        }
    }
}
