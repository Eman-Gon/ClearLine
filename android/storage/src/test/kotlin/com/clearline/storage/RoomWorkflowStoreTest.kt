package com.clearline.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clearline.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomWorkflowStoreTest {
    private lateinit var store: RoomWorkflowStore
    private lateinit var context: Context
    private lateinit var audioRoot: File
    private var now = 1000L
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioRoot = File(context.noBackupFilesDir, "audio-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        store = RoomWorkflowStore(Room.inMemoryDatabaseBuilder(context, ClearLineDatabase::class.java).allowMainThreadQueries().build(), audioRoot)
    }
    @After fun cleanup() { store.close(); audioRoot.deleteRecursively() }
    private fun checkpoint(s: SessionSnapshot) = AgentCheckpoint(sessionId = s.sessionId, profileId = s.profileId, inputRevision = s.inputRevision, stateVersion = s.stateVersion, phase = s.phase, task = s.task, dataOrigin = s.dataOrigin, metrics = s.metrics, comparison = s.comparison, approvedResources = s.approvedResources, scopeRevision = s.comparisonRevision)
    private suspend fun session(profile: LocalProfile? = null, fields: Set<ExportField> = emptySet()): SessionSnapshot {
        val p = profile ?: LocalProfile(ProfileId.new(), "Labeled synthetic test", DataOrigin.SYNTHETIC, now++)
        store.putProfile(p)
        val s = SessionSnapshot(sessionId = SessionId.new(), profileId = p.profileId, phase = Phase.RECORDING, executionMode = ExecutionMode.SYNTHETIC_FIXTURE, dataOrigin = DataOrigin.SYNTHETIC, createdAtMs = now++, updatedAtMs = now++, consent = ConsentState(true, fields, updatedAtMs = now))
        return store.applyCommand(CommandMutation(null, null, s, checkpoint(s)))
    }
    private suspend fun change(old: SessionSnapshot, next: SessionSnapshot = old.copy(stateVersion = old.stateVersion + 1, updatedAtMs = now++), jobs: List<JobRecord> = emptyList(), exports: List<ApprovedExport> = emptyList(), resume: Boolean = false): SessionSnapshot = store.applyCommand(CommandMutation(old.stateVersion, old.inputRevision, next, checkpoint(next), jobs, resumeEligibleJobs = resume, exports = exports))
    private fun clip(session: SessionSnapshot, id: ClipId = ClipId.new(), sample: Short = 3276): CompletedLocalClip {
        val payload = ByteBuffer.allocate(64044).order(ByteOrder.LITTLE_ENDIAN)
        payload.put("RIFF".toByteArray()).putInt(64036).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(64000)
        repeat(32000) { payload.putShort(sample) }
        val bytes = payload.array()
        val file = File(audioRoot, "${session.sessionId.value}/${id.value}.wav").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return CompletedLocalClip(session.sessionId, id, file.absolutePath, hash, 2.0, DataOrigin.SYNTHETIC, createdAtMs = now++)
    }
    private suspend fun completed(profile: LocalProfile? = null, fields: Set<ExportField> = emptySet()): SessionSnapshot {
        val s = session(profile, fields)
        val c = clip(s)
        store.admitClip(c, now++)
        val claim = store.claimJob(s.sessionId, now++)!!
        val current = store.getSession(s.sessionId)!!
        val metrics = RecordingMetrics(2.0, 2, 60.0, .1, dataOrigin = DataOrigin.SYNTHETIC)
        val result = AudioResult(c.clipId, "two words", metrics, ModelId("test-whisper"), now++)
        val next = current.copy(stateVersion = current.stateVersion + 1, updatedAtMs = now++, phase = Phase.COMPARING, metrics = metrics, captureFinished = true, summaryVersion = 1)
        val summary = SessionSummary(s.sessionId, s.profileId, 1, 0, s.task, s.dataOrigin, metrics, now++, listOf(c.clipId))
        return store.commitAudioSuccess(AudioSuccessCommit(claim, result, CommandMutation(current.stateVersion, current.inputRevision, next, checkpoint(next), summary = summary)))
    }
    private suspend fun plan(s: SessionSnapshot, proposal: ProposedAction = ProposedAction.GetBaselineSummary): Pair<JobClaim, ActionRecord> {
        val job = JobRecord(JobId.new(), s.sessionId, s.inputRevision, JobKind.ADVANCE_WORKFLOW, createdAtMs = now++, updatedAtMs = now++, scopeRevision = s.comparisonRevision, stepOrdinal = 1)
        change(s, jobs = listOf(job))
        val claim = store.claimJob(s.sessionId, now++)!!
        val action = ActionRecord(ActionId.new(), job.jobId, s.sessionId, s.inputRevision, 1, proposal, ChatMessage(ChatRole.ASSISTANT, "synthetic test proposal"), "test-call-one", createdAtMs = now++, scopeRevision = s.comparisonRevision)
        return claim to store.persistPlan(claim, action)
    }
    private suspend fun expectFailure(code: ErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ClearLineException) { assertEquals(code, e.error.code) }
    }
    @Test fun admissionDeduplicatesContentAndRejectsIdentityReuse() = runBlocking {
        val s = session()
        val c = clip(s)
        val first = store.admitClip(c, now++)
        assertEquals(first.clipId, store.admitClip(c, now++).clipId)
        val duplicate = store.admitClip(clip(s), now++)
        assertTrue(duplicate.duplicate)
        assertEquals(first.clipId, duplicate.clipId)
        expectFailure(ErrorCode.IDENTITY_CONFLICT) { store.admitClip(clip(s, c.clipId, 4000), now++) }
        assertEquals(1, store.getSession(s.sessionId)!!.clips.size)
        assertNotNull(store.claimJob(s.sessionId, now++))
        assertNull(store.claimJob(s.sessionId, now++))
    }
    @Test fun badOutboxProjectionRollsBackResultCheckpointAndState() = runBlocking {
        val s = completed()
        val (claim, action) = plan(s)
        val current = store.getSession(s.sessionId)!!
        val cp = store.getCheckpoint(s.sessionId)
        val next = current.copy(stateVersion = current.stateVersion + 1, updatedAtMs = now++)
        val denied = ApprovedExport(ExportId.new(), s.profileId, s.sessionId, 0, 0, DataOrigin.SYNTHETIC, now++, ExportProjection.WorkflowCounts(Phase.COMPARING, 1, 0))
        expectFailure(ErrorCode.CONSENT_REQUIRED) {
            store.commitSuccess(ActionSuccessCommit(claim, action.actionId, ActionResult.Baseline(MeasurementMath.baseline(emptyList())), ChatMessage(ChatRole.TOOL, "synthetic result", action.toolCallId), CommandMutation(current.stateVersion, current.inputRevision, next, checkpoint(next), exports = listOf(denied)), now++))
        }
        assertEquals(current.stateVersion, store.getSession(s.sessionId)!!.stateVersion)
        assertEquals(cp, store.getCheckpoint(s.sessionId))
        assertEquals(ActionStatus.RUNNING, store.getPendingPlan(s.sessionId)!!.status)
        assertNull(store.claimOutbox(now++))
    }
    @Test fun deletionRejectsLateCompletionAndCleanupSurvivesRestart() = runBlocking {
        val s = completed()
        val (claim, action) = plan(s)
        val current = store.getSession(s.sessionId)!!
        val next = current.copy(stateVersion = current.stateVersion + 1)
        val deletion = store.deleteProfile(s.profileId, now++)
        assertEquals(listOf(s.sessionId), deletion.sessionIds)
        assertTrue(store.pendingFileCleanup().isNotEmpty())
        expectFailure(ErrorCode.DELETED) { store.commitSuccess(ActionSuccessCommit(claim, action.actionId, ActionResult.Baseline(MeasurementMath.baseline(emptyList())), ChatMessage(ChatRole.TOOL, "synthetic", action.toolCallId), CommandMutation(current.stateVersion, current.inputRevision, next, checkpoint(next)), now++)) }
        expectFailure(ErrorCode.DELETED) { store.putProfile(LocalProfile(s.profileId, "Deleted", DataOrigin.SYNTHETIC, 1)) }
        assertNull(store.getSession(s.sessionId))
        store.cleanPendingAudioFiles()
        assertTrue(store.pendingFileCleanup().isEmpty())
    }
    @Test fun recoveryPreservesLogicalPlanAndExplicitResumeIsIdempotent() = runBlocking {
        val s = completed()
        val (_, action) = plan(s)
        val recovery = store.recoverInterrupted(now++)
        assertEquals(listOf(action.actionId), recovery.interruptedActionIds)
        assertEquals(ActionStatus.UNKNOWN, store.getPendingPlan(s.sessionId)!!.status)
        assertNull(store.claimJob(s.sessionId, now++))
        assertTrue(store.recoverInterrupted(now++).pausedSessions.isEmpty())
        val paused = store.getSession(s.sessionId)!!
        val resumed = change(paused, paused.copy(stateVersion = paused.stateVersion + 1, phase = Phase.COMPARING), resume = true)
        val claim = store.claimJob(s.sessionId, now++)!!
        assertNull(store.claimJob(s.sessionId, now++))
        val persisted = store.persistPlan(claim, store.getPendingPlan(s.sessionId)!!)
        assertEquals(action.actionId, persisted.actionId)
        assertEquals(resumed.inputRevision, claim.job.inputRevision)
    }
    @Test fun syntheticExportStillNeedsConsentAndRevocationInvalidatesDispatch() = runBlocking {
        val s = completed(fields = setOf(ExportField.MEASUREMENTS))
        val export = ApprovedExport(ExportId.new(), s.profileId, s.sessionId, 0, 0, DataOrigin.SYNTHETIC, now++, ExportProjection.Measurements(1, s.metrics!!))
        change(s, exports = listOf(export))
        val claim = store.claimOutbox(now++)!!
        assertTrue(store.isExportStillApproved(claim))
        store.revokeExportConsent(s.sessionId, 0, now++)
        assertFalse(store.isExportStillApproved(claim))
        assertNull(store.claimOutbox(now++))
    }
    @Test fun transcriptSnippetRequiresSeparateFieldAndDoesNotLeakWithMeasurementsOnly() = runBlocking {
        val s = completed(fields = setOf(ExportField.MEASUREMENTS))
        val projection = ExportProjection.Measurements(1, s.metrics!!, "separately approved excerpt", "support")
        val export = ApprovedExport(ExportId.new(), s.profileId, s.sessionId, 0, 0, DataOrigin.SYNTHETIC, now++, projection)
        expectFailure(ErrorCode.CONSENT_REQUIRED) { change(s, exports = listOf(export)) }
        assertNull(store.claimOutbox(now++))
    }
    @Test fun baselineUsesLatestFiveEligiblePriorLocalSessionsWithExportOff() = runBlocking {
        val p = LocalProfile(ProfileId.new(), "Synthetic baseline", DataOrigin.SYNTHETIC, now++)
        repeat(7) { completed(p) }
        val current = session(p)
        val baseline = store.readBaseline(BaselineQuery(p.profileId, current.sessionId, RecordingTask.CHECK_IN, DataOrigin.SYNTHETIC, "android-pcm-v1", "english-lexical-v1", current.createdAtMs))
        assertEquals(5, baseline.previousSessions.size)
        assertEquals(BaselineStatus.AVAILABLE, baseline.status)
        assertEquals(60.0, baseline.meanRecordingWpm!!, 0.0)
        assertNull(MeasurementMath.compare(baseline.previousSessions.first().metrics, baseline).recordingWpm!!.standardizedDifference)
        assertEquals(7, store.observeHistory(p.profileId).first().size)
        assertEquals(BaselineStatus.INSUFFICIENT_HISTORY, store.readBaseline(BaselineQuery(p.profileId, current.sessionId, RecordingTask.CHECK_IN, DataOrigin.CONSENTED_DEMO, "android-pcm-v1", "english-lexical-v1", current.createdAtMs)).status)
    }
    @Test fun staleRevisionCannotCommitAfterCorrection() = runBlocking {
        val s = completed()
        val (claim, action) = plan(s)
        val old = store.getSession(s.sessionId)!!
        val newer = old.copy(stateVersion = old.stateVersion + 1, inputRevision = old.inputRevision + 1, comparisonRevision = old.comparisonRevision + 1)
        store.applyCommand(CommandMutation(old.stateVersion, old.inputRevision, newer, checkpoint(newer), invalidateScopes = setOf(WorkflowScope.COMPARISON)))
        val next = newer.copy(stateVersion = newer.stateVersion + 1)
        expectFailure(ErrorCode.STALE_REVISION) { store.commitSuccess(ActionSuccessCommit(claim, action.actionId, ActionResult.Baseline(MeasurementMath.baseline(emptyList())), ChatMessage(ChatRole.TOOL, "stale", action.toolCallId), CommandMutation(newer.stateVersion, newer.inputRevision, next, checkpoint(next)), now++)) }
    }
    @Test fun admittedIdentityCanBeRetriedAfterTerminalAudioDeletion() = runBlocking {
        val s = completed()
        val accepted = s.clips.single()
        store.cleanPendingAudioFiles()
        assertFalse(File(accepted.clip.privatePath).exists())
        val duplicate = store.admitClip(accepted.clip, now++)
        assertTrue(duplicate.duplicate)
        assertEquals(accepted.receipt.clipId, duplicate.clipId)
    }
    @Test fun startupCleansPartialsAndOrphansButRetainsAcceptedUnprocessedInput() = runBlocking {
        val s = session()
        val accepted = clip(s)
        store.admitClip(accepted, now++)
        val orphan = clip(s)
        val partial = File(audioRoot, "capture.part").apply { writeText("interrupted") }
        store.recoverInterrupted(now++)
        store.cleanPendingAudioFiles()
        assertTrue(File(accepted.privatePath).exists())
        assertFalse(File(orphan.privatePath).exists())
        assertFalse(partial.exists())
    }
    @Test fun acceptedReplacementClearsRejectedAudioBlockerAndQueuesOnlyCurrentClip() = runBlocking {
        val s = session()
        val rejected = clip(s)
        store.admitClip(rejected, now++)
        val claim = store.claimJob(s.sessionId, now++)!!
        val current = store.getSession(s.sessionId)!!
        val blocked = current.copy(stateVersion = current.stateVersion + 1, phase = Phase.AWAITING_INPUT, captureFinished = true, pendingInput = PendingInput("no_speech", "Please record another clip."))
        store.recordFailure(FailureCommit(claim, null, AppError(ErrorCode.NO_SPEECH, "No speech found"), CommandMutation(current.stateVersion, current.inputRevision, blocked, checkpoint(blocked)), now++))
        val replacement = clip(s, sample = 4000).copy(supersedesClipId = rejected.clipId)
        store.admitClip(replacement, now++)
        val next = store.getSession(s.sessionId)!!
        assertEquals(Phase.PROCESSING, next.phase)
        assertFalse(next.captureFinished)
        assertNull(next.pendingInput)
        assertEquals(replacement.clipId, store.claimJob(s.sessionId, now++)!!.job.clipId)
    }
    @Test fun successfulActionAndNextJobCommitTogetherAndDoNotReplayAfterRecovery() = runBlocking {
        val s = completed()
        val (claim, action) = plan(s)
        val current = store.getSession(s.sessionId)!!
        val next = current.copy(stateVersion = current.stateVersion + 1)
        val nextJob = claim.job.copy(jobId = JobId.new(), status = JobStatus.PENDING, stepOrdinal = 2, attempt = 0, claimToken = null, createdAtMs = now++, updatedAtMs = now++)
        store.commitSuccess(ActionSuccessCommit(claim, action.actionId, ActionResult.Baseline(MeasurementMath.baseline(emptyList())), ChatMessage(ChatRole.TOOL, "result", action.toolCallId), CommandMutation(current.stateVersion, current.inputRevision, next, checkpoint(next), enqueueJobs = listOf(nextJob)), now++))
        store.recoverInterrupted(now++)
        val paused = store.getSession(s.sessionId)!!
        assertEquals(ActionStatus.SUCCEEDED, paused.actions.single().status)
        change(paused, paused.copy(stateVersion = paused.stateVersion + 1, phase = Phase.COMPARING), resume = true)
        assertEquals(nextJob.jobId, store.claimJob(s.sessionId, now++)!!.job.jobId)
        assertNull(store.getPendingPlan(s.sessionId))
    }
    @Test fun researchOnlyRevisionKeepsBaselineAndCorrectionUsesOnlyNewestSummaryVersion() = runBlocking {
        val p = LocalProfile(ProfileId.new(), "Synthetic corrected history", DataOrigin.SYNTHETIC, now++)
        val first = completed(p)
        val second = completed(p)
        // Changing research increments the global revision but must preserve eligible measurements.
        val researchEdit = first.copy(stateVersion = first.stateVersion + 1, inputRevision = 1, researchRevision = 1)
        change(first, researchEdit)
        val accepted = second.clips.single()
        val correctedResult = accepted.result!!.copy(transcript = "now three words", metrics = MeasurementMath.correctTranscript(accepted.result!!.metrics, "now three words"))
        val corrected = second.copy(stateVersion = second.stateVersion + 1, inputRevision = 1, comparisonRevision = 1, summaryVersion = 2, metrics = correctedResult.metrics, clips = listOf(accepted.copy(result = correctedResult)))
        val summary = SessionSummary(second.sessionId, p.profileId, 2, 1, second.task, second.dataOrigin, corrected.metrics!!, now++, listOf(accepted.clip.clipId))
        store.applyCommand(CommandMutation(second.stateVersion, second.inputRevision, corrected, checkpoint(corrected), invalidateScopes = setOf(WorkflowScope.COMPARISON), summary = summary))
        val current = session(p)
        val baseline = store.readBaseline(BaselineQuery(p.profileId, current.sessionId, current.task, current.dataOrigin, "android-pcm-v1", "english-lexical-v1", current.createdAtMs))
        assertEquals(2, baseline.previousSessions.size)
        assertEquals(75.0, baseline.meanRecordingWpm!!, 0.0)
        assertEquals(2, baseline.previousSessions.first { it.sessionId == second.sessionId }.version)
        assertEquals(accepted.result!!.metrics.energyRms, store.getSession(second.sessionId)!!.clips.single().result!!.metrics.energyRms, 0.0)
    }
}
