package io.github.kimgeonwoo718.roki.model

import android.content.Context
import android.os.storage.StorageManager
import io.github.kimgeonwoo718.roki.BuildConfig
import java.io.File

/** Where a model file lives on the device, and whether it is usable. */
sealed interface ModelLocation {
    data class Found(val file: File, val sideloaded: Boolean) : ModelLocation

    data class Missing(val checkedPaths: List<String>) : ModelLocation

    data class Unreadable(val file: File) : ModelLocation

    data class SizeMismatch(val file: File, val actualBytes: Long, val expectedBytes: Long) :
        ModelLocation
}

class ModelStorage(private val context: Context) {
    /** App-specific external storage: no permission needed, removed on uninstall, adb-writable. */
    val modelsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    fun finalFile(spec: ModelSpec) = File(modelsDir, spec.fileName)

    fun partFile(spec: ModelSpec) = File(modelsDir, spec.fileName + ".part")

    fun partialBytes(spec: ModelSpec): Long = partFile(spec).let { if (it.exists()) it.length() else 0L }

    /** Paths checked in order. /data/local/tmp is a debug-only convenience for adb pushes. */
    private fun candidates(spec: ModelSpec): List<Pair<File, Boolean>> = buildList {
        add(finalFile(spec) to false)
        if (BuildConfig.DEBUG) add(File(DEBUG_SIDELOAD_DIR, spec.fileName) to true)
    }

    fun locate(spec: ModelSpec): ModelLocation {
        val candidates = candidates(spec)
        for ((file, sideloaded) in candidates) {
            if (!file.exists()) continue
            if (!file.canRead()) return ModelLocation.Unreadable(file)
            val size = file.length()
            if (size != spec.sizeBytes) return ModelLocation.SizeMismatch(file, size, spec.sizeBytes)
            return ModelLocation.Found(file, sideloaded)
        }
        return ModelLocation.Missing(candidates.map { it.first.absolutePath })
    }

    /** Bytes the system can make available for the models directory (includes clearable cache). */
    fun allocatableBytes(): Long {
        val dir = modelsDir.apply { mkdirs() }
        val storageManager = context.getSystemService(StorageManager::class.java)
        return runCatching { storageManager.getAllocatableBytes(storageManager.getUuidForPath(dir)) }
            .getOrElse { dir.usableSpace }
    }

    companion object {
        const val DEBUG_SIDELOAD_DIR = "/data/local/tmp/llm"
    }
}
