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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.chat.ChatTurn
import io.github.kimgeonwoo718.roki.ui.formatSeconds
import kotlinx.coroutines.delay

/** How often a still-growing text is re-parsed; the whole string is parsed each time. */
private const val STREAMING_PARSE_INTERVAL_MS = 250L

/**
 * Renders model output as Markdown (headings, bold, lists, code blocks) so "##" and "**" do not
 * show up raw. Headings are scaled down from the library defaults (display sizes) to fit a chat.
 * While [streaming], the text is sampled every [STREAMING_PARSE_INTERVAL_MS] instead of being
 * re-parsed on every token, which would keep a CPU core busy during long answers and thoughts.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    streaming: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val latest by rememberUpdatedState(text)
    var sampled by remember { mutableStateOf(text) }
    LaunchedEffect(streaming) {
        while (streaming) {
            sampled = latest
            delay(STREAMING_PARSE_INTERVAL_MS)
        }
    }
    // immediate: parse synchronously on first composition so items never appear empty (scroll
    // jumps); retainState keeps the previous rendering while the next version is parsed.
    val state = rememberMarkdownState(if (streaming) sampled else text, retainState = true, immediate = true)
    val t = MaterialTheme.typography
    val body = if (small) t.bodySmall else t.bodyLarge
    val textColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    Markdown(
        markdownState = state,
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(text = textColor),
        typography = markdownTypography(
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
        ),
        // No size animation: the list's auto-scroll must see the final height right away.
        animations = markdownAnimations(animateTextSize = { this }),
    )
}

/** Collapsible reasoning shown above the answer; open while the model is still thinking. */
@Composable
fun ThinkingSection(turn: ChatTurn) {
    val inProgress = turn.isThinking
    // null = follow the default (open while thinking, closed afterwards); set once the user taps.
    var userOpen by rememberSaveable(turn.id) { mutableStateOf<Boolean?>(null) }
    val open = userOpen ?: inProgress
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { userOpen = !open }
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
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            MarkdownText(
                text = turn.thinking,
                small = true,
                streaming = inProgress,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .drawBehind { drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }
                    .padding(start = 12.dp),
            )
        }
    }
}
