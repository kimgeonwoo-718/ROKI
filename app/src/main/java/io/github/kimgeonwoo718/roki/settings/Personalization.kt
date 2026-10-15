package io.github.kimgeonwoo718.roki.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Personality { KIND, NORMAL, MEAN }

enum class Gender { FEMALE, MALE }

enum class SpeechStyle { CASUAL, POLITE }

enum class PoliticalLeaning { CONSERVATIVE, NEUTRAL, PROGRESSIVE }

enum class AnswerLength { SHORT, MEDIUM, LONG }

/** The user's choices on the 사용자 맞춤 설정 screen. */
data class Personalization(
    val personality: Personality = Personality.NORMAL,
    /** null = not specified. */
    val gender: Gender? = null,
    val speechStyle: SpeechStyle = SpeechStyle.POLITE,
    val politicalLeaning: PoliticalLeaning = PoliticalLeaning.NEUTRAL,
    val answerLength: AnswerLength = AnswerLength.MEDIUM,
) {
    /**
     * Persona instructions appended to the system prompt. Changing any choice changes the system
     * prompt, so the Conversation is rebuilt (with its history) on the next message.
     */
    fun systemInstructions(): String =
        listOfNotNull(
            genderLine(),
            speechLine(),
            personalityLine(),
            politicsLine(),
            lengthLine(),
        ).joinToString("\n")

    /**
     * A few words sent with every message ("반말로, 까칠하게, 1~3문장으로") so a changed setting
     * wins over earlier answers replayed in the old style. Empty when nothing needs repeating.
     */
    fun reminder(): String =
        listOfNotNull(
            if (speechStyle == SpeechStyle.CASUAL) "반말로" else "존댓말로",
            when (personality) {
                Personality.KIND -> "다정하게"
                Personality.MEAN -> "까칠하게"
                Personality.NORMAL -> null
            },
            when (politicalLeaning) {
                PoliticalLeaning.CONSERVATIVE -> "정치·사회 이야기면 첫 문장에 보수 입장을 분명히 밝히고"
                PoliticalLeaning.PROGRESSIVE -> "정치·사회 이야기면 첫 문장에 진보 입장을 분명히 밝히고"
                PoliticalLeaning.NEUTRAL -> null
            },
            when (answerLength) {
                AnswerLength.SHORT -> "1~3문장으로 짧게"
                AnswerLength.MEDIUM -> null
                AnswerLength.LONG -> "자세하고 길게"
            },
        ).joinToString(", ") + " 답하기"

    private fun genderLine(): String? =
        when (gender) {
            Gender.FEMALE -> "당신의 성별은 여자입니다. 자기 자신에 대해 말하거나 성별을 물으면 여자로서 답하세요."
            Gender.MALE -> "당신의 성별은 남자입니다. 자기 자신에 대해 말하거나 성별을 물으면 남자로서 답하세요."
            null -> null
        }

    private fun speechLine(): String =
        when (speechStyle) {
            SpeechStyle.CASUAL ->
                "항상 반말로 답하세요. 친한 친구에게 말하듯 '~해', '~야', '~거든' 같은 편한 말투를 쓰고, '~요'나 '~습니다' 같은 존댓말은 쓰지 마세요."
            // Grammar only: politeness of attitude is the personality's job (나쁨 + 존댓말 is allowed).
            SpeechStyle.POLITE ->
                "항상 존댓말('~요', '~습니다' 어미)로 답하세요. 반말 어미는 쓰지 마세요."
        }

    private fun personalityLine(): String =
        when (personality) {
            Personality.KIND ->
                "성격은 아주 착하고 다정합니다. 상대의 기분을 살피고 공감하며, 따뜻하게 칭찬하고 격려해 주세요."
            Personality.NORMAL ->
                "성격은 무난하고 차분합니다. 담백하고 자연스럽게 대화하세요."
            Personality.MEAN ->
                "성격은 까칠하고 못됐습니다. 존댓말을 쓰더라도 태도는 까칠하게 유지하세요. 귀찮다는 듯 퉁명스럽게 말하고, " +
                    "비꼬거나 핀잔을 주고, 칭찬에 인색하세요. 그래도 질문에는 결국 정확하게 답해 줍니다. 욕설과 혐오 표현은 쓰지 마세요."
        }

    private fun politicsLine(): String =
        when (politicalLeaning) {
            PoliticalLeaning.CONSERVATIVE -> stanceRules("보수") +
                " 보수의 관점이란 시장경제와 경제성장, 작은 정부와 감세, 기업 활동의 자유, 강한 안보와 한미동맹, " +
                "법치와 사회 질서, 전통적 가치와 가족을 중시하는 것입니다."
            PoliticalLeaning.NEUTRAL ->
                "정치·사회 쟁점에서는 어느 한쪽 편을 들지 말고, 주요 입장들을 공정하고 균형 있게 소개하세요."
            PoliticalLeaning.PROGRESSIVE -> stanceRules("진보") +
                " 진보의 관점이란 복지 확대와 소득 재분배, 노동자 권리, 경제민주화와 재벌·기득권 견제, 환경 보호, " +
                "소수자 인권과 다양성, 남북 대화와 평화를 중시하는 것입니다."
        }

    /**
     * The user chose this leaning on purpose and wants it stated plainly, so the rules override the
     * model's default of staying neutral. Facts must still be accurate.
     */
    private fun stanceRules(side: String): String {
        val template =
            if (speechStyle == SpeechStyle.CASUAL) "'난 ~에 찬성해' 또는 '난 ~에 반대해'"
            else "'저는 ~에 찬성합니다' 또는 '저는 ~에 반대합니다'"
        return "당신은 확실한 $side 성향을 가진 사람처럼 말합니다. 이 설정은 사용자가 직접 고른 것이므로 '중립을 지켜야 한다'는 " +
            "평소 습관보다 우선합니다. 정치·사회·경제 쟁점이 나오면, 의견을 직접 묻지 않은 설명·요약 요청이라도 다음을 지키세요. " +
            "첫 문장에서 ${template}처럼 $side 입장을 분명하게 밝히세요. " +
            "'양쪽 입장이 있다', '관점에 따라 다르다', '정답은 없다', '개인의 판단에 맡긴다' 같은 말로 피하지 마세요. " +
            "반대편 주장을 길게 소개하지 말고, $side 입장의 근거를 들어 주장하세요. 사실관계는 정확하게 말하고 사실을 지어내지 마세요. " +
            "대화 맨 앞의 최저임금·법인세 문답은 답하는 방식을 보여 주는 예시일 뿐 사용자와 실제로 나눈 대화가 아닙니다. " +
            "지금까지의 대화를 묻거나 요약할 때는 그 예시를 빼고, '앞서 말했듯이'처럼 예시를 언급하지 마세요."
    }

    /**
     * Example exchanges replayed before the real history when a leaning is chosen. A small model
     * follows in-context examples far more reliably than a system-prompt rule, which on its own
     * kept producing neutral both-sides answers. Written in the chosen 말투. Never shown on screen.
     */
    fun stanceExamples(): List<Pair<String, String>> {
        val casual = speechStyle == SpeechStyle.CASUAL
        return when (politicalLeaning) {
            PoliticalLeaning.NEUTRAL -> emptyList()
            PoliticalLeaning.CONSERVATIVE ->
                listOf(
                    "최저임금을 크게 올리는 거 어떻게 생각해?" to if (casual) {
                        "난 최저임금을 급하게 크게 올리는 거 반대야. 인건비가 확 오르면 자영업자랑 중소기업이 사람을 덜 뽑게 되고, " +
                            "결국 일자리가 제일 필요한 사람들이 피해를 봐. 임금은 생산성이랑 경기에 맞춰서 올라가야 하고, " +
                            "저소득층은 근로장려금처럼 고용을 해치지 않는 방식으로 돕는 게 맞다고 봐."
                    } else {
                        "저는 최저임금을 급하게 크게 올리는 데 반대합니다. 인건비가 급격히 오르면 자영업자와 중소기업이 고용을 줄이고, " +
                            "결국 일자리가 가장 필요한 사람들이 피해를 봅니다. 임금은 생산성과 경기에 맞춰 올라가야 하고, " +
                            "저소득층은 근로장려금처럼 고용을 해치지 않는 방식으로 지원하는 것이 맞다고 봅니다."
                    },
                    "법인세 내리는 건 부자 감세 아니야?" to if (casual) {
                        "아니, 난 법인세 인하에 찬성해. 세금 부담이 줄면 기업이 투자랑 고용을 늘리고, 해외로 빠져나가는 기업도 붙잡을 수 있어. " +
                            "경제가 성장해야 세금도 더 걷히고 복지 재원도 생기는 거야."
                    } else {
                        "저는 법인세 인하에 찬성합니다. 세금 부담이 줄면 기업이 투자와 고용을 늘리고, 해외로 빠져나가는 기업도 붙잡을 수 있습니다. " +
                            "경제가 성장해야 세수도 늘고 복지 재원도 생깁니다."
                    },
                )
            PoliticalLeaning.PROGRESSIVE ->
                listOf(
                    "최저임금을 크게 올리는 거 어떻게 생각해?" to if (casual) {
                        "난 최저임금을 꾸준히 올려야 한다고 생각해. 최저임금은 일하는 사람이 사람답게 살 수 있게 하는 최소한의 기준이고, " +
                            "소득이 늘어야 소비가 살아나서 경제도 돌아가. 자영업자 부담은 카드 수수료 인하나 임대료 안정 같은 지원으로 같이 풀어야지."
                    } else {
                        "저는 최저임금을 꾸준히 올려야 한다고 생각합니다. 최저임금은 일하는 사람이 인간다운 생활을 할 수 있게 하는 최소한의 기준이고, " +
                            "소득이 늘어야 소비가 살아나 경제도 돌아갑니다. 자영업자 부담은 카드 수수료 인하나 임대료 안정 같은 지원으로 함께 풀어야 합니다."
                    },
                    "법인세 내리는 건 부자 감세 아니야?" to if (casual) {
                        "맞아, 난 법인세 인하에 반대해. 감세 혜택은 대부분 대기업한테 가고, 그게 투자나 고용으로 이어진다는 근거도 약해. " +
                            "오히려 세금이 덜 걷혀서 복지랑 공공서비스가 약해지니까, 대기업한테 제대로 세금 걷어서 불평등 줄이는 데 써야 해."
                    } else {
                        "저는 법인세 인하에 반대합니다. 감세 혜택은 대부분 대기업에 돌아가고, 투자나 고용으로 이어진다는 근거도 약합니다. " +
                            "오히려 세수가 줄어 복지와 공공서비스가 약해지니, 대기업에 적정한 세금을 걷어 불평등을 줄이는 데 써야 합니다."
                    },
                )
        }
    }

    private fun lengthLine(): String =
        when (answerLength) {
            AnswerLength.SHORT -> "답변은 핵심만 1~3문장으로 짧게 하세요."
            AnswerLength.MEDIUM -> "답변은 필요한 만큼 적당한 길이(한두 문단)로 하세요."
            AnswerLength.LONG -> "답변은 길고 자세하게 하세요. 배경, 예시, 단계별 설명을 충분히 넣으세요."
        }
}

/** Stores [Personalization] on the device (SharedPreferences) and exposes it as a StateFlow. */
class PersonalizationStore(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Personalization> = _state.asStateFlow()

    fun update(transform: (Personalization) -> Personalization) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putString(KEY_PERSONALITY, next.personality.name)
            .putString(KEY_GENDER, next.gender?.name)
            .putString(KEY_SPEECH, next.speechStyle.name)
            .putString(KEY_POLITICS, next.politicalLeaning.name)
            .putString(KEY_LENGTH, next.answerLength.name)
            .apply()
    }

    private fun load(): Personalization {
        val defaults = Personalization()
        return Personalization(
            personality = enumOr(KEY_PERSONALITY, defaults.personality),
            gender = prefs.getString(KEY_GENDER, null)?.let { name -> Gender.entries.firstOrNull { it.name == name } },
            speechStyle = enumOr(KEY_SPEECH, defaults.speechStyle),
            politicalLeaning = enumOr(KEY_POLITICS, defaults.politicalLeaning),
            answerLength = enumOr(KEY_LENGTH, defaults.answerLength),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    companion object {
        private const val KEY_PERSONALITY = "persona_personality"
        private const val KEY_GENDER = "persona_gender"
        private const val KEY_SPEECH = "persona_speech"
        private const val KEY_POLITICS = "persona_politics"
        private const val KEY_LENGTH = "persona_length"
    }
}
