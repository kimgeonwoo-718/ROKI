package io.github.kimgeonwoo718.roki.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.download.DownloadError
import io.github.kimgeonwoo718.roki.download.DownloadState
import io.github.kimgeonwoo718.roki.model.ModelLocation
import io.github.kimgeonwoo718.roki.model.ModelSpec
import io.github.kimgeonwoo718.roki.ui.formatBytes
import io.github.kimgeonwoo718.roki.ui.formatDuration

@Composable
fun ModelSetupScreen(
    spec: ModelSpec,
    location: ModelLocation?,
    downloadState: DownloadState,
    freeBytes: Long?,
    modelsDirPath: String,
    memoryWarning: String?,
    isMeteredNetwork: () -> Boolean,
    onStartDownload: (allowMetered: Boolean) -> Unit,
    onPauseDownload: () -> Unit,
    onRedownload: () -> Unit,
    onRecheck: () -> Unit,
) {
    val context = LocalContext.current
    var showMeteredDialog by remember { mutableStateOf(false) }
    // Mobile data is used only after the user confirmed it in the dialog below.
    var allowMetered by rememberSaveable { mutableStateOf(false) }

    // The download notification needs POST_NOTIFICATIONS; the download runs either way.
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            onStartDownload(allowMetered)
        }
    val startWithPermission = { metered: Boolean ->
        allowMetered = metered
        val needsPermission =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else onStartDownload(metered)
    }
    val requestStart = {
        if (isMeteredNetwork()) showMeteredDialog = true else startWithPermission(false)
    }

    if (showMeteredDialog) {
        AlertDialog(
            onDismissRequest = { showMeteredDialog = false },
            title = { Text(stringResource(R.string.download_metered_title)) },
            text = { Text(stringResource(R.string.download_metered_body, formatBytes(spec.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    showMeteredDialog = false
                    startWithPermission(true)
                }) { Text(stringResource(R.string.download_metered_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showMeteredDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
                when (location) {
                    null -> Text(stringResource(R.string.setup_checking))
                    is ModelLocation.Unreadable -> {
                        ErrorText(stringResource(R.string.model_unreadable, location.file.absolutePath))
                        OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.download_retry)) }
                    }
                    is ModelLocation.SizeMismatch -> {
                        ErrorText(stringResource(R.string.model_size_mismatch, location.file.absolutePath))
                        if (location.file.absolutePath.startsWith(modelsDirPath) && spec.downloadUrl != null) {
                            Button(onClick = onRedownload) { Text(stringResource(R.string.model_redownload)) }
                        } else {
                            OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.download_retry)) }
                        }
                    }
                    else -> {
                        if (spec.downloadUrl == null) {
                            Text(stringResource(R.string.sideload_only_body, spec.displayName, "$modelsDirPath/${spec.fileName}"))
                            OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.download_retry)) }
                        } else {
                            Text(
                                stringResource(R.string.setup_body, spec.displayName, formatBytes(spec.sizeBytes)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            freeBytes?.let {
                                Text(
                                    stringResource(R.string.setup_free_space, formatBytes(it)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            memoryWarning?.let { ErrorText(it) }
                            DownloadSection(
                                state = downloadState,
                                onStart = requestStart,
                                onPause = onPauseDownload,
                                onManageStorage = {
                                    context.startActivity(
                                        Intent(StorageManager.ACTION_MANAGE_STORAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.sideload_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadSection(
    state: DownloadState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onManageStorage: () -> Unit,
) {
    when (state) {
        is DownloadState.Idle ->
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.partialBytes > 0) stringResource(R.string.download_resume, formatBytes(state.partialBytes))
                    else stringResource(R.string.download_start)
                )
            }
        is DownloadState.Waiting -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(if (state.wifiOnly) R.string.download_waiting_wifi else R.string.download_waiting),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onPause) { Text(stringResource(R.string.download_pause)) }
        }
        is DownloadState.Downloading -> {
            val fraction = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.download_progress, formatBytes(state.doneBytes), formatBytes(state.totalBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.bytesPerSecond > 0) {
                val eta = (state.totalBytes - state.doneBytes) / state.bytesPerSecond
                Text(
                    stringResource(R.string.download_speed_eta, formatBytes(state.bytesPerSecond), formatDuration(eta)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.download_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPause) { Text(stringResource(R.string.download_pause)) }
        }
        is DownloadState.Verifying -> {
            val fraction = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.download_verifying), style = MaterialTheme.typography.bodySmall)
        }
        DownloadState.Succeeded -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.download_done), style = MaterialTheme.typography.bodySmall)
        }
        is DownloadState.Failed -> {
            ErrorText(downloadErrorMessage(state))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.partialBytes > 0) stringResource(R.string.download_resume, formatBytes(state.partialBytes))
                    else stringResource(R.string.download_retry)
                )
            }
            if (state.error == DownloadError.NOT_ENOUGH_SPACE) {
                OutlinedButton(onClick = onManageStorage) { Text(stringResource(R.string.free_space_manage)) }
            }
        }
    }
}

@Composable
private fun downloadErrorMessage(state: DownloadState.Failed): String =
    when (state.error) {
        DownloadError.NO_SOURCE -> stringResource(R.string.download_error_no_source)
        DownloadError.NOT_ENOUGH_SPACE ->
            if (state.requiredBytes > 0 && state.availableBytes > 0) {
                stringResource(R.string.download_error_space, formatBytes(state.requiredBytes), formatBytes(state.availableBytes))
            } else {
                stringResource(R.string.download_error_space_unknown)
            }
        DownloadError.NETWORK -> stringResource(R.string.download_error_network)
        DownloadError.HTTP -> stringResource(R.string.download_error_http, state.httpCode)
        DownloadError.SERVER_MISMATCH -> stringResource(R.string.download_error_server_mismatch)
        DownloadError.SIZE_MISMATCH, DownloadError.HASH_MISMATCH -> stringResource(R.string.download_error_corrupt)
        DownloadError.STORAGE -> stringResource(R.string.download_error_storage)
        DownloadError.UNKNOWN -> stringResource(R.string.download_error_unknown)
    }

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
