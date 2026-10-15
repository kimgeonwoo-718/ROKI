package io.github.kimgeonwoo718.roki

import android.content.Context
import android.util.Log
import io.github.kimgeonwoo718.roki.chat.ChatController
import io.github.kimgeonwoo718.roki.chat.ChatRepository
import io.github.kimgeonwoo718.roki.data.db.RokiDatabase
import io.github.kimgeonwoo718.roki.download.ModelDownloadManager
import io.github.kimgeonwoo718.roki.llm.LlmEngineManager
import io.github.kimgeonwoo718.roki.model.ModelCatalog
import io.github.kimgeonwoo718.roki.model.ModelStorage
import io.github.kimgeonwoo718.roki.settings.PersonalizationStore
import io.github.kimgeonwoo718.roki.system.ResourceMonitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container: one instance per process, owned by [RokiApplication]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Lives as long as the process, so generations survive Activity recreation. */
    val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                // Last resort: a failure in a background job is logged instead of killing the app.
                CoroutineExceptionHandler { _, t -> Log.e("RokiApp", "Uncaught in appScope", t) },
        )

    val modelSpec = ModelCatalog.active
    val modelStorage = ModelStorage(appContext)
    val downloadManager = ModelDownloadManager(appContext, modelStorage)
    val engineManager = LlmEngineManager(appContext, appScope)
    val resourceMonitor = ResourceMonitor(appContext)

    /** Small user preferences (reasoning level, 사용자 맞춤 설정). */
    private val settingsPrefs = appContext.getSharedPreferences("roki_settings", Context.MODE_PRIVATE)
    val personalizationStore = PersonalizationStore(settingsPrefs)

    private val database = RokiDatabase.create(appContext)
    private val chatRepository = ChatRepository(database.chatDao(), appScope)

    val chatController =
        ChatController(engineManager, appScope, modelSpec, settingsPrefs, chatRepository, personalizationStore.state)
}
