package com.clearline.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable data class ConsentState(val recording: Boolean = false, val exportFields: Set<ExportField> = emptySet(), val exportRevision: Long = 0, val updatedAtMs: Long = 0, val reviewedTranscriptSnippet: String? = null, val reviewedKeyword: String? = null) { init { require(exportRevision >= 0); require(reviewedTranscriptSnippet == null || reviewedTranscriptSnippet.length <= 200); require(reviewedKeyword == null || reviewedKeyword.length <= 40) } }
@Serializable data class CloudSyncCounts(val pending: Int = 0, val delivered: Int = 0, val failed: Int = 0) { init { require(pending >= 0 && delivered >= 0 && failed >= 0) } }
@Serializable data class SessionSummary(val sessionId: SessionId, val profileId: ProfileId, val version: Int, val inputRevision: Long, val task: RecordingTask, val dataOrigin: DataOrigin, val metrics: RecordingMetrics, val completedAtMs: Long, val clipIds: List<ClipId>) { init { require(version > 0 && inputRevision >= 0 && clipIds.isNotEmpty() && clipIds.size <= 20) } }
@Serializable enum class BaselineStatus { AVAILABLE, INSUFFICIENT_HISTORY }
@Serializable data class BaselineSummary(val status: BaselineStatus, val previousSessions: List<SessionSummary>, val meanRecordingWpm: Double?, val meanEnergyRms: Double?) { init { require(previousSessions.size <= 5); require((status == BaselineStatus.AVAILABLE) == (previousSessions.size >= 2)); require(status == BaselineStatus.AVAILABLE || (meanRecordingWpm == null && meanEnergyRms == null)) } }
@Serializable data class MetricDifference(val current: Double, val mean: Double, val delta: Double, val standardizedDifference: Double?)
@Serializable data class DescriptiveComparison(val baseline: BaselineSummary, val recordingWpm: MetricDifference?, val energyRms: MetricDifference?, val methodVersion: String = "descriptive-baseline-v1")
@Serializable data class PendingInput(val reason: String, val question: String) { init { require(reason.length in 1..80 && question.length in 1..400) } }
@Serializable enum class WorkflowScope { COMPARISON, RESEARCH }
@Serializable enum class Requirement { AUDIO_ACCEPTED, BASELINE_READ, COMPARISON_COMPLETE, PUBLIC_SEARCH_COMPLETE, PUBLIC_EXTRACTION_COMPLETE, USER_INPUT_RECEIVED }
@Serializable enum class ToolName { GET_BASELINE_SUMMARY, COMPARE_RECORDING_METRICS, SEARCH_PUBLIC_RESOURCES, EXTRACT_PUBLIC_PAGE, REQUEST_USER_INPUT, FINISH_TASK }
@Serializable sealed interface ProposedAction {
    @Serializable data object GetBaselineSummary : ProposedAction
    @Serializable data object CompareRecordingMetrics : ProposedAction
    @Serializable data object SearchPublicResources : ProposedAction
    @Serializable data class ExtractPublicPage(val candidateId: String) : ProposedAction { init { require(candidateId.length in 1..128) } }
    @Serializable data class RequestUserInput(val input: PendingInput) : ProposedAction
    @Serializable data object FinishTask : ProposedAction
}
@Serializable sealed interface ActionResult {
    @Serializable data class Baseline(val value: BaselineSummary) : ActionResult
    @Serializable data class Comparison(val value: DescriptiveComparison) : ActionResult
    @Serializable data class Search(val value: SearchResult) : ActionResult
    @Serializable data class Extract(val value: SourceEvidence) : ActionResult
    @Serializable data class InputRequested(val value: PendingInput) : ActionResult
    @Serializable data object Finished : ActionResult
}
@Serializable enum class ActionStatus { PLANNED, RUNNING, SUCCEEDED, FAILED, UNKNOWN, INVALIDATED }
@Serializable data class ActionRecord(val actionId: ActionId, val jobId: JobId, val sessionId: SessionId, val inputRevision: Long, val ordinal: Int, val proposal: ProposedAction, val assistantMessage: ChatMessage, val toolCallId: String, val status: ActionStatus = ActionStatus.PLANNED, val result: ActionResult? = null, val error: AppError? = null, val createdAtMs: Long, val completedAtMs: Long? = null, val scope: WorkflowScope = WorkflowScope.COMPARISON, val scopeRevision: Long = inputRevision, val toolMessage: ChatMessage? = null) { init { require(scopeRevision >= 0 && inputRevision >= 0 && ordinal in 1..12 && toolCallId.length in 1..128) } }
@Serializable data class AgentCheckpoint(val schemaVersion: Int = 1, val sessionId: SessionId, val profileId: ProfileId, val inputRevision: Long, val stateVersion: Long, val phase: Phase, val task: RecordingTask, val dataOrigin: DataOrigin, val metrics: RecordingMetrics? = null, val comparison: DescriptiveComparison? = null, val approvedResources: ApprovedResourceRequest? = null, val pendingInput: PendingInput? = null, val completedActionIds: List<ActionId> = emptyList(), val pendingActionId: ActionId? = null, val exchanges: List<ChatMessage> = emptyList(), val sourceIds: List<EvidenceId> = emptyList(), val baseline: BaselineSummary? = null, val candidateSources: List<ResourceCandidate> = emptyList(), val workflowScope: WorkflowScope = WorkflowScope.COMPARISON, val scopeRevision: Long = inputRevision, val unresolvedRequirements: Set<Requirement> = emptySet()) { init { require(schemaVersion == 1 && inputRevision >= 0 && stateVersion >= 0); require(scopeRevision >= 0 && candidateSources.size <= 3 && unresolvedRequirements.size <= 6); require(completedActionIds.size <= 144 && exchanges.size <= 48 && sourceIds.size <= 36) } }
@Serializable data class SessionSnapshot(val schemaVersion: Int = 1, val sessionId: SessionId, val profileId: ProfileId, val phase: Phase, val stateVersion: Long = 0, val inputRevision: Long = 0, val executionMode: ExecutionMode, val dataOrigin: DataOrigin, val task: RecordingTask = RecordingTask.CHECK_IN, val createdAtMs: Long, val updatedAtMs: Long, val consent: ConsentState, val clips: List<StoredClip> = emptyList(), val metrics: RecordingMetrics? = null, val comparison: DescriptiveComparison? = null, val resources: List<ResourceCandidate> = emptyList(), val sources: List<SourceEvidence> = emptyList(), val approvedResources: ApprovedResourceRequest? = null, val pendingAction: ActionRecord? = null, val pendingInput: PendingInput? = null, val actions: List<ActionRecord> = emptyList(), val errors: List<AppError> = emptyList(), val cloudSync: CloudSyncCounts = CloudSyncCounts(), val pauseRequested: Boolean = false, val comparisonRevision: Long = 0, val researchRevision: Long = 0, val captureFinished: Boolean = false, val summaryVersion: Int = 0, val rawTreeMemory: RawTreeMemorySnapshot? = null) {
    init { require(schemaVersion == 1 && stateVersion >= 0 && inputRevision >= 0 && comparisonRevision >= 0 && researchRevision >= 0); require(clips.size <= 20 && resources.size <= 3 && sources.size <= 36 && actions.size <= 144 && errors.size <= 16); require((executionMode == ExecutionMode.SYNTHETIC_FIXTURE) == (dataOrigin == DataOrigin.SYNTHETIC)) }
}
@Serializable data class CreateSession(val profileId: ProfileId, val recordingConsent: Boolean, val dataOrigin: DataOrigin = DataOrigin.CONSENTED_DEMO, val executionMode: ExecutionMode = ExecutionMode.REAL_ON_DEVICE, val task: RecordingTask = RecordingTask.CHECK_IN)
@Serializable sealed interface UserInput { @Serializable data class TranscriptCorrection(val clipId: ClipId, val transcript: String) : UserInput { init { require(transcript.length in 1..20000) } }; @Serializable data class Answer(val text: String) : UserInput { init { require(text.length in 1..1000) } } }
@Serializable data class RevisionedUserInput(val sessionId: SessionId, val inputRevision: Long, val input: UserInput)
@Serializable data class ExportConsentChange(val sessionId: SessionId, val expectedConsentRevision: Long, val selectedFields: Set<ExportField>, val reviewedTranscriptSnippet: String? = null, val reviewedKeyword: String? = null) { init { require(reviewedTranscriptSnippet == null || reviewedTranscriptSnippet.length <= 200); require(reviewedKeyword == null || reviewedKeyword.length <= 40) } }
interface CheckInCommands {
    suspend fun createSession(input: CreateSession): SessionId
    suspend fun acceptClip(input: CompletedLocalClip): ClipReceipt
    suspend fun finishCapture(sessionId: SessionId)
    suspend fun requestResources(input: ApprovedResourceRequest)
    suspend fun applyInput(input: RevisionedUserInput)
    suspend fun pause(sessionId: SessionId)
    suspend fun resume(sessionId: SessionId)
    suspend fun setExportConsent(input: ExportConsentChange)
    suspend fun refreshExportedMemory(sessionId: SessionId)
    suspend fun deleteSession(sessionId: SessionId)
    suspend fun deleteProfile(profileId: ProfileId)
}
interface CheckInQueries { fun observeSession(id: SessionId): Flow<SessionSnapshot>; fun observeHistory(id: ProfileId): Flow<List<SessionSummary>>; fun observeOpenFollowUps(id: ProfileId): Flow<List<SessionSnapshot>>; fun observeReadiness(): Flow<ComponentReadiness> }
interface LocalAgent { suspend fun propose(checkpoint: AgentCheckpoint): ProposedAction }

@Serializable data class ModelProposal(val action: ProposedAction, val assistantMessage: ChatMessage, val inputTokens: Int, val outputTokens: Int) { init { require(inputTokens >= 0 && outputTokens >= 0) } }
interface ToolCallingLocalAgent : LocalAgent { suspend fun proposeTurn(checkpoint: AgentCheckpoint): ModelProposal; override suspend fun propose(checkpoint: AgentCheckpoint): ProposedAction = proposeTurn(checkpoint).action }
