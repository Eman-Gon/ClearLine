package com.clearline.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clearline.audio.*
import com.clearline.core.*
import com.clearline.inference.LiquidModelCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CheckInViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as ClearLineApplication).graph
    private val preferences = application.getSharedPreferences("local-selection", 0)
    private val stateMutable = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = stateMutable.asStateFlow()
    private var sessionObservation: Job? = null
    private var historyObservation: Job? = null
    private var followUpObservation: Job? = null
    private val uiCommands = Mutex()
    private var pendingCommands = 0
    private var creatingSession = false

    init {
        viewModelScope.launch { graph.liquid.status.collect { value -> stateMutable.update { it.copy(liquid = value) } } }
        viewModelScope.launch { graph.asr.status.collect { value -> stateMutable.update { it.copy(asr = value) } } }
        viewModelScope.launch { graph.sponsors.credentials.observeStatus().collect { value -> stateMutable.update { it.copy(credentials = value) } } }
        viewModelScope.launch { graph.sponsors.credentials.observeConfiguration().collect { value -> stateMutable.update { it.copy(sponsorConfiguration = value) } } }
        viewModelScope.launch { graph.deliveryMessage.collect { value -> stateMutable.update { it.copy(message = value) } } }
        viewModelScope.launch { graph.pendingCapture.collect { clip -> if (clip != null) stateMutable.update { it.copy(capture = it.capture.copy(message = "Complete clip waiting for admission.")) } } }
        viewModelScope.launch {
            graph.recorder.state.collect { capture ->
                when (capture) {
                    CaptureState.Idle -> stateMutable.update { it.copy(capture = CaptureUi()) }
                    is CaptureState.Recording -> stateMutable.update { it.copy(capture = CaptureUi(true, capture.elapsedMs, (capture.levelRms * 5).toFloat())) }
                    is CaptureState.Interrupted -> stateMutable.update { it.copy(capture = CaptureUi(message = capture.reason)) }
                    is CaptureState.Failed -> stateMutable.update { it.copy(capture = CaptureUi(message = capture.error.message), error = capture.error.message) }
                    is CaptureState.Finalized -> {
                        stateMutable.update { it.copy(capture = CaptureUi(elapsedMs = (capture.clip.durationSeconds * 1000).toLong(), message = "Complete clip waiting for admission."), screen = Screen.SUMMARY) }
                    }
                }
            }
        }
        viewModelScope.launch {
            try {
                graph.awaitInitialized()
                graph.store.observeProfiles().collect { profiles ->
                    stateMutable.update { it.copy(profiles = profiles) }
                    if (state.value.profile == null || profiles.none { it.profileId == state.value.profile?.profileId }) {
                        val chosen = profiles.find { it.profileId.value == preferences.getString("profile", null) } ?: profiles.firstOrNull()
                        if (chosen != null) selectProfile(chosen.profileId, restoreSession = true)
                    }
                }
            } catch (failure: Throwable) { displayFailure(failure) }
        }
    }

    fun handle(event: UiEvent) {
        if ((state.value.capture.active || creatingSession || graph.pendingCapture.value != null) && (event is UiEvent.SelectSession || event is UiEvent.SelectProfile || event is UiEvent.CreateProfile || event == UiEvent.NewCheckIn || event == UiEvent.DeleteSession || event == UiEvent.DeleteProfile)) {
            stateMutable.update { it.copy(error = "Finish or admit the current recording before switching or deleting check-ins.") }; return
        }
        if (state.value.capture.active && (event is UiEvent.DownloadModel || event is UiEvent.ImportModel || event is UiEvent.LoadModel || event is UiEvent.UnloadModel)) { stateMutable.update { it.copy(error = "Stop recording before changing model setup.") }; return }
        when (event) {
            is UiEvent.Navigate -> stateMutable.update { it.copy(screen = event.screen) }
            UiEvent.ClearMessage -> stateMutable.update { it.copy(error = null, message = null) }
            UiEvent.NewCheckIn -> {
                if (state.value.capture.active) return
                sessionObservation?.cancel(); preferences.edit().remove("session").apply()
                stateMutable.update { it.copy(screen = Screen.CHECK_IN, session = null, capture = CaptureUi(), error = null, message = null) }
            }
            is UiEvent.SelectSession -> runCommand { selectSession(event.id) }
            is UiEvent.SelectProfile -> runCommand { selectProfile(event.id) }
            is UiEvent.CreateProfile -> runCommand { val profile = LocalProfile(ProfileId.new(), event.label, DataOrigin.CONSENTED_DEMO, System.currentTimeMillis()); graph.store.putProfile(profile); selectProfile(profile.profileId) }
            is UiEvent.StartRecording -> { val generation = graph.captureIntent(); runCommand { startRecording(event, generation) } }
            UiEvent.StopRecording -> viewModelScope.launch { try { graph.recorder.stop() } catch (failure: Exception) { displayFailure(failure) } }
            UiEvent.RetryAdmission -> runCommand { graph.admitCapturedClip() }
            UiEvent.FinishCapture -> runCommand { currentSession()?.let { graph.coordinator.finishCapture(it.sessionId) } }
            UiEvent.Pause -> runCommand { currentSession()?.let { graph.coordinator.pause(it.sessionId) } }
            UiEvent.Resume -> runCommand { currentSession()?.let { graph.coordinator.resume(it.sessionId) } }
            is UiEvent.RequestResources -> runCommand {
                val session = currentSession() ?: return@runCommand
                graph.coordinator.requestResources(ApprovedResourceRequest(session.sessionId, event.revision, ApprovalId.new(), event.draft.category, event.draft.city, System.currentTimeMillis(), queryDraft = event.draft))
            }
            is UiEvent.CorrectTranscript -> runCommand { currentSession()?.let { graph.coordinator.applyInput(RevisionedUserInput(it.sessionId, event.revision, UserInput.TranscriptCorrection(event.clipId, event.text))) } }
            is UiEvent.Answer -> runCommand { currentSession()?.let { graph.coordinator.applyInput(RevisionedUserInput(it.sessionId, event.revision, UserInput.Answer(event.text))) } }
            is UiEvent.ExportConsent -> runCommand { currentSession()?.let { graph.coordinator.setExportConsent(ExportConsentChange(it.sessionId, event.revision, event.fields, reviewedTranscriptSnippet = event.snippet, reviewedKeyword = event.keyword)) } }
            UiEvent.RefreshExportedMemory -> runCommand { currentSession()?.let { graph.coordinator.refreshExportedMemory(it.sessionId) } }
            UiEvent.DeleteSession -> runCommand { currentSession()?.let { graph.coordinator.deleteSession(it.sessionId) }; sessionObservation?.cancel(); preferences.edit().remove("session").apply(); stateMutable.update { it.copy(session = null, screen = Screen.HOME, message = "Local check-in deleted. Delivered cloud records are unchanged.") } }
            UiEvent.DeleteProfile -> runCommand { state.value.profile?.let { graph.coordinator.deleteProfile(it.profileId) }; sessionObservation?.cancel(); historyObservation?.cancel(); followUpObservation?.cancel(); preferences.edit().clear().apply(); stateMutable.update { it.copy(profile = null, session = null, history = emptyList(), openFollowUps = emptyList(), screen = Screen.SETUP) } }
            is UiEvent.DownloadModel -> runCommand { when (event.kind) { ModelKind.LIQUID -> graph.liquid.install(LiquidModelCatalog.downloadArtifact(System.currentTimeMillis())); ModelKind.WHISPER -> graph.asr.install(WhisperModelManifest.approvedDownload()) } }
            is UiEvent.LoadModel -> runCommand { graph.load(event.kind) }
            is UiEvent.UnloadModel -> runCommand { graph.unload(event.kind) }
            is UiEvent.SetCredential -> runCommand {
                val secret = SecretValue(event.secret)
                event.secret.fill('\u0000')
                try { graph.sponsors.credentials.setCredential(event.sponsor, secret) } finally { secret.close() }
            }
            is UiEvent.ClearCredential -> runCommand { graph.sponsors.credentials.clearCredential(event.sponsor) }
            is UiEvent.SetSponsorConfiguration -> runCommand { graph.sponsors.credentials.setConfiguration(event.configuration) }
            is UiEvent.OpenSource, is UiEvent.ImportModel -> Unit // Activity owns explicit OS interactions.
        }
    }
    fun importModel(kind: ModelKind, uri: Uri) = runCommand {
        if (state.value.capture.active) throw ClearLineException(AppError(ErrorCode.INVALID_STATE, "Stop recording before importing a model."))
        val identity = if (kind == ModelKind.LIQUID) LiquidModelCatalog.DEFAULT else WhisperModelManifest.identity
        val approved = ApprovedModelArtifact(identity, uri.toString(), System.currentTimeMillis())
        if (kind == ModelKind.LIQUID) graph.liquid.install(approved) else graph.asr.install(approved)
    }
    fun permissionDenied() { stateMutable.update { it.copy(error = "Microphone permission was denied. You can grant it in Android settings and try again.") } }
    fun reportActionError(message: String) { stateMutable.update { it.copy(error = message) } }
    fun foreground() { graph.foreground() }
    fun background() { graph.markBackground(); graph.scope.launch { graph.background() } }
    private suspend fun startRecording(event: UiEvent.StartRecording, generation: Long?) {
        require(event.consent) { "Recording consent is required." }
        if (creatingSession || state.value.capture.active || graph.pendingCapture.value != null) return
        creatingSession = true
        try {
            graph.awaitInitialized()
            val profile = state.value.profile ?: throw ClearLineException(AppError(ErrorCode.NOT_FOUND, "Create a local profile first."))
            var session = currentSession()
            if (session == null) {
                val id = graph.coordinator.createSession(CreateSession(profile.profileId, true))
                if (event.selectedExportFields.isNotEmpty()) graph.coordinator.setExportConsent(ExportConsentChange(id, 0, event.selectedExportFields))
                selectSession(id, Screen.CHECK_IN)
                session = graph.store.getSession(id)
            }
            requireNotNull(session)
            if (session.phase != Phase.RECORDING && !(session.phase == Phase.AWAITING_INPUT && session.pendingInput?.reason == "replacement_recording")) throw ClearLineException(AppError(ErrorCode.INVALID_STATE, "This check-in is not waiting for a recording."))
            graph.startRecording(generation, session)
        } finally { creatingSession = false }
    }
    private suspend fun selectProfile(id: ProfileId, restoreSession: Boolean = false) {
        val profile = graph.store.getProfile(id) ?: return
        sessionObservation?.cancel(); historyObservation?.cancel(); followUpObservation?.cancel()
        preferences.edit().putString("profile", id.value).apply()
        stateMutable.update { it.copy(profile = profile, session = null, history = emptyList(), openFollowUps = emptyList()) }
        historyObservation = viewModelScope.launch { graph.store.observeHistory(id).collect { rows -> stateMutable.update { it.copy(history = rows) } } }
        followUpObservation = viewModelScope.launch { graph.store.observeOpenFollowUps(id).collect { rows -> stateMutable.update { it.copy(openFollowUps = rows) } } }
        if (restoreSession) preferences.getString("session", null)?.let { raw -> runCatching { SessionId(raw) }.getOrNull()?.let { sessionId -> if (graph.store.getSession(sessionId)?.profileId == id) selectSession(sessionId, Screen.HOME) } }
        else preferences.edit().remove("session").apply()
    }
    private suspend fun selectSession(id: SessionId, screen: Screen = Screen.SUMMARY) {
        val session = graph.store.getSession(id) ?: throw ClearLineException(AppError(ErrorCode.NOT_FOUND, "This check-in is no longer on the phone."))
        sessionObservation?.cancel()
        preferences.edit().putString("session", id.value).apply()
        stateMutable.update { it.copy(session = session, screen = screen, error = null) }
        sessionObservation = viewModelScope.launch { graph.store.observeSession(id).catch { failure -> if (failure is CancellationException) throw failure else displayFailure(failure) }.collect { value -> stateMutable.update { it.copy(session = value) } } }
    }
    private suspend fun currentSession() = state.value.session?.sessionId?.let { graph.store.getSession(it) }
    private fun runCommand(block: suspend () -> Unit) {
        pendingCommands++
        stateMutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { uiCommands.withLock { graph.awaitInitialized(); block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { displayFailure(failure) }
            finally { pendingCommands--; stateMutable.update { it.copy(busy = pendingCommands > 0) } }
        }
    }
    private fun displayFailure(failure: Throwable) {
        if (failure is CancellationException) return
        val message = (failure as? ClearLineException)?.error?.message ?: "This action could not finish. Your accepted check-in remains on this phone."
        stateMutable.update { it.copy(error = message) }
    }
}
