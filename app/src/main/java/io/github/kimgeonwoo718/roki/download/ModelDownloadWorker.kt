package io.github.kimgeonwoo718.roki.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.kimgeonwoo718.roki.BuildConfig
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.RokiApplication
import io.github.kimgeonwoo718.roki.model.ModelCatalog
import io.github.kimgeonwoo718.roki.model.ModelSpec
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads the model file into app-specific storage as a dataSync foreground service.
 *
 * - Resumes from `<file>.part` with an HTTP Range request; the partial file is kept on cancel.
 * - Checks free space first, then verifies size and SHA-256 before renaming to the final name.
 * - Sends no credentials: only ungated model URLs are downloadable.
 */
class ModelDownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private class DownloadFailure(
        val error: DownloadError,
        val httpCode: Int = 0,
        message: String,
    ) : Exception(message)

    /** False when the system refused the foreground service (e.g. started from the background). */
    private var foregroundAvailable = true
    private var lastNotificationAt = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(ModelCatalog.active, 0L, 0L, verifying = false)

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val spec = ModelCatalog.all.firstOrNull { it.id == inputData.getString(KEY_MODEL_ID) }
                ?: return@withContext failure(DownloadError.UNKNOWN)
            val url = spec.downloadUrl ?: return@withContext failure(DownloadError.NO_SOURCE)
            val storage = (applicationContext as RokiApplication).container.modelStorage
            val finalFile = storage.finalFile(spec)
            val partFile = storage.partFile(spec)

            if (finalFile.exists() && finalFile.length() == spec.sizeBytes) return@withContext Result.success()
            if (!storage.modelsDir.exists() && !storage.modelsDir.mkdirs()) {
                return@withContext failure(DownloadError.STORAGE)
            }

            runCatching { setForeground(foregroundInfo(spec, partFile.length(), spec.sizeBytes, false)) }
                .onFailure {
                    foregroundAvailable = false
                    Log.w(TAG, "Could not start foreground service; continuing in background", it)
                }

            val remaining = spec.sizeBytes - partFile.length().coerceAtMost(spec.sizeBytes)
            val available = storage.allocatableBytes()
            if (available < remaining + SPACE_MARGIN_BYTES) {
                return@withContext failure(
                    DownloadError.NOT_ENOUGH_SPACE,
                    requiredBytes = remaining + SPACE_MARGIN_BYTES,
                    availableBytes = available,
                )
            }

            // Retry network errors inside this run, which already holds the foreground service; a
            // WorkManager retry would restart in the background, where a new FGS is not allowed.
            val allowMetered = inputData.getBoolean(KEY_ALLOW_METERED, false)
            val runStartBytes = partFile.length()
            var noProgressStreak = 0
            while (true) {
                if (!allowMetered && ModelDownloadManager.isMeteredNetwork(applicationContext)) {
                    // Wi-Fi went away: the UNMETERED constraint makes WorkManager wait for it.
                    return@withContext Result.retry()
                }
                val before = partFile.length()
                try {
                    download(spec, url, partFile)
                    break
                } catch (e: DownloadFailure) {
                    Log.w(TAG, "Download failed: ${e.message}")
                    // A partial file that does not line up with the server's file cannot be resumed.
                    if (e.error == DownloadError.SERVER_MISMATCH) partFile.delete()
                    return@withContext failure(e.error, httpCode = e.httpCode)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (isOutOfSpace(e)) {
                        return@withContext failure(DownloadError.NOT_ENOUGH_SPACE, requiredBytes = remaining)
                    }
                    noProgressStreak = if (partFile.length() > before) 0 else noProgressStreak + 1
                    Log.w(TAG, "Network error (no-progress streak $noProgressStreak)", e)
                    if (noProgressStreak >= MAX_IN_RUN_RETRIES) {
                        val progressed = partFile.length() > runStartBytes
                        return@withContext if (progressed || runAttemptCount < MAX_RUN_RETRIES) Result.retry()
                        else failure(DownloadError.NETWORK)
                    }
                    delay(minOf(60_000L, 2_000L shl noProgressStreak))
                }
            }

            if (partFile.length() != spec.sizeBytes) {
                partFile.delete()
                return@withContext failure(DownloadError.SIZE_MISMATCH)
            }
            val hash = sha256(spec, partFile)
            if (!hash.equals(spec.sha256, ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch: expected ${spec.sha256}, got $hash")
                partFile.delete()
                return@withContext failure(DownloadError.HASH_MISMATCH)
            }
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) return@withContext failure(DownloadError.STORAGE)
            Result.success()
        }

    /**
     * One HTTP request from the current end of [partFile]. Always re-requests the Hugging Face URL
     * (its signed CDN redirect expires), and writes at explicit offsets so a late write from a
     * cancelled run can never duplicate bytes.
     */
    private suspend fun download(spec: ModelSpec, url: String, partFile: File) = coroutineScope {
        var existing = if (partFile.exists()) partFile.length() else 0L
        if (existing > spec.sizeBytes) {
            partFile.delete()
            existing = 0L
        }
        if (existing == spec.sizeBytes) return@coroutineScope

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "ROKI/${BuildConfig.VERSION_NAME} (Android)")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        // Closing the socket is the only way to unblock a read() promptly when the work is stopped.
        val disconnectOnCancel = launch {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            val append =
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        checkContentRange(connection.getHeaderField("Content-Range"), existing, spec.sizeBytes)
                        true
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val length = connection.contentLengthLong
                        if (length > 0 && length != spec.sizeBytes) {
                            throw DownloadFailure(DownloadError.SERVER_MISMATCH, code, "Content-Length $length")
                        }
                        existing = 0L // Server ignored the Range header: start over.
                        false
                    }
                    HTTP_RANGE_NOT_SATISFIABLE -> {
                        // The stale partial file does not match the server's file: restart cleanly.
                        partFile.delete()
                        throw IOException("HTTP 416 for range $existing-")
                    }
                    // Transient server-side problems: take the network-retry path and keep the .part.
                    408, 429, in 500..599 -> throw IOException("HTTP $code")
                    else -> throw DownloadFailure(DownloadError.HTTP, code, "HTTP $code")
                }

            RandomAccessFile(partFile, "rw").use { file ->
                if (!append) file.setLength(0)
                file.seek(existing)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var done = existing
                    var lastReportTime = System.nanoTime()
                    var lastReportBytes = done
                    var speed = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        done += read
                        val now = System.nanoTime()
                        val elapsed = now - lastReportTime
                        if (elapsed >= REPORT_INTERVAL_NS) {
                            val instant = (done - lastReportBytes) * 1_000_000_000L / elapsed
                            speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                            lastReportTime = now
                            lastReportBytes = done
                            reportProgress(spec, PHASE_DOWNLOADING, done, spec.sizeBytes, speed)
                        }
                    }
                }
                file.fd.sync()
            }
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    private fun checkContentRange(header: String?, expectedStart: Long, expectedTotal: Long) {
        // Format: "bytes <start>-<end>/<total>"
        val match = header?.let { CONTENT_RANGE.find(it) }
            ?: throw DownloadFailure(DownloadError.SERVER_MISMATCH, 206, "Missing Content-Range")
        val start = match.groupValues[1].toLong()
        val total = match.groupValues[3].toLong()
        if (start != expectedStart || total != expectedTotal) {
            throw DownloadFailure(DownloadError.SERVER_MISMATCH, 206, "Content-Range $header")
        }
    }

    private suspend fun sha256(spec: ModelSpec, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var done = 0L
            var lastReportTime = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                done += read
                val now = System.nanoTime()
                if (now - lastReportTime >= REPORT_INTERVAL_NS) {
                    lastReportTime = now
                    reportProgress(spec, PHASE_VERIFYING, done, total, 0L)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun reportProgress(spec: ModelSpec, phase: String, done: Long, total: Long, speed: Long) {
        setProgress(
            workDataOf(
                KEY_PHASE to phase,
                KEY_DONE_BYTES to done,
                KEY_TOTAL_BYTES to total,
                KEY_SPEED_BPS to speed,
            )
        )
        val now = System.nanoTime()
        if (foregroundAvailable && now - lastNotificationAt >= NOTIFICATION_INTERVAL_NS) {
            lastNotificationAt = now
            runCatching { setForeground(foregroundInfo(spec, done, total, phase == PHASE_VERIFYING)) }
                .onFailure { foregroundAvailable = false }
        }
    }

    private fun foregroundInfo(spec: ModelSpec, done: Long, total: Long, verifying: Boolean): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_roki)
                .setContentTitle(
                    context.getString(
                        if (verifying) R.string.download_notification_verifying
                        else R.string.download_notification_title,
                        spec.displayName,
                    )
                )
                .setContentText("$percent%")
                .setProgress(100, percent, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(
                    0,
                    context.getString(R.string.download_pause),
                    WorkManager.getInstance(context).createCancelPendingIntent(id),
                )
                .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(
        error: DownloadError,
        httpCode: Int = 0,
        requiredBytes: Long = 0,
        availableBytes: Long = 0,
    ): Result =
        Result.failure(
            Data.Builder()
                .putString(KEY_ERROR, error.name)
                .putInt(KEY_HTTP_CODE, httpCode)
                .putLong(KEY_REQUIRED_BYTES, requiredBytes)
                .putLong(KEY_AVAILABLE_BYTES, availableBytes)
                .build()
        )

    private fun isOutOfSpace(e: IOException): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) return true
            cause = cause.cause
        }
        return e.message?.contains("ENOSPC") == true
    }

    companion object {
        private const val TAG = "ModelDownload"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_ALLOW_METERED = "allow_metered"
        const val KEY_PHASE = "phase"
        const val KEY_DONE_BYTES = "done"
        const val KEY_TOTAL_BYTES = "total"
        const val KEY_SPEED_BPS = "speed"
        const val KEY_ERROR = "error"
        const val KEY_HTTP_CODE = "http_code"
        const val KEY_REQUIRED_BYTES = "required"
        const val KEY_AVAILABLE_BYTES = "available"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_VERIFYING = "verifying"

        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1001
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 256 * 1024
        private const val REPORT_INTERVAL_NS = 500_000_000L
        private const val NOTIFICATION_INTERVAL_NS = 2_000_000_000L
        private const val MAX_IN_RUN_RETRIES = 5
        private const val MAX_RUN_RETRIES = 5
        private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")
    }
}
