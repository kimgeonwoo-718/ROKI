package io.github.kimgeonwoo718.roki.ui

import java.util.Locale

/** Decimal units (1 GB = 10^9 bytes), matching how phones report storage. */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1e9 -> String.format(Locale.US, "%.2fGB", b / 1e9)
        b >= 1e6 -> String.format(Locale.US, "%.0fMB", b / 1e6)
        b >= 1e3 -> String.format(Locale.US, "%.0fKB", b / 1e3)
        else -> "${bytes}B"
    }
}

fun formatDuration(seconds: Long): String =
    when {
        seconds >= 3600 -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
        seconds >= 60 -> "${seconds / 60}분"
        else -> "${seconds.coerceAtLeast(1)}초"
    }

fun formatSeconds(millis: Long): String = String.format(Locale.US, "%.1f", millis / 1000.0)

fun formatRate(rate: Double): String = String.format(Locale.US, "%.1f", rate)
