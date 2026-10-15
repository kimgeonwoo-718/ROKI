package io.github.kimgeonwoo718.roki

import android.content.Context
import io.github.kimgeonwoo718.roki.chat.ChatController
import io.github.kimgeonwoo718.roki.download.ModelDownloadManager
import io.github.kimgeonwoo718.roki.llm.LlmEngineManager
import io.github.kimgeonwoo718.roki.model.ModelCatalog
import io.github.kimgeonwoo718.roki.model.ModelStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container: one instance per process, owned by [RokiApplication]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Lives as long as the process, so generations survive Activity recreation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelSpec = ModelCatalog.active
    val modelStorage = ModelStorage(appContext)
    val downloadManager = ModelDownloadManager(appContext, modelStorage)
    val engineManager = LlmEngineManager(appContext, appScope)

    /** Small user preferences until the stage-4 settings screen (DataStore) replaces them. */
    private val settingsPrefs = appContext.getSharedPreferences("roki_settings", Context.MODE_PRIVATE)
    val chatController = ChatController(engineManager, appScope, modelSpec, settingsPrefs)
}
