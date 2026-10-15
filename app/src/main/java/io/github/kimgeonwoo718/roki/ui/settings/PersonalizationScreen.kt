package io.github.kimgeonwoo718.roki.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.settings.AnswerLength
import io.github.kimgeonwoo718.roki.settings.Gender
import io.github.kimgeonwoo718.roki.settings.Personality
import io.github.kimgeonwoo718.roki.settings.Personalization
import io.github.kimgeonwoo718.roki.settings.PoliticalLeaning
import io.github.kimgeonwoo718.roki.settings.SpeechStyle

/** 사용자 맞춤 설정: personality, gender, speech style, political leaning and answer length. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    personalization: Personalization,
    onChange: ((Personalization) -> Personalization) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.personalization_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.personalization_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow(
                title = stringResource(R.string.persona_personality),
                options = listOf(
                    Personality.KIND to R.string.persona_personality_kind,
                    Personality.NORMAL to R.string.persona_personality_normal,
                    Personality.MEAN to R.string.persona_personality_mean,
                ),
                selected = personalization.personality,
                onSelect = { value -> onChange { it.copy(personality = value) } },
            )
            ChoiceRow(
                title = stringResource(R.string.persona_gender),
                note = stringResource(R.string.persona_gender_note),
                options = listOf(
                    Gender.FEMALE to R.string.persona_gender_female,
                    Gender.MALE to R.string.persona_gender_male,
                ),
                selected = personalization.gender,
                // Tapping the selected option again clears it (not specified).
                onSelect = { value -> onChange { it.copy(gender = if (it.gender == value) null else value) } },
            )
            ChoiceRow(
                title = stringResource(R.string.persona_speech),
                options = listOf(
                    SpeechStyle.CASUAL to R.string.persona_speech_casual,
                    SpeechStyle.POLITE to R.string.persona_speech_polite,
                ),
                selected = personalization.speechStyle,
                onSelect = { value -> onChange { it.copy(speechStyle = value) } },
            )
            ChoiceRow(
                title = stringResource(R.string.persona_politics),
                note = stringResource(R.string.persona_politics_note),
                options = listOf(
                    PoliticalLeaning.CONSERVATIVE to R.string.persona_politics_conservative,
                    PoliticalLeaning.NEUTRAL to R.string.persona_politics_neutral,
                    PoliticalLeaning.PROGRESSIVE to R.string.persona_politics_progressive,
                ),
                selected = personalization.politicalLeaning,
                onSelect = { value -> onChange { it.copy(politicalLeaning = value) } },
            )
            ChoiceRow(
                title = stringResource(R.string.persona_length),
                options = listOf(
                    AnswerLength.SHORT to R.string.persona_length_short,
                    AnswerLength.MEDIUM to R.string.persona_length_medium,
                    AnswerLength.LONG to R.string.persona_length_long,
                ),
                selected = personalization.answerLength,
                onSelect = { value -> onChange { it.copy(answerLength = value) } },
            )
            Text(
                stringResource(R.string.personalization_applies_next),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, Int>>,
    selected: T?,
    onSelect: (T) -> Unit,
    note: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }
    }
}
