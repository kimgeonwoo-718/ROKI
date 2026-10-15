package io.github.kimgeonwoo718.roki.system

import android.content.Context
import android.os.Build
import android.os.GpuHeadroomParams
import android.os.health.SystemHealthManager
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.math.roundToInt

enum class GpuSource {
    /** Qualcomm KGSL busy counters: an actual busy percentage (device-wide). */
    KGSL,

    /** 100 - Android 16 GPU headroom: an approximation. */
    HEADROOM,
}

data class GpuReading(val percent: Int, val source: GpuSource)

/**
 * Best-effort GPU utilisation. Android has no public "GPU usage" API for apps, so:
 * 1. Qualcomm's KGSL `gpubusy` node, which Qualcomm's reference SELinux policy makes app-readable
 *    (Samsung's policy is not public: probed once at runtime);
 * 2. on Android 16+, 100 - SystemHealthManager.getGpuHeadroom() (spare capacity, approximate);
 * 3. otherwise null ("GPU -").
 *
 * Not thread-safe: call only from ResourceMonitor's single polling flow.
 */
class GpuUsageReader(context: Context, private val intervalMs: Long) {
    private val appContext = context.applicationContext

    private var kgslProbed = false
    private var kgslPath: String? = null
    private var headroomDisabled = false
    private var headroomParams: Any? = null // GpuHeadroomParams on API 36+

    /** Qualcomm's HAL returns 0.0 on internal failure, so 0 is trusted only after a real value. */
    private var headroomTrusted = false

    fun read(): GpuReading? {
        readKgsl()?.let { return GpuReading(it, GpuSource.KGSL) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            readHeadroom()?.let { return GpuReading(it, GpuSource.HEADROOM) }
        }
        return null
    }

    private fun readKgsl(): Int? {
        if (!kgslProbed) {
            // Probe once: a denied sysfs read logs an SELinux denial every time.
            kgslProbed = true
            kgslPath = KGSL_CANDIDATES.firstOrNull { parseKgsl(it) != null }
        }
        return kgslPath?.let(::parseKgsl)
    }

    private fun parseKgsl(path: String): Int? =
        try {
            val text = File(path).readText().trim()
            if (path.endsWith("gpubusy")) {
                // "<busy> <total>" in microseconds over the last sampling window.
                val parts = text.split(Regex("\\s+"))
                val busy = parts[0].toLong()
                val total = parts[1].toLong()
                if (total <= 0L) 0 else (busy * 100 / total).toInt().coerceIn(0, 100)
            } else {
                // "37 %" or "37"
                text.substringBefore('%').trim().toInt().coerceIn(0, 100)
            }
        } catch (e: Exception) {
            null // EACCES (SELinux), ENOENT, unexpected format
        }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun readHeadroom(): Int? {
        if (headroomDisabled) return null
        val manager = appContext.getSystemService(SystemHealthManager::class.java)
            ?: run {
                headroomDisabled = true
                return null
            }
        return try {
            val params = headroomParams as? GpuHeadroomParams ?: run {
                val range = manager.gpuHeadroomCalculationWindowRange // throws if unsupported
                val window = maxOf(intervalMs, manager.gpuHeadroomMinIntervalMillis, 1000L).toInt()
                GpuHeadroomParams.Builder()
                    .setCalculationType(GpuHeadroomParams.GPU_HEADROOM_CALCULATION_TYPE_AVERAGE)
                    .setCalculationWindowMillis(window.coerceIn(range.first, range.second))
                    .build()
                    .also { headroomParams = it }
            }
            val headroom = manager.getGpuHeadroom(params)
            when {
                headroom.isNaN() -> null
                headroom > 0f -> {
                    headroomTrusted = true
                    (100f - headroom).roundToInt().coerceIn(0, 100)
                }
                headroomTrusted -> 100
                else -> null // 0 before any real value: most likely a HAL failure, not a full GPU
            }
        } catch (e: RuntimeException) {
            // Unsupported, IllegalArgument, Security, IllegalState or a vendor HAL error: stop asking.
            headroomDisabled = true
            null
        }
    }

    companion object {
        // Only the canonical gpubusy node is app-readable in Qualcomm's reference policy; the rest
        // are cheap one-time probes in case the vendor labels them differently.
        private val KGSL_CANDIDATES =
            listOf(
                "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpubusy",
                "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/kernel/gpu/gpu_busy",
            )
    }
}
