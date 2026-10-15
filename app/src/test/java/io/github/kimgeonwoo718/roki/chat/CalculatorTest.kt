package io.github.kimgeonwoo718.roki.chat

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {
    private fun one(prompt: String): Calculator.Result {
        val results = Calculator.evaluateAll(prompt)
        assertEquals("results for '$prompt': $results", 1, results.size)
        return results[0]
    }

    private fun none(prompt: String) {
        assertEquals("no results expected for '$prompt'", emptyList<Calculator.Result>(), Calculator.evaluateAll(prompt))
    }

    private fun grouped(value: BigInteger): String = String.format("%,d", value)

    @Test
    fun bigMultiplicationIsExact() {
        val r = one("2928473773×292828827는?")
        val expected = BigInteger("2928473773") * BigInteger("292828827")
        assertEquals(grouped(expected), r.value)
        assertEquals("2928473773 × 292828827", r.expression)
        assertFalse(r.approximate)
        assertTrue(r.reading!!.contains("경"))
    }

    @Test
    fun operatorsAndPrecedence() {
        assertEquals("14", one("2+3*4는?").value)
        assertEquals("20", one("(2+3)×4").value)
        assertEquals("-4", one("-2^2 = ?").value)
        assertEquals("512", one("2^3^2").value)
        assertEquals("1,024", one("2의 10제곱은?").value)
        assertEquals("15", one("3 곱하기 5는 얼마야").value)
        assertEquals("12", one("3x4는?").value)
        assertEquals("1,000,000", one("1,000 * 1,000").value)
        assertEquals("2.5", one("5/2는?").value)
        assertEquals("7", one("10-3은?").value)
        assertEquals("3", one("１＋２").value)
    }

    @Test
    fun inexactDivisionIsMarkedApproximate() {
        val r = one("1÷3")
        assertTrue(r.approximate)
        assertTrue(r.value.startsWith("0.3333"))
    }

    @Test
    fun divisionByZero() {
        assertEquals("0으로 나눌 수 없음", one("5÷0").value)
    }

    @Test
    fun hugePowerIsScientific() {
        val r = one("9^1000")
        assertTrue(r.value, r.value.contains("× 10^954"))
        assertTrue(r.approximate)
    }

    @Test
    fun notArithmetic() {
        none("010-1234-5678로 전화해")
        none("2026-10-16에 만나")
        none("1~3문장으로 답해")
        none("2x+3=7 풀어줘")
        none("버전 0.3.0 나왔어")
        none("10/16에 보자")
        none("1-2-3 순서로")
        none("오늘 3시 30분")
        none("```\n1+1\n```")
        none("숫자 1, 2, 3")
    }

    @Test
    fun namesRangesAndScoresAreNotSums() {
        none("3-4일 여행 코스 추천해줄래?")
        none("2020-2024년 집값 어때?")
        none("수학 1-1 문제 답 알려줘")
        none("5-6명이 갈 식당 추천해줘?")
        none("3-2로 이겼어?")
        none("3-2는 이긴거야?")
        none("9:00-18:00 근무야?")
        none("10-15% 수익률이면 좋아?")
        none("1588-1234 번호 맞아?")
        none("편의점 1+1 행사 뭐가 좋아?")
        none("2+2 할인 언제까지야")
        none("3x3 큐브 공식 알려줘")
        none("1920x1080 해상도면 괜찮아?")
        none("24/7 영업하는 곳?")
        none("3/4 분기 실적 어때")
        none("1000+10%는?")
        none(".5+.5는?")
        none("2024-2025 몇 년이야?")
    }

    @Test
    fun askedSums() {
        assertEquals(grouped(BigInteger("123456789") * BigInteger("987654321")), one("123456789 * 987654321 계산해줘").value)
        assertEquals("14.285714285714285714", one("100/7").value)
        assertEquals("643", one("1000 빼기 357").value)
        assertEquals("7", one("10-3은 얼마야?").value)
        assertEquals("2,073,600", one("1920x1080은 몇 픽셀이야?").value)
        val two = Calculator.evaluateAll("3+4, 5+6 계산해줘")
        assertEquals(listOf("7", "11"), two.map { it.value })
        val exact = one("10^-2는?")
        assertEquals("0.01", exact.value)
        assertFalse(exact.approximate)
    }

    @Test
    fun naturalRequests() {
        val big = grouped(BigInteger("2928473773") * BigInteger("292828827"))
        assertEquals(big, one("2928473773×292828827 좀 계산해줘").value)
        assertEquals(big, one("2928473773×292828827 정확히 얼마야?").value)
        assertEquals(big, one("2928473773 곱하기 292828827 구하면?").value)
        assertEquals(big, one("다음을 계산해줘: 2928473773×292828827.").value)
        assertEquals(grouped(BigInteger("1234") * BigInteger("5678")), one("계산해줘 1234×5678 ㅋㅋ").value)
        assertEquals("7,006,652", one("1234×5678.").value)
        assertEquals(listOf("56,088", "9,468"), Calculator.evaluateAll("123*456, 789*12 각각 계산해줘").map { it.value })
        assertEquals("2", one("1+1은?").value)
    }

    @Test
    fun promotionsAndNamesAreNotSums() {
        none("편의점 1+1 뭐 있어?")
        none("CU 1+1은 뭐가 있어?")
        none("올리브영 2+1")
        none("S26 울트라 1+1")
        none("1+1이랑 2+1 중에 뭐가 더 이득이야?")
        none("1+1은 왜 2야?")
        none("4x4가 좋아?")
        none("1920x1080이 FHD야?")
        none("24/7이 뭐야?")
        none("7-11이 뭐야?")
        none("점수 3-2")
    }

    @Test
    fun longMessageStaysFast() {
        val text = (1..1000).joinToString("\n") { "2024-01-5, 매출 1500, 비용 1000, 이익 500" } + "\n이 데이터 요약해줘"
        val started = System.nanoTime()
        Calculator.evaluateAll(text)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 1000)
    }

    @Test
    fun noteMentionsValue() {
        val results = Calculator.evaluateAll("12345×6789는?")
        val note = Calculator.note(results, "12345×6789는?")!!
        assertTrue(note, note.contains("83,810,205"))
        assertTrue(note, note.contains("이 값을 그대로"))
        val why = Calculator.note(Calculator.evaluateAll("0.1+0.2는 왜 0.30000000000000004야?"), "0.1+0.2는 왜 0.30000000000000004야?")
        assertTrue(why.orEmpty(), why == null || why.contains("이유"))
    }
}
