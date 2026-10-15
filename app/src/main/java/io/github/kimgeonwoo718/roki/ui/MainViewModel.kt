package io.github.kimgeonwoo718.roki.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kimgeonwoo718.roki.AppContainer
import io.github.kimgeonwoo718.roki.RokiApplication
import io.github.kimgeonwoo718.roki.chat.ChatState
import io.github.kimgeonwoo718.roki.download.DownloadState
import io.github.kimgeonwoo718.roki.llm.EngineState
import io.github.kimgeonwoo718.roki.llm.LlmError
import io.github.kimgeonwoo718.roki.llm.ReasoningLevel
import io.github.kimgeonwoo718.roki.model.ModelLocation
import io.github.kimgeonwoo718.roki.model.ModelSpec
import io.github.kimgeonwoo718.roki.system.ResourceUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val spec: ModelSpec = container.modelSpec
    private val storage = container.modelStorage
    private val downloads = container.downloadManager
    private val engine = container.engineManager
    private val chat = container.chatController

    /** null while the first file check is running. */
    private val _location = MutableStateFlow<ModelLocation?>(null)
    val location: StateFlow<ModelLocation?> = _location.asStateFlow()

    private val _freeBytes = MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _freeBytes.asStateFlow()

    val downloadState: StateFlow<DownloadState> =
        downloads.state(spec).stateIn(viewModelScope, SharingStarted.Eagerly, DownloadState.Idle(0L))

    val engineState: StateFlow<EngineState> = engine.state
    val chatState: StateFlow<ChatState> = chat.state

    val modelsDirPath: String = storage.modelsDir.absolutePath
    val deviceMemoryGb: Long = engine.deviceMemoryGb()
    val isLowMemoryDevice: Boolean = deviceMemoryGb in 1 until spec.minDeviceMemoryGb

    val isGpuDisabled: Boolean
        get() = engine.isGpuDisabled

    val forceCpu: StateFlow<Boolean> = engine.forceCpu

    /** Polled only while the chat screen collects it; keeps the last CPU value across restarts. */
    val resourceUsage: StateFlow<ResourceUsage> =
        container.resourceMonitor.usage
            .scan(ResourceUsage()) { previous, next ->
                if (next.cpuPercent == null) next.copy(cpuPercent = previous.cpuPercent) else next
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceUsage())

    val reasoningLevel: StateFlow<ReasoningLevel> = chat.reasoningLevel

    fun setReasoningLevel(level: ReasoningLevel) = chat.setReasoningLevel(level)

    fun setForceCpu(enabled: Boolean) {
        if (chat.state.value.isGenerating) return
        val found = _location.value as? ModelLocation.Found ?: return
        engine.setForceCpu(spec, found.file, enabled)
    }

    init {
        refreshLocation()
        // The model loads once, on first use of the UI (not in Application.onCreate, so a
        // background download wake-up never loads 2.6 GB into memory).
        viewModelScope.launch {
            _location.collect { if (it is ModelLocation.Found) engine.load(spec, it.file) }
        }
        viewModelScope.launch {
            downloadState.filterIsInstance<DownloadState.Succeeded>().collect { refreshLocation() }
        }
    }

    fun refreshLocation() {
        viewModelScope.launch(Dispatchers.IO) {
            val located = storage.locate(spec)
            // A finished download whose file was deleted outside the app: offer a fresh start.
            if (located is ModelLocation.Missing && downloadState.value is DownloadState.Succeeded) {
                downloads.forgetFinished()
            }
            _location.value = located
            _freeBytes.value = storage.allocatableBytes()
        }
    }

    fun startDownload(allowMetered: Boolean) = downloads.start(spec, allowMetered)

    fun pauseDownload() = downloads.pause(spec)

    fun isMeteredNetwork(): Boolean = downloads.isMeteredNetwork()

    /** Deletes a broken downloaded file so the setup screen can fetch it again. */
    fun deleteModelAndRedownload() {
        viewModelScope.launch(Dispatchers.IO) {
            storage.finalFile(spec).delete()
            downloads.forgetFinished()
            _location.value = storage.locate(spec)
            _freeBytes.value = storage.allocatableBytes()
        }
    }

    fun retryLoad() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = storage.locate(spec)
            _location.value = found
            if (found is ModelLocation.Found) engine.reload(spec, found.file)
        }
    }

    fun retryWithGpu() {
        if (chat.state.value.isGenerating) return
        val found = _location.value as? ModelLocation.Found ?: return
        engine.retryWithGpu(spec, found.file)
    }

    /** Broken AND re-downloadable: a side-loaded file has to be replaced with adb instead. */
    fun isModelBroken(state: EngineState): Boolean =
        state is EngineState.Failed &&
            (state.error == LlmError.MODEL_CORRUPT || state.error == LlmError.MODEL_INCOMPLETE) &&
            (_location.value as? ModelLocation.Found)?.sideloaded == false &&
            spec.downloadUrl != null

    fun send(text: String) = chat.send(text)

    fun stop() = chat.stop()

    fun newChat() = chat.newChat()

    companion object {
        val Factory = viewModelFactory {
            initializer { MainViewModel((this[APPLICATION_KEY] as RokiApplication).container) }
        }
    }
}
