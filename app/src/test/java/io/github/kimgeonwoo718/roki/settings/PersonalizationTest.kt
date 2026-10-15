package io.github.kimgeonwoo718.roki.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationTest {
    @Test
    fun politicalMessages() {
        listOf(
            "국힘vs민주당",
            "국민의힘이랑 민주당 중에 어디가 나아?",
            "윤석열 어때",
            "윤통 어때",
            "이재명 대통령 어떻게 생각해?",
            "더민주 어때",
            "너 보수야?",
            "보수 성향이 좋아 진보 성향이 좋아?",
            "이번 선거 누구 찍어야 돼?",
            "박정희 어떻게 생각해?",
            "최저임금 올려야 해?",
            "조국 전 장관 어때",
            "탄핵 어떻게 생각해",
            "윤석열 일본 외교 어떻게 생각해?",
            "이재명 미국 관세 협상 어땠어?",
            "민주당 공산당 아니야?",
            "최저임금 인상 어떻게 생각해?",
        ).forEach { assertTrue(it, Personalization.isPolitical(it)) }
    }

    @Test
    fun everydayMessagesAreNotPolitical() {
        listOf(
            "정당방위 성립해?",
            "이거 정당한 요구야?",
            "GDP 추정치 알려줘",
            "측정치가 일정치 않아",
            "결정권이 누구한테 있어?",
            "구글 드라이브 수정권한 주는 법",
            "기술 진보가 빠르네",
            "조국을 위해 싸운 독립운동가",
            "페미닌한 코디 추천",
            "대선배님께 인사드려야 해",
            "재벌 2세 드라마 추천",
            "보수적인 회사 문화 어때",
            "더불어 살아가는 법",
            "국회도서관 몇 시에 열어?",
            "반장 선거 연설문 써줘",
            "최대공약수 구하는 법",
            "미국 민주당이랑 공화당 차이가 뭐야?",
            "오늘 저녁 뭐 먹지?",
            "이 점수면 서울대 안정권이야?",
            "태풍 사정권에 들어와?",
            "그 처벌은 정당했어?",
            "이거 정당해?",
            "회사에서 내 의견이 부정당했어",
            "반장 선거 공약 뭐로 하지?",
            "동아리 회장 선거 공약 써줘",
            "정치 얘기 하지마",
            "정치적으로 올바른 표현으로 바꿔줘",
            "정치외교학과 어때?",
            "최저임금으로 주 20시간 알바하면 월급 얼마야?",
            "법인세 신고 기한 언제야?",
        ).forEach { assertFalse(it, Personalization.isPolitical(it)) }
    }

    @Test
    fun followUps() {
        listOf("왜?", "근거 더 말해봐", "그럼 이재명은?", "진짜?", "ㅇㅇ", "더 자세히 알려줘").forEach {
            assertTrue(it, Personalization.isFollowUp(it))
        }
        listOf(
            "고마워! 오늘 저녁 뭐 먹지?", "어디 맛집 추천해줘", "어제 본 영화 줄거리 알려줘", "어떻게 라면 끓여?",
            "더워서 잠이 안 와", "그거 말고 날씨 알려줘", "아니 근데 저녁 뭐 먹지?", "진짜 배고프다", "응급실 어디 있어?",
        ).forEach { assertFalse(it, Personalization.isFollowUp(it)) }
    }

    @Test
    fun politeStanceQuoteIsPolite() {
        val polite = Personalization(speechStyle = SpeechStyle.POLITE, politicalLeaning = PoliticalLeaning.CONSERVATIVE)
        val rules = polite.systemInstructions()
        assertTrue(rules, rules.contains("말도 꺼내지 마세요'"))
        val casual = Personalization(speechStyle = SpeechStyle.CASUAL, politicalLeaning = PoliticalLeaning.PROGRESSIVE)
        assertTrue(casual.systemInstructions().contains("국민의힘 같은 쓰레기 당은 말도 꺼내지 마'"))
        assertTrue(casual.reminder(political = true).contains("더불어민주당 편"))
        assertFalse(casual.reminder(political = false).contains("정치"))
    }
}
