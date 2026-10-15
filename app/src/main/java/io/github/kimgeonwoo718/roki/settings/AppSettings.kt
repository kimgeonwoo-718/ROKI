package io.github.kimgeonwoo718.roki.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The 설정 screen: theme, system prompt and temperature. (Answer length lives in
 * 사용자 맞춤 설정, and the performance line under answers is always shown.)
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Replaces the built-in base system prompt when not blank (persona lines are still added). */
    val customSystemPrompt: String = "",
    /** null = the model's recommended default. */
    val temperature: Double? = null,
) {
    companion object {
        const val MIN_TEMPERATURE = 0.1
        const val MAX_TEMPERATURE = 2.0

        /** A longer base prompt would crowd the conversation history out of the context. */
        const val MAX_SYSTEM_PROMPT_CHARS = 1000
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
            }
            .apply()
    }

    private fun load(): AppSettings =
        AppSettings(
            themeMode = prefs.getString(KEY_THEME, null)
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
            customSystemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null).orEmpty().take(AppSettings.MAX_SYSTEM_PROMPT_CHARS),
            temperature = if (prefs.contains(KEY_TEMPERATURE)) prefs.getFloat(KEY_TEMPERATURE, 1f).toDouble() else null,
        )

    companion object {
        private const val KEY_THEME = "app_theme"
        private const val KEY_SYSTEM_PROMPT = "app_system_prompt"
        private const val KEY_TEMPERATURE = "app_temperature"
    }
}
