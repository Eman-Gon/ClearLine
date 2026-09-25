package com.clearline.app

import android.content.Context
import com.clearline.agent.OnDeviceCoordinator
import com.clearline.audio.*
import com.clearline.core.*
import com.clearline.inference.*
import com.clearline.sponsors.*
import com.clearline.storage.RoomWorkflowStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Concrete composition. No web server, laptop connection, hidden inference client or fixture runner. */
class AppGraph(context: Context) {
    private val context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = RoomWorkflowStore.open(context)
    private val modelArbiter = MutexModelArbiter()
    private val modelSwitch = Mutex()
    val liquid = EmbeddedLiquidRuntime(context, modelArbiter)
    val asr = WhisperAsrRuntime(context, modelArbiter)
    private val captureAdmission = Mutex()
    private val pendingCaptureMutable = MutableStateFlow<CompletedLocalClip?>(null)
    val pendingCapture = pendingCaptureMutable.asStateFlow()
    private val deliveryMessageMutable = MutableStateFlow<String?>(null)
    val deliveryMessage = deliveryMessageMutable.asStateFlow()
    val recorder = PcmAudioRecorder(context, scope) { clip ->
        pendingCaptureMutable.value = clip
        scope.launch { admitCapturedClip() } // One hardware completion event, independent of UI observation.
    }
    private val processor = WhisperAudioProcessor(context, asr)
    private val localAgent = OnDeviceLocalAgent(liquid)
    val sponsors = SponsorServices.create(context, CurrentStateAuthorization(store))
    val coordinator = OnDeviceCoordinator(
        store = store,
        audio = object : LocalAudioProcessor {
            override suspend fun process(clip: CompletedLocalClip): AudioResult = modelSwitch.withLock {
                liquid.unload()
                asr.load(WhisperModelManifest.identity.modelId)
                processor.process(clip)
            }
        },
        agent = object : ToolCallingLocalAgent {
            override suspend fun proposeTurn(checkpoint: AgentCheckpoint): ModelProposal = modelSwitch.withLock {
                asr.unload()
                liquid.load(LiquidModelCatalog.DEFAULT.modelId)
                localAgent.proposeTurn(checkpoint)
            }
        },
        resources = sponsors.resources,
        history = sponsors.history,
        scope = scope,
        cancelInference = { liquid.cancel() },
        cancelAudio = { asr.cancel() },
    )
    private val initialization = CompletableDeferred<Unit>()
    private var started = false
    fun initialize() {
        if (started) return
        started = true
        scope.launch {
            try {
                recorder.cleanPartialFiles()
                coordinator.initialize() // Recovery marks interrupted work; never starts a tool.
                asr.refreshInstalledStatus()
                liquid.refreshInstalledStatus()
                if (store.observeProfiles().first().isEmpty()) store.putProfile(LocalProfile(ProfileId.new(), "My check-ins", DataOrigin.CONSENTED_DEMO, System.currentTimeMillis()))
                initialization.complete(Unit)
            } catch (failure: Throwable) { initialization.completeExceptionally(failure) }
        }
    }
    suspend fun awaitInitialized() { initialization.await() }
    suspend fun admitCapturedClip() = captureAdmission.withLock {
        var clip = pendingCaptureMutable.value ?: return@withLock
        try {
            val session = store.getSession(clip.sessionId) ?: throw ClearLineException(AppError(ErrorCode.DELETED, "The recording’s check-in was deleted."))
            if (session.clips.none { it.clip.clipId == clip.clipId }) {
                val replaced = session.clips.lastOrNull { it.result == null && it.supersededBy == null }?.clip?.clipId
                if (replaced != null) clip = clip.copy(supersedesClipId = replaced)
                pendingCaptureMutable.value = clip
                coordinator.acceptClip(clip)
            }
            coordinator.finishCapture(clip.sessionId)
            pendingCaptureMutable.value = null
            deliveryMessageMutable.value = "Complete recording accepted on this phone. Processing can resume from local storage."
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            deliveryMessageMutable.value = (failure as? ClearLineException)?.error?.message ?: "Complete clip is waiting for admission. Retry when ready."
        }
    }
    suspend fun load(kind: ModelKind) = modelSwitch.withLock {
        when (kind) {
            ModelKind.LIQUID -> { asr.unload(); liquid.load(LiquidModelCatalog.DEFAULT.modelId) }
            ModelKind.WHISPER -> { liquid.unload(); asr.load(WhisperModelManifest.identity.modelId) }
        }
    }
    suspend fun unload(kind: ModelKind) = modelSwitch.withLock { when (kind) { ModelKind.LIQUID -> liquid.unload(); ModelKind.WHISPER -> asr.unload() } }
    @Volatile private var foregroundNow = false
    @Volatile private var foregroundGeneration = 0L
    private val lifecycle = Mutex()
    fun captureIntent(): Long? = foregroundGeneration.takeIf { foregroundNow }
    suspend fun startRecording(generation: Long?, session: SessionSnapshot) = withContext(Dispatchers.Main.immediate) {
        if (!foregroundNow || generation == null || generation != foregroundGeneration) throw ClearLineException(AppError(ErrorCode.CANCELLED, "Recording start was interrupted. Press Start again when ready."))
        recorder.start(session.sessionId, ClipId.new(), session.dataOrigin)
    }
    suspend fun background() {
        lifecycle.withLock {
            liquid.cancel(); asr.cancel()
            try { coordinator.onBackground() }
            finally {
                recorder.interrupt()
                if (foregroundNow) coordinator.onForeground()
            }
        }
    }
    fun markBackground() { foregroundNow = false; foregroundGeneration++; coordinator.markBackground(); recorder.requestInterruption(); liquid.cancel(); asr.cancel() }
    fun foreground() { foregroundNow = true; coordinator.onForeground() }
}
