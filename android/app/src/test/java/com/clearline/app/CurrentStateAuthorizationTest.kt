package com.clearline.app

import com.clearline.core.*
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CurrentStateAuthorizationTest {
    @Test fun researchUsesCurrentResearchScopeInsteadOfGlobalInputRevision() = runBlocking {
        val f = Fixture()
        assertTrue(f.current.inputRevision != f.request.inputRevision)
        f.authorization.requireResearch(f.request)
        assertDenied { f.authorization.requireResearch(f.request.copy(city = "Oakland")) }
        listOf(
            f.initial.copy(researchRevision = f.request.inputRevision + 1),
            f.initial.copy(phase = Phase.PAUSED),
            f.initial.copy(pauseRequested = true),
        ).forEach { state ->
            f.session = state
            assertDenied { f.authorization.requireResearch(f.request) }
        }
        assertTrue(f.reads.all { it == "getSession" })
    }

    @Test fun approvedResearchCannotReuseAChangedTranscript() = runBlocking {
        val f = Fixture()
        f.authorization.requireResearch(f.request)
        val correctedClip = f.clip.copy(result = f.clip.result!!.copy(transcript = "I enjoyed a walk today."))
        f.session = f.initial.copy(clips = listOf(correctedClip))
        assertDenied { f.authorization.requireResearch(f.request) }
    }

    @Test fun extractionRequiresExactMembershipInTheCurrentSearch() = runBlocking {
        val f = Fixture()
        f.authorization.requireSource(ApprovedSource(f.request, f.candidate))
        assertDenied {
            f.authorization.requireSource(ApprovedSource(f.request, f.candidate.copy(url = "https://example.org/other")))
        }
        assertDenied {
            f.authorization.requireSource(ApprovedSource(f.request, f.candidate.copy(description = "Uncommitted source text")))
        }
        f.session = f.initial.copy(resources = emptyList())
        assertDenied { f.authorization.requireSource(ApprovedSource(f.request, f.candidate)) }
    }

    @Test fun textExportRequiresItsCurrentGrantAndTheExactReviewedValues() = runBlocking {
        val f = Fixture()
        val withText = f.measurement.copy(transcriptSnippet = "I feel lonely today.", topKeyword = "loneliness")
        f.session = f.initial.copy(consent = f.initial.consent.copy(
            exportFields = setOf(ExportField.MEASUREMENTS, ExportField.TRANSCRIPT_SNIPPET),
            reviewedTranscriptSnippet = withText.transcriptSnippet, reviewedKeyword = withText.topKeyword,
        ))
        f.authorization.requireExport(f.export(withText))
        assertDenied { f.authorization.requireExport(f.export(withText.copy(transcriptSnippet = "Another sentence."))) }
        assertDenied { f.authorization.requireExport(f.export(withText.copy(topKeyword = "memory"))) }
        assertDenied { f.authorization.requireExport(f.export(withText).copy(consentRevision = 2)) }
        f.session = f.current.copy(consent = f.current.consent.copy(exportFields = setOf(ExportField.MEASUREMENTS)))
        assertDenied { f.authorization.requireExport(f.export(withText)) }
        // Revoking text does not prevent an otherwise approved measurements-only projection.
        f.authorization.requireExport(f.export())
    }

    @Test fun measurementExportMustMatchTheCommittedSummaryAndItsChronology() = runBlocking {
        val f = Fixture()
        f.authorization.requireExport(f.export())
        listOf(
            f.measurement.copy(summaryVersion = 2),
            f.measurement.copy(metrics = f.metrics.copy(recordingWpm = 121.0)),
            f.measurement.copy(sessionCreatedAtMs = f.initial.createdAtMs + 1),
            f.measurement.copy(completedAtMs = f.summary.completedAtMs + 1),
            f.measurement.copy(completedAtMs = null),
        ).forEach { projection -> assertDenied { f.authorization.requireExport(f.export(projection)) } }
        f.session = f.initial.copy(metrics = f.metrics.copy(energyRms = 0.2))
        assertDenied { f.authorization.requireExport(f.export()) }
        f.session = f.initial
        f.history = listOf(f.summary.copy(metrics = f.metrics.copy(wordCount = 41)))
        assertDenied { f.authorization.requireExport(f.export()) }
        f.history = emptyList()
        assertDenied { f.authorization.requireExport(f.export()) }
        assertTrue("Committed history must actually be read", "observeHistory" in f.reads)
    }

    @Test fun deletingTheSessionImmediatelyDeniesEveryDispatchPort() = runBlocking {
        val f = Fixture()
        f.authorization.requireResearch(f.request)
        f.session = null
        assertDenied { f.authorization.requireResearch(f.request) }
        assertDenied { f.authorization.requireSource(ApprovedSource(f.request, f.candidate)) }
        assertDenied { f.authorization.requireExport(f.export()) }
        assertDenied { f.authorization.requireHistory(BoundedHistoryQuery(f.initial.sessionId)) }
        assertDenied { f.authorization.requireMemory(f.memoryQuery) }
    }

    @Test fun historyTextReadsRequireTheCurrentTextSelection() = runBlocking {
        val f = Fixture()
        val query = BoundedHistoryQuery(f.initial.sessionId, field = ExportField.TRANSCRIPT_SNIPPET)
        f.authorization.requireHistory(query.copy(field = ExportField.MEASUREMENTS))
        assertDenied { f.authorization.requireHistory(query) }
        f.session = f.initial.copy(consent = f.initial.consent.copy(exportFields = setOf(ExportField.TRANSCRIPT_SNIPPET)))
        f.authorization.requireHistory(query)
        f.session = f.initial
        assertDenied { f.authorization.requireHistory(query) }
    }

    @Test fun exportedMemoryRequiresMatchingProfileProvenanceMethodsAndTimeWindow() = runBlocking {
        val f = Fixture()
        f.authorization.requireMemory(f.memoryQuery)
        listOf(
            f.memoryQuery.copy(profileId = ProfileId(id(9))),
            f.memoryQuery.copy(dataOrigin = DataOrigin.SYNTHETIC),
            f.memoryQuery.copy(measurementVersion = "different-acoustic-method"),
            f.memoryQuery.copy(lexicalVersion = "different-word-counting"),
            f.memoryQuery.copy(beforeCreatedAtMs = f.initial.createdAtMs + 1),
        ).forEach { query -> assertDenied { f.authorization.requireMemory(query) } }
        f.session = f.initial.copy(metrics = null)
        assertDenied { f.authorization.requireMemory(f.memoryQuery) }
    }

    private suspend fun assertDenied(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected current authorization to reject this dispatch")
        } catch (failure: ClearLineException) {
            assertEquals(ErrorCode.CONSENT_REQUIRED, failure.error.code)
        }
    }

    private class Fixture {
        private val sessionId = SessionId(id(1))
        private val profileId = ProfileId(id(2))
        private val clipId = ClipId(id(3))
        val metrics = RecordingMetrics(20.0, 40, 120.0, 0.1, dataOrigin = DataOrigin.CONSENTED_DEMO)
        private val completedClip = CompletedLocalClip(sessionId, clipId, "/private/demo.wav", "a".repeat(64), 20.0, DataOrigin.CONSENTED_DEMO, createdAtMs = 1_001)
        val clip = StoredClip(completedClip, ClipReceipt(sessionId, clipId, completedClip.sha256, 1_025),
            AudioResult(clipId, "I feel lonely today.", metrics, ModelId("fixture-whisper"), 1_030))
        val request = ApprovedResourceRequest(sessionId, 2, ApprovalId(id(4)), ResourceCategory.CAREGIVER_SUPPORT,
            "Berkeley", 1_050, CallInsights.buildQuery(clip.result!!.transcript, "Berkeley", ResourceCategory.CAREGIVER_SUPPORT))
        val candidate = ResourceCandidate("candidate-1", "Public support", "https://example.org/support", "Public description", "search-1", 1_060)
        val initial = SessionSnapshot(sessionId = sessionId, profileId = profileId, phase = Phase.RESEARCHING,
            inputRevision = 9, executionMode = ExecutionMode.REAL_ON_DEVICE, dataOrigin = DataOrigin.CONSENTED_DEMO,
            createdAtMs = 1_000, updatedAtMs = 1_060,
            consent = ConsentState(true, setOf(ExportField.MEASUREMENTS), exportRevision = 3, updatedAtMs = 900),
            clips = listOf(clip), metrics = metrics, resources = listOf(candidate), approvedResources = request,
            researchRevision = 2, captureFinished = true, summaryVersion = 1)
        val summary = SessionSummary(sessionId, profileId, 1, 9, RecordingTask.CHECK_IN, DataOrigin.CONSENTED_DEMO,
            metrics, completedAtMs = 1_040, clipIds = listOf(clipId))
        val measurement = ExportProjection.Measurements(1, metrics, sessionCreatedAtMs = initial.createdAtMs, completedAtMs = summary.completedAtMs)
        val memoryQuery = MemoryQuery(profileId, sessionId, initial.dataOrigin, initial.task,
            metrics.measurementVersion, metrics.lexicalVersion, initial.createdAtMs)
        var session: SessionSnapshot? = initial
        val current: SessionSnapshot get() = requireNotNull(session)
        var history = listOf(summary)
        val reads = mutableListOf<String>()

        // No fake mutation implementations: any write/claim/recovery attempt fails the test.
        private val store = Proxy.newProxyInstance(WorkflowStore::class.java.classLoader, arrayOf(WorkflowStore::class.java)) { _, method, args ->
            when (val operation = method.name.substringBefore('-')) {
                "getSession" -> { assertEquals(sessionId.value, args!![0]); reads += operation; session }
                "observeHistory" -> { assertEquals(profileId.value, args!![0]); reads += operation; flowOf(history) }
                else -> throw AssertionError("Authorization attempted non-read operation: $operation")
            }
        } as WorkflowStore
        val authorization = CurrentStateAuthorization(store)

        fun export(projection: ExportProjection.Measurements = measurement) = ApprovedExport(
            ExportId(id(5)), profileId, sessionId, initial.inputRevision, initial.consent.exportRevision,
            initial.dataOrigin, 1_070, projection,
        )
    }

    companion object {
        private fun id(value: Long): String = UUID(0, value).toString()
    }
}
