package io.github.kimgeonwoo718.roki.llm

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.kimgeonwoo718.roki.model.ModelSpec
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings fixed for the lifetime of one LiteRT-LM Conversation. */
data class ConversationSettings(
    val systemPrompt: String?,
    val temperature: Double,
    val topK: Int,
    val topP: Double,
)

/**
 * Owns the single LiteRT-LM [Engine] for the whole process and the one live [Conversation].
 *
 * - The model is loaded once (GPU first, then CPU) and reused.
 * - Engine/Conversation lifecycle calls run on one background thread, never the main thread.
 * - A marker written before each native load, checked against [ApplicationExitInfo] on the next
 *   launch, detects crashes that try/catch cannot see (native GPU faults, low-memory kills).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmEngineManager(private val context: Context, private val appScope: CoroutineScope) {
    private val llmDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    private var conversationSettings: ConversationSettings? = null

    /** Guards cancelProcess() against close() of the same Conversation on another thread. */
    private val conversationLock = Any()

    /** Set when the live Conversation must not be reused (after a stop, an error or a reset). */
    @Volatile private var conversationPoisoned = false

    /** Bumped by stop()/resetConversation() so an in-flight generate() that has not sent yet bails out. */
    private val resetEpoch = AtomicInteger()

    val isGpuDisabled: Boolean
        get() = prefs.getBoolean(KEY_GPU_DISABLED, false)

    /** Loads the model if it is not loaded or loading yet. Safe to call repeatedly. */
    fun load(spec: ModelSpec, file: File) {
        val current = _state.value
        if (current is EngineState.Loading || current is EngineState.Ready) return
        _state.value = EngineState.Loading(if (isGpuDisabled) BackendKind.CPU else BackendKind.GPU)
        appScope.launch(llmDispatcher) { loadBlocking(spec, file, userInitiated = false) }
    }

    /** Re-enables GPU after it was disabled by a crash, and reloads the model. */
    fun retryWithGpu(spec: ModelSpec, file: File) {
        prefs.edit().putBoolean(KEY_GPU_DISABLED, false).commit()
        reload(spec, file)
    }

    /** User-requested reload (also clears an auto-load block left by a crash during loading). */
    fun reload(spec: ModelSpec, file: File) {
        _state.value = EngineState.Loading(if (isGpuDisabled) BackendKind.CPU else BackendKind.GPU)
        resetEpoch.incrementAndGet()
        appScope.launch(llmDispatcher) {
            releaseBlocking()
            loadBlocking(spec, file, userInitiated = true)
        }
    }

    private fun loadBlocking(spec: ModelSpec, file: File, userInitiated: Boolean) {
        var notice = examinePreviousLoad()
        if (!userInitiated && prefs.getBoolean(KEY_AUTOLOAD_BLOCKED, false)) {
            // Loading crashed the app last time: wait for the user instead of crash-looping.
            _state.value = EngineState.Failed(LlmError.OUT_OF_MEMORY, null, EngineNotice.PREVIOUS_LOAD_CRASHED)
            return
        }
        logModelInfo(file)

        val backends = if (isGpuDisabled) listOf(BackendKind.CPU) else listOf(BackendKind.GPU, BackendKind.CPU)
        var lastError: Throwable? = null
        for (backend in backends) {
            _state.value = EngineState.Loading(backend)
            // commit() (synchronous) so the marker survives if the process dies inside native code.
            prefs.edit()
                .putString(KEY_LOADING_BACKEND, backend.name)
                .putLong(KEY_LOADING_AT, System.currentTimeMillis())
                .commit()
            val candidate =
                Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = if (backend == BackendKind.GPU) Backend.GPU() else Backend.CPU(),
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = spec.maxNumTokens,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                )
            val start = SystemClock.elapsedRealtime()
            try {
                candidate.initialize()
                clearLoadingMarker()
                prefs.edit().remove(KEY_AUTOLOAD_BLOCKED).commit()
                engine = candidate
                if (backend == BackendKind.CPU && lastError != null && notice == null) {
                    notice = EngineNotice.GPU_FAILED_FELL_BACK
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "Engine ready on $backend in ${elapsed}ms")
                _state.value = EngineState.Ready(backend, elapsed, notice)
                return
            } catch (t: Throwable) {
                clearLoadingMarker()
                Log.w(TAG, "Engine init failed on $backend", t)
                lastError = t
            }
        }
        _state.value =
            EngineState.Failed(classifyLoadError(lastError), lastError?.let { describe(it) }, notice)
    }

    /**
     * Checks the marker left by a load that never finished, and why the process died.
     * Only a real crash disables GPU; a swipe from Recents or an app update is ignored.
     */
    private fun examinePreviousLoad(): EngineNotice? {
        val backend = prefs.getString(KEY_LOADING_BACKEND, null) ?: return null
        val markerAt = prefs.getLong(KEY_LOADING_AT, 0L)
        clearLoadingMarker()
        val exit =
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            }.getOrDefault(emptyList())
                .filter { it.timestamp >= markerAt }
                .minByOrNull { it.timestamp }
                ?: return null
        val crashed =
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                exit.reason == ApplicationExitInfo.REASON_CRASH ||
                (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status in FATAL_SIGNALS)
        val lowMemory = exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY
        Log.i(TAG, "Previous load on $backend ended: reason=${exit.reason} status=${exit.status}")
        return when {
            crashed && backend == BackendKind.GPU.name -> {
                prefs.edit().putBoolean(KEY_GPU_DISABLED, true).commit()
                EngineNotice.GPU_DISABLED_AFTER_CRASH
            }
            crashed || lowMemory -> {
                prefs.edit().putBoolean(KEY_AUTOLOAD_BLOCKED, true).commit()
                EngineNotice.PREVIOUS_LOAD_CRASHED
            }
            else -> null
        }
    }

    private fun clearLoadingMarker() {
        prefs.edit().remove(KEY_LOADING_BACKEND).remove(KEY_LOADING_AT).commit()
    }

    private fun logModelInfo(file: File) {
        runCatching {
            ModelInfo.from(file.absolutePath).use { info ->
                Log.i(
                    TAG,
                    "ModelInfo: maxContextTokens=${info.maxContextTokens()} " +
                        "dynamicContext=${info.isDynamicContext()} minRuntime=${info.minRuntimeVersion()}",
                )
            }
        }.onFailure { Log.w(TAG, "ModelInfo unavailable", it) }
    }

    /**
     * Streams a response to [text]. The Conversation is created (or rebuilt) on demand with
     * [settings]; [history] seeds a rebuilt Conversation so the model keeps earlier turns.
     */
    fun generate(
        text: String,
        settings: ConversationSettings,
        history: List<Message>,
        maxOutputTokens: Int?,
    ): Flow<GenerationEvent> =
        callbackFlow {
            val epoch = resetEpoch.get()
            val finished = AtomicBoolean(false)
            val callback =
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // Only the main content; thinking/other channels live in message.channels.
                        val chunk = message.toString()
                        if (chunk.isNotEmpty()) trySend(GenerationEvent.Chunk(chunk))
                    }

                    override fun onDone() {
                        finished.set(true)
                        trySend(GenerationEvent.Done)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        finished.set(true)
                        conversationPoisoned = true
                        if (throwable is CancellationException) {
                            trySend(GenerationEvent.Cancelled)
                        } else {
                            Log.w(TAG, "Generation error", throwable)
                            trySend(GenerationEvent.Error(classifyGenerationError(throwable), describe(throwable)))
                        }
                        close()
                    }
                }
            val started =
                try {
                    withContext(llmDispatcher) {
                        if (resetEpoch.get() != epoch) return@withContext false
                        val conv = ensureConversation(settings, history)
                        if (resetEpoch.get() != epoch) {
                            conversationPoisoned = true
                            return@withContext false
                        }
                        synchronized(conversationLock) {
                            conv.sendMessageAsync(text, callback, maxOutputToken = maxOutputTokens)
                        }
                        // stop() raced the send: cancel what just started.
                        if (resetEpoch.get() != epoch) cancelCurrent()
                        true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "Starting generation failed", t)
                    finished.set(true)
                    conversationPoisoned = true
                    trySend(GenerationEvent.Error(classifyGenerationError(t), describe(t)))
                    close()
                    true
                }
            if (!started) {
                finished.set(true)
                trySend(GenerationEvent.Cancelled)
                close()
            }
            awaitClose {
                // Collector went away mid-generation: stop the native work too.
                if (!finished.get()) stop()
            }
        }.buffer(Channel.UNLIMITED)

    /** Stops the current generation immediately. The Conversation is rebuilt before the next send. */
    fun stop() {
        resetEpoch.incrementAndGet()
        conversationPoisoned = true
        // Off the caller's thread: cancelProcess() may wait for a concurrent close() to finish.
        appScope.launch(Dispatchers.IO) { cancelCurrent() }
    }

    /** Drops the live Conversation so the next send starts fresh (new chat, settings change). */
    fun resetConversation() {
        resetEpoch.incrementAndGet()
        conversationPoisoned = true
    }

    /** Tokens currently held in the Conversation's KV cache, or null if unknown. */
    suspend fun tokenCount(): Int? =
        withContext(llmDispatcher) {
            synchronized(conversationLock) {
                conversation
                    ?.takeIf { it.isAlive && !conversationPoisoned }
                    ?.let { runCatching { it.getTokenCount() }.getOrNull() }
            }
        }

    private fun cancelCurrent() {
        synchronized(conversationLock) {
            val conv = conversation ?: return
            if (conv.isAlive) runCatching { conv.cancelProcess() }.onFailure { Log.w(TAG, "cancelProcess failed", it) }
        }
    }

    /** Cancel before close: close() otherwise waits for a running decode to finish on its own. */
    private fun closeConversation(conv: Conversation) {
        synchronized(conversationLock) {
            if (!conv.isAlive) return
            runCatching { conv.cancelProcess() }
            runCatching { conv.close() }.onFailure { Log.w(TAG, "Closing conversation failed", it) }
        }
    }

    private fun ensureConversation(settings: ConversationSettings, history: List<Message>): Conversation {
        val eng = engine?.takeIf { it.isInitialized() } ?: error("Engine is not ready")
        val existing = conversation
        if (existing != null && existing.isAlive && !conversationPoisoned && conversationSettings == settings) {
            return existing
        }
        if (existing != null) closeConversation(existing)
        val created =
            eng.createConversation(
                ConversationConfig(
                    systemInstruction = settings.systemPrompt?.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                    initialMessages = history,
                    samplerConfig = SamplerConfig(
                        topK = settings.topK,
                        topP = settings.topP,
                        temperature = settings.temperature,
                        seed = (System.nanoTime() and 0x7fffffff).toInt(),
                    ),
                )
            )
        conversation = created
        conversationSettings = settings
        conversationPoisoned = false
        return created
    }

    private fun releaseBlocking() {
        conversation?.let { closeConversation(it) }
        conversation = null
        engine?.let { eng -> runCatching { if (eng.isInitialized()) eng.close() } }
        engine = null
    }

    private fun classifyLoadError(t: Throwable?): LlmError {
        val message = t?.message.orEmpty()
        return when {
            t is OutOfMemoryError -> LlmError.OUT_OF_MEMORY
            t is UnsatisfiedLinkError || t is NoClassDefFoundError -> LlmError.NATIVE_LIBRARY
            message.contains("Model file not found", ignoreCase = true) -> LlmError.MODEL_MISSING
            CORRUPT_MARKERS.any { message.contains(it, ignoreCase = true) } -> LlmError.MODEL_CORRUPT
            message.contains("memory", ignoreCase = true) || message.contains("RESOURCE_EXHAUSTED") ->
                LlmError.OUT_OF_MEMORY
            isLowOnMemory() -> LlmError.OUT_OF_MEMORY
            else -> LlmError.LOAD_FAILED
        }
    }

    /** Context overflow: one input longer than the context, or history + input exceed what is left. */
    private fun classifyGenerationError(t: Throwable): LlmError {
        val message = t.message.orEmpty()
        return if (CONTEXT_FULL_MARKERS.any { message.contains(it, ignoreCase = true) }) LlmError.CONTEXT_FULL
        else LlmError.GENERATION_FAILED
    }

    private fun isLowOnMemory(): Boolean {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.lowMemory
    }

    /** Device RAM in GB: the advertised size on Android 14+, otherwise totalMem rounded up to GiB. */
    fun deviceMemoryGb(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        if (Build.VERSION.SDK_INT >= 34) return info.advertisedMem / 1_000_000_000L
        val gib = 1L shl 30
        return (info.totalMem + gib - 1) / gib
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"

    companion object {
        private const val TAG = "LlmEngine"
        private const val PREFS = "llm_engine"
        private const val KEY_LOADING_BACKEND = "loading_backend"
        private const val KEY_LOADING_AT = "loading_at"
        private const val KEY_GPU_DISABLED = "gpu_disabled"
        private const val KEY_AUTOLOAD_BLOCKED = "autoload_blocked"

        private val FATAL_SIGNALS =
            intArrayOf(OsConstants.SIGSEGV, OsConstants.SIGBUS, OsConstants.SIGABRT, OsConstants.SIGILL, OsConstants.SIGFPE)

        private val CORRUPT_MARKERS =
            listOf(
                "Failed to create model assets",
                "Invalid magic number",
                "Invalid header",
                "Failed to read header",
                "has invalid offsets",
                "has zero size",
                "Invalid or incomplete compressed data",
            )

        // tasks.cc: "Input token ids are too long. Exceeding the maximum number of tokens allowed"
        // executor: "Prefill input length exceeds available state entries (remaining capacity: N)"
        private val CONTEXT_FULL_MARKERS =
            listOf("Input token ids are too long", "maximum number of tokens", "exceeds available state entries")
    }
}
