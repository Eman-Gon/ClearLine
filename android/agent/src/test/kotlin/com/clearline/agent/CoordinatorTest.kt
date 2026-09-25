package com.clearline.agent

import com.clearline.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Contract tests with explicit test doubles. These do not exercise Room, Liquid, ASR or an S24. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTest {
    @Test fun observationAndInitializationDoNotExecuteJobs() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison()
        h.coordinator.onForeground()
        h.coordinator.initialize()
        repeat(3) { h.store.observeSession(id).first(); h.store.observeHistory(h.store.profile.profileId).first() }
        runCurrent()
        assertEquals(0, h.agent.calls.size)
        assertEquals(0, h.store.claimCount)
        assertEquals(0, h.resources.searchCalls.size)
    }

    @Test fun localComparisonCompletesOfflineWithExportOffAndResumeIsIdempotent() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison()
        h.store.seedHistory()
        h.coordinator.onForeground()
        h.coordinator.initialize()
        repeat(5) { h.coordinator.resume(id) }
        h.coordinator.awaitIdle()
        val state = h.store.getSession(id)!!
        assertEquals(Phase.AWAITING_USER_CHOICE, state.phase)
        assertEquals(BaselineStatus.AVAILABLE, state.comparison!!.baseline.status)
        assertNull(state.comparison!!.recordingWpm!!.standardizedDifference)
        assertNull(state.comparison!!.energyRms!!.standardizedDifference)
        assertEquals(3, state.actions.size)
        assertEquals(3, state.actions.map { it.actionId }.distinct().size)
        assertTrue(state.actions.all { it.status == ActionStatus.SUCCEEDED })
        assertEquals(1, h.store.baselineReads)
        assertEquals(0, h.resources.searchCalls.size)
        assertEquals(0, h.history.appendCalls.size)
        assertTrue(h.store.exports.isEmpty())
        repeat(5) { h.coordinator.resume(id) }
        h.coordinator.awaitIdle()
        assertEquals(3, h.agent.calls.size)
    }

    @Test fun noHistoryProducesExplicitInsufficientComparison() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison()
        h.coordinator.onForeground()
        h.coordinator.resume(id)
        h.coordinator.awaitIdle()
        val result = h.store.getSession(id)!!.comparison!!
        assertEquals(BaselineStatus.INSUFFICIENT_HISTORY, result.baseline.status)
        assertNull(result.recordingWpm)
        assertNull(result.energyRms)
    }

    @Test fun deniedRecordingCreatesNothingAndFinalizedClipAdmissionIsIdempotent() = runTest {
        val h = Harness(this)
        expectError(ErrorCode.INVALID_INPUT) { h.coordinator.createSession(CreateSession(h.store.profile.profileId, false)) }
        assertTrue(h.store.sessions.isEmpty())
        val id = h.coordinator.createSession(CreateSession(h.store.profile.profileId, true))
        val clip = testClip(id)
        try {
            val first = h.coordinator.acceptClip(clip)
            val duplicate = h.coordinator.acceptClip(clip)
            assertEquals(first.clipId, duplicate.clipId)
            assertTrue(duplicate.duplicate)
            assertEquals(1, h.store.jobs.size)
            h.coordinator.finishCapture(id)
            h.coordinator.onForeground()
            h.coordinator.resume(id)
            h.coordinator.awaitIdle()
            val completed = h.store.getSession(id)!!
            assertEquals(1, h.audioCalls)
            assertEquals(1, completed.clips.size)
            assertEquals(clip.clipId, completed.clips.single().receipt.clipId)
            assertEquals(Phase.AWAITING_USER_CHOICE, completed.phase)
            assertEquals(1, h.store.summaries.count { it.sessionId == id })
            assertFalse(File(clip.privatePath).exists())
        } finally { File(clip.privatePath).delete() }
    }

    @Test fun pauseWaitsForCancellationBoundaryAndDoesNotCommitInterruptedSearch() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        val entered = CompletableDeferred<Unit>()
        val safeBoundary = CompletableDeferred<Unit>()
        h.resources.searchBehavior = {
            entered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { safeBoundary.await() } }
        }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id))
        entered.await()
        val pause = async { h.coordinator.pause(id) }
        runCurrent()
        assertFalse(pause.isCompleted)
        assertTrue(h.store.getSession(id)!!.pauseRequested)
        assertEquals(1, h.cancelInferenceCalls)
        safeBoundary.complete(Unit)
        pause.await()
        val state = h.store.getSession(id)!!
        assertEquals(Phase.PAUSED, state.phase)
        assertFalse(state.pauseRequested)
        assertTrue(state.resources.isEmpty())
        assertTrue(state.actions.none { it.status == ActionStatus.SUCCEEDED })
    }

    @Test fun committedSearchSurvivesReconstructionAndUnknownExtractReusesIdentity() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        val acceptedClipId = h.store.getSession(id)!!.clips.single().clip.clipId
        val entered = CompletableDeferred<Unit>()
        h.resources.extractBehavior = { entered.complete(Unit); awaitCancellation() }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id))
        entered.await()
        h.coordinator.pause(id)
        val saved = h.store.getSession(id)!!
        val completedSearch = saved.actions.single { it.proposal == ProposedAction.SearchPublicResources }
        val interruptedExtract = saved.actions.single { it.proposal is ProposedAction.ExtractPublicPage }
        assertEquals(ActionStatus.SUCCEEDED, completedSearch.status)
        assertEquals(ActionStatus.UNKNOWN, interruptedExtract.status)
        assertEquals(1, h.resources.searchCalls.size)

        // Same in-memory durable records, new coordinator. This simulates the
        // recovery contract; an actual process-kill/Room test is separate.
        val reopened = h.newCoordinator()
        reopened.onForeground()
        reopened.initialize()
        runCurrent()
        assertEquals(1, h.resources.extractCalls.size)
        h.resources.extractBehavior = null
        repeat(3) { reopened.resume(id) }
        reopened.awaitIdle()
        val result = h.store.getSession(id)!!
        assertEquals(Phase.READY, result.phase)
        assertEquals(acceptedClipId, result.clips.single().clip.clipId)
        assertEquals(1, h.resources.searchCalls.size)
        assertEquals(2, h.resources.extractCalls.size) // unknown read may safely repeat
        assertEquals(completedSearch.actionId, result.actions.single { it.proposal == ProposedAction.SearchPublicResources }.actionId)
        assertEquals(interruptedExtract.actionId, result.actions.single { it.proposal is ProposedAction.ExtractPublicPage }.actionId)
        assertEquals(1, h.agent.calls.count { it.candidateSources.isNotEmpty() && it.sourceIds.isEmpty() })
        assertEquals(ChatRole.TOOL, h.store.getCheckpoint(id)!!.exchanges.last().role)
    }

    @Test fun startupRecoveryRequiresExplicitResumeBeforeInterruptedPlanningRuns() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison()
        h.store.claimJob(id, 123)!! // durable planning claim left by terminated process
        h.coordinator.onForeground()
        val report = h.coordinator.initialize()
        assertEquals(listOf(id), report.pausedSessions)
        runCurrent()
        assertTrue(h.agent.calls.isEmpty())
        assertEquals(Phase.PAUSED, h.store.getSession(id)!!.phase)
        h.coordinator.resume(id)
        h.coordinator.awaitIdle()
        assertEquals(Phase.AWAITING_USER_CHOICE, h.store.getSession(id)!!.phase)
    }

    @Test fun staleSearchCannotPopulateEditedRequestOrExportItsEvidence() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        h.resources.searchBehavior = { request ->
            if (request.city == "Old City") {
                entered.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
            h.resources.searchResult(request)
        }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id, city = "Old City"))
        entered.await()
        val oldActionId = h.store.getPendingPlan(id)!!.actionId
        h.coordinator.requestResources(request(id, revision = 1, city = "New City"))
        response.complete(Unit)
        h.coordinator.awaitIdle()
        h.coordinator.resume(id)
        h.coordinator.awaitIdle()
        val state = h.store.getSession(id)!!
        assertEquals("New City", state.approvedResources!!.city)
        assertTrue(state.resources.all { it.title == "New City service" })
        assertEquals(ActionStatus.INVALIDATED, state.actions.single { it.actionId == oldActionId }.status)
        assertTrue(state.sources.all { it.approvalId == state.approvedResources!!.approvalId })
        assertTrue(h.store.exports.isEmpty())
    }

    @Test fun networkFailureIsVisibleAndDoesNotFabricateEvidence() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.resources.searchBehavior = { throw ClearLineException(AppError(ErrorCode.NETWORK_UNAVAILABLE, "Offline test transport", true)) }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id))
        h.coordinator.awaitIdle()
        val state = h.store.getSession(id)!!
        assertEquals(Phase.WAITING_NETWORK, state.phase)
        assertTrue(state.resources.isEmpty())
        assertTrue(state.sources.isEmpty())
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, state.errors.single().code)
        assertTrue(h.history.appendCalls.isEmpty())
        assertEquals(ChatRole.TOOL, h.store.getCheckpoint(id)!!.exchanges.last().role)
    }

    @Test fun completedNetworkErrorReturnsToModelInsteadOfReplayingFailedAction() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.resources.searchBehavior = { throw ClearLineException(AppError(ErrorCode.NETWORK_UNAVAILABLE, "Offline test transport", true)) }
        h.agent.proposalOverride = { checkpoint ->
            if (checkpoint.exchanges.lastOrNull()?.content?.contains("NETWORK_UNAVAILABLE") == true)
                ProposedAction.RequestUserInput(PendingInput("network", "Reconnect, then confirm that research should continue."))
            else ProposedAction.SearchPublicResources
        }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id))
        h.coordinator.awaitIdle()
        val failed = h.store.getSession(id)!!.actions.single()
        assertEquals(ActionStatus.FAILED, failed.status)
        h.coordinator.resume(id)
        h.coordinator.awaitIdle()
        val state = h.store.getSession(id)!!
        assertEquals(Phase.AWAITING_INPUT, state.phase)
        assertEquals(1, h.resources.searchCalls.size)
        assertEquals(2, h.agent.calls.size)
        assertEquals(2, state.actions.size)
        assertEquals(failed.actionId, state.actions.first().actionId)
        assertEquals(ActionStatus.FAILED, state.actions.first().status)
        assertNotEquals(failed.actionId, state.actions.last().actionId)
        assertEquals(failed.toolCallId, h.agent.calls.last().exchanges.last().toolCallId)
    }

    @Test fun planningTimeoutWaitsForRetryWithoutPretendingUserPaused() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison()
        h.agent.proposalOverride = { awaitCancellation() }
        h.coordinator.onForeground()
        h.coordinator.resume(id)
        h.coordinator.awaitIdle() // runTest advances the 120 s virtual deadline
        val state = h.store.getSession(id)!!
        assertEquals(Phase.WAITING_RETRY, state.phase)
        assertEquals(ErrorCode.TIMEOUT, state.errors.single().code)
        assertTrue(state.actions.isEmpty())
        assertTrue(state.sources.isEmpty())
    }

    @Test fun timedOutReadRetainsUnknownActionIdentityForExplicitRetry() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.resources.searchBehavior = { awaitCancellation() }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id))
        h.coordinator.awaitIdle()
        val timedOut = h.store.getSession(id)!!
        assertEquals(Phase.WAITING_RETRY, timedOut.phase)
        assertEquals(ErrorCode.TIMEOUT, timedOut.errors.single().code)
        val unknown = timedOut.actions.single()
        assertEquals(ActionStatus.UNKNOWN, unknown.status)
        h.resources.searchBehavior = null
        h.coordinator.resume(id)
        h.coordinator.awaitIdle()
        val result = h.store.getSession(id)!!
        assertEquals(Phase.READY, result.phase)
        assertEquals(unknown.actionId, result.actions.single { it.proposal == ProposedAction.SearchPublicResources }.actionId)
        assertEquals(1, h.agent.calls.count { it.candidateSources.isEmpty() })
        assertEquals(2, h.resources.searchCalls.size)
    }

    @Test fun approvalExportsOnlyCurrentSummaryAndRevocationCancelsQueuedProjection() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.store.seedHistory()
        h.coordinator.setExportConsent(ExportConsentChange(id, 0, setOf(ExportField.MEASUREMENTS)))
        val state = h.store.getSession(id)!!
        assertEquals(1, h.store.exports.size)
        assertEquals(id, h.store.exports.single().sessionId)
        h.coordinator.setExportConsent(ExportConsentChange(id, state.consent.exportRevision, emptySet()))
        assertTrue(h.store.exports.isEmpty())
        assertTrue(h.store.getSession(id)!!.consent.exportFields.isEmpty())
        assertTrue(h.history.appendCalls.isEmpty())
    }

    @Test fun revokedExportCannotBeEnqueuedByAlreadyRunningExtraction() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.coordinator.setExportConsent(ExportConsentChange(id, 0, setOf(ExportField.PUBLIC_RESOURCES)))
        val extractionStarted = CompletableDeferred<Unit>()
        val extractionCanFinish = CompletableDeferred<Unit>()
        h.resources.extractBehavior = { source ->
            extractionStarted.complete(Unit)
            extractionCanFinish.await()
            h.resources.evidence(source)
        }
        h.coordinator.onForeground()
        h.coordinator.requestResources(request(id))
        extractionStarted.await()
        val revision = h.store.getSession(id)!!.consent.exportRevision
        h.coordinator.setExportConsent(ExportConsentChange(id, revision, emptySet()))
        extractionCanFinish.complete(Unit)
        h.coordinator.awaitIdle()
        assertEquals(Phase.READY, h.store.getSession(id)!!.phase)
        assertEquals(1, h.store.getSession(id)!!.sources.size)
        assertTrue(h.store.exports.isEmpty())
        assertTrue(h.history.appendCalls.isEmpty())
    }

    @Test fun proposalCannotSearchBeforePublicApproval() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison()
        h.agent.proposalOverride = { ProposedAction.SearchPublicResources }
        h.coordinator.onForeground()
        h.coordinator.resume(id)
        h.coordinator.awaitIdle()
        assertTrue(h.resources.searchCalls.isEmpty())
        assertTrue(h.store.getSession(id)!!.actions.isEmpty())
        assertEquals(Phase.AGENT_UNAVAILABLE, h.store.getSession(id)!!.phase)
    }

    @Test fun reviewedEditedQueryIsPreservedAndMismatchedRationaleIsRejected() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        val state = h.store.getSession(id)!!
        val draft = CallInsights.buildQuery(CallInsights.transcriptFor(state), "Test City", ResourceCategory.CAREGIVER_SUPPORT, state.comparison)
            .copy(query = "reviewed public caregiver support query in Test City")
        val approval = request(id).copy(queryDraft = draft)
        h.coordinator.onForeground()
        h.coordinator.requestResources(approval)
        h.coordinator.awaitIdle()
        assertEquals(draft, h.resources.searchCalls.single().queryDraft)
        val invalid = draft.copy(transcriptHash = "b".repeat(64))
        expectError(ErrorCode.INVALID_INPUT) { h.coordinator.requestResources(request(id, revision = 1).copy(queryDraft = invalid)) }
        assertEquals(1, h.resources.searchCalls.size)
    }

    @Test fun measurementExportUsesExactReviewedTextAndOriginalChronology() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.store.seedHistory()
        val chosenSnippet = "This exact edited snippet was reviewed."
        val chosenKeyword = "chosen topic"
        h.coordinator.setExportConsent(ExportConsentChange(id, 0,
            setOf(ExportField.MEASUREMENTS, ExportField.TRANSCRIPT_SNIPPET), chosenSnippet, chosenKeyword))
        val export = h.store.exports.single()
        val projection = export.projection as ExportProjection.Measurements
        assertEquals(id, export.sessionId)
        assertEquals(chosenSnippet, projection.transcriptSnippet)
        assertEquals(chosenKeyword, projection.topKeyword)
        assertEquals(100L, projection.sessionCreatedAtMs)
        assertEquals(110L, projection.completedAtMs)
        assertNotEquals(export.createdAtMs, projection.completedAtMs)
        assertEquals(1, projection.summaryVersion)
        assertTrue(h.history.appendCalls.isEmpty()) // approval itself does not bypass foreground scheduling
    }

    @Test fun optionalMemoryResponseCannotRestoreCacheAfterConsentChanges() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        h.coordinator.setExportConsent(ExportConsentChange(id, 0, setOf(ExportField.EVENTS)))
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        h.history.memoryBehavior = { query ->
            entered.complete(Unit)
            response.await()
            RawTreeMemoryMath.snapshot(query, emptyList(), 0, 0, 120, RawTreeMemoryDiagnostics())
        }
        h.coordinator.onForeground()
        val refresh = async { h.coordinator.refreshExportedMemory(id) }
        entered.await()
        val oldRevision = h.store.getSession(id)!!.consent.exportRevision
        h.coordinator.setExportConsent(ExportConsentChange(id, oldRevision, emptySet()))
        response.complete(Unit)
        refresh.await()
        assertNull(h.store.getSession(id)!!.rawTreeMemory)
        assertEquals(1, h.history.memoryCalls.size)
    }

    @Test fun backgroundingCancelsAndWaitsForOptionalMemoryWork() = runTest {
        val h = Harness(this)
        val id = h.store.seedComparison(Phase.AWAITING_USER_CHOICE)
        val entered = CompletableDeferred<Unit>()
        val safeBoundary = CompletableDeferred<Unit>()
        h.history.memoryBehavior = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { safeBoundary.await() } }
        }
        h.coordinator.onForeground()
        val refresh = async { h.coordinator.refreshExportedMemory(id) }
        entered.await()
        val background = async { h.coordinator.onBackground() }
        runCurrent()
        assertFalse(background.isCompleted)
        safeBoundary.complete(Unit)
        background.await()
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertNull(h.store.getSession(id)!!.rawTreeMemory)
    }

    private suspend fun expectError(code: ErrorCode, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: ClearLineException) { assertEquals(code, error.error.code) }
    }

    private fun request(id: SessionId, revision: Long = 0, city: String = "Test City") = ApprovedResourceRequest(
        id, revision, ApprovalId.new(), ResourceCategory.CAREGIVER_SUPPORT, city, 100,
    )

    private class Harness(val scope: CoroutineScope) {
        val store = TransactionalFakeStore()
        val agent = TestAgent()
        val resources = TestResources()
        val history = TestHistory()
        var audioCalls = 0
        var cancelInferenceCalls = 0
        private val audio = object : LocalAudioProcessor {
            override suspend fun process(clip: CompletedLocalClip): AudioResult {
                audioCalls++
                return testAudio(clip)
            }
        }
        val coordinator = newCoordinator()
        fun newCoordinator() = OnDeviceCoordinator(store, audio, agent, resources, history, scope,
            cancelInference = { cancelInferenceCalls++ }, now = { 1000 })
    }

    private class TestAgent : ToolCallingLocalAgent {
        val calls = mutableListOf<AgentCheckpoint>()
        var proposalOverride: (suspend (AgentCheckpoint) -> ProposedAction)? = null
        override suspend fun proposeTurn(checkpoint: AgentCheckpoint): ModelProposal {
            calls += checkpoint
            val action = proposalOverride?.invoke(checkpoint) ?: if (checkpoint.workflowScope == WorkflowScope.RESEARCH) when {
                checkpoint.candidateSources.isEmpty() -> ProposedAction.SearchPublicResources
                checkpoint.sourceIds.isEmpty() -> ProposedAction.ExtractPublicPage(checkpoint.candidateSources.first().candidateId)
                else -> ProposedAction.FinishTask
            } else when {
                checkpoint.baseline == null -> ProposedAction.GetBaselineSummary
                checkpoint.comparison == null -> ProposedAction.CompareRecordingMetrics
                else -> ProposedAction.FinishTask
            }
            return ModelProposal(action, ChatMessage(ChatRole.ASSISTANT, "explicit test-only proposal"), 1, 1)
        }
    }

    private class TestResources : PublicResourceClient {
        val searchCalls = mutableListOf<ApprovedResourceRequest>()
        val extractCalls = mutableListOf<ApprovedSource>()
        var searchBehavior: (suspend (ApprovedResourceRequest) -> SearchResult)? = null
        var extractBehavior: (suspend (ApprovedSource) -> SourceEvidence)? = null
        fun searchResult(request: ApprovedResourceRequest) = SearchResult(request.approvalId, request.inputRevision,
            listOf(ResourceCandidate("candidate-${request.inputRevision}", "${request.city} service", "https://example.org/service", "Test-only description", "search-test", 101)), 101, "search-test")
        override suspend fun search(request: ApprovedResourceRequest): SearchResult {
            searchCalls += request
            return searchBehavior?.invoke(request) ?: searchResult(request)
        }
        override suspend fun extract(source: ApprovedSource): SourceEvidence {
            extractCalls += source
            return extractBehavior?.invoke(source) ?: evidence(source)
        }
        fun evidence(source: ApprovedSource) = SourceEvidence(EvidenceId.new(), 1, source.request.approvalId,
                source.request.inputRevision, source.candidate.title, source.candidate.description, source.candidate.url,
                102, "a".repeat(64), listOf(SupportingPassage("p1", "Test-only public service.")))
    }

    private class TestHistory : ApprovedHistoryClient {
        val appendCalls = mutableListOf<ApprovedExport>()
        val memoryCalls = mutableListOf<MemoryQuery>()
        var memoryBehavior: (suspend (MemoryQuery) -> RawTreeMemorySnapshot)? = null
        override suspend fun append(event: ApprovedExport): DeliveryReceipt {
            appendCalls += event
            return DeliveryReceipt(event.exportId, 200)
        }
        override suspend fun query(request: BoundedHistoryQuery): HistoryResult = error("Unexpected cloud history query")
        override suspend fun memory(query: MemoryQuery): RawTreeMemorySnapshot {
            memoryCalls += query
            return memoryBehavior?.invoke(query) ?: error("Unexpected cloud memory query")
        }
    }

    companion object {
        private fun metrics() = RecordingMetrics(20.0, 40, 120.0, .1, dataOrigin = DataOrigin.CONSENTED_DEMO)
        private fun testAudio(clip: CompletedLocalClip) = AudioResult(clip.clipId, "test-only transcript",
            metrics().copy(durationSeconds = clip.durationSeconds, recordingWpm = 40 * 60 / clip.durationSeconds), ModelId("test-asr"), 100)
        private fun testClip(id: SessionId): CompletedLocalClip {
            val file = File.createTempFile("clearline-coordinator-test-", ".wav")
            val bytes = ByteArray(64044)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(bytes.size - 8).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes.size - 44)
            file.writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return CompletedLocalClip(id, ClipId.new(), file.absolutePath, digest, 2.0, DataOrigin.CONSENTED_DEMO, createdAtMs = 1)
        }
    }

    /**
     * Test-only synchronous transactions; models durable identities, revision CAS,
     * per-step jobs and atomic result/checkpoint/outbox writes. Does not establish
     * Room SQL constraints, rollback behavior, file validation or process durability.
     */
    private class TransactionalFakeStore : WorkflowStore {
        val profile = LocalProfile(ProfileId.new(), "Test profile", DataOrigin.CONSENTED_DEMO, 0)
        val sessions = linkedMapOf<SessionId, SessionSnapshot>()
        val jobs = linkedMapOf<JobId, JobRecord>()
        val summaries = mutableListOf<SessionSummary>()
        val exports = mutableListOf<ApprovedExport>()
        private val profiles = mutableMapOf(profile.profileId to profile)
        private val checkpoints = mutableMapOf<SessionId, AgentCheckpoint>()
        private val actions = linkedMapOf<ActionId, ActionRecord>()
        private val tombstones = mutableSetOf<SessionId>()
        private val claimedExports = mutableSetOf<ExportId>()
        var claimCount = 0
        var baselineReads = 0

        private fun fail(code: ErrorCode): Nothing = throw ClearLineException(AppError(code, "Test-store contract rejection"))
        private fun snapshot(id: SessionId): SessionSnapshot = sessions[id]?.let { value ->
            val records = actions.values.filter { it.sessionId == id }
            value.copy(actions = records, pendingAction = records.lastOrNull { it.status in setOf(ActionStatus.RUNNING, ActionStatus.UNKNOWN, ActionStatus.PLANNED) && it.scopeRevision == value.revision(it.scope) })
        } ?: fail(if (id in tombstones) ErrorCode.DELETED else ErrorCode.NOT_FOUND)
        override suspend fun putProfile(profile: LocalProfile) { profiles[profile.profileId] = profile }
        override fun observeProfiles() = flow { emit(profiles.values.toList()) }
        override suspend fun getProfile(profileId: ProfileId) = profiles[profileId]
        override suspend fun getSession(sessionId: SessionId) = if (sessionId in sessions) snapshot(sessionId) else null
        override suspend fun getCheckpoint(sessionId: SessionId) = checkpoints[sessionId]
        override fun observeSession(sessionId: SessionId) = flow { emit(snapshot(sessionId)) }
        override fun observeHistory(profileId: ProfileId) = flow { emit(summaries.filter { it.profileId == profileId }) }
        override fun observeOpenFollowUps(profileId: ProfileId) = flow { emit(sessions.keys.map(::snapshot).filter { it.profileId == profileId && it.phase != Phase.READY }) }

        private fun checkMutation(mutation: CommandMutation) {
            val next = mutation.snapshot
            if (next.sessionId in tombstones) fail(ErrorCode.DELETED)
            val old = sessions[next.sessionId]
            if (old == null) {
                if (mutation.expectedStateVersion != null || mutation.expectedInputRevision != null) fail(ErrorCode.STALE_REVISION)
            } else if (old.stateVersion != mutation.expectedStateVersion || old.inputRevision != mutation.expectedInputRevision) fail(ErrorCode.STALE_REVISION)
            if (mutation.checkpoint.sessionId != next.sessionId || mutation.checkpoint.stateVersion != next.stateVersion) fail(ErrorCode.IDENTITY_CONFLICT)
            if (mutation.enqueueJobs.any { it.kind == JobKind.ADVANCE_WORKFLOW && it.stepOrdinal !in 1..12 }) fail(ErrorCode.INVALID_INPUT)
            if (mutation.exports.any { it.requiredField !in next.consent.exportFields || it.consentRevision != next.consent.exportRevision }) fail(ErrorCode.CONSENT_REQUIRED)
        }
        private fun apply(mutation: CommandMutation): SessionSnapshot {
            checkMutation(mutation)
            val next = mutation.snapshot
            jobs.replaceAll { _, job ->
                if (job.sessionId == next.sessionId && (job.scope in mutation.invalidateScopes || job.scopeRevision != next.revision(job.scope))) job.copy(status = JobStatus.INVALIDATED, claimToken = null)
                else if (job.sessionId == next.sessionId && mutation.resumeEligibleJobs && job.status in setOf(JobStatus.PAUSED, JobStatus.FAILED)) job.copy(status = JobStatus.PENDING, claimToken = null)
                else job
            }
            actions.replaceAll { _, action ->
                if (action.sessionId == next.sessionId && (action.scope in mutation.invalidateScopes || action.scopeRevision != next.revision(action.scope))) action.copy(status = ActionStatus.INVALIDATED) else action
            }
            sessions[next.sessionId] = next
            checkpoints[next.sessionId] = mutation.checkpoint
            mutation.enqueueJobs.forEach { jobs.putIfAbsent(it.jobId, it) }
            mutation.summary?.let { summaries += it }
            exports.removeAll { it.sessionId == next.sessionId && (it.consentRevision != next.consent.exportRevision || it.requiredField !in next.consent.exportFields) }
            exports += mutation.exports
            return snapshot(next.sessionId)
        }
        override suspend fun applyCommand(mutation: CommandMutation) = apply(mutation)
        override suspend fun admitClip(clip: CompletedLocalClip, acceptedAtMs: Long): ClipReceipt {
            val old = snapshot(clip.sessionId)
            old.clips.find { it.clip.clipId == clip.clipId }?.let {
                if (it.clip.sha256 != clip.sha256) fail(ErrorCode.IDENTITY_CONFLICT)
                return it.receipt.copy(duplicate = true)
            }
            old.clips.find { it.clip.sha256 == clip.sha256 }?.let { return it.receipt.copy(duplicate = true) }
            val receipt = ClipReceipt(clip.sessionId, clip.clipId, clip.sha256, acceptedAtMs)
            sessions[old.sessionId] = old.copy(clips = old.clips + StoredClip(clip, receipt), stateVersion = old.stateVersion + 1)
            checkpoints[old.sessionId] = checkpoints.getValue(old.sessionId).copy(stateVersion = old.stateVersion + 1)
            val job = JobRecord(JobId.new(), old.sessionId, old.inputRevision, JobKind.PROCESS_AUDIO, clipId = clip.clipId,
                createdAtMs = acceptedAtMs, updatedAtMs = acceptedAtMs, scopeRevision = old.comparisonRevision)
            jobs[job.jobId] = job
            return receipt
        }
        override suspend fun claimJob(sessionId: SessionId, nowMs: Long): JobClaim? {
            val state = snapshot(sessionId)
            if (state.phase in setOf(Phase.PAUSED, Phase.READY, Phase.AWAITING_INPUT, Phase.AWAITING_USER_CHOICE, Phase.AGENT_UNAVAILABLE) || state.pauseRequested) return null
            if (jobs.values.any { it.status == JobStatus.CLAIMED }) return null
            val job = jobs.values.firstOrNull { it.sessionId == sessionId && it.status == JobStatus.PENDING && it.scopeRevision == state.revision(it.scope) } ?: return null
            val token = UUID.randomUUID().toString()
            val claimed = job.copy(status = JobStatus.CLAIMED, claimToken = token, attempt = job.attempt + 1)
            jobs[job.jobId] = claimed
            claimCount++
            return JobClaim(claimed, token)
        }
        private fun checkClaim(claim: JobClaim) {
            val state = snapshot(claim.job.sessionId)
            val current = jobs[claim.job.jobId] ?: fail(ErrorCode.NOT_FOUND)
            if (current.status != JobStatus.CLAIMED || current.claimToken != claim.claimToken || current.scopeRevision != state.revision(current.scope)) fail(ErrorCode.STALE_REVISION)
        }
        override suspend fun getPendingPlan(sessionId: SessionId) = snapshot(sessionId).pendingAction
        override suspend fun persistPlan(claim: JobClaim, action: ActionRecord): ActionRecord {
            checkClaim(claim)
            val prior = actions[action.actionId]
            if (prior != null && prior.copy(status = action.status, error = action.error) != action) fail(ErrorCode.IDENTITY_CONFLICT)
            val saved = action.copy(status = ActionStatus.RUNNING, error = null)
            actions[saved.actionId] = saved
            checkpoints[saved.sessionId] = checkpoints.getValue(saved.sessionId).copy(pendingActionId = saved.actionId)
            return saved
        }
        override suspend fun commitSuccess(commit: ActionSuccessCommit): SessionSnapshot {
            checkClaim(commit.claim)
            checkMutation(commit.mutation)
            val action = actions[commit.actionId] ?: fail(ErrorCode.NOT_FOUND)
            if (action.status != ActionStatus.RUNNING || action.jobId != commit.claim.job.jobId) fail(ErrorCode.STALE_REVISION)
            if (commit.toolMessage.toolCallId != action.toolCallId) fail(ErrorCode.IDENTITY_CONFLICT)
            actions[action.actionId] = action.copy(status = ActionStatus.SUCCEEDED, result = commit.result,
                completedAtMs = commit.completedAtMs, toolMessage = commit.toolMessage)
            jobs[commit.claim.job.jobId] = commit.claim.job.copy(status = JobStatus.COMPLETED, claimToken = null)
            return apply(commit.mutation)
        }
        override suspend fun commitAudioSuccess(commit: AudioSuccessCommit): SessionSnapshot {
            checkClaim(commit.claim)
            checkMutation(commit.mutation)
            jobs[commit.claim.job.jobId] = commit.claim.job.copy(status = JobStatus.COMPLETED, claimToken = null)
            return apply(commit.mutation)
        }
        override suspend fun recordFailure(commit: FailureCommit): SessionSnapshot {
            checkClaim(commit.claim)
            checkMutation(commit.mutation)
            jobs[commit.claim.job.jobId] = commit.claim.job.copy(status = if (commit.retrySameAction) JobStatus.FAILED else JobStatus.COMPLETED, claimToken = null)
            commit.actionId?.let { id -> actions[id] = actions.getValue(id).copy(status = if (commit.retrySameAction) ActionStatus.UNKNOWN else ActionStatus.FAILED, error = commit.error) }
            return apply(commit.mutation)
        }
        override suspend fun recoverInterrupted(nowMs: Long): RecoveryReport {
            val interrupted = jobs.values.filter { it.status == JobStatus.CLAIMED }
            val actionIds = actions.values.filter { it.status == ActionStatus.RUNNING }.map { it.actionId }
            interrupted.forEach { job ->
                jobs[job.jobId] = job.copy(status = JobStatus.PAUSED, claimToken = null)
                val old = snapshot(job.sessionId)
                sessions[old.sessionId] = old.copy(phase = Phase.PAUSED, pauseRequested = false, stateVersion = old.stateVersion + 1)
                checkpoints[old.sessionId] = checkpoints.getValue(old.sessionId).copy(phase = Phase.PAUSED, stateVersion = old.stateVersion + 1)
            }
            actionIds.forEach { id -> actions[id] = actions.getValue(id).copy(status = ActionStatus.UNKNOWN) }
            return RecoveryReport(interrupted.map { it.sessionId }.distinct(), actionIds, emptyList())
        }
        override suspend fun readBaseline(query: BaselineQuery): BaselineSummary {
            baselineReads++
            val previous = summaries.groupBy { it.sessionId }.values.map { it.maxBy { s -> s.version } }.filter {
                it.sessionId != query.currentSessionId && it.profileId == query.profileId && it.task == query.task && it.dataOrigin == query.dataOrigin &&
                    it.metrics.measurementVersion == query.measurementVersion && it.metrics.lexicalVersion == query.lexicalVersion && it.completedAtMs < query.beforeCreatedAtMs
            }.sortedByDescending { it.completedAtMs }.take(5)
            val enough = previous.size >= 2
            return BaselineSummary(if (enough) BaselineStatus.AVAILABLE else BaselineStatus.INSUFFICIENT_HISTORY, previous,
                if (enough) previous.map { it.metrics.recordingWpm }.average() else null,
                if (enough) previous.map { it.metrics.energyRms }.average() else null)
        }
        override suspend fun claimOutbox(nowMs: Long): OutboxClaim? = exports.firstOrNull { it.exportId !in claimedExports }?.let {
            claimedExports += it.exportId; OutboxClaim(it, UUID.randomUUID().toString(), 1)
        }
        override suspend fun isExportStillApproved(claim: OutboxClaim): Boolean = sessions[claim.export.sessionId]?.let {
            claim.export in exports && claim.export.requiredField in it.consent.exportFields && claim.export.consentRevision == it.consent.exportRevision
        } ?: false
        override suspend fun acknowledgeOutbox(claim: OutboxClaim, receipt: DeliveryReceipt) { exports.remove(claim.export); claimedExports.remove(claim.export.exportId) }
        override suspend fun failOutbox(claim: OutboxClaim, error: AppError, nowMs: Long) { claimedExports.remove(claim.export.exportId) }
        override suspend fun revokeExportConsent(sessionId: SessionId, expectedConsentRevision: Long, nowMs: Long): SessionSnapshot {
            val old = snapshot(sessionId)
            if (old.consent.exportRevision != expectedConsentRevision) fail(ErrorCode.STALE_REVISION)
            val next = old.copy(stateVersion = old.stateVersion + 1, consent = old.consent.copy(exportFields = emptySet(), exportRevision = old.consent.exportRevision + 1))
            sessions[sessionId] = next
            checkpoints[sessionId] = checkpoints.getValue(sessionId).copy(stateVersion = next.stateVersion)
            exports.removeAll { it.sessionId == sessionId }
            return next
        }
        override suspend fun deleteSession(sessionId: SessionId, nowMs: Long): DeletionReceipt {
            tombstones += sessionId
            val old = sessions.remove(sessionId)
            checkpoints.remove(sessionId)
            jobs.entries.removeAll { it.value.sessionId == sessionId }
            actions.entries.removeAll { it.value.sessionId == sessionId }
            exports.removeAll { it.sessionId == sessionId }
            return DeletionReceipt(listOf(sessionId), old?.clips?.map { it.clip.privatePath } ?: emptyList())
        }
        override suspend fun deleteProfile(profileId: ProfileId, nowMs: Long): DeletionReceipt {
            val ids = sessions.values.filter { it.profileId == profileId }.map { it.sessionId }
            ids.forEach { deleteSession(it, nowMs) }; profiles.remove(profileId)
            return DeletionReceipt(ids, emptyList())
        }
        override suspend fun pendingFileCleanup(): List<String> = emptyList()
        override suspend fun acknowledgeFileCleanup(privatePath: String) = Unit

        fun seedComparison(phase: Phase = Phase.COMPARING): SessionId {
            val id = SessionId.new()
            val clip = CompletedLocalClip(id, ClipId.new(), "/tmp/test-only-not-recorded.wav", "a".repeat(64), 20.0, DataOrigin.CONSENTED_DEMO, createdAtMs = 1)
            val state = SessionSnapshot(sessionId = id, profileId = profile.profileId, phase = phase,
                executionMode = ExecutionMode.REAL_ON_DEVICE, dataOrigin = DataOrigin.CONSENTED_DEMO, createdAtMs = 100, updatedAtMs = 100,
                consent = ConsentState(recording = true), metrics = metrics(), captureFinished = true, summaryVersion = 1,
                clips = listOf(StoredClip(clip, ClipReceipt(id, clip.clipId, clip.sha256, 1), testAudio(clip), audioDeleted = true)))
            sessions[id] = state
            summaries += SessionSummary(id, profile.profileId, 1, 0, state.task, state.dataOrigin, state.metrics!!, 110, listOf(clip.clipId))
            checkpoints[id] = AgentCheckpoint(sessionId = id, profileId = profile.profileId, inputRevision = 0, stateVersion = 0,
                phase = phase, task = state.task, dataOrigin = state.dataOrigin, metrics = state.metrics)
            if (phase == Phase.COMPARING) {
                val job = JobRecord(JobId.new(), id, 0, JobKind.ADVANCE_WORKFLOW, createdAtMs = 1, updatedAtMs = 1, stepOrdinal = 1)
                jobs[job.jobId] = job
            }
            return id
        }
        fun seedHistory() { repeat(2) { summaries += SessionSummary(SessionId.new(), profile.profileId, 1, 0, RecordingTask.CHECK_IN,
            DataOrigin.CONSENTED_DEMO, metrics(), it.toLong() + 1, listOf(ClipId.new())) } }
    }
}
