package io.github.kimgeonwoo718.roki.chat

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact arithmetic for expressions in the user's message ("2928473773×292828827는?").
 *
 * A 2B model cannot multiply long numbers: it guesses, or starts writing out its working (even
 * Python code) in the answer. The app computes the value itself and hands it to the model as a
 * fact with the message, so the model only has to state it. Runs on the device, no network.
 */
object Calculator {
    data class Result(
        /** The expression as the user wrote it (operators normalized). */
        val expression: String,
        /** The value with thousands separators, or a short reason it has none ("0으로 나눌 수 없음"). */
        val value: String,
        /** Korean reading of a large integer ("85경 7559조 …"), or null. */
        val reading: String?,
        /** Rounded (non-terminating division, fractional power, very large numbers). */
        val approximate: Boolean,
    )

    /** Up to [MAX_RESULTS] calculations found in [prompt]; empty when there is nothing to compute. */
    fun evaluateAll(prompt: String): List<Result> {
        if (prompt.contains("```")) return emptyList()
        val text = normalize(prompt)
        // Once per message (a calculation word anywhere): keeps this linear for long messages.
        val calcWord = CALC_WORD.containsMatchIn(text)
        val results = ArrayList<Result>()
        for (match in SPAN.findAll(text)) {
            if (results.size >= MAX_RESULTS) break
            // Only a number, no operator: nothing to compute (most spans in a long message).
            if (match.value.drop(1).none { it in OPERATOR_CHARS }) continue
            val before = text.getOrNull(match.range.first - 1)
            val after = text.getOrNull(match.range.last + 1)
            // Part of something else: a variable or unit ("2x+3", "0x1F", "3+4cm"), more math that
            // is not plain arithmetic ("x^2 + 3"), a time ("9:00-18:00"), a percentage ("10-15%"),
            // money ("$5+3"), a number written as ".5", or "1+2.5x". A sentence-final "." is fine.
            if (before.blocksSpan() || before == '.' || after.blocksSpan()) continue
            if (after == '.' && text.getOrNull(match.range.last + 2)?.isLetterOrDigit() == true) continue
            val asked = Asked(text, match.range.first, text.substring(match.range.last + 1), calcWord)
            val span = match.value.trim()
            val pieces = evaluateSpan(span, asked)?.let(::listOf)
                ?: span.split(PIECE_SPLIT).mapNotNull { evaluateSpan(it.trim(), asked) }
            results.addAll(pieces.take(MAX_RESULTS - results.size))
        }
        return results
    }

    /**
     * Whether the message asks for the expression's value. The expression has to be followed by a
     * particle, a question or a calculation word ("…는?", "…를 좀 계산해줘", "… 얼마야"), or end the
     * message. A word right after it means it is a name, not a sum: "1+1 행사", "3x3 큐브",
     * "1920x1080 해상도", "3-4일", "5-6명", "1-1 문제".
     */
    private class Asked(text: String, start: Int, rest: String, calcWord: Boolean) {
        private val atEnd = END.matches(rest)
        private val beforeLength by lazy { text.substring(0, start).count { !it.isWhitespace() } }

        val normal = ASK_AFTER.containsMatchIn(rest) || (atEnd && (calcWord || beforeLength <= SHORT_BEFORE))

        /**
         * A direct request: a calculation word, "…는?" / "= ?", or the expression alone. Needed for
         * small sums ("1+1", "2+1" are 편의점 행사) and "x" ("4x4", "1920x1080" are names).
         */
        val explicit = calcWord || DIRECT_ASK.containsMatchIn(rest) || (atEnd && beforeLength == 0)

        /** "-" and "/" also write ranges, scores, dates and codes ("7-11", "24/7"): even stricter. */
        val weak = WEAK_ASK.containsMatchIn(rest) || (atEnd && (calcWord || beforeLength == 0))
    }

    private fun Char?.blocksSpan(): Boolean =
        this != null && (this in 'a'..'z' || this in 'A'..'Z' || this in "_^*/×÷:%％$₩#@")

    private fun evaluateSpan(span: String, asked: Asked): Result? {
        if (span.isEmpty() || !span.any { it.isDigit() }) return null
        if (PHONE.matches(span) || DATE.matches(span)) return null
        val tokens = tokenize(span) ?: return null
        val operators = tokens.filter { it.kind == Kind.OP && it.binary }
        if (operators.isEmpty()) return null
        // "-" and "/" as typed are weak; "÷", "×", "*", "x", "^", "+" and 빼기 are clearly arithmetic.
        val strong = operators.any { !it.weak }
        if (strong) {
            val numbers = tokens.filter { it.kind == Kind.NUM }
            val smallSum = operators.all { it.text == "+" } && numbers.all { it.number!!.abs() < BigDecimal(100) }
            val needsExplicit = smallSum || operators.any { it.typedX }
            if (!asked.normal || (needsExplicit && !asked.explicit)) return null
        } else {
            // Never a chain of hyphens ("1-2-3") or slashes.
            if (!asked.weak || operators.size > 1) return null
        }
        val parser = Parser(tokens)
        val value =
            try {
                parser.parse()
            } catch (e: CalcError) {
                return if (e.message == DIV_ZERO) Result(display(tokens), DIV_ZERO, null, approximate = false) else null
            } catch (e: ArithmeticException) {
                return null
            }
        return format(display(tokens), value, parser.approximate)
    }

    /** Full-width forms, other minus/times signs and Korean operator words → ASCII-ish operators. */
    private fun normalize(prompt: String): String {
        val sb = StringBuilder(prompt.length)
        for (c in prompt) {
            sb.append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '＋' -> '+'
                    '－', '−', '–' -> '-'
                    '＊', '✕', '✖', '⨯' -> '×'
                    '／' -> '/'
                    '（' -> '('
                    '）' -> ')'
                    '＾' -> '^'
                    else -> c
                }
            )
        }
        var s = sb.toString()
        s = POWER_WORD.replace(s) { "${it.groupValues[1]}^${it.groupValues[2]}" }
        for ((word, op) in OP_WORDS) {
            s = Regex("(?<=[0-9)])\\s*$word\\s*(?=[0-9(])").replace(s, " $op ")
        }
        return s
    }

    private enum class Kind { NUM, OP, LPAREN, RPAREN }

    private class Token(
        val kind: Kind,
        val text: String,
        val number: BigDecimal? = null,
        var binary: Boolean = false,
        /** Typed as "-" or "/", which are often not arithmetic (dates, phone numbers, ranges). */
        val weak: Boolean = false,
        /** Typed as the letter "x" ("4x4", "1920x1080" are usually names). */
        val typedX: Boolean = false,
    )

    private fun tokenize(span: String): List<Token>? {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < span.length) {
            val c = span[i]
            when {
                c == ' ' || c == '\t' -> i++
                c.isDigit() || c == '.' -> {
                    val m = NUMBER.matchAt(span, i) ?: return null
                    val digits = m.value.replace(",", "")
                    if (digits.length > MAX_DIGITS) return null
                    tokens += Token(Kind.NUM, m.value, BigDecimal(digits))
                    i += m.value.length
                    // "1.2.3" or "1,2,3": not a number this calculator understands.
                    if (i < span.length && (span[i] == '.' || span[i] == ',' || span[i].isDigit())) return null
                }
                c == '(' -> { tokens += Token(Kind.LPAREN, "("); i++ }
                c == ')' -> { tokens += Token(Kind.RPAREN, ")"); i++ }
                c == '*' && span.getOrNull(i + 1) == '*' -> { tokens += Token(Kind.OP, "^"); i += 2 }
                c == 'x' || c == 'X' -> {
                    // Only "3x4" / "3 x 4" is multiplication; "2x+3" is algebra.
                    val prev = tokens.lastOrNull()
                    val next = span.substring(i + 1).trimStart().firstOrNull()
                    if (prev == null || (prev.kind != Kind.NUM && prev.kind != Kind.RPAREN)) return null
                    if (next == null || !(next.isDigit() || next == '(')) return null
                    tokens += Token(Kind.OP, "×", typedX = true)
                    i++
                }
                c in "+-*/×÷^" -> {
                    tokens += Token(Kind.OP, when (c) { '*' -> "×"; '/' -> "÷"; else -> c.toString() }, weak = c == '-' || c == '/')
                    i++
                }
                // "빼기" (see normalize): a minus the user clearly meant.
                c == WORD_MINUS -> { tokens += Token(Kind.OP, "-"); i++ }
                else -> return null
            }
        }
        // Mark binary operators (after a number or ")"), the rest are signs.
        for ((index, token) in tokens.withIndex()) {
            if (token.kind != Kind.OP) continue
            val prev = tokens.getOrNull(index - 1)
            token.binary = prev != null && (prev.kind == Kind.NUM || prev.kind == Kind.RPAREN)
            if (!token.binary && token.text != "-" && token.text != "+") return null
        }
        return tokens
    }

    private fun display(tokens: List<Token>): String =
        buildString {
            for (t in tokens) {
                when {
                    t.kind == Kind.OP && t.binary -> append(' ').append(t.text).append(' ')
                    else -> append(t.text)
                }
            }
        }.replace("( ", "(").replace(" )", ")").trim()

    private class CalcError(message: String) : Exception(message)

    /** Recursive descent: + - < × ÷ < unary sign < ^ (right-associative). */
    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        var approximate = false

        fun parse(): BigDecimal {
            val v = expr()
            if (pos != tokens.size) throw CalcError("trailing")
            return v
        }

        private fun peek(): Token? = tokens.getOrNull(pos)

        private fun expr(): BigDecimal {
            var v = term()
            while (true) {
                val t = peek() ?: return v
                if (t.kind != Kind.OP || !t.binary || (t.text != "+" && t.text != "-")) return v
                pos++
                val r = term()
                v = if (t.text == "+") v.add(r) else v.subtract(r)
            }
        }

        private fun term(): BigDecimal {
            var v = unary()
            while (true) {
                val t = peek() ?: return v
                if (t.kind != Kind.OP || !t.binary || (t.text != "×" && t.text != "÷")) return v
                pos++
                val r = unary()
                v = if (t.text == "×") v.multiply(r) else divide(v, r)
            }
        }

        private fun unary(): BigDecimal {
            val t = peek()
            if (t != null && t.kind == Kind.OP && !t.binary) {
                pos++
                val v = unary()
                return if (t.text == "-") v.negate() else v
            }
            return power()
        }

        private fun power(): BigDecimal {
            val base = primary()
            val t = peek()
            if (t == null || t.kind != Kind.OP || t.text != "^") return base
            pos++
            val exponent = unary()
            return pow(base, exponent)
        }

        private fun primary(): BigDecimal {
            val t = peek() ?: throw CalcError("end")
            pos++
            return when (t.kind) {
                Kind.NUM -> t.number!!
                Kind.LPAREN -> {
                    val v = expr()
                    if (peek()?.kind != Kind.RPAREN) throw CalcError("paren")
                    pos++
                    v
                }
                else -> throw CalcError("unexpected")
            }
        }

        private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
            if (b.signum() == 0) throw CalcError(DIV_ZERO)
            return try {
                a.divide(b)
            } catch (e: ArithmeticException) {
                approximate = true
                a.divide(b, CONTEXT)
            }
        }

        private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val whole = exponent.stripTrailingZeros()
            if (whole.scale() <= 0 && whole.abs() <= BigDecimal(MAX_EXPONENT)) {
                val n = whole.toInt()
                if (base.signum() == 0 && n < 0) throw CalcError(DIV_ZERO)
                // Exact unless the result would be huge (then 30 significant digits).
                val digits = (base.precision() - base.scale()).coerceAtLeast(1).toLong() * kotlin.math.abs(n)
                if (digits <= MAX_EXACT_DIGITS) {
                    if (n >= 0) return base.pow(n)
                    // 10^-2 = 0.01 exactly; 3^-1 is not.
                    try {
                        return BigDecimal.ONE.divide(base.pow(-n))
                    } catch (e: ArithmeticException) {
                        // non-terminating: rounded below
                    }
                }
                approximate = true
                return base.pow(n, CONTEXT)
            }
            // Fractional exponents (square roots etc.) only for positive bases, as a double.
            if (base.signum() < 0) throw CalcError("complex")
            approximate = true
            val d = Math.pow(base.toDouble(), exponent.toDouble())
            if (d.isNaN() || d.isInfinite()) throw CalcError("range")
            return BigDecimal(d, MathContext(15))
        }
    }

    private fun format(expression: String, value: BigDecimal, approximateIn: Boolean): Result {
        var approximate = approximateIn
        var v = value.stripTrailingZeros()
        val integer = v.scale() <= 0
        val intDigits = v.precision() - v.scale()
        val text: String
        var reading: String? = null
        if (integer && intDigits <= MAX_SHOWN_DIGITS) {
            val big = v.toBigIntegerExact()
            text = group(big.abs().toString(), big.signum() < 0)
            if (big.abs() >= TEN_THOUSAND && intDigits <= KOREAN_UNITS.size * 4) reading = korean(big)
        } else if (intDigits > MAX_SHOWN_DIGITS || intDigits < -10) {
            // Scientific: 1.2345 × 10^75
            val rounded = v.round(MathContext(15, RoundingMode.HALF_EVEN))
            approximate = approximate || rounded.compareTo(v) != 0
            val exp = rounded.precision() - rounded.scale() - 1
            val mantissa = rounded.movePointLeft(exp).stripTrailingZeros().toPlainString()
            text = "$mantissa × 10^$exp"
        } else {
            val rounded = v.round(MathContext(SHOWN_SIGNIFICANT, RoundingMode.HALF_EVEN)).stripTrailingZeros()
            approximate = approximate || rounded.compareTo(v) != 0
            v = rounded
            val plain = v.abs().toPlainString()
            val dot = plain.indexOf('.')
            text = if (dot < 0) group(plain, v.signum() < 0) else group(plain.substring(0, dot), v.signum() < 0) + plain.substring(dot)
        }
        return Result(expression, text, reading, approximate)
    }

    private fun group(digits: String, negative: Boolean): String {
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return if (negative) "-$sb" else sb.toString()
    }

    /** 857559… → "85경 7559조 8820억 …" (groups of four digits with 만, 억, 조, 경 …). */
    private fun korean(value: BigInteger): String {
        val digits = value.abs().toString()
        val parts = ArrayList<String>()
        var end = digits.length
        var unit = 0
        while (end > 0) {
            val start = maxOf(0, end - 4)
            val group = digits.substring(start, end).trimStart('0')
            if (group.isNotEmpty()) parts += group + KOREAN_UNITS[unit]
            end = start
            unit++
        }
        val reading = parts.asReversed().joinToString(" ")
        return if (value.signum() < 0) "마이너스 $reading" else reading
    }

    /** The calculation note sent to the model with the message (never shown on screen). */
    fun note(results: List<Result>, prompt: String): String? {
        if (results.isEmpty()) return null
        val lines = results.joinToString(", ") { r ->
            val eq = if (r.approximate) "≈" else "="
            val reading = r.reading?.let { " ($it)" }.orEmpty()
            "${r.expression} $eq ${r.value}$reading"
        }
        val howTo =
            if (PROCESS.containsMatchIn(prompt)) "풀이나 이유를 물었으니 설명은 하되, 값을 말할 때는 이 값과 같아야 함"
            else "이 계산이 필요하면 다시 계산하거나 계산 과정·코드를 늘어놓지 말고 이 값을 그대로 쓰기"
        return "앱 계산기로 미리 구한 정확한 결과: $lines. $howTo"
    }

    private const val MAX_RESULTS = 3
    private const val MAX_DIGITS = 200
    private const val MAX_EXPONENT = 100_000
    private const val MAX_EXACT_DIGITS = 400L
    private const val MAX_SHOWN_DIGITS = 60
    private const val SHOWN_SIGNIFICANT = 20
    private const val DIV_ZERO = "0으로 나눌 수 없음"
    private val CONTEXT = MathContext(30, RoundingMode.HALF_EVEN)
    private val TEN_THOUSAND = BigInteger.valueOf(10_000)
    private val KOREAN_UNITS = listOf("", "만", "억", "조", "경", "해", "자", "양", "구", "간", "정", "재")

    /** A run of digits, operators, parentheses and spaces that starts and ends with a digit or parenthesis. */
    private val SPAN = Regex("-?[0-9(][0-9 \\t.,+\\-*/×xX÷^()$WORD_MINUS]*[0-9)]")
    private val NUMBER = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?|\\.\\d+")
    private val PIECE_SPLIT = Regex(",\\s+|\\.\\s+|\\s{2,}")
    private val PHONE = Regex("0\\d{1,2}-\\d{3,4}-\\d{4}")
    private val DATE = Regex("\\d{2,4}[-/.]\\d{1,2}[-/.]\\d{1,2}")
    private val POWER_WORD = Regex("(\\d+(?:\\.\\d+)?)\\s*의\\s*(\\d+)\\s*(?:제곱|승)")
    /** Stands for "빼기" between normalize() and tokenize(): unlike a typed "-", never a range. */
    private const val WORD_MINUS = '⊖'
    private val OP_WORDS = listOf("곱하기" to "×", "나누기" to "÷", "더하기" to "+", "빼기" to "$WORD_MINUS")

    private const val OPERATOR_CHARS = "+-*/×xX÷^$WORD_MINUS"

    /** Words that ask for a value: "계산해줘", "얼마야", "구하면", "몇이야". */
    private const val CALC = "계산|얼마|구하|구해|몇이"

    /** One adverb may sit before the calculation word: "좀 계산해줘", "정확히 얼마야". */
    private const val ADVERB = "(?:(?:좀|한번|한 번|정확히|정확하게|각각|다|빨리|직접)\\s*)?"

    /** The expression is followed by a particle, a question mark or a calculation word. */
    private val ASK_AFTER =
        Regex(
            "^\\s*(?:[=?？.!,~]|(?:이면|이랑|하면|하고|이나|은요|는요|은|는|이|가|을|를|의|면|랑|과|와|도|만|요)(?![가-힣])|" +
                "$ADVERB(?:$CALC|몇|뭐|값|결과|답|알려|맞|정답))",
        )

    /** "…는?", "…은 얼마", "…은 몇 픽셀", "= ?": a direct question about the value. */
    private val DIRECT_ASK = Regex("^\\s*(?:(?:은|는|이|가|을|를)\\s*)?(?:[=?？]|$ADVERB(?:$CALC|값|몇))")

    /**
     * For "-" and "/" only a direct question: "10-3은?", "100-37 계산해줘". Not "…이 뭐야?",
     * which asks what "7-11" or "24/7" means, nor "2024-2025 몇 년", a range.
     */
    private val WEAK_ASK = Regex("^\\s*(?:(?:은|는|이|가|을|를)\\s*)?(?:[=?？]|$ADVERB(?:$CALC|값))")

    /** The message asks for a calculation somewhere. */
    private val CALC_WORD = Regex("$CALC|=")

    /** Nothing but laughter or punctuation after the expression ("1234×5678 ㅋㅋ"). */
    private val END = Regex("[\\sㅋㅎㅠㅜ~!.?？]*")

    /** An expression that ends the message counts when little comes before it ("이거 2+3"). */
    private const val SHORT_BEFORE = 10

    /** The prompt asks for the working or the reason, not only the value. */
    private val PROCESS = Regex("과정|풀이|어떻게|설명|방법|증명|단계|원리|왜|이유")
}
