package io.github.kimgeonwoo718.roki.chat

/**
 * Turns LaTeX math ("2.842 \times 10^{22}", "\frac{a+b}{2}") into plain text ("2.842 × 10²²",
 * "(a+b)/2"). The Markdown renderer has no math support and shows "$…$" source as is.
 */
object Latex {
    fun toText(source: String): String =
        Converter(source).run { readUntil(null) }
            .replace(SPACES, " ")
            .replace(SPACE_BEFORE_SEMICOLON, ";")
            .replace(OPEN_PAREN_SPACE, "$1")
            .replace(CLOSE_PAREN_SPACE, "$1")
            .trim()
            .trimEnd(';')
            .trim()

    /** Whether "\name" is a command [toText] knows (for bare commands outside "$…$"). */
    fun isKnownCommand(name: String): Boolean =
        name in SYMBOLS || name in ONE_ARG || name in IGNORED || name in FUNCTIONS || name in ACCENTS ||
            name in FRACTIONS || name in OTHER_COMMANDS

    fun isFraction(name: String): Boolean = name in FRACTIONS

    /** Commands with their own handling in Converter.command(). */
    private val OTHER_COMMANDS =
        setOf("sqrt", "mathbb", "binom", "not", "pmod", "phantom", "begin", "end", "overset", "underset", "stackrel")

    private class Converter(private val s: String) {
        private var i = 0

        /** The last command read was a fraction (for "2\frac{1}{3}" → "2 1/3"). */
        private var fraction = false

        /** Reads up to (and consumes) [end], or to the end of the text. Grouping braces are dropped. */
        fun readUntil(end: Char?): String {
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    end != null && c == end -> { i++; return out.toString() }
                    c == '{' -> { i++; out.append(readUntil('}')) }
                    c == '}' -> i++
                    c == '\\' -> {
                        val text = command()
                        // A mixed number (대분수) keeps its whole part apart: 2⅓ is "2 1/3", not "21/3".
                        if (fraction && out.lastOrNull()?.isDigit() == true) out.append(' ')
                        out.append(text)
                    }
                    c == '^' -> { i++; out.append(superscript(argument())) }
                    c == '_' -> { i++; out.append(subscript(argument())) }
                    // Alignment and table columns ("cases", "aligned", "pmatrix").
                    c == '&' -> { i++; out.append(' ') }
                    c == '~' -> { i++; out.append(' ') }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        /** Index after any "\left", "\big" … at [from] (what a delimiter size command wraps). */
        private fun afterSizing(from: Int): Int {
            var j = from
            while (j < s.length && s[j] == '\\') {
                var k = j + 1
                while (k < s.length && s[k].isLetter() && s[k] < '\u0080') k++
                if (s.substring(j + 1, k) !in DELIMITER_SIZES) break
                j = k
                while (j < s.length && s[j] == ' ') j++
            }
            return j
        }

        /** The next argument: a {group}, a command, or one character. */
        private fun argument(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return when (s[i]) {
                '{' -> { i++; readUntil('}') }
                '\\' -> command()
                else -> s[i++].toString()
            }
        }

        private fun command(): String {
            fraction = false
            i++ // backslash
            if (i >= s.length) return ""
            val start = i
            while (i < s.length && s[i].isLetter() && s[i] < '\u0080') i++
            if (i == start) {
                // "\{" "\%" "\," "\\" …: one symbol character.
                val c = s[i++]
                return when (c) {
                    ',', ';', ':', ' ' -> " "
                    '\\' -> "; " // a new row in "cases" or "aligned"
                    '!' -> ""
                    else -> c.toString()
                }
            }
            val name = s.substring(start, i)
            SYMBOLS[name]?.let { return it }
            if (name in IGNORED) {
                // "\left." / "\right.": an invisible delimiter, not a full stop.
                if (name in DELIMITER_SIZES) {
                    var j = i
                    while (j < s.length && s[j] == ' ') j++
                    if (j < s.length && s[j] == '.') i = j + 1
                }
                return ""
            }
            if (name in FUNCTIONS) return name
            ACCENTS[name]?.let { mark ->
                // 0.\overline{3} → 0.3̅, \bar{x} → x̄: the mark matters (순환소수, 평균).
                return argument().trim().map { if (it == ' ') " " else "$it$mark" }.joinToString("")
            }
            return when (name) {
                in FRACTIONS -> {
                    val top = argument().trim()
                    val bottom = argument().trim()
                    val text = "${wrap(top)}/${wrap(bottom)}"
                    fraction = true
                    // "\frac{1}{2}mv^2" is (1/2)mv², not 1/(2mv²); also before "\left(".
                    val follows = s.getOrNull(afterSizing(i))
                    if (follows != null && (follows.isLetterOrDigit() || follows == '(' || follows == '[')) "($text)" else text
                }
                "not" -> {
                    // \not= → ≠, \not\in → ∉: dropping it would state the opposite.
                    val negated = argument().trim()
                    NEGATIONS[negated] ?: "¬$negated"
                }
                "phantom" -> { argument(); "" }
                "sqrt" -> {
                    var root: String? = null
                    if (i < s.length && s[i] == '[') {
                        i++
                        root = readUntil(']').trim()
                    }
                    val body = argument().trim()
                    when (root) {
                        null, "2" -> "√" + wrap(body)
                        "3" -> "∛" + wrap(body)
                        "4" -> "∜" + wrap(body)
                        else -> "${wrap(body)}^(1/$root)"
                    }
                }
                "mathbb" -> {
                    val body = argument().trim()
                    BLACKBOARD[body] ?: body
                }
                "binom" -> {
                    val n = argument().trim()
                    val k = argument().trim()
                    "C($n, $k)"
                }
                "pmod" -> " (mod ${argument().trim()})"
                "begin" -> {
                    // \begin{array}{c|c}: the column spec is not content.
                    val environment = argument().trim()
                    if (environment == "array" || environment == "tabular") argument()
                    ""
                }
                "end" -> { argument(); "" }
                "overset", "underset", "stackrel" -> { argument(); argument() }
                in ONE_ARG -> argument()
                // An unknown command: its argument is the content ("\ce{H2O}" → "H2O").
                else -> if (s.getOrNull(i) == '{') argument() else name
            }
        }
    }

    /** Parenthesised when it is more than one term ("a+b" → "(a+b)"), so "/" reads right. */
    private fun wrap(term: String): String =
        if (term.length > 1 && term.any { it == ' ' || it in "+-−×÷/·=" } && !(term.startsWith("(") && term.endsWith(")"))) "($term)"
        else term

    private fun superscript(arg: String): String {
        val t = arg.trim()
        if (t == "∘" || t == "°") return "°"
        if (t == "′" || t == "'") return "′"
        if (t.isNotEmpty() && t.all { it in SUPERSCRIPTS }) return t.map { SUPERSCRIPTS.getValue(it) }.joinToString("")
        return if (t.length == 1) "^$t" else "^($t)"
    }

    private fun subscript(arg: String): String {
        val t = arg.trim()
        if (t.isNotEmpty() && t.all { it in SUBSCRIPTS }) return t.map { SUBSCRIPTS.getValue(it) }.joinToString("")
        return if (t.length == 1) "_$t" else "_($t)"
    }

    private val SPACES = Regex("[ \\t]{2,}")
    private val SPACE_BEFORE_SEMICOLON = Regex("[ \\t]+;")

    /** "\left( x \right)" leaves "( x )". */
    private val OPEN_PAREN_SPACE = Regex("([(\\[])[ \\t]+")
    private val CLOSE_PAREN_SPACE = Regex("[ \\t]+([)\\]])")

    private val SUPERSCRIPTS =
        mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ',
        )

    private val SUBSCRIPTS =
        mapOf(
            '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
            '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
            'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ',
            'n' to 'ₙ', 'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        )

    private val BLACKBOARD = mapOf("R" to "ℝ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "C" to "ℂ")

    /** Commands that take one argument and show only that argument. */
    private val ONE_ARG =
        setOf(
            "text", "textrm", "textbf", "textit", "textsf", "texttt", "mathrm", "mathbf", "mathit", "mathsf", "mathtt",
            "mathcal", "mathfrak", "boldsymbol", "operatorname", "boxed", "mbox", "hbox", "emph", "cancel",
        )

    private val FRACTIONS = setOf("frac", "dfrac", "tfrac", "cfrac")

    /** Accents shown as combining marks on each character of the argument. */
    private val ACCENTS =
        mapOf(
            "overline" to '̅', "bar" to '̄', "dot" to '̇', "ddot" to '̈', "hat" to '̂',
            "widehat" to '̂', "tilde" to '̃', "widetilde" to '̃', "vec" to '⃗',
            "overrightarrow" to '⃗', "underline" to '̲',
        )

    /** What "\not" turns a relation into. */
    private val NEGATIONS =
        mapOf(
            "=" to "≠", "∈" to "∉", "≡" to "≢", "⊂" to "⊄", "⊆" to "⊈", "⊃" to "⊅", "⊇" to "⊉", "<" to "≮", ">" to "≯",
            "≤" to "≰", "≥" to "≱", "|" to "∤", "~" to "≁", "≈" to "≉", "∃" to "∄", "∥" to "∦", "≅" to "≇", "≃" to "≄",
        )

    /** Commands that size the delimiter after them ("\left(", "\big|"). */
    private val DELIMITER_SIZES =
        setOf("left", "right", "middle", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr")

    /** Sizing and spacing commands that print nothing. */
    private val IGNORED =
        setOf(
            "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr",
            "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "middle",
        )

    /** Function names printed as they are ("\sin x" → "sin x"). */
    private val FUNCTIONS =
        setOf(
            "sin", "cos", "tan", "sec", "csc", "cot", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
            "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "gcd", "lcm", "deg", "dim", "mod",
        )

    private val SYMBOLS =
        mapOf(
            "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "star" to "⋆",
            "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "approx" to "≈",
            "sim" to "~", "simeq" to "≃", "cong" to "≅", "equiv" to "≡", "propto" to "∝", "ll" to "≪", "gg" to "≫",
            "infty" to "∞", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "Rightarrow" to "⇒",
            "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔",
            "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
            "sum" to "Σ", "prod" to "Π", "int" to "∫", "iint" to "∬", "oint" to "∮", "partial" to "∂", "nabla" to "∇",
            "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
            "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
            "forall" to "∀", "exists" to "∃", "neg" to "¬", "lnot" to "¬", "land" to "∧", "wedge" to "∧",
            "lor" to "∨", "vee" to "∨", "oplus" to "⊕", "otimes" to "⊗", "perp" to "⊥", "parallel" to "∥",
            "angle" to "∠", "triangle" to "△", "circ" to "∘", "degree" to "°", "prime" to "′", "bullet" to "•",
            "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱",
            "therefore" to "∴", "because" to "∵", "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
            "lceil" to "⌈", "rceil" to "⌉", "mid" to "|", "vert" to "|", "Vert" to "‖", "lvert" to "|", "rvert" to "|",
            "quad" to " ", "qquad" to " ", "hbar" to "ℏ", "ell" to "ℓ", "aleph" to "ℵ", "bmod" to " mod ",
            "percent" to "%",
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
            "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
            "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
            "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
            "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
            "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        )
}
