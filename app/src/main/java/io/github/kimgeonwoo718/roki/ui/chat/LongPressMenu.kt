package io.github.kimgeonwoo718.roki.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import io.github.kimgeonwoo718.roki.R

/** Wraps a message: long-press opens 복사 (and 다시 생성 for the newest answer). */
@Composable
fun LongPressMenu(
    text: String,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    enabled: Boolean = true,
    /** 빠른 생성 (MTP) decodes greedily, so a regenerated answer may come out the same. */
    speculativeHint: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.chat_copied)
    Box(
        Modifier.pointerInput(enabled) {
            if (enabled) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    open = true
                })
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("ROKI", text))
                    // Android 13+ shows its own "copied" confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (canRegenerate) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.chat_regenerate))
                            if (speculativeHint) {
                                Text(
                                    stringResource(R.string.chat_regenerate_mtp_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        open = false
                        onRegenerate()
                    },
                )
            }
        }
    }
}
