package io.github.kimgeonwoo718.roki.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.llm.ReasoningLevel

@StringRes
fun ReasoningLevel.labelRes(): Int =
    when (this) {
        ReasoningLevel.LOW -> R.string.reasoning_low
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium
        ReasoningLevel.HIGH -> R.string.reasoning_high
        ReasoningLevel.VERY_HIGH -> R.string.reasoning_very_high
        ReasoningLevel.MAX -> R.string.reasoning_max
    }

@StringRes
private fun ReasoningLevel.descriptionRes(): Int =
    when (this) {
        ReasoningLevel.LOW -> R.string.reasoning_low_desc
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium_desc
        ReasoningLevel.HIGH -> R.string.reasoning_high_desc
        ReasoningLevel.VERY_HIGH -> R.string.reasoning_very_high_desc
        ReasoningLevel.MAX -> R.string.reasoning_max_desc
    }

/** Chip above the input field: "추론: 중간 ▾" opening the five levels. */
@Composable
fun ReasoningSelector(level: ReasoningLevel, enabled: Boolean, onChange: (ReasoningLevel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            label = { Text(stringResource(R.string.reasoning_chip, stringResource(level.labelRes()))) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReasoningLevel.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(option.labelRes()), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(option.descriptionRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = { if (option == level) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}
