package io.github.kimgeonwoo718.roki.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerFilterTest {
    /** Feeds [text] in small pieces (as tokens arrive) and returns the final answer and thought. */
    private fun stream(text: String, step: Int = 3, filter: AnswerFilter = AnswerFilter()): AnswerFilter.Output {
        val answer = StringBuilder()
        val thought = StringBuilder()
        var i = 0
        while (i < text.length) {
            val out = filter.answer(text.substring(i, minOf(text.length, i + step)))
            answer.append(out.answer)
            thought.append(out.thought)
            i += step
        }
        val end = filter.finish()
        answer.append(end.answer)
        thought.append(end.thought)
        return AnswerFilter.Output(answer.toString(), thought.toString())
    }

    private fun clean(text: String, step: Int = 3) = stream(text, step).answer

    @Test
    fun plainTextPassesThrough() {
        for (step in listOf(1, 2, 5, 100)) {
            val text = "안녕! **굵게** 그리고 `코드 \$x\$` 와\n\n- 목록\n```\nval a = \"\$b\"\n```\n끝 < 5 > 3"
            assertEquals(text, clean(text, step))
        }
    }

    @Test
    fun inlineMathBecomesText() {
        assertEquals("99,287,273,737 × 29,292,827 의 결과", clean("\$99,287,273,737 \\times 29,292,827\$ 의 결과"))
        assertEquals("약 **2.842 × 10²²** 정도", clean("약 **\$2.842 \\times 10^{22}\$** 정도"))
        assertEquals("n-times-R의 곱셈", clean("\$n\$-times-\$R\$의 곱셈"))
        assertEquals("(a+b)/2", clean("\$\\frac{a+b}{2}\$"))
        assertEquals("√2 ≈ 1.414", clean("\$\\sqrt{2} \\approx 1.414\$"))
        assertEquals("x₁ + x₂", clean("\\(x_1 + x_2\\)"))
        assertEquals("답: 3 × 4 = 12", clean("답: 3 \\times 4 = 12"))
    }

    @Test
    fun displayMath() {
        assertEquals("앞\nE = mc²\n뒤", clean("앞\n\$\$E = mc^2\$\$\n뒤"))
    }

    @Test
    fun currencyIsNotMath() {
        assertEquals("Pay \$5 and \$10 today", clean("Pay \$5 and \$10 today"))
        assertEquals("US\$5", clean("US\$5"))
    }

    @Test
    fun mathInCodeIsKept() {
        val text = "```latex\n\$a \\times b\$\n```\n"
        assertEquals(text, clean(text))
        assertEquals("`\$x\$`", clean("`\$x\$`"))
    }

    @Test
    fun leakedReasoningMovesToThought() {
        val leaked = "<|channel> thoughtful前\n- **분석:** 사용자가 화났다.\n- **결론:** 짧게 답한다.<channel|>뭐가 그렇게 불만이야?"
        for (step in listOf(1, 3, 7)) {
            val filter = AnswerFilter()
            val out = stream(leaked, step, filter)
            assertEquals("뭐가 그렇게 불만이야?", out.answer)
            assertTrue(out.thought, out.thought.startsWith("- **분석:**"))
            assertFalse(out.thought.contains("thoughtful"))
            assertTrue(filter.leaked)
        }
    }

    @Test
    fun leakWithoutEndUsesFinalLabel() {
        val leaked = "<|channel> thoughtful\n- 분석 1단계: 감정적이다.\n\n---\n**최종 출력:** 근데 뭔데 그렇게 대단하다 싶냐."
        val out = stream(leaked)
        assertEquals("근데 뭔데 그렇게 대단하다 싶냐.", out.answer)
    }

    @Test
    fun leakWithoutEndOrLabelUsesLastParagraph() {
        val leaked = "<|channel>thought\n- 분석: 인사다.\n- 결론: 반갑게.\n\n안녕! 반가워."
        assertEquals("안녕! 반가워.", stream(leaked).answer)
    }

    @Test
    fun strayControlTokensAreRemoved() {
        val filter = AnswerFilter()
        assertEquals("답은 42야.", stream("답은 <|think|>42야.<turn|>", 2, filter).answer)
        assertTrue(filter.leaked)
    }

    @Test
    fun runtimeThoughtHeaderIsDropped() {
        val filter = AnswerFilter()
        val parts = listOf("thought", "\n", "사용자는 ", "인사를 했다.")
        val text = parts.joinToString("") { filter.thought(it) }
        assertEquals("사용자는 인사를 했다.", text)
        val garbled = AnswerFilter()
        assertEquals("생각 중", listOf("thoughtful前", "\n생각 중").joinToString("") { garbled.thought(it) })
        val noHeader = AnswerFilter()
        assertEquals("Let me think\n.", listOf("Let me think", "\n.").joinToString("") { noHeader.thought(it) })
    }

    @Test
    fun answerIsAppendOnly() {
        // Every emitted piece must extend what was emitted before (the renderer only appends).
        val text = "값은 \$2^{10}\$ = 1024 이고 \\frac{1}{2} 는 0.5야. <|channel>x<channel|>끝"
        val filter = AnswerFilter()
        val shown = StringBuilder()
        for (c in text) shown.append(filter.answer(c.toString()).answer)
        shown.append(filter.finish().answer)
        assertEquals("값은 2¹⁰ = 1024 이고 1/2 는 0.5야. 끝", shown.toString())
    }

    /** What the screen shows before the stream ends (nothing may stall a whole line). */
    private fun shownBeforeEnd(text: String): String {
        val filter = AnswerFilter()
        val shown = StringBuilder()
        for (c in text) shown.append(filter.answer(c.toString()).answer)
        return shown.toString()
    }

    @Test
    fun currencyDoesNotStallStreaming() {
        assertTrue(shownBeforeEnd("아이폰 가격은 \$799부터 시작해요. 한국 출시가는 125만 원").startsWith("아이폰 가격은 \$799부터 시작"))
        assertTrue(shownBeforeEnd("**\$5**와 **\$10** 두 가지 있어").startsWith("**\$5**와 **\$10** 두"))
        assertEquals("변수는 \$HOME/\$USER 처럼 써요.", clean("변수는 \$HOME/\$USER 처럼 써요."))
    }

    @Test
    fun indentedFenceIsCode() {
        val text = "1. 환경 변수 설정:\n    ```bash\n    export PATH=\"\$HOME/bin:\$PATH\"\n    ```\n끝 \$x^2\$"
        assertEquals("1. 환경 변수 설정:\n    ```bash\n    export PATH=\"\$HOME/bin:\$PATH\"\n    ```\n끝 x²", clean(text))
    }

    @Test
    fun displayMathDoesNotCrossFences() {
        val text = "PID 는 \$\$ 로 확인하고\n```bash\necho \$\$\n```\n끝"
        assertEquals(text, clean(text))
    }

    @Test
    fun pathsAndRegexStayAsWritten() {
        assertEquals("cd C:\\path\\to\\project 를 실행", clean("cd C:\\path\\to\\project 를 실행"))
        assertEquals("C:\\app\\log\\error.log", clean("C:\\app\\log\\error.log"))
        assertEquals("정규식 \\(\\d+\\) 는 숫자", clean("정규식 \\(\\d+\\) 는 숫자"))
        assertEquals("\\[링크 아님\\] 끝", clean("\\[링크 아님\\] 끝"))
        val hardBreak = "첫 줄\\\n```python\nx = \"\$a\$b\"\n```\n이후 \$x\$ 수식"
        assertEquals("첫 줄\\\n```python\nx = \"\$a\$b\"\n```\n이후 x 수식", clean(hardBreak))
    }

    @Test
    fun controlTokenLookalikesInCodeStay() {
        val text = "```\n<bos> <eos> <pad>\n```"
        val filter = AnswerFilter()
        assertEquals(text, stream(text, 3, filter).answer)
        assertFalse(filter.leaked)
    }

    @Test
    fun unclosedRuntimeThoughtWithLabelGivesAnswer() {
        val filter = AnswerFilter()
        filter.thought("thoughtful前\n- 분석: 화났다.\n\n**최종 출력:** 뭐가 불만이야?")
        assertEquals("뭐가 불만이야?", filter.finish().answer)
        val plain = AnswerFilter()
        plain.thought("thought\n아직 생각 중인데 예산이 끝났다")
        assertEquals("", plain.finish().answer)
    }

    @Test
    fun secondThoughtBlockLosesItsHeader() {
        val filter = AnswerFilter()
        val thoughts = StringBuilder()
        thoughts.append(filter.thought("thought\n첫 생각."))
        filter.answer("중간 답. ")
        thoughts.append(filter.thought("thought\n두 번째 생각."))
        assertEquals("첫 생각.\n\n두 번째 생각.", thoughts.toString())
    }

    @Test
    fun headerFollowedBySpace() {
        val filter = AnswerFilter()
        assertEquals("사용자는 인사했다.", filter.thought("thought") + filter.thought(" 사용자는 인사했다."))
        val markdown = AnswerFilter()
        assertEquals("**분석**\n사용자는 인사했다.", markdown.thought("**분석**\n") + markdown.thought("사용자는 인사했다."))
    }

    @Test
    fun latexMeaningIsKept() {
        assertEquals("답은 2 1/3 이에요", clean("답은 \$2\\frac{1}{3}\$ 이에요"))
        assertEquals("x ≠ 0", Latex.toText("x \\not= 0"))
        assertEquals("x ∉ A", Latex.toText("x \\not\\in A"))
        assertEquals("0.3\u0305 = 1/3", Latex.toText("0.\\overline{3} = \\frac{1}{3}"))
        assertEquals("x\u0304", Latex.toText("\\bar{x}"))
        assertEquals("Eₖ = (1/2)mv²", Latex.toText("E_k = \\frac{1}{2}mv^2"))
        assertEquals("f(x) = 1 x > 0; 0 x ≤ 0", Latex.toText("f(x) = \\begin{cases} 1 & x > 0 \\\\ 0 & x \\le 0 \\end{cases}"))
        assertEquals("1 2", Latex.toText("\\begin{array}{c|c} 1 & 2 \\end{array}"))
        assertEquals("H2O", Latex.toText("\\ce{H2O}"))
        assertEquals("a b", Latex.toText("a \\phantom{x} b"))
    }

    @Test
    fun bareFractionsKeepTheirMeaning() {
        assertEquals("답은 2 1/3 이에요.", clean("답은 2\\frac{1}{3} 이에요."))
        assertEquals("E = (1/2)mv^2", clean("E = \\frac{1}{2}mv^2"))
        assertEquals("Sₙ = (n/2)(2a + (n-1)d)", Latex.toText("S_n = \\frac{n}{2}\\left(2a + (n-1)d\\right)"))
    }

    @Test
    fun displayMathInBrackets() {
        val long = "\\[ S_n = \\frac{n}{2}\\left(2a_1 + (n-1)d\\right) = \\frac{n(a_1 + a_n)}{2} \\quad \\text{(단, } n \\ge 1 \\text{)} \\]"
        val once = clean(long)
        assertFalse(once, once.contains("\\["))
        assertEquals(once, clean(once))
        assertEquals("행렬은 1 2; 3 4 입니다.", clean("행렬은 \\[\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}\\] 입니다."))
    }

    @Test
    fun tokensInCodeAreText() {
        val inline = "토큰 `<|channel>` 은 생각 채널을 열어요. 그 다음 설명."
        val filter = AnswerFilter()
        assertEquals(inline, stream(inline, 1, filter).answer)
        assertFalse(filter.leaked)
        val fenced = "```kotlin\nval s = \"<|channel>\"\n```\n이건 코드야."
        assertEquals(fenced, clean(fenced))
        val other = "Qwen 형식:\n```\n<|im_start|>user\n안녕<|im_end|>\n```\nGPT-2의 EOS는 `<|endoftext|>`입니다."
        assertEquals(other, clean(other))
    }

    @Test
    fun koreanInsideMath() {
        assertEquals("넓이 = 가로 × 세로 입니다.", clean("\$넓이 = 가로 \\times 세로\$ 입니다."))
        assertEquals("넓이는 넓이 = 가로 × 세로 예요.", clean("넓이는 \\(넓이 = 가로 \\times 세로\\) 예요."))
        val pid = "bash 에서 PID 는 \$\$ 로 확인해요. " + "설명이 이어져요. ".repeat(20)
        assertTrue(shownBeforeEnd(pid).length > 100)
    }

    @Test
    fun recoveredAnswerIsClean() {
        val filter = AnswerFilter()
        val out = stream("<|channel> thoughtful\n사용자는 인사를 했다. 반갑게 인사하자.", 3, filter)
        assertFalse(out.answer, out.answer.contains("thoughtful"))
        assertFalse(out.answer, out.answer.contains("사용자는 인사를"))
        val closed = AnswerFilter()
        assertEquals("", stream("<|channel>thought\n사용자가 인사했다. 짧게 답하자.<channel|>", 3, closed).answer)
        val runtime = AnswerFilter()
        runtime.thought("thought\n분석.\n\n최종 출력: 안녕!")
        assertEquals("안녕!", runtime.finish().answer)
        assertTrue(runtime.recovered)
    }

    @Test
    fun thoughtBlocksAreSeparated() {
        val filter = AnswerFilter()
        val thoughts = StringBuilder(filter.thought("thought\n첫 생각."))
        thoughts.append(filter.answer("<|channel>둘째 생각<channel|>답").thought)
        thoughts.append(filter.finish().thought)
        assertEquals("첫 생각.\n\n둘째 생각", thoughts.toString())
    }

    @Test
    fun smallLatexFixes() {
        assertEquals("df/dx |ₓ₌₀ 값", clean("\$\\left. \\frac{df}{dx} \\right|_{x=0}\$ 값"))
        assertEquals("온도는 25° C", clean("온도는 25^\\circ C"))
        assertEquals("The total is \$5.99 plus tax, and x² is the square.", clean("The total is \$5.99 plus tax, and \$x^2\$ is the square."))
    }

    @Test
    fun latexDetails() {
        assertEquals("ℝ", Latex.toText("\\mathbb{R}"))
        assertEquals("90°", Latex.toText("90^\\circ"))
        assertEquals("C(n, k)", Latex.toText("\\binom{n}{k}"))
        assertEquals("∛(x+1)", Latex.toText("\\sqrt[3]{x+1}"))
        assertEquals("(x + 1)", Latex.toText("\\left( x + 1 \\right)"))
        assertEquals("1,000원", Latex.toText("1{,}000\\text{원}"))
    }
}
