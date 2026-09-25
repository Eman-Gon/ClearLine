package com.clearline.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.clearline.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Phone-private authority. Native processing/network calls are deliberately absent. */
class RoomWorkflowStore(val database: ClearLineDatabase, private val audioDirectory: File) : WorkflowStore, AutoCloseable {
    private val dao = database.workflow()
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    private inline fun <reified T> decode(value: String): T = json.decodeFromString(value)
    private fun fail(code: ErrorCode, message: String): Nothing = throw ClearLineException(AppError(code, message))
    private suspend fun active(id: SessionId): SessionSnapshot {
        if (dao.tombstoned("session", id.value) != 0) fail(ErrorCode.DELETED, "Session was deleted")
        return dao.session(id.value)?.let { decode<SessionSnapshot>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Session does not exist")
    }
    private suspend fun hydrate(snapshot: SessionSnapshot): SessionSnapshot {
        val actions = dao.actions(snapshot.sessionId.value).map { decode<ActionRecord>(it.json) }
        val outbox = dao.outboxForSession(snapshot.sessionId.value)
        return snapshot.copy(clips = dao.clips(snapshot.sessionId.value).map { decode<StoredClip>(it.json) }, actions = actions.takeLast(144), pendingAction = actions.lastOrNull { it.status in setOf(ActionStatus.PLANNED, ActionStatus.RUNNING, ActionStatus.UNKNOWN) && scopeRevision(snapshot, it.scope) == it.scopeRevision }, cloudSync = CloudSyncCounts(outbox.count { it.status in setOf("PENDING", "CLAIMED") }, outbox.count { it.status == "DELIVERED" }, outbox.count { it.status == "FAILED" }))
    }
    private fun sessionRow(value: SessionSnapshot) = SessionRow(value.sessionId.value, value.profileId.value, value.phase.name, value.inputRevision, value.stateVersion, value.createdAtMs, value.summaryVersion, value.metrics != null, encode(value.copy(clips = emptyList(), actions = emptyList(), pendingAction = null, cloudSync = CloudSyncCounts())))
    private fun jobRow(value: JobRecord) = JobRow(value.jobId.value, value.sessionId.value, value.scope.name, value.scopeRevision, value.kind.name, value.clipId?.value ?: "", value.stepOrdinal, value.status.name, value.createdAtMs, encode(value))
    private fun actionRow(value: ActionRecord) = ActionRow(value.actionId.value, value.jobId.value, value.sessionId.value, value.scope.name, value.scopeRevision, value.ordinal, value.status.name, encode(value))
    private fun clipRow(value: StoredClip) = ClipRow(value.clip.clipId.value, value.clip.sessionId.value, value.clip.sha256, value.clip.privatePath, encode(value))
    private fun scopeRevision(session: SessionSnapshot, scope: WorkflowScope) = if (scope == WorkflowScope.COMPARISON) session.comparisonRevision else session.researchRevision
    private suspend fun saveSession(value: SessionSnapshot) { dao.session(sessionRow(value)) }
    private suspend fun bumpVisibility(id: SessionId) { val s = active(id); saveSession(s) }

    override suspend fun putProfile(profile: LocalProfile) = database.withTransaction {
        if (dao.tombstoned("profile", profile.profileId.value) != 0) fail(ErrorCode.DELETED, "Profile was deleted")
        val existing = dao.profile(profile.profileId.value)?.let { decode<LocalProfile>(it.json) }
        if (existing != null && (existing.dataOrigin != profile.dataOrigin || existing.createdAtMs != profile.createdAtMs)) fail(ErrorCode.IDENTITY_CONFLICT, "Profile identity cannot change")
        dao.profile(ProfileRow(profile.profileId.value, profile.createdAtMs, encode(profile)))
    }
    override fun observeProfiles(): Flow<List<LocalProfile>> = dao.profiles().map { rows -> rows.map { decode(it.json) } }
    override suspend fun getProfile(profileId: ProfileId): LocalProfile? = dao.profile(profileId.value)?.let { decode(it.json) }
    override suspend fun getSession(sessionId: SessionId): SessionSnapshot? = database.withTransaction { dao.session(sessionId.value)?.let { hydrate(decode(it.json)) } }
    override suspend fun getCheckpoint(sessionId: SessionId): AgentCheckpoint? = dao.checkpoint(sessionId.value)?.let { decode(it.json) }
    override fun observeSession(sessionId: SessionId): Flow<SessionSnapshot> = dao.observeSession(sessionId.value).map { row -> if (row == null) null else database.withTransaction { getSession(sessionId) } }.filterNotNull()
    override fun observeHistory(profileId: ProfileId): Flow<List<SessionSummary>> = dao.observeSummaries(profileId.value).map { rows -> latest(rows).sortedByDescending { it.completedAtMs } }
    override fun observeOpenFollowUps(profileId: ProfileId): Flow<List<SessionSnapshot>> = dao.observeSessions(profileId.value).map { rows -> database.withTransaction { rows.filter { it.phase != Phase.READY.name }.mapNotNull { getSession(SessionId(it.sessionId)) } } }
    private fun latest(rows: List<SummaryRow>): List<SessionSummary> = rows.groupBy { it.sessionId }.values.map { group -> decode(group.maxBy { it.version }.json) }

    override suspend fun applyCommand(mutation: CommandMutation): SessionSnapshot = database.withTransaction { apply(mutation) }
    private suspend fun apply(mutation: CommandMutation): SessionSnapshot {
        val next = mutation.snapshot
        if (dao.tombstoned("session", next.sessionId.value) != 0 || dao.tombstoned("profile", next.profileId.value) != 0) fail(ErrorCode.DELETED, "Deleted state cannot be recreated")
        val profile = dao.profile(next.profileId.value)?.let { decode<LocalProfile>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Profile does not exist")
        if (profile.dataOrigin != next.dataOrigin) fail(ErrorCode.IDENTITY_CONFLICT, "Profile provenance must match session")
        val previous = dao.session(next.sessionId.value)?.let { decode<SessionSnapshot>(it.json) }
        if (previous == null) {
            if (mutation.expectedStateVersion != null || mutation.expectedInputRevision != null || next.stateVersion != 0L || next.inputRevision != 0L) fail(ErrorCode.STALE_REVISION, "New session starts at revision zero")
            if (!next.consent.recording) fail(ErrorCode.CONSENT_REQUIRED, "Recording consent is required")
        } else {
            if (previous.stateVersion != mutation.expectedStateVersion || previous.inputRevision != mutation.expectedInputRevision || next.stateVersion != previous.stateVersion + 1) fail(ErrorCode.STALE_REVISION, "Session changed before commit")
            if (next.inputRevision !in previous.inputRevision..previous.inputRevision + 1 || next.comparisonRevision !in previous.comparisonRevision..previous.comparisonRevision + 1 || next.researchRevision !in previous.researchRevision..previous.researchRevision + 1) fail(ErrorCode.STALE_REVISION, "Invalid revision transition")
            if (next.profileId != previous.profileId || next.dataOrigin != previous.dataOrigin || next.executionMode != previous.executionMode || next.createdAtMs != previous.createdAtMs || next.task != previous.task) fail(ErrorCode.IDENTITY_CONFLICT, "Session identity cannot change")
            if (next.consent.exportRevision !in previous.consent.exportRevision..previous.consent.exportRevision + 1 || (next.consent.exportFields != previous.consent.exportFields && next.consent.exportRevision != previous.consent.exportRevision + 1)) fail(ErrorCode.STALE_REVISION, "Export approval revision must advance")
        }
        val checkpoint = mutation.checkpoint
        if (checkpoint.sessionId != next.sessionId || checkpoint.profileId != next.profileId || checkpoint.inputRevision != next.inputRevision || checkpoint.stateVersion != next.stateVersion || checkpoint.phase != next.phase || checkpoint.dataOrigin != next.dataOrigin || checkpoint.scopeRevision != scopeRevision(next, checkpoint.workflowScope)) fail(ErrorCode.INVALID_INPUT, "Checkpoint must describe committed state")
        if (next.approvedResources?.sessionId?.let { it != next.sessionId } == true) fail(ErrorCode.IDENTITY_CONFLICT, "Resource approval belongs to another session")
        val invalidScopes = if (mutation.invalidatePriorRevision) WorkflowScope.entries.toSet() else mutation.invalidateScopes
        for (row in dao.jobs(next.sessionId.value)) {
            val job = decode<JobRecord>(row.json)
            if ((job.scope in invalidScopes || job.scopeRevision != scopeRevision(next, job.scope)) && job.status !in setOf(JobStatus.COMPLETED, JobStatus.INVALIDATED)) dao.job(jobRow(job.copy(status = JobStatus.INVALIDATED, claimToken = null, updatedAtMs = next.updatedAtMs)))
        }
        for (row in dao.actions(next.sessionId.value)) {
            val action = decode<ActionRecord>(row.json)
            if ((action.scope in invalidScopes || action.scopeRevision != scopeRevision(next, action.scope)) && action.status != ActionStatus.INVALIDATED) dao.updateAction(actionRow(action.copy(status = ActionStatus.INVALIDATED)))
        }
        // Corrections may replace word-derived values only; acoustic measurements and receipts are immutable.
        for (clip in next.clips) {
            val stored = dao.clip(clip.clip.clipId.value)?.let { decode<StoredClip>(it.json) } ?: fail(ErrorCode.INVALID_INPUT, "Clips must first be admitted")
            if (clip.clip != stored.clip || clip.receipt != stored.receipt || clip.supersededBy != stored.supersededBy || clip.audioDeleted != stored.audioDeleted) fail(ErrorCode.IDENTITY_CONFLICT, "Accepted clip identity cannot change")
            if (clip.result != stored.result) {
                val old = stored.result ?: fail(ErrorCode.INVALID_INPUT, "Only processing commits may set initial transcription")
                val corrected = clip.result ?: fail(ErrorCode.INVALID_INPUT, "Transcription cannot be removed")
                if (corrected.copy(transcript = old.transcript, metrics = old.metrics) != old || corrected.metrics != MeasurementMath.correctTranscript(old.metrics, corrected.transcript)) fail(ErrorCode.INVALID_INPUT, "Correction must preserve acoustic measurements")
                dao.updateClip(clipRow(clip))
            }
        }
        saveSession(next)
        dao.checkpoint(CheckpointRow(next.sessionId.value, encode(checkpoint)))
        val consent = ConsentRow(next.sessionId.value, next.consent.exportRevision, encode(next.consent))
        val oldConsent = dao.consent(consent.sessionId, consent.revision)
        if (oldConsent == null) dao.consent(consent) else if (oldConsent.json != consent.json) fail(ErrorCode.IDENTITY_CONFLICT, "Consent revision is immutable")
        if (mutation.resumeEligibleJobs) for (row in dao.jobs(next.sessionId.value)) {
            val job = decode<JobRecord>(row.json)
            if (job.status in setOf(JobStatus.PAUSED, JobStatus.FAILED) && job.scopeRevision == scopeRevision(next, job.scope)) dao.job(jobRow(job.copy(status = JobStatus.PENDING, claimToken = null, updatedAtMs = next.updatedAtMs)))
        }
        for (job in mutation.enqueueJobs) enqueue(job, next)
        for (source in next.sources) {
            if (next.approvedResources?.approvalId != source.approvalId) fail(ErrorCode.CONSENT_REQUIRED, "Evidence must match approved request")
            val row = SourceRow(source.evidenceId.value, source.version, next.sessionId.value, encode(source))
            val old = dao.source(row.evidenceId, row.version)
            if (old == null) dao.source(row) else if (old != row) fail(ErrorCode.IDENTITY_CONFLICT, "Evidence versions are immutable")
        }
        mutation.summary?.let { summary ->
            if (summary.sessionId != next.sessionId || summary.profileId != next.profileId || summary.dataOrigin != next.dataOrigin || summary.inputRevision != next.inputRevision || summary.task != next.task) fail(ErrorCode.IDENTITY_CONFLICT, "Summary identity mismatch")
            val clips = dao.clips(next.sessionId.value).map { decode<StoredClip>(it.json) }.filter { it.supersededBy == null }
            if (clips.isEmpty() || clips.any { it.result == null } || clips.map { it.clip.clipId }.toSet() != summary.clipIds.toSet() || MeasurementMath.pool(clips.map { it.result!!.metrics }) != summary.metrics) fail(ErrorCode.INVALID_INPUT, "Summary must pool accepted current clips")
            val prior = dao.summary(next.sessionId.value)
            if (summary.version != (prior?.version ?: 0) + 1) fail(ErrorCode.STALE_REVISION, "Summary version must advance exactly once")
            dao.summary(SummaryRow(summary.sessionId.value, summary.version, summary.profileId.value, summary.completedAtMs, summary.inputRevision, summary.task.name, summary.dataOrigin.name, summary.metrics.measurementVersion, summary.metrics.lexicalVersion, encode(summary)))
        }
        cancelUnapprovedOutbox(next)
        for (export in mutation.exports) enqueueExport(export, next)
        dao.event(EventRow(UUID.randomUUID().toString(), next.sessionId.value, "state_committed", next.updatedAtMs))
        return hydrate(next)
    }
    private suspend fun enqueue(job: JobRecord, session: SessionSnapshot) {
        if (job.sessionId != session.sessionId || job.scopeRevision != scopeRevision(session, job.scope) || job.inputRevision > session.inputRevision || job.status != JobStatus.PENDING || job.claimToken != null) fail(ErrorCode.INVALID_INPUT, "Invalid job revision or state")
        val existing = dao.job(job.jobId.value)?.let { decode<JobRecord>(it.json) }
        if (existing != null) {
            if (existing.copy(status = job.status, claimToken = job.claimToken, updatedAtMs = job.updatedAtMs, attempt = job.attempt) != job) fail(ErrorCode.IDENTITY_CONFLICT, "Job identity reused")
            if (existing.status in setOf(JobStatus.COMPLETED, JobStatus.CLAIMED, JobStatus.PENDING)) return
        }
        dao.job(jobRow(job.copy(attempt = existing?.attempt ?: job.attempt)))
    }

    override suspend fun admitClip(clip: CompletedLocalClip, acceptedAtMs: Long): ClipReceipt {
        val admitted = database.withTransaction {
            active(clip.sessionId)
            dao.clip(clip.clipId.value)?.let { decode<StoredClip>(it.json) }
        }
        if (admitted != null) {
            if (admitted.clip.sessionId != clip.sessionId || admitted.clip.sha256 != clip.sha256) fail(ErrorCode.IDENTITY_CONFLICT, "Clip ID already names different content")
            return database.withTransaction { active(clip.sessionId); admitted.receipt.copy(duplicate = true) }
        }
        withContext(Dispatchers.IO) { verifyAudioFile(clip) }
        return database.withTransaction {
            var session = active(clip.sessionId)
            if (!session.consent.recording) fail(ErrorCode.CONSENT_REQUIRED, "Recording consent is required")
            if (session.dataOrigin != clip.dataOrigin) fail(ErrorCode.IDENTITY_CONFLICT, "Clip provenance must match session")
            val sameId = dao.clip(clip.clipId.value)?.let { decode<StoredClip>(it.json) }
            if (sameId != null) {
                if (sameId.clip.sessionId != clip.sessionId || sameId.clip.sha256 != clip.sha256) fail(ErrorCode.IDENTITY_CONFLICT, "Clip ID already names different content")
                return@withTransaction sameId.receipt.copy(duplicate = true)
            }
            val clips = dao.clips(clip.sessionId.value).map { decode<StoredClip>(it.json) }
            clips.firstOrNull { it.clip.sha256 == clip.sha256 }?.let { duplicate ->
                if (duplicate.clip.privatePath != clip.privatePath) dao.cleanup(CleanupRow(clip.privatePath))
                return@withTransaction duplicate.receipt.copy(duplicate = true)
            }
            if (clips.size >= 20) fail(ErrorCode.INVALID_INPUT, "Clip limit reached")
            val superseded = clip.supersedesClipId?.let { id -> clips.find { it.clip.clipId == id && it.supersededBy == null } ?: fail(ErrorCode.INVALID_INPUT, "Replacement target is not current") }
            val lastSummary = dao.summary(session.sessionId.value)?.let { decode<SessionSummary>(it.json) }
            if (lastSummary != null && session.metrics != null) {
                session = session.copy(inputRevision = session.inputRevision + 1, comparisonRevision = session.comparisonRevision + 1, researchRevision = session.researchRevision + 1, metrics = null, comparison = null, resources = emptyList(), sources = emptyList(), approvedResources = null, rawTreeMemory = null)
                for (row in dao.jobs(session.sessionId.value)) { val old = decode<JobRecord>(row.json); dao.job(jobRow(old.copy(status = JobStatus.INVALIDATED, claimToken = null, updatedAtMs = acceptedAtMs))) }
                for (row in dao.actions(session.sessionId.value)) { val old = decode<ActionRecord>(row.json); dao.updateAction(actionRow(old.copy(status = ActionStatus.INVALIDATED))) }
                val oldConsent = session.consent
                if (ExportField.TRANSCRIPT_SNIPPET in oldConsent.exportFields || oldConsent.reviewedTranscriptSnippet != null || oldConsent.reviewedKeyword != null) {
                    val cleared = oldConsent.copy(exportFields = oldConsent.exportFields - ExportField.TRANSCRIPT_SNIPPET, reviewedTranscriptSnippet = null, reviewedKeyword = null, exportRevision = oldConsent.exportRevision + 1, updatedAtMs = acceptedAtMs)
                    session = session.copy(consent = cleared)
                    dao.consent(ConsentRow(session.sessionId.value, cleared.exportRevision, encode(cleared)))
                }
                cancelUnapprovedOutbox(session)
            }
            val receipt = ClipReceipt(clip.sessionId, clip.clipId, clip.sha256, acceptedAtMs)
            dao.insertClip(clipRow(StoredClip(clip, receipt)))
            superseded?.let { oldClip ->
                dao.updateClip(clipRow(oldClip.copy(supersededBy = clip.clipId)))
                for (row in dao.jobs(session.sessionId.value)) {
                    val oldJob = decode<JobRecord>(row.json)
                    if (oldJob.clipId == oldClip.clip.clipId) dao.job(jobRow(oldJob.copy(status = JobStatus.INVALIDATED, claimToken = null, updatedAtMs = acceptedAtMs)))
                }
                dao.cleanup(CleanupRow(oldClip.clip.privatePath))
            }
            val job = JobRecord(JobId(UUID.nameUUIDFromBytes("audio:${clip.sessionId.value}:${clip.clipId.value}".toByteArray()).toString()), clip.sessionId, session.inputRevision, JobKind.PROCESS_AUDIO, clipId = clip.clipId, createdAtMs = acceptedAtMs, updatedAtMs = acceptedAtMs, scopeRevision = session.comparisonRevision)
            enqueue(job, session)
            val next = session.copy(stateVersion = session.stateVersion + 1, updatedAtMs = acceptedAtMs, phase = Phase.PROCESSING, captureFinished = false, pendingInput = null, errors = emptyList(), pauseRequested = false)
            saveSession(next)
            dao.checkpoint(next.sessionId.value)?.let { row ->
                val cp = decode<AgentCheckpoint>(row.json)
                val nextCp = if (cp.inputRevision != next.inputRevision) cp.copy(inputRevision = next.inputRevision, stateVersion = next.stateVersion, phase = next.phase, metrics = null, comparison = null, baseline = null, approvedResources = null, pendingInput = null, completedActionIds = emptyList(), pendingActionId = null, exchanges = emptyList(), sourceIds = emptyList(), candidateSources = emptyList(), workflowScope = WorkflowScope.COMPARISON, scopeRevision = next.comparisonRevision) else cp.copy(stateVersion = next.stateVersion, phase = next.phase, pendingInput = null)
                dao.checkpoint(CheckpointRow(next.sessionId.value, encode(nextCp)))
            }
            dao.event(EventRow("clip:${clip.clipId.value}", clip.sessionId.value, "clip_accepted", acceptedAtMs))
            receipt
        }
    }
    private fun verifyAudioFile(clip: CompletedLocalClip) {
        val file = File(clip.privatePath).canonicalFile
        val root = audioDirectory.canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || file.name != "${clip.clipId.value}.wav") fail(ErrorCode.INVALID_AUDIO, "Audio must be a finalized private WAV")
        if (file.length() !in 64044L..1920044L) fail(ErrorCode.INVALID_AUDIO, "Audio file length is outside supported bounds")
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(at: Int, size: Int) = String(bytes, at, size, Charsets.US_ASCII)
        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE" || ascii(12, 4) != "fmt " || header.getInt(16) != 16 || header.getShort(20).toInt() != 1 || header.getShort(22).toInt() != 1 || header.getInt(24) != 16000 || header.getInt(28) != 32000 || header.getShort(32).toInt() != 2 || header.getShort(34).toInt() != 16 || ascii(36, 4) != "data" || header.getInt(40) != bytes.size - 44 || header.getInt(4) != bytes.size - 8 || (bytes.size - 44) % 2 != 0) fail(ErrorCode.INVALID_AUDIO, "Audio WAV header is invalid")
        if (kotlin.math.abs((bytes.size - 44) / 32000.0 - clip.durationSeconds) > 1.0 / 16000.0) fail(ErrorCode.INVALID_AUDIO, "Audio duration does not match content")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != clip.sha256) fail(ErrorCode.IDENTITY_CONFLICT, "Audio checksum mismatch")
    }

    override suspend fun claimJob(sessionId: SessionId, nowMs: Long): JobClaim? = database.withTransaction {
        val session = active(sessionId)
        if (session.pauseRequested || session.phase !in setOf(Phase.PROCESSING, Phase.COMPARING, Phase.RESEARCHING)) return@withTransaction null
        // Enforce the single execution claim in Room as well as in the coordinator.
        for (row in dao.sessions()) if (dao.jobs(row.sessionId).any { it.status == JobStatus.CLAIMED.name }) return@withTransaction null
        val pending = dao.jobs(sessionId.value).map { decode<JobRecord>(it.json) }.firstOrNull { it.status == JobStatus.PENDING && it.scopeRevision == scopeRevision(session, it.scope) } ?: return@withTransaction null
        val token = UUID.randomUUID().toString()
        val claimed = pending.copy(status = JobStatus.CLAIMED, claimToken = token, attempt = pending.attempt + 1, updatedAtMs = nowMs)
        dao.job(jobRow(claimed)); JobClaim(claimed, token)
    }
    private suspend fun requireClaim(claim: JobClaim): Pair<SessionSnapshot, JobRecord> {
        val session = active(claim.job.sessionId)
        val current = dao.job(claim.job.jobId.value)?.let { decode<JobRecord>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Job does not exist")
        if (current.status != JobStatus.CLAIMED || current.claimToken != claim.claimToken || current.scopeRevision != scopeRevision(session, current.scope) || current.sessionId != session.sessionId) fail(ErrorCode.STALE_REVISION, "Execution claim is no longer current")
        return session to current
    }
    override suspend fun getPendingPlan(sessionId: SessionId): ActionRecord? = database.withTransaction {
        val session = active(sessionId)
        dao.actions(sessionId.value).map { decode<ActionRecord>(it.json) }.lastOrNull { it.status in setOf(ActionStatus.PLANNED, ActionStatus.RUNNING, ActionStatus.UNKNOWN) && it.scopeRevision == scopeRevision(session, it.scope) }
    }
    override suspend fun persistPlan(claim: JobClaim, action: ActionRecord): ActionRecord = database.withTransaction {
        val (session, job) = requireClaim(claim)
        if (session.pauseRequested || job.kind != JobKind.ADVANCE_WORKFLOW || action.sessionId != session.sessionId || action.jobId != job.jobId || action.scope != job.scope || action.scopeRevision != job.scopeRevision || action.inputRevision > session.inputRevision || action.assistantMessage.role != ChatRole.ASSISTANT || action.result != null) fail(ErrorCode.INVALID_INPUT, "Plan identity or state is invalid")
        val old = dao.action(action.actionId.value)?.let { decode<ActionRecord>(it.json) }
        if (old != null) {
            if (old.copy(status = action.status, error = action.error) != action || old.status == ActionStatus.SUCCEEDED) fail(ErrorCode.IDENTITY_CONFLICT, "Action identity already names another plan")
        } else {
            val current = dao.actions(session.sessionId.value).map { decode<ActionRecord>(it.json) }.filter { it.scope == action.scope && it.scopeRevision == action.scopeRevision }
            if (current.any { it.status in setOf(ActionStatus.PLANNED, ActionStatus.RUNNING, ActionStatus.UNKNOWN) } || action.ordinal != current.size + 1 || current.size >= 12) fail(ErrorCode.INVALID_INPUT, "One plan at a time within the action budget")
        }
        validateProposal(session, action.proposal)
        val saved = action.copy(status = ActionStatus.RUNNING, error = null)
        if (old == null) dao.insertAction(actionRow(saved)) else dao.updateAction(actionRow(saved))
        val cp = dao.checkpoint(session.sessionId.value)?.let { decode<AgentCheckpoint>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Checkpoint is missing")
        dao.checkpoint(CheckpointRow(session.sessionId.value, encode(cp.copy(pendingActionId = action.actionId))))
        bumpVisibility(session.sessionId)
        saved
    }
    private fun validateProposal(session: SessionSnapshot, action: ProposedAction) {
        if (action is ProposedAction.SearchPublicResources || action is ProposedAction.ExtractPublicPage) {
            val approved = session.approvedResources ?: fail(ErrorCode.CONSENT_REQUIRED, "Public research requires approval")
            if (approved.sessionId != session.sessionId) fail(ErrorCode.IDENTITY_CONFLICT, "Public approval identity mismatch")
            if (action is ProposedAction.ExtractPublicPage && session.resources.none { it.candidateId == action.candidateId }) fail(ErrorCode.INVALID_INPUT, "Extraction must reference a saved search candidate")
        }
    }
    override suspend fun commitSuccess(commit: ActionSuccessCommit): SessionSnapshot = database.withTransaction {
        val (session, job) = requireClaim(commit.claim)
        val action = dao.action(commit.actionId.value)?.let { decode<ActionRecord>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Saved plan is missing")
        if (action.jobId != job.jobId || action.scopeRevision != job.scopeRevision || action.status != ActionStatus.RUNNING || commit.mutation.snapshot.sessionId != session.sessionId) fail(ErrorCode.STALE_REVISION, "Saved plan is no longer current")
        validateProposal(session, action.proposal)
        validateResult(session, action.proposal, commit.result)
        if (commit.toolMessage.role != ChatRole.TOOL || commit.toolMessage.toolCallId != action.toolCallId) fail(ErrorCode.IDENTITY_CONFLICT, "Tool result must match persisted call identity")
        dao.updateAction(actionRow(action.copy(status = ActionStatus.SUCCEEDED, result = commit.result, toolMessage = commit.toolMessage, completedAtMs = commit.completedAtMs)))
        val result = apply(commit.mutation)
        dao.job(jobRow(job.copy(status = JobStatus.COMPLETED, claimToken = null, updatedAtMs = commit.completedAtMs)))
        hydrate(result)
    }
    private fun validateResult(session: SessionSnapshot, proposed: ProposedAction, result: ActionResult) {
        val valid = when (proposed) {
            ProposedAction.GetBaselineSummary -> result is ActionResult.Baseline
            ProposedAction.CompareRecordingMetrics -> result is ActionResult.Comparison
            ProposedAction.SearchPublicResources -> result is ActionResult.Search && result.value.approvalId == session.approvedResources?.approvalId && result.value.inputRevision == session.approvedResources?.inputRevision
            is ProposedAction.ExtractPublicPage -> result is ActionResult.Extract && result.value.approvalId == session.approvedResources?.approvalId && result.value.inputRevision == session.approvedResources?.inputRevision && result.value.sourceUrl == session.resources.firstOrNull { it.candidateId == proposed.candidateId }?.url
            is ProposedAction.RequestUserInput -> result is ActionResult.InputRequested && result.value == proposed.input
            ProposedAction.FinishTask -> result == ActionResult.Finished
        }
        if (!valid) fail(ErrorCode.IDENTITY_CONFLICT, "Result does not match its saved action")
    }
    override suspend fun commitAudioSuccess(commit: AudioSuccessCommit): SessionSnapshot = database.withTransaction {
        val (session, job) = requireClaim(commit.claim)
        if (job.kind != JobKind.PROCESS_AUDIO || job.clipId != commit.result.clipId || commit.mutation.snapshot.sessionId != session.sessionId) fail(ErrorCode.IDENTITY_CONFLICT, "Audio result must match claimed clip")
        val stored = dao.clip(commit.result.clipId.value)?.let { decode<StoredClip>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Accepted clip is missing")
        val metrics = commit.result.metrics
        if (stored.supersededBy != null || stored.result != null || metrics.dataOrigin != stored.clip.dataOrigin || metrics.measurementVersion != stored.clip.measurementVersion || kotlin.math.abs(metrics.durationSeconds - stored.clip.durationSeconds) > 1.0 / 16000.0 || metrics.quality != AudioQuality.ACCEPTED || metrics.wordCount == 0 || metrics.energyRms <= 0.0 || metrics.wordCount != MeasurementMath.countEnglishWords(commit.result.transcript)) fail(ErrorCode.INVALID_INPUT, "Audio result does not describe accepted current input")
        dao.updateClip(clipRow(stored.copy(result = commit.result)))
        val result = apply(commit.mutation.copy(snapshot = commit.mutation.snapshot.copy(clips = emptyList())))
        dao.job(jobRow(job.copy(status = JobStatus.COMPLETED, claimToken = null, updatedAtMs = result.updatedAtMs)))
        dao.cleanup(CleanupRow(stored.clip.privatePath))
        hydrate(result)
    }
    override suspend fun recordFailure(commit: FailureCommit): SessionSnapshot = database.withTransaction {
        val (_, job) = requireClaim(commit.claim)
        commit.actionId?.let { id ->
            val action = dao.action(id.value)?.let { decode<ActionRecord>(it.json) } ?: fail(ErrorCode.NOT_FOUND, "Failed plan is missing")
            if (action.jobId != job.jobId) fail(ErrorCode.IDENTITY_CONFLICT, "Failed plan does not match claim")
            dao.updateAction(actionRow(action.copy(status = if (commit.retrySameAction) ActionStatus.UNKNOWN else ActionStatus.FAILED, error = commit.error)))
        }
        val next = apply(commit.mutation)
        dao.job(jobRow(job.copy(status = if (commit.retrySameAction) JobStatus.FAILED else JobStatus.COMPLETED, claimToken = null, updatedAtMs = commit.failedAtMs)))
        if (!commit.error.retryable && job.kind == JobKind.PROCESS_AUDIO) job.clipId?.let { id -> dao.clip(id.value)?.let { dao.cleanup(CleanupRow(it.privatePath)) } }
        hydrate(next)
    }
    override suspend fun recoverInterrupted(nowMs: Long): RecoveryReport {
        val diskFiles = withContext(Dispatchers.IO) { if (audioDirectory.exists()) audioDirectory.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList() else emptyList() }
        return database.withTransaction {
        val admittedPaths = dao.sessions().flatMap { dao.clips(it.sessionId) }.map { File(it.privatePath).canonicalPath }.toSet()
        diskFiles.filter { it !in admittedPaths }.forEach { dao.cleanup(CleanupRow(it)) }
        val paused = mutableListOf<SessionId>()
        val unknown = mutableListOf<ActionId>()
        for (row in dao.sessions()) {
            val session = decode<SessionSnapshot>(row.json)
            var interrupted = false
            for (storedJobRow in dao.jobs(row.sessionId)) {
                val job = decode<JobRecord>(storedJobRow.json)
                if (job.status in setOf(JobStatus.CLAIMED, JobStatus.PENDING)) {
                    dao.job(jobRow(job.copy(status = JobStatus.PAUSED, claimToken = null, updatedAtMs = nowMs)))
                    interrupted = true
                }
            }
            for (storedActionRow in dao.actions(row.sessionId)) {
                val action = decode<ActionRecord>(storedActionRow.json)
                if (action.status in setOf(ActionStatus.RUNNING, ActionStatus.PLANNED)) {
                    dao.updateAction(actionRow(action.copy(status = ActionStatus.UNKNOWN)))
                    unknown += action.actionId
                    interrupted = true
                }
            }
            if (interrupted) {
                val next = session.copy(phase = Phase.PAUSED, stateVersion = session.stateVersion + 1, updatedAtMs = nowMs, pauseRequested = false)
                saveSession(next)
                dao.checkpoint(row.sessionId)?.let { old -> val cp = decode<AgentCheckpoint>(old.json); dao.checkpoint(CheckpointRow(row.sessionId, encode(cp.copy(phase = Phase.PAUSED, stateVersion = next.stateVersion)))) }
                paused += session.sessionId
            }
        }
        for (row in dao.claimedOutbox()) {
            val export = decode<ApprovedExport>(row.json)
            val session = dao.session(row.sessionId)?.let { decode<SessionSnapshot>(it.json) }
            dao.updateOutbox(row.copy(status = if (session != null && approved(export, session)) "PENDING" else "CANCELLED", claimToken = null))
        }
        RecoveryReport(paused, unknown, dao.cleanupPaths())
        }
    }
    override suspend fun readBaseline(query: BaselineQuery): BaselineSummary = database.withTransaction {
        val eligible = dao.baseline(query.profileId.value, query.currentSessionId.value, query.task.name, query.dataOrigin.name, query.measurementVersion, query.lexicalVersion, query.beforeCreatedAtMs).map { decode<SessionSummary>(it.json) }
        MeasurementMath.baseline(eligible)
    }

    private fun approved(export: ApprovedExport, session: SessionSnapshot): Boolean {
        if (export.profileId != session.profileId || export.sessionId != session.sessionId || export.dataOrigin != session.dataOrigin || export.inputRevision != session.inputRevision || export.consentRevision != session.consent.exportRevision || export.requiredField !in session.consent.exportFields || export.createdAtMs < session.consent.updatedAtMs) return false
        val measurements = export.projection as? ExportProjection.Measurements
        if (measurements != null && (measurements.transcriptSnippet != null || measurements.topKeyword != null)) {
            if (ExportField.TRANSCRIPT_SNIPPET !in session.consent.exportFields || measurements.transcriptSnippet != session.consent.reviewedTranscriptSnippet || measurements.topKeyword != session.consent.reviewedKeyword) return false
        }
        return true
    }
    private suspend fun enqueueExport(export: ApprovedExport, session: SessionSnapshot) {
        if (!approved(export, session)) fail(ErrorCode.CONSENT_REQUIRED, "Export fields and revision require current approval")
        val row = OutboxRow(export.exportId.value, export.sessionId.value, export.profileId.value, "PENDING", null, 0, export.createdAtMs, encode(export))
        val existing = dao.outbox(row.exportId)
        if (existing == null) dao.insertOutbox(row) else if (existing.json != row.json) fail(ErrorCode.IDENTITY_CONFLICT, "Export identity is immutable")
    }
    private suspend fun cancelUnapprovedOutbox(session: SessionSnapshot) {
        for (row in dao.outboxForSession(session.sessionId.value)) if (row.status in setOf("PENDING", "CLAIMED", "FAILED") && !approved(decode(row.json), session)) dao.updateOutbox(row.copy(status = "CANCELLED", claimToken = null))
    }
    override suspend fun claimOutbox(nowMs: Long): OutboxClaim? = database.withTransaction {
        if (dao.claimedOutbox().isNotEmpty()) return@withTransaction null
        for (row in dao.pendingOutbox()) {
            val export = decode<ApprovedExport>(row.json)
            val session = dao.session(row.sessionId)?.let { decode<SessionSnapshot>(it.json) }
            if (session == null || !approved(export, session)) { dao.updateOutbox(row.copy(status = "CANCELLED", claimToken = null)); continue }
            val token = UUID.randomUUID().toString()
            dao.updateOutbox(row.copy(status = "CLAIMED", claimToken = token, attempt = row.attempt + 1))
            bumpVisibility(session.sessionId)
            return@withTransaction OutboxClaim(export, token, row.attempt + 1)
        }
        null
    }
    override suspend fun isExportStillApproved(claim: OutboxClaim): Boolean = database.withTransaction {
        val row = dao.outbox(claim.export.exportId.value) ?: return@withTransaction false
        val session = dao.session(row.sessionId)?.let { decode<SessionSnapshot>(it.json) } ?: return@withTransaction false
        row.status == "CLAIMED" && row.claimToken == claim.claimToken && row.json == encode(claim.export) && approved(claim.export, session)
    }
    override suspend fun acknowledgeOutbox(claim: OutboxClaim, receipt: DeliveryReceipt) = database.withTransaction {
        if (receipt.exportId != claim.export.exportId) fail(ErrorCode.IDENTITY_CONFLICT, "Receipt does not match export")
        val row = dao.outbox(receipt.exportId.value) ?: return@withTransaction
        if (row.status == "DELIVERED") return@withTransaction
        if (row.status != "CLAIMED" || row.claimToken != claim.claimToken) return@withTransaction
        dao.updateOutbox(row.copy(status = "DELIVERED", claimToken = null, lastError = null))
        bumpVisibility(claim.export.sessionId)
    }
    override suspend fun failOutbox(claim: OutboxClaim, error: AppError, nowMs: Long) = database.withTransaction {
        val row = dao.outbox(claim.export.exportId.value) ?: return@withTransaction
        if (row.status != "CLAIMED" || row.claimToken != claim.claimToken) return@withTransaction
        dao.updateOutbox(row.copy(status = if (error.retryable && row.attempt < 3) "PENDING" else "FAILED", claimToken = null, lastError = encode(error)))
        bumpVisibility(claim.export.sessionId)
    }
    override suspend fun revokeExportConsent(sessionId: SessionId, expectedConsentRevision: Long, nowMs: Long): SessionSnapshot = database.withTransaction {
        val old = active(sessionId)
        if (old.consent.exportRevision != expectedConsentRevision) fail(ErrorCode.STALE_REVISION, "Export approval already changed")
        val next = old.copy(stateVersion = old.stateVersion + 1, updatedAtMs = nowMs, consent = old.consent.copy(exportFields = emptySet(), exportRevision = old.consent.exportRevision + 1, updatedAtMs = nowMs, reviewedTranscriptSnippet = null, reviewedKeyword = null))
        val cp = getCheckpoint(sessionId) ?: fail(ErrorCode.NOT_FOUND, "Checkpoint is missing")
        apply(CommandMutation(old.stateVersion, old.inputRevision, next, cp.copy(stateVersion = next.stateVersion)))
    }
    override suspend fun deleteSession(sessionId: SessionId, nowMs: Long): DeletionReceipt = database.withTransaction { deleteSessionInternal(sessionId, nowMs) }
    private suspend fun deleteSessionInternal(id: SessionId, nowMs: Long): DeletionReceipt {
        dao.tombstone(TombstoneRow("session", id.value, nowMs))
        val paths = dao.clips(id.value).map { it.privatePath }
        paths.forEach { dao.cleanup(CleanupRow(it)) }
        dao.deleteJobs(id.value); dao.deleteActions(id.value); dao.deleteOutbox(id.value)
        dao.deleteClips(id.value); dao.deleteCheckpoints(id.value); dao.deleteEvents(id.value)
        dao.deleteSummaries(id.value); dao.deleteSources(id.value); dao.deleteConsents(id.value); dao.deleteSession(id.value)
        return DeletionReceipt(listOf(id), paths)
    }
    override suspend fun deleteProfile(profileId: ProfileId, nowMs: Long): DeletionReceipt = database.withTransaction {
        dao.tombstone(TombstoneRow("profile", profileId.value, nowMs))
        val deleted = dao.sessions(profileId.value).map { deleteSessionInternal(SessionId(it.sessionId), nowMs) }
        dao.deleteProfile(profileId.value)
        DeletionReceipt(deleted.flatMap { it.sessionIds }, deleted.flatMap { it.cleanupPaths })
    }
    override suspend fun pendingFileCleanup(): List<String> = dao.cleanupPaths()
    override suspend fun acknowledgeFileCleanup(privatePath: String) = database.withTransaction {
        if (File(privatePath).exists()) fail(ErrorCode.INVALID_INPUT, "Cleanup can be acknowledged only after file removal")
        dao.acknowledgeCleanup(privatePath)
        for (session in dao.sessions()) for (row in dao.clips(session.sessionId)) if (row.privatePath == privatePath) { val clip = decode<StoredClip>(row.json); dao.updateClip(clipRow(clip.copy(audioDeleted = true))); bumpVisibility(clip.clip.sessionId) }
    }
    /** Idempotent startup/terminal cleanup; interrupted deletion remains in the durable queue. */
    suspend fun cleanPendingAudioFiles() = withContext(Dispatchers.IO) {
        for (path in pendingFileCleanup()) {
            val file = File(path).canonicalFile
            if (file.path.startsWith(audioDirectory.canonicalPath + File.separator) && (!file.exists() || file.delete())) acknowledgeFileCleanup(path)
        }
    }
    override fun close() = database.close()
    companion object {
        fun open(context: Context, name: String = "clearline.db"): RoomWorkflowStore {
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            val root = File(context.noBackupFilesDir, "database").apply { mkdirs() }
            val db = Room.databaseBuilder(context.applicationContext, ClearLineDatabase::class.java, File(root, name).absolutePath).addMigrations(ClearLineDatabase.MIGRATION_1_2).build()
            return RoomWorkflowStore(db, File(context.noBackupFilesDir, "audio"))
        }
    }
}
