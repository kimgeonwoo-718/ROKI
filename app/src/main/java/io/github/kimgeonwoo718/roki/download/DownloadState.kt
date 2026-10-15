package io.github.kimgeonwoo718.roki.download

enum class DownloadError {
    NO_SOURCE,
    NOT_ENOUGH_SPACE,
    NETWORK,
    HTTP,
    SERVER_MISMATCH,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    STORAGE,
    UNKNOWN,
}

sealed interface DownloadState {
    /** No download running. [partialBytes] > 0 means a previous download can be resumed. */
    data class Idle(val partialBytes: Long) : DownloadState

    /** Queued: waiting for a network (Wi-Fi only when [wifiOnly]) or for a retry backoff. */
    data class Waiting(val wifiOnly: Boolean) : DownloadState

    data class Downloading(val doneBytes: Long, val totalBytes: Long, val bytesPerSecond: Long) :
        DownloadState

    data class Verifying(val doneBytes: Long, val totalBytes: Long) : DownloadState

    data object Succeeded : DownloadState

    data class Failed(
        val error: DownloadError,
        val httpCode: Int = 0,
        val requiredBytes: Long = 0,
        val availableBytes: Long = 0,
        val partialBytes: Long = 0,
    ) : DownloadState
}
