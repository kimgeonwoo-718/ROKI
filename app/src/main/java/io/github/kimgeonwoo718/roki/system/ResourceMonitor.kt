package io.github.kimgeonwoo718.roki.system

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Percentages for the top bar; null when a value cannot be read on this device. */
data class ResourceUsage(val cpuPercent: Int? = null, val gpu: GpuReading? = null, val ramPercent: Int? = null)

/**
 * Samples resource usage every [INTERVAL_MS] while collected.
 *
 * - CPU: this app's own CPU time (all threads, including LiteRT-LM's native ones) from
 *   /proc/self/stat, as a share of all cores. Device-wide /proc/stat is not readable by apps.
 * - RAM: device-wide used memory from ActivityManager.MemoryInfo.
 * - GPU: see [GpuUsageReader].
 */
class ResourceMonitor(context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val gpu = GpuUsageReader(context, INTERVAL_MS)
    private val clockTicksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    val usage: Flow<ResourceUsage> =
        flow {
            var lastTicks = readOwnCpuTicks()
            var lastAt = SystemClock.elapsedRealtimeNanos()
            emit(sample(cpu = null))
            while (true) {
                delay(INTERVAL_MS)
                val ticks = readOwnCpuTicks()
                val now = SystemClock.elapsedRealtimeNanos()
                val cpu =
                    if (ticks != null && lastTicks != null && now > lastAt) {
                        val cpuSeconds = (ticks - lastTicks).toDouble() / clockTicksPerSecond
                        val wallSeconds = (now - lastAt) / 1e9
                        (cpuSeconds / wallSeconds / cores * 100).toInt().coerceIn(0, 100)
                    } else null
                lastTicks = ticks
                lastAt = now
                emit(sample(cpu))
            }
        }.flowOn(Dispatchers.IO)

    /** A cosmetic readout must never crash the app: any failure just shows "-". */
    private fun sample(cpu: Int?) =
        ResourceUsage(
            cpuPercent = cpu,
            gpu = runCatching { gpu.read() }.getOrNull(),
            ramPercent = runCatching { ramPercent() }.getOrNull(),
        )

    private fun ramPercent(): Int? {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        if (info.totalMem <= 0) return null
        return ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt().coerceIn(0, 100)
    }

    /** utime + stime of this process in clock ticks (fields 14 and 15 of /proc/self/stat). */
    private fun readOwnCpuTicks(): Long? =
        runCatching {
            val stat = File("/proc/self/stat").readText()
            // The command name in field 2 may contain spaces, so split after its closing ')'.
            val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            fields[11].toLong() + fields[12].toLong()
        }.getOrNull()

    companion object {
        const val INTERVAL_MS = 1500L
    }
}
