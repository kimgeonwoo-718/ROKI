package io.github.kimgeonwoo718.roki.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The 설정 screen: appearance, system prompt, sampling and developer options. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Replaces the built-in base system prompt when not blank (persona lines are still added). */
    val customSystemPrompt: String = "",
    /** null = the model's recommended default. */
    val temperature: Double? = null,
    /** Hard cap on answer tokens; null = no limit. Thinking tokens are budgeted separately. */
    val maxAnswerTokens: Int? = null,
    /** Shows time to first token, tokens per second and backend under each answer. */
    val developerMode: Boolean = false,
) {
    companion object {
        const val MIN_TEMPERATURE = 0.1
        const val MAX_TEMPERATURE = 2.0
        val MAX_ANSWER_CHOICES: List<Int?> = listOf(null, 256, 512, 1024, 2048)
    }
}

/** Stores [AppSettings] on the device (SharedPreferences) and exposes it as a StateFlow. */
class AppSettingsStore(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putString(KEY_THEME, next.themeMode.name)
            .putString(KEY_SYSTEM_PROMPT, next.customSystemPrompt)
            .apply {
                if (next.temperature != null) putFloat(KEY_TEMPERATURE, next.temperature.toFloat()) else remove(KEY_TEMPERATURE)
                if (next.maxAnswerTokens != null) putInt(KEY_MAX_ANSWER, next.maxAnswerTokens) else remove(KEY_MAX_ANSWER)
            }
            .putBoolean(KEY_DEVELOPER, next.developerMode)
            .apply()
    }

    private fun load(): AppSettings =
        AppSettings(
            themeMode = prefs.getString(KEY_THEME, null)
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
            customSystemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null).orEmpty(),
            temperature = if (prefs.contains(KEY_TEMPERATURE)) prefs.getFloat(KEY_TEMPERATURE, 1f).toDouble() else null,
            maxAnswerTokens = if (prefs.contains(KEY_MAX_ANSWER)) prefs.getInt(KEY_MAX_ANSWER, 0).takeIf { it > 0 } else null,
            developerMode = prefs.getBoolean(KEY_DEVELOPER, false),
        )

    companion object {
        private const val KEY_THEME = "app_theme"
        private const val KEY_SYSTEM_PROMPT = "app_system_prompt"
        private const val KEY_TEMPERATURE = "app_temperature"
        private const val KEY_MAX_ANSWER = "app_max_answer_tokens"
        private const val KEY_DEVELOPER = "app_developer_mode"
    }
}
