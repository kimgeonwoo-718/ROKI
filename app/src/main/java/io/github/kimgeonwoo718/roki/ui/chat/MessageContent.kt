package io.github.kimgeonwoo718.roki.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.chat.ChatTurn
import io.github.kimgeonwoo718.roki.ui.formatSeconds

/**
 * Renders model output as Markdown (headings, bold, lists, code blocks) so "##" and "**" do not
 * show up raw. Headings are scaled down from the library defaults (display sizes) to fit a chat.
 *
 * While [streaming], every new token is appended to an incremental streaming parser, so the
 * answer grows token by token (smooth, like plain text) without re-parsing the whole answer.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    streaming: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val colors = markdownColor(
        text = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
    )
    val typography = chatTypography(small)
    // No size animation: the list's auto-scroll must see the final height right away.
    val animations = markdownAnimations(animateTextSize = { this })
    // An answer that started streaming keeps the streaming renderer after it finishes: swapping
    // renderers at the end would re-parse and re-lay out the whole answer in one frame (a hitch).
    val startedStreaming = remember { streaming }
    if (streaming || startedStreaming) {
        StreamingMarkdown(text, modifier, colors, typography)
    } else {
        // immediate: parse synchronously so a finished answer never shows an empty frame.
        val state = rememberMarkdownState(text, retainState = true, immediate = true)
        Markdown(
            markdownState = state,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            typography = typography,
            animations = animations,
        )
    }
}

@Composable
private fun StreamingMarkdown(
    text: String,
    modifier: Modifier,
    colors: MarkdownColors,
    typography: MarkdownTypography,
) {
    // A new parser whenever the text stops extending what was already appended (e.g. a retry).
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        val state = rememberStreamingMarkdownState()
        val latest by rememberUpdatedState(text)
        // Until the first append lands, render the text with a synchronous parse so the item is
        // never empty for a frame (scroll jumps on rotation or when the first token arrives).
        var primed by remember { mutableStateOf(false) }
        LaunchedEffect(state) {
            var appended = 0
            snapshotFlow { latest }.collect { current ->
                when {
                    current.length < appended || !current.startsWith(state.content) -> generation++
                    current.length > appended -> {
                        val delta = current.substring(appended)
                        appended = current.length
                        state.append(delta)
                        primed = true
                    }
                }
            }
        }
        val animations = markdownAnimations(animateTextSize = { this })
        if (primed) {
            Markdown(
                streamingMarkdownState = state,
                modifier = modifier.fillMaxWidth(),
                colors = colors,
                typography = typography,
                animations = animations,
            )
        } else {
            Markdown(
                markdownState = rememberMarkdownState(text, retainState = true, immediate = true),
                modifier = modifier.fillMaxWidth(),
                colors = colors,
                typography = typography,
                animations = animations,
            )
        }
    }
}

@Composable
private fun chatTypography(small: Boolean): MarkdownTypography {
    val t = MaterialTheme.typography
    val body = if (small) t.bodySmall else t.bodyLarge
    return markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        quote = body,
        h1 = if (small) t.titleSmall else t.titleLarge,
        h2 = if (small) t.titleSmall else t.titleMedium,
        h3 = t.titleSmall,
        h4 = t.titleSmall,
        h5 = body,
        h6 = body,
    )
}

/**
 * Compact reasoning indicator: "숙고하는 중…" plus the latest line of thought on one line while the
 * model thinks; afterwards a single "N초 동안 숙고함" line that can be tapped to read everything.
 */
@Composable
fun ThinkingSection(turn: ChatTurn) {
    val inProgress = turn.isThinking
    var expanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(enabled = !inProgress) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inProgress) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(
                if (inProgress) stringResource(R.string.thinking_in_progress)
                else turn.thinkingMillis?.let { stringResource(R.string.thinking_done_seconds, formatSeconds(it)) }
                    ?: stringResource(R.string.thinking_done),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!inProgress) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (inProgress) {
            Text(
                latestThoughtLine(turn.thinking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                // Keep the newest words visible while a long line is still being written.
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(start = 22.dp),
            )
        }
        AnimatedVisibility(expanded && !inProgress) {
            MarkdownText(
                text = turn.thinking,
                small = true,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .drawBehind { drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }
                    .padding(start = 12.dp),
            )
        }
    }
}

/** The last non-empty line of the thoughts so far, without Markdown markers (scans from the end). */
private fun latestThoughtLine(thinking: String): String {
    var end = thinking.length
    while (end > 0) {
        val start = thinking.lastIndexOf('\n', end - 1) + 1
        val line = thinking.substring(start, end).trim().trimStart('#', '*', '-', '>', ' ').replace("**", "")
        if (line.isNotBlank()) return line
        end = start - 1
    }
    return ""
}
