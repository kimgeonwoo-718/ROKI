package io.github.kimgeonwoo718.roki.chat

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.kimgeonwoo718.roki.data.db.ConversationEntity
import io.github.kimgeonwoo718.roki.llm.BackendKind
import io.github.kimgeonwoo718.roki.llm.ConversationSettings
import io.github.kimgeonwoo718.roki.llm.EngineState
import io.github.kimgeonwoo718.roki.llm.GenerationEvent
import io.github.kimgeonwoo718.roki.llm.LlmEngineManager
import io.github.kimgeonwoo718.roki.llm.LlmError
import io.github.kimgeonwoo718.roki.llm.ReasoningLevel
import io.github.kimgeonwoo718.roki.model.ModelSpec
import io.github.kimgeonwoo718.roki.settings.AnswerLength
import io.github.kimgeonwoo718.roki.settings.AppSettings
import io.github.kimgeonwoo718.roki.settings.Personalization
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
    val createdAt: Long = System.currentTimeMillis(),
    internal val thinkingStartedAt: Long? = null,
    internal val thoughtChunks: Int = 0,
) {
    val isActive: Boolean
        get() = status == TurnStatus.WAITING || status == TurnStatus.STREAMING

    val isThinking: Boolean
        get() = isActive && response.isEmpty() && thinking.isNotEmpty()
}

data class ChatState(
    val turns: List<ChatTurn> = emptyList(),
    /** The archived conversation shown on screen; null for a new chat not saved yet. */
    val conversationId: Long? = null,
) {
    val isGenerating: Boolean
        get() = turns.lastOrNull()?.isActive == true
}

/**
 * Runs generations in the application scope so they survive rotation and leaving the screen,
 * and keeps the chat archive (Room) in sync. The UI only observes [state].
 */
class ChatController(
    private val engine: LlmEngineManager,
    private val appScope: CoroutineScope,
    private val spec: ModelSpec,
    private val prefs: SharedPreferences,
    private val repository: ChatRepository,
    private val personalization: StateFlow<Personalization>,
    private val appSettings: StateFlow<AppSettings>,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    val conversations: Flow<List<ConversationEntity>> = repository.conversations

    private val _reasoningLevel = MutableStateFlow(ReasoningLevel.fromName(prefs.getString(KEY_REASONING, null)))
    val reasoningLevel: StateFlow<ReasoningLevel> = _reasoningLevel.asStateFlow()

    private var job: Job? = null
    private var idCounter = 0L

    /** When each streaming turn was last written to the database (throttles saves). */
    private val lastSavedAt = ConcurrentHashMap<Long, Long>()

    /** Thought chunks of the last finished turn: still in the KV cache until the next send strips them. */
    private var lastTurnThoughtChunks = 0

    /**
     * Conversation settings for a reasoning level (sampling uses the model defaults until the
     * stage-4 settings screen). Only thinking on/off is part of the rebuild key: switching between
     * the thinking levels reuses the live KV cache, while switching to or from LOW rebuilds it.
     */
    private fun settingsFor(level: ReasoningLevel?) =
        ConversationSettings(
            systemPrompt = systemPrompt(),
            temperature = appSettings.value.temperature ?: spec.defaultTemperature,
            topK = spec.defaultTopK,
            topP = spec.defaultTopP,
            thinkingEnabled = spec.supportsThinking && level?.thinks == true,
        )

    /**
     * The text actually sent: the level's effort instruction in front of the user's prompt.
     * Sent per message (not in the system prompt) so changing the level never forces a full
     * re-prefill of the history. ChatTurn.prompt and replayed history keep the raw prompt.
     */
    private fun outgoing(level: ReasoningLevel?, prompt: String): String {
        val effort = level?.takeIf { it.thinks && spec.supportsThinking }?.effortInstruction
        // A short persona reminder per message: replayed earlier answers in the old style would
        // otherwise outweigh a changed system prompt for a small model.
        val tags = listOfNotNull(effort, personalization.value.reminder().ifBlank { null }).joinToString(" / ")
        return if (tags.isEmpty()) prompt else "[$tags]\n\n$prompt"
    }

    /** Room kept free for the answer, sized to the chosen 답변길이. */
    private fun answerReserve(): Int =
        minOf(
            when (personalization.value.answerLength) {
                AnswerLength.SHORT -> 512
                AnswerLength.MEDIUM -> 1024
                AnswerLength.LONG -> 3072
            },
            spec.maxNumTokens / 4,
        )

    /**
     * The base prompt plus the user's persona choices (사용자 맞춤 설정). It is part of the rebuild
     * key, so a changed choice rebuilds the Conversation (keeping the history) on the next send.
     */
    private fun systemPrompt(): String {
        // 설정 화면에서 바꾼 기본 지시가 있으면 그것을 쓰고, 성격·말투 등은 항상 덧붙인다.
        val base = appSettings.value.customSystemPrompt.take(AppSettings.MAX_SYSTEM_PROMPT_CHARS).ifBlank { DEFAULT_SYSTEM_PROMPT }
        return "$base\n\n${personalization.value.systemInstructions()}"
    }

    /** Takes effect from the next message. */
    fun setReasoningLevel(level: ReasoningLevel) {
        _reasoningLevel.value = level
        prefs.edit().putString(KEY_REASONING, level.name).apply()
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isGenerating) return
        regenPrevious = null
        // The first message of a new chat creates its archive entry, titled after that message.
        val conversationId = _state.value.conversationId ?: newId().also { id ->
            repository.createConversation(id, titleFor(prompt), System.currentTimeMillis())
            _state.update { it.copy(conversationId = id) }
        }
        val turnId = newId()
        val backend = (engine.state.value as? EngineState.Ready)?.backend
        val level = if (spec.supportsThinking) _reasoningLevel.value else null
        val turn = ChatTurn(id = turnId, prompt = prompt, backend = backend, reasoningLevel = level)
        _state.update { it.copy(turns = it.turns + turn) }
        repository.saveTurn(conversationId, turn, touch = true)
        lastSavedAt[turnId] = SystemClock.elapsedRealtime()
        job = appScope.launch { runTurn(turnId, prompt, level) }
    }

    /** Shows an archived chat; the model gets its history when the next message is sent. */
    fun openConversation(id: Long) {
        if (_state.value.conversationId == id) return
        stop()
        job?.cancel()
        job = null
        appScope.launch {
            val turns =
                try {
                    repository.loadTurns(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Loading chat $id failed", e)
                    return@launch
                }
            _state.value = ChatState(turns = turns, conversationId = id)
            lastTurnThoughtChunks = 0
            engine.resetConversation()
        }
    }

    fun renameConversation(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) repository.rename(id, trimmed.take(MAX_TITLE_LENGTH))
    }

    fun deleteConversation(id: Long) {
        if (_state.value.conversationId == id) newChat()
        repository.delete(id)
    }

    /** Time-based ids so the archive can be written asynchronously without waiting for row ids. */
    private fun newId(): Long = System.currentTimeMillis() * 1000 + (idCounter++ % 1000)

    private suspend fun runTurn(turnId: Long, prompt: String, level: ReasoningLevel?) {
        // Not enough room left in the live KV cache for this prompt plus thinking and an answer:
        // rebuild trimmed up front instead of letting the answer be cut off when the cache fills.
        engine.tokenCount()?.let { used ->
            val effective = used - lastTurnThoughtChunks
            if (effective + estimateTokens(outgoing(level, prompt)) + reserveTokens(level) > spec.maxNumTokens) {
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
            GenerationEvent.Cancelled -> updateTurn(turnId) {
                if (it.isActive) it.copy(status = TurnStatus.STOPPED).withThinkingTime() else it
            }
            is GenerationEvent.Error ->
                updateTurn(turnId) {
                    if (it.status == TurnStatus.STOPPED) it
                    else it.copy(status = TurnStatus.ERROR, error = outcome.error, errorDetail = outcome.detail)
                        .withThinkingTime()
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
        val settings = settingsFor(level)
        val sent = outgoing(level, prompt)
        val thinking = level?.takeIf { settings.thinkingEnabled }?.let { thinkingFor(it, sent) }
        // No hard output cap (답변길이 in 사용자 맞춤 설정 guides the length): an answer ends when
        // the model finishes or the KV cache fills, and the latter is flagged as truncated.
        engine.generate(sent, settings, history, maxOutputTokens = null, thinking = thinking).collect { event ->
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
        // Stopping a regeneration before it wrote anything brings the previous answer back.
        if (!restorePreviousIfEmpty(last.id)) {
            updateTurn(last.id) { it.copy(status = TurnStatus.STOPPED).withThinkingTime() }
        }
        job?.cancel()
        engine.stop()
    }

    /** Stamps how long the model thought when a turn ends without reaching the answer. */
    private fun ChatTurn.withThinkingTime(): ChatTurn {
        val started = thinkingStartedAt ?: return this
        if (thinkingMillis != null) return this
        return copy(thinkingMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
    }

    /**
     * 다시 생성: replaces the last answer with a new one for the same question. The Conversation is
     * rebuilt without that answer (no rollback API in LiteRT-LM 0.18.0) and a new random seed is
     * used, so the answer usually differs (not with 빠른 생성, which decodes greedily).
     */
    fun regenerate() {
        // Only with a working model: otherwise the old answer would be replaced by an error.
        val ready = engine.state.value as? EngineState.Ready ?: return
        val last = _state.value.turns.lastOrNull() ?: return
        if (last.isActive) return
        val level = if (spec.supportsThinking) _reasoningLevel.value else null
        regenPrevious = last
        updateTurn(last.id) {
            ChatTurn(id = it.id, prompt = it.prompt, backend = ready.backend, reasoningLevel = level, createdAt = it.createdAt)
        }
        lastTurnThoughtChunks = 0
        engine.resetConversation()
        job = appScope.launch {
            runTurn(last.id, last.prompt, level)
            // Failed, or no answer text (only thoughts): keep the previous answer instead.
            restorePreviousIfEmpty(last.id)
        }
    }

    /** The answer being regenerated, restored if the new attempt produces nothing. */
    @Volatile private var regenPrevious: ChatTurn? = null

    private fun restorePreviousIfEmpty(turnId: Long): Boolean {
        val previous = regenPrevious?.takeIf { it.id == turnId } ?: return false
        val current = _state.value.turns.firstOrNull { it.id == turnId } ?: return false
        if (current.response.isNotEmpty()) {
            if (!current.isActive) regenPrevious = null
            return false
        }
        regenPrevious = null
        updateTurn(turnId) { previous }
        engine.resetConversation()
        return true
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
                answerReserve() - estimateTokens(systemPrompt()) - estimateTokens(outgoing(level, prompt))
        val completed =
            _state.value.turns.filter {
                (it.status == TurnStatus.DONE || it.status == TurnStatus.STOPPED) &&
                    it.response.isNotBlank() && !it.garbled
            }
        // Hidden example exchanges that show the chosen political stance, replayed first when they
        // fit, but never at the cost of the newest real exchange.
        val examples = personalization.value.stanceExamples()
        val exampleCost = examples.sumOf { (q, a) -> estimateTokens(q) + estimateTokens(a) }
        val newestCost = completed.lastOrNull()?.let { estimateTokens(it.prompt) + estimateTokens(it.response) } ?: 0
        val useExamples =
            examples.isNotEmpty() && budget - exampleCost >= (if (newestCost <= budget) newestCost else 0)
        if (useExamples) budget -= exampleCost
        val kept = ArrayDeque<ChatTurn>()
        for (turn in completed.asReversed()) {
            val cost = estimateTokens(turn.prompt) + estimateTokens(turn.response)
            if (cost > budget) break
            budget -= cost
            kept.addFirst(turn)
        }
        val exampleMessages =
            if (useExamples) examples.flatMap { (q, a) -> listOf(Message.user(q), Message.model(a)) } else emptyList()
        return exampleMessages + kept.flatMap { listOf(Message.user(it.prompt), Message.model(it.response)) }
    }

    /**
     * The level's thinking budget, capped by the room a very long prompt leaves, so thinking can
     * never fill the KV cache and leave no answer. Never 0 (that means "uncapped" in 0.18.0).
     */
    private fun thinkingFor(level: ReasoningLevel, sent: String): ThinkingConfig {
        val room = spec.maxNumTokens - estimateTokens(systemPrompt()) - estimateTokens(sent) -
            answerReserve() - 16
        val budget = level.budget(spec.maxNumTokens).coerceAtMost(room).coerceAtLeast(MIN_THINKING_BUDGET)
        return ThinkingConfig(enableThinking = true, thinkingTokenBudget = budget)
    }

    /** Room kept free for this turn's thinking and answer. */
    private fun reserveTokens(level: ReasoningLevel?): Int =
        answerReserve() + (level?.reserveTokens(spec.maxNumTokens) ?: 0)

    private fun hasOutput(turnId: Long): Boolean =
        _state.value.turns.firstOrNull { it.id == turnId }?.let { it.response.isNotEmpty() || it.thinking.isNotEmpty() } == true

    /**
     * Updates a turn on screen and in the archive: at most once per second while it streams, and
     * always once it finishes.
     */
    private fun updateTurn(id: Long, transform: (ChatTurn) -> ChatTurn) {
        var updated: ChatTurn? = null
        val state = _state.updateAndGet { state ->
            // Only a turn that actually changed is saved (ignored post-stop chunks change nothing).
            state.copy(turns = state.turns.map { if (it.id == id) transform(it).also { t -> if (t != it) updated = t } else it })
        }
        val turn = updated ?: return
        val conversationId = state.conversationId ?: return
        val now = SystemClock.elapsedRealtime()
        if (!turn.isActive || now - (lastSavedAt[id] ?: 0L) >= SAVE_INTERVAL_MS) {
            lastSavedAt[id] = now
            if (!turn.isActive) lastSavedAt.remove(id)
            repository.saveTurn(conversationId, turn, touch = !turn.isActive)
        }
    }

    companion object {
        private const val TAG = "ChatController"
        private const val KEY_REASONING = "reasoning_level"


        private const val MIN_THINKING_BUDGET = 128

        /** Rebuild the Conversation once the KV cache passes this share (same ratio as AI Edge Gallery). */
        const val CONTEXT_FILL_RATIO = 0.75

        // Positive wording on purpose: naming the unwanted languages can prime a small model.
        const val DEFAULT_SYSTEM_PROMPT =
            "당신은 ROKI입니다. 사용자의 휴대폰 안에서 인터넷 없이 동작하는 AI 어시스턴트입니다. " +
                "모든 답변은 자연스러운 한국어로 작성하세요. 사용자가 다른 언어로 답해 달라고 직접 요청할 때만 그 언어를 쓰세요. " +
                "코드, 고유명사, 널리 쓰이는 영어 약어는 원래 표기대로 써도 됩니다."

        /** Deliberately pessimistic (about one token per character) since there is no tokenizer API. */
        private fun estimateTokens(text: String): Int = text.length + 8

        private const val SAVE_INTERVAL_MS = 1000L
        private const val MAX_TITLE_LENGTH = 40
        private const val AUTO_TITLE_LENGTH = 30

        /** "첫 질문" as the chat title: its first line, whitespace collapsed, cut at 30 characters. */
        private fun titleFor(prompt: String): String {
            val firstLine = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val collapsed = firstLine.trim().replace(Regex("\\s+"), " ")
            return if (collapsed.length > AUTO_TITLE_LENGTH) collapsed.take(AUTO_TITLE_LENGTH) + "…" else collapsed
        }

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
