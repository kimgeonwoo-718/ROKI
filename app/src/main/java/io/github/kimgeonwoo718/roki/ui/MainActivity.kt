package io.github.kimgeonwoo718.roki.ui

import android.app.UiModeManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kimgeonwoo718.roki.R
import io.github.kimgeonwoo718.roki.RokiApplication
import io.github.kimgeonwoo718.roki.chat.ChatController
import io.github.kimgeonwoo718.roki.model.ModelLocation
import io.github.kimgeonwoo718.roki.ui.chat.ChatScreen
import io.github.kimgeonwoo718.roki.ui.drawer.ChatDrawer
import io.github.kimgeonwoo718.roki.settings.ThemeMode
import io.github.kimgeonwoo718.roki.ui.settings.AppSettingsScreen
import io.github.kimgeonwoo718.roki.ui.settings.PersonalizationScreen
import io.github.kimgeonwoo718.roki.ui.setup.ModelSetupScreen
import io.github.kimgeonwoo718.roki.ui.theme.RokiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsStore = (application as RokiApplication).container.appSettingsStore
        setContent {
            val settings by settingsStore.state.collectAsStateWithLifecycle()
            // Also tell the system, so the splash screen and window background match the chosen
            // mode instead of flashing the system's mode on every launch (Android 12+ API).
            LaunchedEffect(settings.themeMode) {
                getSystemService(UiModeManager::class.java).setApplicationNightMode(
                    when (settings.themeMode) {
                        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    }
                )
            }
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status/navigation bar icons must follow the app's theme, not only the system's.
            DisposableEffect(darkTheme) {
                val style =
                    if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            RokiTheme(darkTheme = darkTheme) {
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
    val speculative by vm.speculative.collectAsStateWithLifecycle()

    // Pick up a model pushed with adb (or space freed) while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshLocation() }

    val memoryWarning =
        if (vm.isLowMemoryDevice) {
            stringResource(R.string.memory_warning, vm.deviceMemoryGb.toInt(), vm.spec.minDeviceMemoryGb)
        } else null

    if (location is ModelLocation.Found) {
        // Keeps the chat's saved state (unsent draft, scroll position) while settings are open.
        val chatStateHolder = rememberSaveableStateHolder()
        var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
        when (screen) {
            Screen.PERSONALIZATION -> {
                val personalization by vm.personalization.collectAsStateWithLifecycle()
                BackHandler { screen = Screen.CHAT }
                PersonalizationScreen(
                    personalization = personalization,
                    onChange = vm::updatePersonalization,
                    onBack = { screen = Screen.CHAT },
                )
                return
            }
            Screen.SETTINGS -> {
                val appSettings by vm.appSettings.collectAsStateWithLifecycle()
                BackHandler { screen = Screen.CHAT }
                AppSettingsScreen(
                    settings = appSettings,
                    defaultSystemPrompt = ChatController.DEFAULT_SYSTEM_PROMPT,
                    defaultTemperature = vm.spec.defaultTemperature,
                    onChange = vm::updateAppSettings,
                    onBack = { screen = Screen.CHAT },
                )
                return
            }
            Screen.CHAT -> Unit
        }

        // Collected only here, so usage is not polled on the download screen.
        val resourceUsage by vm.resourceUsage.collectAsStateWithLifecycle()
        val conversations by vm.conversations.collectAsStateWithLifecycle()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val closeDrawerThen: (() -> Unit) -> Unit = { action ->
            action()
            scope.launch { drawerState.close() }
        }
        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

        // Opening the sidebar (button or swipe) dismisses the keyboard instead of leaving it up.
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        LaunchedEffect(drawerState.targetValue) {
            if (drawerState.targetValue == DrawerValue.Open) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatDrawer(
                    conversations = conversations,
                    currentConversationId = chatState.conversationId,
                    onNewChat = { closeDrawerThen(vm::newChat) },
                    onOpen = { id -> closeDrawerThen { vm.openConversation(id) } },
                    onRename = vm::renameConversation,
                    onDelete = vm::deleteConversation,
                    onOpenPersonalization = { closeDrawerThen { screen = Screen.PERSONALIZATION } },
                    onOpenSettings = { closeDrawerThen { screen = Screen.SETTINGS } },
                )
            },
        ) {
            chatStateHolder.SaveableStateProvider("chat") {
            ChatScreen(
                modelName = vm.spec.displayName,
                engineState = engineState,
                chatState = chatState,
                modelBroken = vm.isModelBroken(engineState),
                gpuDisabled = vm.isGpuDisabled,
                forceCpu = forceCpu,
                speculative = speculative,
                resourceUsage = resourceUsage,
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
                onRegenerate = vm::regenerate,
                onSpeculativeChange = vm::setSpeculative,
                onOpenDrawer = { scope.launch { drawerState.open() } },
            )
            }
        }
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

/** Full-screen destinations shown instead of the chat. */
private enum class Screen { CHAT, PERSONALIZATION, SETTINGS }
