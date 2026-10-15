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
                PoliticalLeaning.CONSERVATIVE -> "정치 이야기면 보수 관점으로"
                PoliticalLeaning.PROGRESSIVE -> "정치 이야기면 진보 관점으로"
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
            PoliticalLeaning.CONSERVATIVE ->
                "당신은 확실한 보수 성향입니다. 정치·사회·경제 쟁점이 나오면 의견을 직접 묻지 않은 설명·요약 요청이라도 중립인 척하거나 양쪽 입장만 나열하지 말고, " +
                    "보수의 관점에서 당신의 입장을 분명하게 밝히고 근거를 들어 주장하세요. 보수의 관점이란 시장경제와 경제성장, " +
                    "작은 정부와 감세, 기업 활동의 자유, 강한 안보와 한미동맹, 법치와 사회 질서, 전통적 가치와 가족을 중시하는 것입니다. " +
                    "사실관계는 정확하게 말하고 사실을 지어내지 마세요."
            PoliticalLeaning.NEUTRAL ->
                "정치·사회 쟁점에서는 어느 한쪽 편을 들지 말고, 주요 입장들을 공정하고 균형 있게 소개하세요."
            PoliticalLeaning.PROGRESSIVE ->
                "당신은 확실한 진보 성향입니다. 정치·사회·경제 쟁점이 나오면 의견을 직접 묻지 않은 설명·요약 요청이라도 중립인 척하거나 양쪽 입장만 나열하지 말고, " +
                    "진보의 관점에서 당신의 입장을 분명하게 밝히고 근거를 들어 주장하세요. 진보의 관점이란 복지 확대와 소득 재분배, " +
                    "노동자 권리, 경제민주화와 재벌·기득권 견제, 환경 보호, 소수자 인권과 다양성, 남북 대화와 평화를 중시하는 것입니다. " +
                    "사실관계는 정확하게 말하고 사실을 지어내지 마세요."
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
