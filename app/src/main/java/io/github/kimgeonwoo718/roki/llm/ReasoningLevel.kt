package io.github.kimgeonwoo718.roki.llm

/**
 * How much the model reasons before answering.
 *
 * The only hard levers in LiteRT-LM 0.18.0 are thinking ON/OFF (fixed per Conversation) and a
 * per-message budget that only CAPS thinking (at the cap the runtime forces the end-of-thought
 * marker; there is no minimum). So:
 * - LOW turns thinking off (instant answers, like a plain chat model);
 * - MEDIUM uses a cap low enough to actually bind, plus a "think briefly" instruction;
 * - HIGH is the model's own natural thinking;
 * - VERY_HIGH and MAX add a system-prompt [effortInstruction] asking for step-by-step work and
 *   verification, with a large cap only as a safety limit.
 * The instructions are heuristics (no Gemma-specific data); measure on device before tuning.
 * Budget 0 must never be used with thinking ON (in 0.18.0 it removes the cap).
 */
enum class ReasoningLevel(val thinks: Boolean, private val budget: Int, val effortInstruction: String?) {
    LOW(thinks = false, budget = 0, effortInstruction = null),
    MEDIUM(
        thinks = true,
        budget = 256,
        effortInstruction = "생각은 짧게 하세요. 질문에 무엇이 필요한지만 파악하고 바로 답하세요. 간단한 사실은 다시 확인하지 마세요.",
    ),
    HIGH(thinks = true, budget = 1024, effortInstruction = null),
    VERY_HIGH(
        thinks = true,
        budget = 4096,
        effortInstruction = "답하기 전에 단계별로 차근차근 풀고, 질문을 다르게 해석할 여지가 있는지 살피고, " +
            "생각을 마치기 전에 결과에 실수가 없는지 확인하세요.",
    ),

    /** The cap is only a safety limit: an unlimited budget can loop until the KV cache fills. */
    MAX(
        thinks = true,
        budget = 8192,
        effortInstruction = "답하기 전에 문제를 여러 부분으로 나눠 하나씩 풀고, 결과를 다른 방법으로 한 번 더 계산하거나 " +
            "조건에 대입해서 검증한 뒤에 생각을 마치세요.",
    );

    /** Clamped so thoughts can never take more than half of the KV cache. 0 when not thinking. */
    fun budget(maxNumTokens: Int): Int = if (thinks) minOf(budget, maxNumTokens / 2) else 0

    /** Worst-case KV used by one turn's thoughts, including the channel delimiters. */
    fun reserveTokens(maxNumTokens: Int): Int = if (thinks) budget(maxNumTokens) + 16 else 0

    companion object {
        /** The model's natural thinking. */
        val DEFAULT = HIGH

        fun fromName(name: String?): ReasoningLevel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
