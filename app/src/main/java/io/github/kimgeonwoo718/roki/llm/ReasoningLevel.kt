package io.github.kimgeonwoo718.roki.llm

/**
 * How long the model may think before answering. Gemma 4 thinking itself is ON/OFF; a level only
 * caps the thinking length: at the cap LiteRT-LM 0.18.0 forces the end-of-thought marker and the
 * answer follows (thinking_budget_constraint.cc). Budget 0 must never be used as "off" in 0.18.0
 * (it removes the cap instead), so every level is a positive budget.
 */
enum class ReasoningLevel(private val budget: Int) {
    LOW(512),
    MEDIUM(1024),
    HIGH(2048),
    VERY_HIGH(4096),

    /** Finite on purpose: an unlimited budget can loop until the KV cache fills, leaving no answer. */
    MAX(8192);

    /** Clamped so thoughts can never take more than half of the KV cache. */
    fun budget(maxNumTokens: Int): Int = minOf(budget, maxNumTokens / 2)

    /** Worst-case KV used by one turn's thoughts, including the channel delimiters. */
    fun reserveTokens(maxNumTokens: Int): Int = budget(maxNumTokens) + 16

    companion object {
        val DEFAULT = MEDIUM

        fun fromName(name: String?): ReasoningLevel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
