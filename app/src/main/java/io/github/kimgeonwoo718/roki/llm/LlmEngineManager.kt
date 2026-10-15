package io.github.kimgeonwoo718.roki.llm

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel as ModelChannel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.kimgeonwoo718.roki.BuildConfig
import io.github.kimgeonwoo718.roki.model.ModelSpec
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    /** "<|think|>" is rendered into the system turn once per Conversation, so this is a rebuild key. */
    val thinkingEnabled: Boolean,
    /**
     * Gemma 4 thoughts open with "<|channel>thought\n", and the model file's channel config only
     * matches that exact text. When the model samples a different word after "<|channel>"
     * ("<|channel> thoughtful…"), all of its reasoning ends up in the answer, uncapped by the
     * thinking budget and left in the KV cache. Overriding the channel to start at "<|channel>"
     * alone catches every variant; the name that follows stays in the thought text (AnswerFilter
     * drops it). Named "thought", so the runtime still treats it as the reasoning channel.
     */
    val tolerantThoughtChannel: Boolean = false,
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
    @Volatile private var loadedBackend: BackendKind? = null
    @Volatile private var conversation: Conversation? = null
    private var conversationSettings: ConversationSettings? = null

    /** Guards cancelProcess() against close() of the same Conversation on another thread. */
    private val conversationLock = Any()

    /** Set when the live Conversation must not be reused (after a stop, an error or a reset). */
    @Volatile private var conversationPoisoned = false

    /** Bumped by stop()/resetConversation() so an in-flight generate() that has not sent yet bails out. */
    private val resetEpoch = AtomicInteger()

    /** GPU turned off automatically after a crash (the user can re-enable it). */
    val isGpuDisabled: Boolean
        get() = prefs.getBoolean(KEY_GPU_DISABLED, false)

    private val _forceCpu = MutableStateFlow(prefs.getBoolean(KEY_FORCE_CPU, false))

    /** The user chose to run on CPU only. */
    val forceCpu: StateFlow<Boolean> = _forceCpu.asStateFlow()

    private val cpuOnly: Boolean
        get() = isGpuDisabled || _forceCpu.value

    /**
     * "빠른 생성": multi-token prediction (speculative decoding) on GPU. Opt-in: LiteRT-LM's MTP
     * drafter and verifier sample greedily, so after the first token temperature/topK/seed no
     * longer apply and a small model is more prone to repetition (AI Edge Gallery also defaults off).
     */
    private val _speculative = MutableStateFlow(prefs.getBoolean(KEY_SPECULATIVE, false))
    val speculative: StateFlow<Boolean> = _speculative.asStateFlow()

    /** Whether the loaded engine runs with speculative decoding. */
    @Volatile private var loadedSpeculative = false

    /** Whether the model file has an MTP head (known after the first load). */
    @Volatile private var speculativeSupported = false

    /** Turns "빠른 생성" on or off; reloads only if that changes the GPU engine actually loaded. */
    fun setSpeculative(spec: ModelSpec, file: File, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPECULATIVE, enabled).commit()
        _speculative.value = enabled
        if (loadedBackend == BackendKind.GPU && speculativeSupported && enabled != loadedSpeculative) reload(spec, file)
    }

    /** Loads the model if it is not loaded or loading yet. Safe to call repeatedly. */
    fun load(spec: ModelSpec, file: File) {
        val current = _state.value
        if (current is EngineState.Loading || current is EngineState.Ready) return
        _state.value = EngineState.Loading(if (cpuOnly) BackendKind.CPU else BackendKind.GPU)
        appScope.launch(llmDispatcher) { loadBlocking(spec, file, userInitiated = false) }
    }

    /** Re-enables GPU after it was disabled by a crash (and clears "CPU only"), then reloads. */
    fun retryWithGpu(spec: ModelSpec, file: File) {
        prefs.edit().putBoolean(KEY_GPU_DISABLED, false).putBoolean(KEY_FORCE_CPU, false).commit()
        _forceCpu.value = false
        reload(spec, file)
    }

    /** Switches between "GPU first" and "CPU only"; reloads only if the backend actually changes. */
    fun setForceCpu(spec: ModelSpec, file: File, enabled: Boolean) {
        val before = cpuOnly
        prefs.edit().putBoolean(KEY_FORCE_CPU, enabled).commit()
        _forceCpu.value = enabled
        if (cpuOnly != before) reload(spec, file)
    }

    /** User-requested reload (also clears an auto-load block left by a crash during loading). */
    fun reload(spec: ModelSpec, file: File) {
        _state.value = EngineState.Loading(if (cpuOnly) BackendKind.CPU else BackendKind.GPU)
        resetEpoch.incrementAndGet()
        appScope.launch(llmDispatcher) {
            releaseBlocking()
            loadBlocking(spec, file, userInitiated = true)
        }
    }

    private fun loadBlocking(spec: ModelSpec, file: File, userInitiated: Boolean) {
        var notice = examinePreviousRun()
        if (!userInitiated && prefs.getBoolean(KEY_AUTOLOAD_BLOCKED, false)) {
            // Loading crashed the app last time: wait for the user instead of crash-looping.
            _state.value = EngineState.Failed(LlmError.OUT_OF_MEMORY, null, EngineNotice.PREVIOUS_LOAD_CRASHED)
            return
        }
        purgeLegacyEngineCaches(spec)
        speculativeSupported = logModelInfo(file)

        // GPU with speculative decoding (fastest), then plain GPU, then CPU. Speculative decoding
        // gains little on CPU (model card), so it is only tried on GPU, and only once plain GPU has
        // worked on this device, so a GPU fault is never mistaken for an MTP fault.
        val gpuProven = prefs.getBoolean(KEY_GPU_OK, false)
        val attempts = buildList {
            if (!cpuOnly) {
                if (speculativeSupported && _speculative.value && gpuProven) {
                    add(LoadAttempt(BackendKind.GPU, speculative = true))
                }
                add(LoadAttempt(BackendKind.GPU, speculative = false))
            }
            add(LoadAttempt(BackendKind.CPU, speculative = false))
        }
        var lastError: Throwable? = null
        for (attempt in attempts) {
            val backend = attempt.backend
            _state.value = EngineState.Loading(backend)
            // commit() (synchronous) so the marker survives if the process dies inside native code.
            prefs.edit()
                .putString(KEY_LOADING_BACKEND, attempt.marker)
                .putLong(KEY_LOADING_AT, System.currentTimeMillis())
                .commit()
            val candidate = Engine(engineConfigFor(backend, spec, file))
            val start = SystemClock.elapsedRealtime()
            try {
                // Read only while the Engine is created; reset right after, as AI Edge Gallery does.
                @OptIn(ExperimentalApi::class)
                ExperimentalFlags.enableSpeculativeDecoding = attempt.speculative
                try {
                    candidate.initialize()
                } finally {
                    @OptIn(ExperimentalApi::class)
                    ExperimentalFlags.enableSpeculativeDecoding = null
                }
                clearLoadingMarker()
                prefs.edit().remove(KEY_AUTOLOAD_BLOCKED).commit()
                engine = candidate
                loadedBackend = backend
                loadedSpeculative = attempt.speculative
                if (backend == BackendKind.GPU && !attempt.speculative) prefs.edit().putBoolean(KEY_GPU_OK, true).commit()
                if (backend == BackendKind.CPU && lastError != null && notice == null) {
                    notice = EngineNotice.GPU_FAILED_FELL_BACK
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "Engine ready on $backend (speculative=${attempt.speculative}) in ${elapsed}ms")
                _state.value = EngineState.Ready(backend, elapsed, notice, speculative = attempt.speculative)
                return
            } catch (t: Throwable) {
                clearLoadingMarker()
                Log.w(TAG, "Engine init failed on $backend (speculative=${attempt.speculative})", t)
                if (attempt.speculative) {
                    // Not a GPU failure (plain GPU follows). Remember it, so later loads skip the
                    // failing attempt and the menu shows 빠른 생성 as off.
                    prefs.edit().putBoolean(KEY_SPECULATIVE, false).commit()
                    _speculative.value = false
                } else {
                    lastError = t
                }
            }
        }
        _state.value =
            EngineState.Failed(classifyLoadError(lastError), lastError?.let { describe(it) }, notice)
    }

    /**
     * Checks the markers left by a load or a GPU generation that never finished, and why the
     * process died. A swipe from Recents or an app update is ignored; a native crash on GPU, or
     * a memory kill while loading on GPU in the foreground, turns GPU off; any other crash or
     * memory kill during loading stops automatic loading until the user retries.
     */
    private fun examinePreviousRun(): EngineNotice? {
        val inferAt = prefs.getLong(KEY_INFER_AT, 0L)
        if (inferAt != 0L) {
            val inferSpeculative = prefs.getBoolean(KEY_INFER_SPECULATIVE, false)
            prefs.edit().remove(KEY_INFER_AT).remove(KEY_INFER_SPECULATIVE).commit()
            val exit = exitSince(inferAt)
            if (exit != null && isCrash(exit)) {
                Log.i(TAG, "Previous GPU generation crashed: reason=${exit.reason} status=${exit.status}")
                // With speculative decoding on, turn that off first and keep the GPU.
                return if (inferSpeculative) disableSpeculative() else disableGpu()
            }
        }

        val backend = prefs.getString(KEY_LOADING_BACKEND, null) ?: return null
        val markerAt = prefs.getLong(KEY_LOADING_AT, 0L)
        clearLoadingMarker()
        val exit = exitSince(markerAt) ?: return null
        val crashed = isCrash(exit)
        // Devices without low-memory-kill reports show the same kill as SIGNALED + SIGKILL.
        val killed =
            exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == OsConstants.SIGKILL)
        val wasVisible = exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        Log.i(TAG, "Previous load on $backend ended: reason=${exit.reason} status=${exit.status}")
        return when {
            backend == MARKER_GPU_SPECULATIVE && (crashed || (killed && wasVisible)) -> disableSpeculative()
            backend == BackendKind.GPU.name && (crashed || (killed && wasVisible)) -> disableGpu()
            crashed || killed -> {
                prefs.edit().putBoolean(KEY_AUTOLOAD_BLOCKED, true).commit()
                EngineNotice.PREVIOUS_LOAD_CRASHED
            }
            else -> null
        }
    }

    private fun disableGpu(): EngineNotice {
        prefs.edit().putBoolean(KEY_GPU_DISABLED, true).remove(KEY_GPU_OK).commit()
        return EngineNotice.GPU_DISABLED_AFTER_CRASH
    }

    private fun disableSpeculative(): EngineNotice {
        prefs.edit().putBoolean(KEY_SPECULATIVE, false).commit()
        _speculative.value = false
        return EngineNotice.SPECULATIVE_DISABLED_AFTER_CRASH
    }

    /** The first process exit recorded at or after [since]. */
    private fun exitSince(since: Long): ApplicationExitInfo? =
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrDefault(emptyList())
            .filter { it.timestamp >= since }
            .minByOrNull { it.timestamp }

    private fun isCrash(exit: ApplicationExitInfo): Boolean =
        exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exit.reason == ApplicationExitInfo.REASON_CRASH ||
            (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status in FATAL_SIGNALS)

    private fun clearLoadingMarker() {
        prefs.edit().remove(KEY_LOADING_BACKEND).remove(KEY_LOADING_AT).commit()
    }

    private fun clearInferMarker() {
        prefs.edit().remove(KEY_INFER_AT).apply()
    }

    private fun engineConfigFor(backend: BackendKind, spec: ModelSpec, file: File): EngineConfig {
        val gpu = backend == BackendKind.GPU
        // Cache file names carry no precision, so each backend/precision gets its own folder, which
        // must exist beforehand (LiteRT-LM rejects a missing cacheDir).
        val cacheDir = File(context.cacheDir, if (gpu) "litertlm/gpu-fp32" else "litertlm/cpu").apply { mkdirs() }
        return EngineConfig(
            modelPath = file.absolutePath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = spec.maxNumTokens,
            cacheDir = cacheDir.absolutePath,
            // GPU defaults to FLOAT16 activations, which garble Korean output with stray
            // Japanese/Arabic/Cyrillic tokens (LiteRT-LM #3879, #2992) and lose rotary positions
            // past 2048 (#3913). FLOAT32 fixes both; the CPU backend ignores this setting.
            activationDataType = if (gpu) ActivationDataType.FLOAT32 else null,
        )
    }

    /** Once: delete engine caches that stage 1 wrote straight into cacheDir (FP16 GPU programs). */
    private fun purgeLegacyEngineCaches(spec: ModelSpec) {
        if (prefs.getBoolean(KEY_CACHE_V2, false)) return
        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(spec.fileName) } // all LiteRT caches start with the model name
            ?.forEach { runCatching { it.delete() } }
        prefs.edit().putBoolean(KEY_CACHE_V2, true).commit()
    }

    /** Logs the file's metadata and returns whether it supports speculative decoding (MTP). */
    private fun logModelInfo(file: File): Boolean =
        runCatching {
            ModelInfo.from(file.absolutePath).use { info ->
                val llm = info as? ModelInfo.Llm
                Log.i(
                    TAG,
                    "ModelInfo: maxContextTokens=${info.maxContextTokens()} " +
                        "dynamicContext=${info.isDynamicContext()} minRuntime=${info.minRuntimeVersion()} " +
                        "supportsThinking=${llm?.supportsThinking()} " +
                        "speculative=${llm?.hasSpeculativeDecodingSupport()}",
                )
                llm?.hasSpeculativeDecodingSupport() == true
            }
        }.onFailure { Log.w(TAG, "ModelInfo unavailable", it) }.getOrDefault(false)

    /**
     * Streams a response to [text]. The Conversation is created (or rebuilt) on demand with
     * [settings]; [history] seeds a rebuilt Conversation so the model keeps earlier turns.
     */
    fun generate(
        text: String,
        settings: ConversationSettings,
        history: List<Message>,
        maxOutputTokens: Int?,
        thinking: ThinkingConfig?,
    ): Flow<GenerationEvent> =
        callbackFlow {
            val epoch = resetEpoch.get()
            val finished = AtomicBoolean(false)
            val sentTo = AtomicReference<Conversation?>(null)
            val recancelled = AtomicBoolean(false)
            val callback =
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (resetEpoch.get() != epoch) {
                            // A cancel that lands between prefill and decode is lost by the runtime:
                            // cancel this exact Conversation once more (never lock on this native thread).
                            val conv = sentTo.get()
                            if (conv != null && recancelled.compareAndSet(false, true)) {
                                appScope.launch(Dispatchers.IO) {
                                    synchronized(conversationLock) {
                                        if (conv.isAlive) runCatching { conv.cancelProcess() }
                                    }
                                }
                            }
                            return
                        }
                        // Thoughts arrive as deltas in channels["thought"]; toString() is the answer only.
                        val thought = message.channels[THOUGHT_CHANNEL]
                        if (!thought.isNullOrEmpty()) trySend(GenerationEvent.Thought(thought))
                        val chunk = message.toString()
                        if (BuildConfig.DEBUG && (chunk.isNotEmpty() || !thought.isNullOrEmpty())) {
                            Log.d(RAW_TAG, if (thought.isNullOrEmpty()) "A:$chunk" else "T:$thought")
                        }
                        if (chunk.isNotEmpty()) trySend(GenerationEvent.Chunk(chunk))
                    }

                    override fun onDone() {
                        finished.set(true)
                        clearInferMarker()
                        trySend(GenerationEvent.Done)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        finished.set(true)
                        clearInferMarker()
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
                        // GPU driver faults often surface on the first prefill, not at load time.
                        if (loadedBackend == BackendKind.GPU) {
                            prefs.edit().putLong(KEY_INFER_AT, System.currentTimeMillis())
                                .putBoolean(KEY_INFER_SPECULATIVE, loadedSpeculative).commit()
                        }
                        synchronized(conversationLock) {
                            sentTo.set(conv)
                            conv.sendMessageAsync(
                                text,
                                callback,
                                maxOutputToken = maxOutputTokens,
                                // Per-message budget; on/off itself is fixed per Conversation.
                                thinkingConfig = thinking,
                            )
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
                    clearInferMarker()
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
                    // Use ThinkingConfig, never extraContext["enable_thinking"]. Old thoughts are
                    // stripped from the KV cache by default (filterChannelContentFromKvCache).
                    thinkingConfig = if (settings.thinkingEnabled) ThinkingConfig(enableThinking = true) else null,
                    // null keeps the model file's channels. The thinking budget uses the first channel.
                    channels = if (settings.tolerantThoughtChannel) listOf(TOLERANT_THOUGHT_CHANNEL) else null,
                    // Prefill system prompt + replayed history now. Otherwise the first answer's
                    // thoughts make the runtime rewind to step 0 and re-prefill all of it on the
                    // second message. Runs on llmDispatcher, never the main thread.
                    prefillPrefaceOnInit = true,
                )
            )
        if (BuildConfig.DEBUG) {
            @OptIn(ExperimentalApi::class)
            runCatching { created.renderPrefaceIntoString() }
                .onSuccess { Log.d(TAG, "Preface has <|think|>=${it.contains("<|think|>")}:\n$it") }
        }
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
        loadedBackend = null
        loadedSpeculative = false
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
        private const val RAW_TAG = "LlmRaw"
        private const val PREFS = "llm_engine"
        private const val KEY_LOADING_BACKEND = "loading_backend"
        private const val KEY_LOADING_AT = "loading_at"
        private const val KEY_GPU_DISABLED = "gpu_disabled"
        private const val KEY_AUTOLOAD_BLOCKED = "autoload_blocked"
        private const val KEY_INFER_AT = "gpu_infer_at"
        private const val KEY_INFER_SPECULATIVE = "gpu_infer_speculative"
        private const val KEY_SPECULATIVE = "speculative_decoding_v2"
        private const val KEY_GPU_OK = "gpu_ok"
        private const val KEY_FORCE_CPU = "force_cpu"
        private const val KEY_CACHE_V2 = "engine_cache_v2"

        /** Gemma 4's reasoning channel name (LlmMetadata channel_name). */
        const val THOUGHT_CHANNEL = "thought"

        /** See [ConversationSettings.tolerantThoughtChannel]. */
        private val TOLERANT_THOUGHT_CHANNEL = ModelChannel(THOUGHT_CHANNEL, "<|channel>", "<channel|>")

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

/** One way of creating the engine, tried in order until one initializes. */
private data class LoadAttempt(val backend: BackendKind, val speculative: Boolean) {
    /** Written before native init so a crash can be attributed to this attempt next launch. */
    val marker: String
        get() = if (speculative) MARKER_GPU_SPECULATIVE else backend.name
}

private const val MARKER_GPU_SPECULATIVE = "GPU_SPECULATIVE"
