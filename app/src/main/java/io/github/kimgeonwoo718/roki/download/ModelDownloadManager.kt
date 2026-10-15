package io.github.kimgeonwoo718.roki.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.kimgeonwoo718.roki.model.ModelSpec
import io.github.kimgeonwoo718.roki.model.ModelStorage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ModelDownloadManager(context: Context, private val storage: ModelStorage) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    private fun workName(spec: ModelSpec) = "model-download-${spec.id}"

    /**
     * Starts (or resumes) the download. Without [allowMetered] the work only runs on an unmetered
     * network, so a Wi-Fi drop never silently continues over mobile data.
     */
    fun start(spec: ModelSpec, allowMetered: Boolean) {
        val request =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        ModelDownloadWorker.KEY_MODEL_ID to spec.id,
                        ModelDownloadWorker.KEY_ALLOW_METERED to allowMetered,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
        // KEEP: never start a second worker on the same .part file. Finished work is still replaced.
        workManager.enqueueUniqueWork(workName(spec), ExistingWorkPolicy.KEEP, request)
    }

    /** Stops the download and keeps the partial file so it can be resumed. */
    fun pause(spec: ModelSpec) {
        workManager.cancelUniqueWork(workName(spec))
    }

    /** Drops finished work records so [state] falls back to Idle (e.g. after the file was deleted). */
    fun forgetFinished() {
        workManager.pruneWork()
    }

    fun state(spec: ModelSpec): Flow<DownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(spec))
            .map { infos -> toState(spec, infos.firstOrNull()) }
            .flowOn(Dispatchers.IO)

    fun isMeteredNetwork(): Boolean = isMeteredNetwork(appContext)

    private fun toState(spec: ModelSpec, info: WorkInfo?): DownloadState {
        val partial = storage.partialBytes(spec)
        if (info == null) return DownloadState.Idle(partial)
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                DownloadState.Waiting(wifiOnly = info.constraints.requiredNetworkType == NetworkType.UNMETERED)
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                val done = p.getLong(ModelDownloadWorker.KEY_DONE_BYTES, partial)
                val total = p.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, spec.sizeBytes)
                if (p.getString(ModelDownloadWorker.KEY_PHASE) == ModelDownloadWorker.PHASE_VERIFYING) {
                    DownloadState.Verifying(done, total)
                } else {
                    DownloadState.Downloading(done, total, p.getLong(ModelDownloadWorker.KEY_SPEED_BPS, 0L))
                }
            }
            // A finished download whose file has since been deleted is not "done" any more.
            WorkInfo.State.SUCCEEDED ->
                if (storage.finalFile(spec).exists()) DownloadState.Succeeded else DownloadState.Idle(partial)
            WorkInfo.State.CANCELLED -> DownloadState.Idle(partial)
            WorkInfo.State.FAILED -> {
                val out = info.outputData
                DownloadState.Failed(
                    error = out.getString(ModelDownloadWorker.KEY_ERROR)
                        ?.let { runCatching { DownloadError.valueOf(it) }.getOrNull() }
                        ?: DownloadError.UNKNOWN,
                    httpCode = out.getInt(ModelDownloadWorker.KEY_HTTP_CODE, 0),
                    requiredBytes = out.getLong(ModelDownloadWorker.KEY_REQUIRED_BYTES, 0),
                    availableBytes = out.getLong(ModelDownloadWorker.KEY_AVAILABLE_BYTES, 0),
                    partialBytes = partial,
                )
            }
        }
    }

    companion object {
        fun isMeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}
