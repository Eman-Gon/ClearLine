package com.clearline.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable enum class JobKind { PROCESS_AUDIO, ADVANCE_WORKFLOW }
@Serializable enum class JobStatus { PENDING, CLAIMED, PAUSED, COMPLETED, FAILED, INVALIDATED }
@Serializable data class JobRecord(val jobId: JobId, val sessionId: SessionId, val inputRevision: Long, val kind: JobKind, val status: JobStatus = JobStatus.PENDING, val clipId: ClipId? = null, val attempt: Int = 0, val createdAtMs: Long, val updatedAtMs: Long, val claimToken: String? = null, val scope: WorkflowScope = WorkflowScope.COMPARISON, val scopeRevision: Long = inputRevision, val stepOrdinal: Int = 0) { init { require(stepOrdinal in 0..12); require(scopeRevision >= 0 && inputRevision >= 0 && attempt >= 0); require((kind == JobKind.PROCESS_AUDIO) == (clipId != null)) } }
@Serializable data class JobClaim(val job: JobRecord, val claimToken: String)
/** Agent computes transitions outside Room; store enforces identities, optimistic versions and atomicity. */
data class CommandMutation(
    val expectedStateVersion: Long?, // null only for a new session, at state/input version 0
    val expectedInputRevision: Long?,
    val snapshot: SessionSnapshot,
    val checkpoint: AgentCheckpoint,
    val enqueueJobs: List<JobRecord> = emptyList(),
    val invalidatePriorRevision: Boolean = false,
    val invalidateScopes: Set<WorkflowScope> = emptySet(),
    val resumeEligibleJobs: Boolean = false,
    val summary: SessionSummary? = null,
    val exports: List<ApprovedExport> = emptyList(),
)
data class ActionSuccessCommit(val claim: JobClaim, val actionId: ActionId, val result: ActionResult, val toolMessage: ChatMessage, val mutation: CommandMutation, val completedAtMs: Long)
data class AudioSuccessCommit(val claim: JobClaim, val result: AudioResult, val mutation: CommandMutation)
data class FailureCommit(val claim: JobClaim, val actionId: ActionId?, val error: AppError, val mutation: CommandMutation, val failedAtMs: Long, val retrySameAction: Boolean = true)
@Serializable data class BaselineQuery(val profileId: ProfileId, val currentSessionId: SessionId, val task: RecordingTask, val dataOrigin: DataOrigin, val measurementVersion: String, val lexicalVersion: String, val beforeCreatedAtMs: Long)
@Serializable data class OutboxClaim(val export: ApprovedExport, val claimToken: String, val attempt: Int)
@Serializable data class RecoveryReport(val pausedSessions: List<SessionId>, val interruptedActionIds: List<ActionId>, val cleanupPaths: List<String>)
@Serializable data class DeletionReceipt(val sessionIds: List<SessionId>, val cleanupPaths: List<String>)

/** All mutating calls are bounded database-only transactions; never run native or HTTP work inside. */
interface WorkflowStore {
    suspend fun putProfile(profile: LocalProfile)
    fun observeProfiles(): Flow<List<LocalProfile>>
    suspend fun getProfile(profileId: ProfileId): LocalProfile?
    suspend fun getSession(sessionId: SessionId): SessionSnapshot?
    suspend fun getCheckpoint(sessionId: SessionId): AgentCheckpoint?
    fun observeSession(sessionId: SessionId): Flow<SessionSnapshot>
    fun observeHistory(profileId: ProfileId): Flow<List<SessionSummary>>
    fun observeOpenFollowUps(profileId: ProfileId): Flow<List<SessionSnapshot>>
    suspend fun applyCommand(mutation: CommandMutation): SessionSnapshot
    /** Idempotent by clip identity and by session/content digest; supersession happens only on admission. Atomically creates one deterministic PROCESS_AUDIO job. */
    suspend fun admitClip(clip: CompletedLocalClip, acceptedAtMs: Long): ClipReceipt
    suspend fun claimJob(sessionId: SessionId, nowMs: Long): JobClaim?
    suspend fun getPendingPlan(sessionId: SessionId): ActionRecord?
    suspend fun persistPlan(claim: JobClaim, action: ActionRecord): ActionRecord
    suspend fun commitSuccess(commit: ActionSuccessCommit): SessionSnapshot
    suspend fun commitAudioSuccess(commit: AudioSuccessCommit): SessionSnapshot
    suspend fun recordFailure(commit: FailureCommit): SessionSnapshot
    suspend fun recoverInterrupted(nowMs: Long): RecoveryReport
    suspend fun readBaseline(query: BaselineQuery): BaselineSummary
    suspend fun claimOutbox(nowMs: Long): OutboxClaim?
    /** Recheck immediately before dispatch; revocation cannot recall a request already in flight. */
    suspend fun isExportStillApproved(claim: OutboxClaim): Boolean
    suspend fun acknowledgeOutbox(claim: OutboxClaim, receipt: DeliveryReceipt)
    suspend fun failOutbox(claim: OutboxClaim, error: AppError, nowMs: Long)
    suspend fun revokeExportConsent(sessionId: SessionId, expectedConsentRevision: Long, nowMs: Long): SessionSnapshot
    /** Coordinator must cancel execution first. Tombstones reject all late result writes. */
    suspend fun deleteSession(sessionId: SessionId, nowMs: Long): DeletionReceipt
    suspend fun deleteProfile(profileId: ProfileId, nowMs: Long): DeletionReceipt
    suspend fun pendingFileCleanup(): List<String>
    suspend fun acknowledgeFileCleanup(privatePath: String)
}
