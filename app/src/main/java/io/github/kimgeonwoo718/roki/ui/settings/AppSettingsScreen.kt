package io.github.kimgeonwoo718.roki.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.settings.AppSettings
import io.github.kimgeonwoo718.roki.settings.ThemeMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 설정: 다크모드, 온도, 시스템 프롬프트. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    settings: AppSettings,
    defaultSystemPrompt: String,
    defaultTemperature: Double,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    // The system prompt is the only field that is not saved on every change: an unsaved edit is
    // kept when leaving the screen (back gesture or the arrow) instead of being thrown away.
    val savedPrompt = settings.customSystemPrompt.ifBlank { defaultSystemPrompt }
    var promptDraft by rememberSaveable(savedPrompt) { mutableStateOf(savedPrompt) }
    val savePrompt = {
        val text = promptDraft.trim()
        if (text.isNotBlank() && text != savedPrompt) {
            onChange { it.copy(customSystemPrompt = if (text == defaultSystemPrompt) "" else text) }
        }
    }
    val leave = {
        savePrompt()
        onBack()
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 화면 모드
            Section(stringResource(R.string.settings_theme)) {
                val options = listOf(
                    ThemeMode.SYSTEM to R.string.settings_theme_system,
                    ThemeMode.LIGHT to R.string.settings_theme_light,
                    ThemeMode.DARK to R.string.settings_theme_dark,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { onChange { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            HorizontalDivider()

            // 온도
            Section(
                stringResource(R.string.settings_temperature),
                stringResource(R.string.settings_temperature_desc),
            ) {
                val current = settings.temperature ?: defaultTemperature
                // Local value while dragging; saved when the finger lifts.
                var dragging by rememberSaveable(current) { mutableFloatStateOf(current.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        String.format(Locale.US, "%.1f", dragging),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Slider(
                        value = dragging,
                        onValueChange = { dragging = (it * 10).roundToInt() / 10f },
                        onValueChangeFinished = {
                            // Landing on the default keeps "follow the model default" (null).
                            val value = dragging.toDouble().takeIf { abs(it - defaultTemperature) >= 0.05 }
                            onChange { s -> s.copy(temperature = value) }
                        },
                        valueRange = AppSettings.MIN_TEMPERATURE.toFloat()..AppSettings.MAX_TEMPERATURE.toFloat(),
                        steps = 18,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (settings.temperature != null) {
                    TextButton(onClick = { onChange { it.copy(temperature = null) } }) {
                        Text(stringResource(R.string.settings_reset_default, String.format(Locale.US, "%.1f", defaultTemperature)))
                    }
                }
            }
            HorizontalDivider()

            // 시스템 프롬프트
            Section(
                stringResource(R.string.settings_system_prompt),
                stringResource(R.string.settings_system_prompt_desc),
            ) {
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it.take(AppSettings.MAX_SYSTEM_PROMPT_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    supportingText = { Text("${promptDraft.length}/${AppSettings.MAX_SYSTEM_PROMPT_CHARS}") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = savePrompt,
                        enabled = promptDraft.isNotBlank() && promptDraft.trim() != savedPrompt,
                    ) { Text(stringResource(R.string.drawer_save)) }
                    TextButton(
                        onClick = {
                            promptDraft = defaultSystemPrompt
                            onChange { it.copy(customSystemPrompt = "") }
                        },
                        enabled = settings.customSystemPrompt.isNotBlank() || promptDraft != defaultSystemPrompt,
                    ) { Text(stringResource(R.string.settings_system_prompt_reset)) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, description: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}
