package io.github.kimgeonwoo718.roki.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.model.ModelLocation
import io.github.kimgeonwoo718.roki.ui.chat.ChatScreen
import io.github.kimgeonwoo718.roki.ui.setup.ModelSetupScreen
import io.github.kimgeonwoo718.roki.ui.theme.RokiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RokiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RokiApp()
                }
            }
        }
    }
}

@Composable
private fun RokiApp(vm: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val location by vm.location.collectAsStateWithLifecycle()
    val downloadState by vm.downloadState.collectAsStateWithLifecycle()
    val freeBytes by vm.freeBytes.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val chatState by vm.chatState.collectAsStateWithLifecycle()
    val reasoningLevel by vm.reasoningLevel.collectAsStateWithLifecycle()
    val forceCpu by vm.forceCpu.collectAsStateWithLifecycle()

    // Pick up a model pushed with adb (or space freed) while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshLocation() }

    val memoryWarning =
        if (vm.isLowMemoryDevice) {
            stringResource(R.string.memory_warning, vm.deviceMemoryGb.toInt(), vm.spec.minDeviceMemoryGb)
        } else null

    if (location is ModelLocation.Found) {
        ChatScreen(
            modelName = vm.spec.displayName,
            engineState = engineState,
            chatState = chatState,
            modelBroken = vm.isModelBroken(engineState),
            gpuDisabled = vm.isGpuDisabled,
            forceCpu = forceCpu,
            memoryWarning = memoryWarning,
            reasoningLevel = if (vm.spec.supportsThinking) reasoningLevel else null,
            onReasoningLevelChange = vm::setReasoningLevel,
            onSend = vm::send,
            onStop = vm::stop,
            onNewChat = vm::newChat,
            onRetryLoad = vm::retryLoad,
            onRetryGpu = vm::retryWithGpu,
            onRedownload = vm::deleteModelAndRedownload,
            onForceCpuChange = vm::setForceCpu,
        )
    } else {
        ModelSetupScreen(
            spec = vm.spec,
            location = location,
            downloadState = downloadState,
            freeBytes = freeBytes,
            modelsDirPath = vm.modelsDirPath,
            memoryWarning = memoryWarning,
            isMeteredNetwork = vm::isMeteredNetwork,
            onStartDownload = vm::startDownload,
            onPauseDownload = vm::pauseDownload,
            onRedownload = vm::deleteModelAndRedownload,
            onRecheck = vm::refreshLocation,
        )
    }
}
