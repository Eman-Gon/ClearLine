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
        if (!foregroundNow || generation == null || generation != foregroundGeneration) throw ClearLineException(AppError(ErrorCode.INTERRUPTED, "Recording start was interrupted. Press Start again when ready."))
        recorder.start(session.sessionId, ClipId.new(), session.dataOrigin)
    }
    suspend fun background() {
        lifecycle.withLock {
            liquid.cancel(); asr.cancel()
            coordinator.onBackground()
            recorder.interrupt()
            if (foregroundNow) coordinator.onForeground()
        }
    }
    fun markBackground() { foregroundNow = false; foregroundGeneration++; coordinator.markBackground(); recorder.requestInterruption(); liquid.cancel(); asr.cancel() }
    fun foreground() { foregroundNow = true; coordinator.onForeground() }
}

/** Reads current local state immediately before each sponsor dispatch; never grants consent. */
internal class CurrentStateAuthorization(private val store: WorkflowStore) : SponsorAuthorization {
    private fun deny(message: String): Nothing = throw ClearLineException(AppError(ErrorCode.CONSENT_REQUIRED, message))
    override suspend fun requireResearch(request: ApprovedResourceRequest) {
        val session = store.getSession(request.sessionId) ?: deny("This check-in was deleted.")
        if (session.approvedResources != request || session.phase == Phase.PAUSED || session.pauseRequested) deny("Approve the current search before it can leave the phone.")
        request.queryDraft?.let { if (it.transcriptHash != CallInsights.transcriptHash(CallInsights.transcriptFor(session))) deny("The transcript changed. Review and approve a new search.") }
    }
    override suspend fun requireSource(source: ApprovedSource) {
        requireResearch(source.request)
        val session = store.getSession(source.request.sessionId) ?: deny("This check-in was deleted.")
        if (source.candidate !in session.resources) deny("Only a current approved search result can be inspected.")
    }
    override suspend fun requireExport(event: ApprovedExport) {
        val session = store.getSession(event.sessionId) ?: deny("This check-in was deleted.")
        if (session.profileId != event.profileId || session.dataOrigin != event.dataOrigin || session.inputRevision != event.inputRevision || session.consent.exportRevision != event.consentRevision || event.requiredField !in session.consent.exportFields) deny("Export consent or input has changed.")
        val measurements = event.projection as? ExportProjection.Measurements
        if (measurements != null && (measurements.transcriptSnippet != null || measurements.topKeyword != null) && ExportField.TRANSCRIPT_SNIPPET !in session.consent.exportFields) deny("Transcript snippet export requires its own approval.")
        if (measurements != null && (measurements.transcriptSnippet != session.consent.reviewedTranscriptSnippet || measurements.topKeyword != session.consent.reviewedKeyword) && (measurements.transcriptSnippet != null || measurements.topKeyword != null)) deny("Only the exact reviewed snippet and keyword may be exported.")
    }
    override suspend fun requireHistory(request: BoundedHistoryQuery) {
        if (store.getSession(request.sessionId) == null) deny("This check-in was deleted.")
    }
    override suspend fun requireMemory(query: MemoryQuery) {
        val session = store.getSession(query.currentSessionId) ?: deny("This check-in was deleted.")
        if (session.profileId != query.profileId || session.dataOrigin != query.dataOrigin || session.task != query.task) deny("Exported history must match this local profile.")
    }
}
