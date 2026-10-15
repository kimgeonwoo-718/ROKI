package io.github.kimgeonwoo718.roki.llm

enum class BackendKind { GPU, CPU }

/** User-facing failure reasons. Each maps to a Korean message in strings.xml. */
enum class LlmError {
    MODEL_MISSING,
    MODEL_UNREADABLE,
    MODEL_INCOMPLETE,
    MODEL_CORRUPT,
    NATIVE_LIBRARY,
    OUT_OF_MEMORY,
    LOAD_FAILED,
    GENERATION_FAILED,
    CONTEXT_FULL,
}

/** A notice shown alongside a working engine, e.g. why GPU was skipped. */
enum class EngineNotice {
    /** The process died during GPU initialization last time, so GPU is disabled. */
    GPU_DISABLED_AFTER_CRASH,

    /** The process died while loading the model last time (likely out of memory). */
    PREVIOUS_LOAD_CRASHED,

    /** GPU initialization threw an error and the engine fell back to CPU. */
    GPU_FAILED_FELL_BACK,
}

sealed interface EngineState {
    data object Idle : EngineState

    data class Loading(val backend: BackendKind) : EngineState

    data class Ready(
        val backend: BackendKind,
        val loadMillis: Long,
        val notice: EngineNotice? = null,
    ) : EngineState

    data class Failed(val error: LlmError, val detail: String?, val notice: EngineNotice? = null) :
        EngineState
}

/** Streaming events for one model response. */
sealed interface GenerationEvent {
    /** A piece of the model's reasoning (thinking), streamed before the answer. */
    data class Thought(val text: String) : GenerationEvent

    /** A piece of the answer. */
    data class Chunk(val text: String) : GenerationEvent

    data object Done : GenerationEvent

    data object Cancelled : GenerationEvent

    data class Error(val error: LlmError, val detail: String?) : GenerationEvent
}
