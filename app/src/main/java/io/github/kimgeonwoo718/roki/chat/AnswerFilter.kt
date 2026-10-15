package io.github.kimgeonwoo718.roki.chat

/**
 * Cleans the model's output while it streams, before it reaches the screen and the chat archive:
 * - the channel name the runtime leaves at the start of the thoughts ("thought\n", or a garbled
 *   "thoughtful前\n") is dropped;
 * - reasoning that still shows up in the answer behind a "<|channel>" marker is moved to the
 *   thoughts, and the answer after "<channel|>" is kept;
 * - other Gemma 4 control-token text ("<turn|>", "<|think|>" …) is removed;
 * - LaTeX math ("$2.842 \times 10^{22}$") becomes plain text ("2.842 × 10²²").
 * The answer only ever grows (append-only), so the streaming Markdown renderer keeps appending.
 * One instance per generation attempt.
 */
class AnswerFilter {
    data class Output(val answer: String = "", val thought: String = "")

    /**
     * Control-token text or reasoning was found in the answer: the KV cache holds that raw text,
     * so the Conversation should be rebuilt from the cleaned history before the next message.
     */
    var leaked = false
        private set

    /**
     * The answer was recovered from the thoughts: the KV cache only has it inside the thought
     * channel (which the runtime drops), so the Conversation should be rebuilt as well.
     */
    var recovered = false
        private set

    private val thoughtHeader = HeaderStripper()
    private val leakHeader = HeaderStripper()
    private val math = MathStream()
    private val pending = StringBuilder()
    private var inLeak = false

    /** Thought text as shown (channel names removed), from the runtime and from leaks. */
    private val runtimeThought = StringBuilder()
    private val leakedThought = StringBuilder()
    private var answeredSomething = false
    private var thoughtShown = false
    private var answerSinceThought = false

    /** A thought delta from the runtime's thought channel. */
    fun thought(delta: String): String {
        // A second thought block after some answer starts with its channel name again.
        if (answerSinceThought) {
            answerSinceThought = false
            thoughtHeader.reset()
        }
        val text = thoughtHeader.push(delta)
        runtimeThought.append(text)
        return shownThought(text)
    }

    /** Separates a new thought block from earlier thoughts (runtime or leaked). */
    private var blockStart = true

    private fun shownThought(text: String): String {
        if (text.isEmpty()) return ""
        val separator = if (blockStart && thoughtShown) "\n\n" else ""
        blockStart = false
        thoughtShown = true
        return separator + text
    }

    /** An answer delta. */
    fun answer(delta: String): Output {
        pending.append(delta)
        return drain(final = false)
    }

    /** Releases everything held back. Call once, when the generation ends (also on an error). */
    fun finish(): Output {
        val leakOpen = inLeak
        val out = drain(final = true)
        var answer = out.answer
        val thought = StringBuilder(out.thought)
        thought.append(shownThought(thoughtHeader.finish().also { runtimeThought.append(it) }))
        if (inLeak) {
            thought.append(leakThought(leakHeader.finish()))
            inLeak = false
        }
        if (!answeredSomething && answer.isBlank()) {
            // Reasoning that never closed: the answer is usually its last part. Without the model's
            // own "최종 출력:" label only for a leak still open at the end (thoughts cut off by the
            // budget, or a closed leak, are reasoning, not an answer).
            val source = if (leakedThought.isNotBlank()) leakedThought else runtimeThought
            val text = recoverAnswer(source.toString(), requireLabel = !(leakedThought.isNotBlank() && leakOpen))
            if (!text.isNullOrBlank()) {
                answer = math.push(text) + math.finish()
                recovered = true
            }
        }
        return Output(answer, thought.toString())
    }

    private fun leakThought(text: String): String {
        leakedThought.append(text)
        return shownThought(text)
    }

    private fun drain(final: Boolean): Output {
        val answer = StringBuilder()
        val thought = StringBuilder()
        val s = pending.toString()
        var i = 0
        while (i < s.length) {
            if (inLeak) {
                val end = s.indexOf(CHANNEL_END, i)
                if (end >= 0) {
                    thought.append(leakThought(leakHeader.push(s.substring(i, end)) + leakHeader.finish()))
                    i = end + CHANNEL_END.length
                    inLeak = false
                    blockStart = true
                    continue
                }
                val stop = if (final) s.length else s.length - partialSuffix(s, i, CHANNEL_END)
                thought.append(leakThought(leakHeader.push(s.substring(i, stop))))
                i = stop
                break
            }
            val token = CONTROL_TOKEN.find(s, i)
            val stop =
                when {
                    token != null -> token.range.first
                    final -> s.length
                    else -> s.length - partialToken(s, i)
                }
            val clean = math.push(s.substring(i, stop))
            if (clean.isNotBlank()) {
                answeredSomething = true
                answerSinceThought = true
                blockStart = true
            }
            answer.append(clean)
            i = stop
            if (token == null) break
            i = token.range.last + 1
            if (math.insideCode) {
                // "`<|channel>`" in an explanation of Gemma's tokens, or a template in a code block.
                answer.append(math.push(token.value))
                continue
            }
            leaked = true
            if (token.value == CHANNEL_START) {
                inLeak = true
                leakHeader.reset()
                blockStart = true
            }
        }
        pending.delete(0, i)
        if (final) {
            val tail = math.finish()
            if (tail.isNotBlank()) answeredSomething = true
            answer.append(tail)
        }
        return Output(answer.toString(), thought.toString())
    }

    /**
     * Drops a channel name at the start ("thought", "thoughtful前"): an ASCII word (plus at most
     * four stray characters) alone on the first line, or a "thought…" word followed by a space.
     * Anything else passes through unchanged.
     */
    private class HeaderStripper {
        private val buf = StringBuilder()
        private var done = false

        fun reset() {
            buf.setLength(0)
            done = false
        }

        fun push(text: String): String {
            if (done) return text
            buf.append(text)
            val decided = decide(buf.toString(), final = false) ?: return ""
            done = true
            buf.setLength(0)
            return decided
        }

        fun finish(): String {
            if (done) return ""
            done = true
            val text = buf.toString()
            buf.setLength(0)
            return decide(text, final = true) ?: text
        }

        /** The text without its channel name, or null while that cannot be decided yet. */
        private fun decide(s: String, final: Boolean): String? {
            val name = NAME.find(s)
            if (name == null) return if (s.isBlank() && !final) null else s
            val end = name.range.last + 1
            if (end >= s.length) {
                // The word may still continue.
                if (final) return if (name.value.trim().length <= MAX_HEADER) "" else s
                return if (s.trim().length > MAX_HEADER) s else null
            }
            return when (s[end]) {
                '\n' -> s.substring(end + 1)
                ' ', '\t' -> if (name.groupValues[1].startsWith("thought", ignoreCase = true)) s.substring(end + 1) else s
                else -> s
            }
        }
    }

    companion object {
        private const val CHANNEL_START = "<|channel>"
        private const val CHANNEL_END = "<channel|>"
        private const val MAX_HEADER = 24
        private val NAME = Regex("^\\s*([A-Za-z][A-Za-z_]*)(\\S{0,4})")

        /**
         * Gemma 4's own control tokens as text: "<|channel>", "<channel|>", "<turn|>", "<|think|>" …
         * Other models' tokens ("<|im_start|>", "<|endoftext|>") are ordinary text in an answer.
         */
        private const val TOKEN_NAMES = "channel|turn|think|tool_call|tool_response|tool|image|audio|video"
        private val CONTROL_TOKEN = Regex("<\\|(?:$TOKEN_NAMES)\\|?>|<(?:$TOKEN_NAMES)\\|>")

        /** An unfinished control token at the end of the text ("<", "<|cha", "<channel|"). */
        private val PARTIAL_TOKEN = Regex("<\\|?[a-z_]{0,20}\\|?")

        /** "최종 출력:", "**최종 답변**:", "Final answer:" … the model's own label for its answer. */
        private val FINAL_MARKER =
            Regex(
                "(?:최종\\s*(?:출력|답변|응답|답)|Final\\s*(?:answer|output|response))\\s*(?:\\*\\*)?\\s*[:：]\\s*(?:\\*\\*)?",
                RegexOption.IGNORE_CASE,
            )

        private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

        private fun partialToken(s: String, from: Int): Int {
            val lt = s.lastIndexOf('<')
            if (lt < from) return 0
            val tail = s.substring(lt)
            return if (PARTIAL_TOKEN.matches(tail)) tail.length else 0
        }

        /** Length of the longest proper prefix of [marker] that [s] ends with (after [from]). */
        private fun partialSuffix(s: String, from: Int, marker: String): Int {
            for (k in minOf(marker.length - 1, s.length - from) downTo 1) {
                if (s.endsWith(marker.substring(0, k))) return k
            }
            return 0
        }

        /**
         * The answer part of reasoning: after its last "최종 출력:" label, else (unless
         * [requireLabel]) its last paragraph that is not a list item or heading.
         */
        internal fun recoverAnswer(text: String, requireLabel: Boolean): String? {
            FINAL_MARKER.findAll(text).lastOrNull()?.let { marker ->
                val after = text.substring(marker.range.last + 1).trim()
                if (after.isNotEmpty()) return after
            }
            if (requireLabel) return null
            // The last plain paragraph after the reasoning; never the reasoning itself.
            val paragraphs = text.trim().split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
            val index = paragraphs.indexOfLast { p -> p.first() !in "-*#>+" && !p.first().isDigit() && p != "---" }
            return if (index >= 1) paragraphs[index] else null
        }
    }
}

/**
 * Streams answer text with LaTeX math turned into plain text. An unfinished "$…" is held back
 * until it closes, the line ends, Korean text shows it is a price ("$799부터"), or [MAX_INLINE]
 * characters pass. Code (fenced at any indent, or `inline`) is left alone.
 */
internal class MathStream {
    private val buf = StringBuilder()
    private var lineStart = true
    private var fenceLine = false
    private var inFence = false
    private var inCode = false
    private var prev = '\n'

    fun push(text: String): String {
        buf.append(text)
        return drain(final = false)
    }

    fun finish(): String = drain(final = true)

    /** Whether text pushed next would be inside a code block or `code` (held text included). */
    val insideCode: Boolean
        get() {
            var fence = inFence || fenceLine
            var code = inCode
            for (c in buf) {
                when (c) {
                    '\n' -> { fence = inFence; code = false }
                    '`' -> code = !code
                }
            }
            return fence || code
        }

    private class Step(val text: String, val next: Int)

    private fun drain(final: Boolean): String {
        val s = buf.toString()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (lineStart) {
                // A code fence ("```", "~~~") opens or closes on its own line, also inside a list item.
                var j = i
                while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                if (j >= s.length && !final) break
                if (j < s.length && (s[j] == '`' || s[j] == '~')) {
                    if (j + 3 > s.length && s.indexOf('\n', i) < 0 && !final) break
                    fenceLine = isFence(s, j)
                } else {
                    fenceLine = false
                }
                if (fenceLine) inFence = !inFence
                lineStart = false
            }
            val c = s[i]
            if (c == '\n') {
                emit(out, c)
                i++
                lineStart = true
                fenceLine = false
                inCode = false
                continue
            }
            if (inFence || fenceLine) {
                emit(out, c)
                i++
                continue
            }
            if (c == '`') inCode = !inCode
            if (!inCode && c == '^') {
                // "25^\circ C" outside "$…$": a degree sign, not a caret and a ring.
                val degree = DEGREE.matchAt(s, i + 1)
                if (degree != null) {
                    emit(out, '°')
                    i += 1 + degree.value.length
                    continue
                }
                if (!final && "\\circ".startsWith(s.substring(i + 1).take(5)) && s.length - i - 1 < 5) break
            }
            if (!inCode && (c == '$' || c == '\\')) {
                val step = (if (c == '$') dollar(s, i, final) else backslash(s, i, final)) ?: break
                out.append(step.text)
                step.text.lastOrNull()?.let { prev = it }
                i = step.next
                continue
            }
            emit(out, c)
            i++
        }
        buf.delete(0, i)
        return out.toString()
    }

    private fun emit(out: StringBuilder, c: Char) {
        out.append(c)
        prev = c
    }

    /** "$…$" or "$$…$$" → plain text; null = wait for more text. */
    private fun dollar(s: String, i: Int, final: Boolean): Step? {
        val n = s.length
        if (i + 1 >= n) return if (final) Step("$", i + 1) else null
        if (s[i + 1] == '$') {
            // Display math ends within its paragraph and never crosses a code fence. Korean words
            // before any LaTeX mean prose ("PID 는 $$ 로 확인"), not math.
            var depth = 0
            var mathSeen = false
            var k = i + 2
            while (k < n && k - i <= MAX_DISPLAY) {
                val ch = s[k]
                when {
                    s.startsWith("$$", k) -> return Step(Latex.toText(s.substring(i + 2, k)), k + 2)
                    ch == '\n' && (s.startsWith("\n\n", k) || isFenceLineAt(s, k + 1)) -> return Step("$$", i + 2)
                    ch == '\\' || ch == '=' || ch == '^' || ch == '_' -> mathSeen = true
                    ch == '{' -> depth++
                    ch == '}' -> depth--
                    depth <= 0 && !mathSeen && isProseLetter(ch) -> return Step("$$", i + 2)
                }
                k++
            }
            return if (final || k - i > MAX_DISPLAY) Step("$$", i + 2) else null
        }
        // "US$5", "$ 5": not math.
        if ((prev.isLetterOrDigit() && prev < '\u0080') || s[i + 1].isWhitespace()) return Step("$", i + 1)
        // A price ("$799부터", "**$5**와") is followed by Korean; "$넓이 = 가로 \times 세로$" is math.
        val price = s[i + 1].isDigit()
        var depth = 0
        var k = i + 1
        while (k < n && k - i <= MAX_INLINE) {
            val ch = s[k]
            when {
                ch == '\n' -> return Step("$", i + 1)
                ch == '{' -> depth++
                ch == '}' -> depth--
                ch == '$' && s[k - 1] != '\\' && !s[k - 1].isWhitespace() -> {
                    if (k + 1 >= n && !final) return null
                    // "$HOME/$USER": a closing "$" is not followed by a digit or letter.
                    val after = s.getOrNull(k + 1)
                    if (after == null || !(after.isLetterOrDigit() && after < '\u0080')) {
                        return Step(Latex.toText(s.substring(i + 1, k)), k + 1)
                    }
                }
                // " $10", " $x": another opening "$", so this one was a price ("$5 and $10").
                ch == '$' && s[k - 1].isWhitespace() && s.getOrNull(k + 1)?.isWhitespace() == false -> return Step("$", i + 1)
                price && depth <= 0 && isProseLetter(ch) -> return Step("$", i + 1)
            }
            k++
        }
        return if (final || k - i > MAX_INLINE) Step("$", i + 1) else null
    }

    /** "\(…\)", "\[…\]" and bare known commands ("\times", "\frac{a}{b}"); null = wait. */
    private fun backslash(s: String, i: Int, final: Boolean): Step? {
        val n = s.length
        if (i + 1 >= n) return if (final) Step("\\", i + 1) else null
        val next = s[i + 1]
        // A line ending in "\" (hard break): the newline goes through the normal line handling.
        if (next == '\n' || next == '\r') return Step("\\", i + 1)
        if (next == '(' || next == '[') {
            val closer = if (next == '(') "\\)" else "\\]"
            // "\[ … \]" is display math: long, and may span lines within its paragraph.
            val limit = if (next == '(') MAX_INLINE else MAX_DISPLAY
            val literal = Step(s.substring(i, i + 2), i + 2)
            var k = i + 2
            while (k < n && k - i <= limit) {
                if (s.startsWith(closer, k)) {
                    val content = s.substring(i + 2, k)
                    // "\(\d+\)" is a regex, "\[링크\]" escaped brackets: only math is converted.
                    return if (looksLikeMath(content)) Step(Latex.toText(content), k + 2) else literal
                }
                if (s[k] == '\n' && (next == '(' || s.startsWith("\n\n", k) || isFenceLineAt(s, k + 1))) return literal
                k++
            }
            return if (final || k - i > limit) literal else null
        }
        if (!(next.isLetter() && next < '\u0080')) {
            // A Markdown escape ("\*", "\$", "\\"): keep both characters together for the renderer.
            return Step(s.substring(i, i + 2), i + 2)
        }
        var k = i + 1
        while (k < n && s[k].isLetter() && s[k] < '\u0080') k++
        if (k >= n && !final) return null
        val name = s.substring(i + 1, k)
        // A path ("C:\path\to\project", "app\log\error.log"), not math.
        val pathLike = (prev.isLetter() && prev < '\u0080') || prev == ':' || prev == '\\' || prev == '/' || s.getOrNull(k) == '\\'
        if (pathLike || !Latex.isKnownCommand(name)) return Step(s.substring(i, k), k)
        val literal = Step(s.substring(i, k), k)
        // Arguments are searched within one line and MAX_INLINE characters, the same whether the
        // text streams in or is filtered whole (cleanArchived), so both give the same result.
        val limit = minOf(n, i + MAX_INLINE, s.indexOf('\n', i).let { if (it < 0) n else it })
        val bounded = limit < n
        var end = k
        if (end < limit && s[end] == '[') {
            val close = s.indexOf(']', end).takeIf { it in 0 until limit }
                ?: return if (final || bounded) literal else null
            end = close + 1
        }
        var groups = 0
        while (end < limit && s[end] == '{' && groups < 2) {
            val close = matchingBrace(s, end, limit) ?: return if (final || bounded) literal else null
            end = close + 1
            groups++
        }
        // More arguments may still arrive ("\frac{a}" then "{b}").
        if (end >= n && !final) return null
        var text = Latex.toText(s.substring(i, end))
        if (Latex.isFraction(name)) {
            // As inside "$…$": 2\frac{1}{3} is "2 1/3", and \frac{1}{2}mv^2 is "(1/2)mv^2".
            val follows = s.getOrNull(end)
            if (follows != null && (follows.isLetterOrDigit() || follows == '(')) text = "($text)"
            if (prev.isDigit()) text = " $text"
        }
        return Step(text, end)
    }

    private fun looksLikeMath(content: String): Boolean {
        if (content.isBlank()) return false
        val commands = COMMAND.findAll(content).map { it.groupValues[1] }.toList()
        if (commands.any { !Latex.isKnownCommand(it) }) return false
        val formula = commands.isNotEmpty() || content.any { it in "^_=+-*/<>" }
        // Korean words in a formula are fine ("넓이 = 가로 \times 세로"), in plain brackets not.
        var depth = 0
        for (ch in content) {
            when {
                ch == '{' -> depth++
                ch == '}' -> depth--
                depth <= 0 && isProseLetter(ch) && !(formula && ('=' in content || commands.isNotEmpty())) -> return false
            }
        }
        return formula || content.trim().length <= 3
    }

    private fun matchingBrace(s: String, open: Int, limit: Int): Int? {
        var depth = 0
        var k = open
        while (k < limit) {
            when (s[k]) {
                '\\' -> k++
                '{' -> depth++
                '}' -> if (--depth == 0) return k
            }
            k++
        }
        return null
    }

    private companion object {
        const val MAX_INLINE = 120
        const val MAX_DISPLAY = 2000
        val COMMAND = Regex("\\\\([A-Za-z]+)")

        /** "\circ" or "{\circ}" right after a caret: "25^\circ" is 25°. */
        val DEGREE = Regex("\\\\circ(?![A-Za-z])|\\{\\\\circ\\}")

        fun isFence(s: String, at: Int): Boolean = s.startsWith("```", at) || s.startsWith("~~~", at)

        /** A fence line starting at [at] (after optional indentation). */
        fun isFenceLineAt(s: String, at: Int): Boolean {
            var j = at
            while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
            return isFence(s, j)
        }

        /** A letter of running text that is not Latin or Greek (Hangul, kana, Han …). */
        fun isProseLetter(c: Char): Boolean =
            c >= '\u0080' && c.isLetter() && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.GREEK
    }
}
