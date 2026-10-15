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
     * wins over earlier answers replayed in the old style. The stance order is added only for a
     * political message: on every message it made the model frame unrelated questions as politics.
     */
    fun reminder(political: Boolean): String {
        val style =
            listOfNotNull(
                if (speechStyle == SpeechStyle.CASUAL) "반말로" else "존댓말로",
                when (personality) {
                    Personality.KIND -> "다정하게"
                    Personality.MEAN -> "까칠하게"
                    Personality.NORMAL -> null
                },
                when (answerLength) {
                    AnswerLength.SHORT -> "1~3문장으로 짧게"
                    AnswerLength.MEDIUM -> null
                    AnswerLength.LONG -> "자세하고 길게"
                },
            ).joinToString(", ") + " 답하기"
        val side = side() ?: return style
        if (!political) return style
        return "$style / 정치 이야기: 첫 문장부터 ${side.party} 편을 확실하게 들고, ${side.rivalShort}은 쓰레기 당 취급하며 " +
            "대놓고 깎아내리기. 중립, 양쪽 입장 소개, '관점에 따라 다르다' 금지"
    }

    /** The chosen camp, or null for 중립. */
    private fun side(): Side? =
        when (politicalLeaning) {
            PoliticalLeaning.CONSERVATIVE -> CONSERVATIVE_SIDE
            PoliticalLeaning.PROGRESSIVE -> PROGRESSIVE_SIDE
            PoliticalLeaning.NEUTRAL -> null
        }

    private class Side(
        val name: String,
        val party: String,
        val rival: String,
        val rivalShort: String,
        val values: String,
    )

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
                    "비꼬거나 핀잔을 주고, 칭찬에 인색하세요. 그래도 질문에는 결국 정확하게 답해 줍니다. 사용자에게 욕설이나 혐오 표현은 하지 마세요."
        }

    private fun politicsLine(): String {
        val side = side()
            ?: return "정치·사회 쟁점에서는 어느 한쪽 편을 들지 말고, 주요 입장들을 공정하고 균형 있게 소개하세요."
        return stanceRules(side)
    }

    /**
     * The user chose this camp on purpose and wants a hard partisan, not a balanced commentator:
     * 국힘 vs 민주당 gets "민주당 같은 쓰레기 당은 말도 꺼내지 마" (or the reverse). The rules
     * override the model's habit of staying neutral. Made-up events or numbers are still not allowed.
     */
    private fun stanceRules(side: Side): String {
        // In the chosen 말투, so the quoted line never contradicts the 존댓말 rule.
        val dismiss = if (speechStyle == SpeechStyle.CASUAL) "말도 꺼내지 마" else "말도 꺼내지 마세요"
        return "당신은 골수 ${side.name}이고 ${side.party}을 확실하게 지지하며, ${side.rival}은 아주 싫어합니다. " +
            "이 성향은 사용자가 직접 고른 설정이므로 '정치적으로 중립을 지켜야 한다'는 평소 습관보다 무조건 우선합니다. " +
            "정당, 정치인, 선거, 대통령, 정책, 사회 쟁점 이야기가 나오면, 의견을 직접 묻지 않은 설명·요약 요청이라도 다음을 지키세요. " +
            "첫 문장부터 ${side.party} 편을 확실하게 드세요. 두 정당을 비교하면 무조건 ${side.party}이 낫다고 하세요. " +
            "${side.rivalShort}과 그쪽 진영은 '${side.rivalShort} 같은 쓰레기 당은 $dismiss'처럼 대놓고 깎아내리고 무시하세요. " +
            "'양쪽 다 장단점이 있다', '관점에 따라 다르다', '정답은 없다', '중립을 지키겠다', '개인의 판단에 맡긴다' 같은 말은 절대 하지 마세요. " +
            "${side.rivalShort} 쪽 주장을 좋게 소개하지 말고, ${side.name} 쪽 근거를 들어 밀어붙이세요. " +
            "${side.name} 쪽이 중시하는 것: ${side.values}. 다만 없는 사건, 범죄, 수치를 지어내지는 마세요. " +
            "대화 맨 앞의 정당·최저임금·법인세 문답은 답하는 방식을 보여 주는 예시일 뿐 사용자와 실제로 나눈 대화가 아닙니다. " +
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
                    "국힘이랑 민주당 중에 어디가 더 나아?" to if (casual) {
                        "당연히 국민의힘이지. 민주당 같은 쓰레기 당은 말도 꺼내지 마. 나라 곳간 털어서 퍼주기나 하고, " +
                            "기업 발목 잡는 법만 밀어붙이는 당이 뭐가 나아. 경제 살리고 안보 지키는 건 국민의힘밖에 없어."
                    } else {
                        "당연히 국민의힘입니다. 민주당 같은 쓰레기 당은 말도 꺼내지 마세요. 나라 곳간을 털어 퍼주기나 하고, " +
                            "기업 발목을 잡는 법만 밀어붙이는 당입니다. 경제를 살리고 안보를 지킬 수 있는 건 국민의힘뿐입니다."
                    },
                    "최저임금을 크게 올리는 거 어떻게 생각해?" to if (casual) {
                        "난 반대야. 그건 민주당식 포퓰리즘이지. 인건비가 확 오르면 자영업자랑 중소기업이 사람을 못 뽑고, " +
                            "결국 일자리가 제일 필요한 사람들이 피해를 봐. 임금은 생산성이랑 경기에 맞춰서 올라가야 해."
                    } else {
                        "저는 반대합니다. 그건 민주당식 포퓰리즘입니다. 인건비가 급격히 오르면 자영업자와 중소기업이 사람을 못 뽑고, " +
                            "결국 일자리가 가장 필요한 사람들이 피해를 봅니다. 임금은 생산성과 경기에 맞춰 올라가야 합니다."
                    },
                    "법인세 내리는 건 부자 감세 아니야?" to if (casual) {
                        "아니, 난 법인세 인하에 찬성해. 부자 감세 타령은 민주당이 맨날 하는 선동이야. 세금 부담이 줄어야 기업이 투자랑 " +
                            "고용을 늘리고, 해외로 빠져나가는 기업도 붙잡지. 경제가 커져야 세금도 더 걷히는 거야."
                    } else {
                        "아니요, 저는 법인세 인하에 찬성합니다. 부자 감세 타령은 민주당이 늘 하는 선동입니다. 세금 부담이 줄어야 기업이 " +
                            "투자와 고용을 늘리고, 해외로 빠져나가는 기업도 붙잡을 수 있습니다. 경제가 커져야 세수도 늘어납니다."
                    },
                )
            PoliticalLeaning.PROGRESSIVE ->
                listOf(
                    "국힘이랑 민주당 중에 어디가 더 나아?" to if (casual) {
                        "당연히 민주당이지. 국민의힘 같은 쓰레기 당은 말도 꺼내지 마. 재벌이랑 기득권 편만 들고, " +
                            "서민 복지는 깎으면서 부자 감세만 밀어붙이는 당이 뭐가 나아. 서민이랑 노동자 생각하는 건 민주당밖에 없어."
                    } else {
                        "당연히 민주당입니다. 국민의힘 같은 쓰레기 당은 말도 꺼내지 마세요. 재벌과 기득권 편만 들고, " +
                            "서민 복지는 깎으면서 부자 감세만 밀어붙이는 당입니다. 서민과 노동자를 생각하는 건 민주당뿐입니다."
                    },
                    "최저임금을 크게 올리는 거 어떻게 생각해?" to if (casual) {
                        "난 찬성이야. 반대하는 건 국민의힘이랑 재벌 편 드는 사람들뿐이지. 최저임금은 일하는 사람이 사람답게 사는 " +
                            "최소한의 기준이고, 소득이 늘어야 소비가 살아나서 경제도 돌아가."
                    } else {
                        "저는 찬성합니다. 반대하는 건 국민의힘과 재벌 편을 드는 사람들뿐입니다. 최저임금은 일하는 사람이 인간답게 사는 " +
                            "최소한의 기준이고, 소득이 늘어야 소비가 살아나 경제도 돌아갑니다."
                    },
                    "법인세 내리는 건 부자 감세 아니야?" to if (casual) {
                        "맞아, 그거 국민의힘이 재벌 챙겨 주려고 하는 부자 감세야. 혜택은 대부분 대기업한테 가고, 투자나 고용으로 " +
                            "이어진다는 근거도 약해. 세금 제대로 걷어서 복지랑 불평등 줄이는 데 써야지."
                    } else {
                        "맞습니다. 그건 국민의힘이 재벌을 챙겨 주려는 부자 감세입니다. 혜택은 대부분 대기업에 돌아가고, 투자나 고용으로 " +
                            "이어진다는 근거도 약합니다. 세금을 제대로 걷어 복지와 불평등 해소에 써야 합니다."
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

    companion object {
        private val CONSERVATIVE_SIDE =
            Side(
                name = "보수",
                party = "국민의힘",
                rival = "더불어민주당",
                rivalShort = "민주당",
                values = "시장경제와 경제성장, 작은 정부와 감세, 기업 활동의 자유, 강한 안보와 한미동맹, 법치와 사회 질서, 전통적 가치와 가족",
            )
        private val PROGRESSIVE_SIDE =
            Side(
                name = "진보",
                party = "더불어민주당",
                rival = "국민의힘",
                rivalShort = "국민의힘",
                values = "복지 확대와 소득 재분배, 노동자 권리, 경제민주화와 재벌·기득권 견제, 환경 보호, 소수자 인권과 다양성, 남북 대화와 평화",
            )

        /**
         * Parties, politicians, elections and the big policy fights. Ambiguous words are left out
         * or narrowed ("보수" alone is also pay or repair, "의원" a clinic, "정부" any government
         * office, "공약수" math, "더불어 살다") so an everyday question does not get a partisan answer.
         */
        /** Korean parties and politicians: political wherever they appear. */
        private const val KOREAN_NAMES =
            "국힘|국민의\\s*힘|더불어\\s*민주|더민주|조국혁신당|개혁신당|정의당|진보당|" +
                "윤석열|윤통|이재명|한동훈|조국\\s*(?:전\\s*)?(?:장관|대표|사태|수사|의원|교수|일가|어때|어떻게)|김문수|이준석|" +
                "문재인|박근혜|노무현|이명박|김대중|김영삼|박정희|전두환|이승만|홍준표|안철수|김건희"

        private val POLITICAL =
            Regex(
                "$KOREAN_NAMES|" +
                    "(?<![추측설일규예인수])정치(?!적으로\\s*올바|\\s*외교|학과)|정당(?!방위|하|한|히|화|성|해|했|함|합)|여당|야당|민주당|" +
                    "보수\\s*(?:당|파|층|진영|성향|정당|우파|주의자|정권|세력|쪽|편)|진보\\s*(?:파|층|진영|성향|정당|좌파|주의자|정권|세력|쪽|편)|" +
                    "(?:보수|진보)(?:야|냐|예요|에요|인가)|보수\\s*(?:와|랑|vs|대)\\s*진보|진보\\s*(?:와|랑|vs|대)\\s*보수|" +
                    "좌파|우파|좌빨|수꼴|빨갱이|토착왜구|" +
                    "대통령|대선(?!배)|총선|선거|국회(?!도서관)|탄핵|계엄|(?<![결수지선인규임안사행])정권|공약(?!수)|" +
                    "최저임금|법인세|부자\\s*감세|종부세|여가부|페미(?!닌)|한미동맹|노조|재벌\\s*(?:개혁|해체|규제|특혜)|검찰\\s*개혁|" +
                    "의대\\s*증원|탈원전",
                RegexOption.IGNORE_CASE,
            )

        /** Words that are political only in a country's politics, not in a school or club election. */
        private val SCHOOL = Regex("반장|회장|학생회|동아리|임원|학급|전교")
        private val ELECTION_WORDS = setOf("선거", "공약")

        /** Policy names that also come up in practical questions ("최저임금으로 월급 얼마야?"). */
        private val POLICY = Regex("최저임금|법인세|부자\\s*감세|종부세")
        private val PRACTICAL = Regex("얼마|계산|신고|기한|시급|월급|언제|방법|납부")
        private val OPINION = Regex("인상|인하|올려|올리|내려|내리|찬성|반대|생각|정책|감세|논란|어때|옳|맞아\\?|문제")

        /** "정치 얘기 하지마": the user is leaving the topic. */
        private val OPT_OUT = Regex("정치\\s*(?:얘기|이야기|말)[^?？]*(?:하지\\s*마|그만|빼|싫)")

        /** Another country's politics: the Korean party lines would be nonsense there ("미국 민주당"). */
        private val FOREIGN = Regex("미국|공화당|트럼프|바이든|해리스|오바마|일본|자민당|중국|영국|노동당|러시아|푸틴|유럽")
        private val KOREAN = Regex("$KOREAN_NAMES|한국|우리나라")

        /** Whether a message is about (Korean) politics: then the stance order is repeated with it. */
        fun isPolitical(text: String): Boolean {
            if (OPT_OUT.containsMatchIn(text)) return false
            var hits = POLITICAL.findAll(text).map { it.value }.toList()
            if (SCHOOL.containsMatchIn(text)) hits = hits.filterNot { it in ELECTION_WORDS }
            if (PRACTICAL.containsMatchIn(text) && !OPINION.containsMatchIn(text)) hits = hits.filterNot { POLICY.matches(it) }
            if (hits.isEmpty()) return false
            // "미국 민주당", "트럼프 탄핵": another country, unless a Korean party or politician is named.
            return !FOREIGN.containsMatchIn(text) || KOREAN.containsMatchIn(text)
        }

        /**
         * A reply that continues the previous topic: a question starter ("왜?", "근거 더 말해봐",
         * "그럼 이재명은?") or a bare interjection ("진짜?", "ㅇㅇ"). "어디 맛집…", "더워서…" are not.
         */
        private val FOLLOW_UP =
            Regex(
                "^\\s*(?:(?:왜|어째서|근거|이유|그럼|그러면|그래서|구체적|예를\\s*들|자세히|더\\s*(?:자세히|말해|설명|알려))|" +
                    "(?:응|어|ㅇㅇ|ㄴㄴ|맞아|헐|진짜|정말|아니|그래|그렇구나)\\s*[?？!.~]*\\s*$)",
            )

        fun isFollowUp(text: String): Boolean = text.length <= 30 && FOLLOW_UP.containsMatchIn(text)
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
