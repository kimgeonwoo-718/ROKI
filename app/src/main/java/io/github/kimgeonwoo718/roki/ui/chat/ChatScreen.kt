package io.github.kimgeonwoo718.roki.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.chat.ChatState
import io.github.kimgeonwoo718.roki.chat.ChatTurn
import io.github.kimgeonwoo718.roki.chat.TurnStatus
import io.github.kimgeonwoo718.roki.llm.BackendKind
import io.github.kimgeonwoo718.roki.llm.EngineNotice
import io.github.kimgeonwoo718.roki.llm.EngineState
import io.github.kimgeonwoo718.roki.llm.LlmError
import io.github.kimgeonwoo718.roki.llm.ReasoningLevel
import io.github.kimgeonwoo718.roki.system.GpuSource
import io.github.kimgeonwoo718.roki.system.ResourceUsage
import io.github.kimgeonwoo718.roki.ui.formatRate
import io.github.kimgeonwoo718.roki.ui.formatSeconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modelName: String,
    engineState: EngineState,
    chatState: ChatState,
    modelBroken: Boolean,
    gpuDisabled: Boolean,
    forceCpu: Boolean,
    speculative: Boolean,
    resourceUsage: ResourceUsage,
    memoryWarning: String?,
    /** null when the model has no reasoning (thinking) support. */
    reasoningLevel: ReasoningLevel?,
    onReasoningLevelChange: (ReasoningLevel) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryGpu: () -> Unit,
    onRedownload: () -> Unit,
    onForceCpuChange: (Boolean) -> Unit,
    onSpeculativeChange: (Boolean) -> Unit,
    onRegenerate: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.menu_open_drawer))
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, maxLines = 1)
                            Spacer(Modifier.size(10.dp))
                            UsageText(resourceUsage, Modifier.weight(1f, fill = false))
                        }
                        Text(
                            engineSubtitle(modelName, engineState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat, enabled = chatState.turns.isNotEmpty()) {
                        Icon(Icons.Outlined.AddComment, contentDescription = stringResource(R.string.chat_new))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.menu_force_cpu))
                                        Text(
                                            stringResource(R.string.menu_force_cpu_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailingIcon = { Checkbox(checked = forceCpu, onCheckedChange = null) },
                                enabled = !chatState.isGenerating && engineState !is EngineState.Loading,
                                onClick = {
                                    menuOpen = false
                                    onForceCpuChange(!forceCpu)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.menu_speculative))
                                        Text(
                                            stringResource(R.string.menu_speculative_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailingIcon = { Checkbox(checked = speculative, onCheckedChange = null) },
                                // Only meaningful while the model runs on GPU.
                                enabled = !chatState.isGenerating &&
                                    (engineState as? EngineState.Ready)?.backend == BackendKind.GPU,
                                onClick = {
                                    menuOpen = false
                                    onSpeculativeChange(!speculative)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            EngineBanner(
                state = engineState,
                modelBroken = modelBroken,
                gpuDisabled = gpuDisabled,
                generating = chatState.isGenerating,
                memoryWarning = memoryWarning,
                onRetryLoad = onRetryLoad,
                onRetryGpu = onRetryGpu,
                onRedownload = onRedownload,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chatState.turns.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_empty),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // A fresh list (scroll position, auto-follow) for each opened chat.
                    key(chatState.conversationId) {
                        TurnList(
                            turns = chatState.turns,
                            engineReady = engineState is EngineState.Ready,
                            speculative = (engineState as? EngineState.Ready)?.speculative == true,
                            onRegenerate = onRegenerate,
                        )
                    }
                }
            }
            InputBar(
                enabled = engineState is EngineState.Ready,
                hint = when (engineState) {
                    is EngineState.Ready -> R.string.chat_input_hint
                    is EngineState.Failed -> R.string.chat_input_hint_failed
                    else -> R.string.chat_input_hint_loading
                },
                generating = chatState.isGenerating,
                reasoningLevel = reasoningLevel,
                onReasoningLevelChange = onReasoningLevelChange,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

/** "CPU 23% · GPU 41% · RAM 62%" ("≈" for the headroom estimate, "-" when unreadable). */
@Composable
private fun UsageText(usage: ResourceUsage, modifier: Modifier = Modifier) {
    fun pct(value: Int?) = value?.let { "$it%" } ?: "-"
    val gpu = usage.gpu?.let { if (it.source == GpuSource.HEADROOM) "≈${it.percent}%" else "${it.percent}%" } ?: "-"
    Text(
        stringResource(R.string.usage_line, pct(usage.cpuPercent), gpu, pct(usage.ramPercent)),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun engineSubtitle(modelName: String, state: EngineState): String =
    when (state) {
        is EngineState.Ready -> "$modelName · " + stringResource(
            R.string.engine_ready_backend,
            if (state.speculative) "${state.backend.name}+MTP" else state.backend.name,
        )
        is EngineState.Loading -> stringResource(R.string.engine_preparing, state.backend.name)
        is EngineState.Failed -> modelName
        EngineState.Idle -> modelName
    }

@Composable
private fun EngineBanner(
    state: EngineState,
    modelBroken: Boolean,
    gpuDisabled: Boolean,
    generating: Boolean,
    memoryWarning: String?,
    onRetryLoad: () -> Unit,
    onRetryGpu: () -> Unit,
    onRedownload: () -> Unit,
) {
    when (state) {
        is EngineState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is EngineState.Failed ->
            BannerCard(error = true) {
                Text(errorMessage(state.error), style = MaterialTheme.typography.bodyMedium)
                state.notice?.let { Text(noticeMessage(it), style = MaterialTheme.typography.bodySmall) }
                memoryWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.detail?.let {
                    Text(stringResource(R.string.error_detail, it), style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (modelBroken) {
                        Button(onClick = onRedownload) { Text(stringResource(R.string.model_redownload)) }
                    }
                    OutlinedButton(onClick = onRetryLoad) { Text(stringResource(R.string.engine_retry)) }
                    if (gpuDisabled) {
                        TextButton(onClick = onRetryGpu) { Text(stringResource(R.string.engine_retry_gpu)) }
                    }
                }
            }
        is EngineState.Ready ->
            // GPU stays off until the user re-enables it, so keep the way back visible.
            if (state.notice != null || gpuDisabled) {
                BannerCard(error = false) {
                    Text(
                        state.notice?.let { noticeMessage(it) } ?: stringResource(R.string.notice_gpu_off),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (gpuDisabled) {
                        TextButton(onClick = onRetryGpu, enabled = !generating) {
                            Text(stringResource(R.string.engine_retry_gpu))
                        }
                    }
                }
            }
        EngineState.Idle -> Unit
    }
}

@Composable
private fun BannerCard(error: Boolean, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun TurnList(turns: List<ChatTurn>, engineReady: Boolean, speculative: Boolean, onRegenerate: () -> Unit) {
    val listState = rememberLazyListState()
    val last = turns.lastOrNull()
    // Follow the growing answer, but stop following once the user scrolls up to read;
    // resume when they scroll back to the bottom or send a new message.
    var followBottom by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) followBottom = false }
    val currentTurns by rememberUpdatedState(turns)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) return@collect
                if (!canScrollForward) {
                    followBottom = true
                } else if (followBottom && currentTurns.isNotEmpty()) {
                    // Content grew after the last scroll (async Markdown parse, stats line): catch up.
                    listState.scrollToItem(currentTurns.lastIndex, Int.MAX_VALUE)
                }
            }
    }
    var lastTurnCount by remember { mutableIntStateOf(turns.size) }
    LaunchedEffect(turns.size, last?.response?.length, last?.thinking?.length, last?.status) {
        if (turns.size != lastTurnCount) {
            lastTurnCount = turns.size
            followBottom = true
        }
        if (turns.isNotEmpty() && followBottom && !dragged) listState.scrollToItem(turns.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 다시 생성 only for the newest answer, and only when nothing is generating.
        val canRegenerate = engineReady && last != null && !last.isActive
        items(turns, key = { it.id }) { turn ->
            TurnItem(turn, canRegenerate = canRegenerate && turn.id == last.id, speculative = speculative, onRegenerate = onRegenerate)
        }
    }
}

@Composable
private fun TurnItem(turn: ChatTurn, canRegenerate: Boolean, speculative: Boolean, onRegenerate: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // User message: right-aligned bubble; long-press to copy.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            LongPressMenu(text = turn.prompt, canRegenerate = false, onRegenerate = {}) {
                Text(
                    turn.prompt,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        // Assistant: optional reasoning, then the answer as Markdown body text without a bubble.
        if (turn.thinking.isNotEmpty()) ThinkingSection(turn)
        when {
            turn.isActive && turn.response.isEmpty() && turn.thinking.isEmpty() ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.chat_thinking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            turn.response.isNotEmpty() ->
                // Long-press: 복사 / 다시 생성 (no text selection, which would steal the long-press).
                LongPressMenu(
                    text = turn.response,
                    canRegenerate = canRegenerate,
                    onRegenerate = onRegenerate,
                    enabled = !turn.isActive,
                    speculativeHint = speculative,
                ) {
                    MarkdownText(turn.response, streaming = turn.isActive)
                }
        }
        if (canRegenerate && (turn.status == TurnStatus.ERROR || turn.response.isBlank())) {
            TextButton(onClick = onRegenerate) { Text(stringResource(R.string.chat_retry)) }
        }
        if (turn.garbled) {
            Text(
                stringResource(if (turn.backend == BackendKind.GPU) R.string.chat_garbled_gpu else R.string.chat_garbled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (turn.truncated) {
            Text(
                stringResource(R.string.chat_truncated),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (turn.status == TurnStatus.STOPPED) {
            Text(
                stringResource(R.string.chat_stopped),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (turn.status == TurnStatus.ERROR) {
            Text(
                errorMessage(turn.error ?: LlmError.GENERATION_FAILED),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        turnStats(turn)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun turnStats(turn: ChatTurn): String? {
    val ttft = turn.ttftMillis ?: return null
    if (turn.isActive) return null
    val parts = mutableListOf(stringResource(R.string.stats_ttft, formatSeconds(ttft)))
    turn.thinkingMillis?.let { parts += stringResource(R.string.stats_thinking, formatSeconds(it)) }
    turn.tokensPerSecond?.let { parts += stringResource(R.string.stats_rate, formatRate(it)) }
    turn.reasoningLevel?.let { parts += stringResource(R.string.stats_reasoning, stringResource(it.labelRes())) }
    parts += turn.backend?.name ?: "-"
    return parts.joinToString(" · ")
}

@Composable
private fun InputBar(
    enabled: Boolean,
    @StringRes hint: Int,
    generating: Boolean,
    reasoningLevel: ReasoningLevel?,
    onReasoningLevelChange: (ReasoningLevel) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    if (reasoningLevel != null) {
        Box(Modifier.padding(start = 16.dp)) {
            ReasoningSelector(level = reasoningLevel, enabled = !generating, onChange = onReasoningLevelChange)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = { Text(stringResource(hint)) },
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.size(8.dp))
        if (generating) {
            FilledIconButton(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
            }
        } else {
            FilledIconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}

@Composable
fun errorMessage(error: LlmError): String =
    stringResource(
        when (error) {
            LlmError.MODEL_MISSING -> R.string.error_model_missing
            LlmError.MODEL_UNREADABLE -> R.string.error_model_unreadable
            LlmError.MODEL_INCOMPLETE -> R.string.error_model_incomplete
            LlmError.MODEL_CORRUPT -> R.string.error_model_corrupt
            LlmError.NATIVE_LIBRARY -> R.string.error_native_library
            LlmError.OUT_OF_MEMORY -> R.string.error_out_of_memory
            LlmError.LOAD_FAILED -> R.string.error_load_failed
            LlmError.GENERATION_FAILED -> R.string.error_generation_failed
            LlmError.CONTEXT_FULL -> R.string.error_context_full
        }
    )

@Composable
fun noticeMessage(notice: EngineNotice): String =
    stringResource(
        when (notice) {
            EngineNotice.GPU_DISABLED_AFTER_CRASH -> R.string.notice_gpu_disabled
            EngineNotice.PREVIOUS_LOAD_CRASHED -> R.string.notice_previous_crash
            EngineNotice.GPU_FAILED_FELL_BACK -> R.string.notice_gpu_fell_back
            EngineNotice.SPECULATIVE_DISABLED_AFTER_CRASH -> R.string.notice_speculative_disabled
        }
    )
