package com.clearline.app

import com.clearline.core.*

enum class Screen { HOME, CHECK_IN, SUMMARY, FOLLOW_UP, SETUP, DIAGNOSTICS }
data class CaptureUi(val active: Boolean = false, val elapsedMs: Long = 0, val rms: Float = 0f, val message: String? = null)
data class AppUiState(
    val screen: Screen = Screen.HOME,
    val profiles: List<LocalProfile> = emptyList(),
    val profile: LocalProfile? = null,
    val history: List<SessionSummary> = emptyList(),
    val openFollowUps: List<SessionSnapshot> = emptyList(),
    val session: SessionSnapshot? = null,
    val liquid: ModelStatus = ModelStatus(ModelKind.LIQUID),
    val asr: ModelStatus = ModelStatus(ModelKind.WHISPER),
    val credentials: List<CredentialStatus> = emptyList(),
    val sponsorConfiguration: SponsorConfiguration = SponsorConfiguration(),
    val capture: CaptureUi = CaptureUi(),
    val pendingCaptureAdmission: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)
sealed interface UiEvent {
    data class Navigate(val screen: Screen) : UiEvent
    data object NewCheckIn : UiEvent
    data class SelectSession(val id: SessionId) : UiEvent
    data class SelectProfile(val id: ProfileId) : UiEvent
    data class CreateProfile(val label: String) : UiEvent
    data class StartRecording(val consent: Boolean, val selectedExportFields: Set<ExportField>) : UiEvent
    data object StopRecording : UiEvent
    data object RetryAdmission : UiEvent
    data object FinishCapture : UiEvent
    data object Pause : UiEvent
    data object Resume : UiEvent
    data class RequestResources(val draft: CallQueryDraft, val revision: Long) : UiEvent
    data class CorrectTranscript(val clipId: ClipId, val text: String, val revision: Long) : UiEvent
    data class Answer(val text: String, val revision: Long) : UiEvent
    data class ExportConsent(val fields: Set<ExportField>, val revision: Long, val snippet: String? = null, val keyword: String? = null, val inputRevision: Long) : UiEvent
    data object RefreshExportedMemory : UiEvent
    data object DeleteSession : UiEvent
    data object DeleteProfile : UiEvent
    data class DownloadModel(val kind: ModelKind) : UiEvent
    data class ImportModel(val kind: ModelKind) : UiEvent
    data class LoadModel(val kind: ModelKind) : UiEvent
    data class UnloadModel(val kind: ModelKind) : UiEvent
    data class SetCredential(val sponsor: Sponsor, val secret: CharArray) : UiEvent
    data class ClearCredential(val sponsor: Sponsor) : UiEvent
    data class SetSponsorConfiguration(val configuration: SponsorConfiguration) : UiEvent
    data class OpenSource(val url: String) : UiEvent
    data object ClearMessage : UiEvent
}
