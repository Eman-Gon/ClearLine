package com.clearline.sponsors

import com.clearline.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SponsorLiveSmokeTest {
    @Test fun commandsRequireExactExplicitOptIn() {
        assertEquals(SmokeCommand.CONFIG, smokeCommand(emptyList()))
        assertEquals(SmokeCommand.CONFIG, smokeCommand(listOf("config")))
        assertEquals(SmokeCommand.NIMBLE, smokeCommand(listOf("nimble-search", "--allow-live-search")))
        assertEquals(SmokeCommand.NIMBLE_ROUNDTRIP, smokeCommand(listOf("nimble-roundtrip", "--allow-live-search", "--allow-live-extract")))
        assertEquals(SmokeCommand.RAWTREE, smokeCommand(listOf("rawtree-roundtrip", "--allow-synthetic-write")))
        assertEquals(SmokeCommand.RAWTREE_MEMORY, smokeCommand(listOf("rawtree-memory", "--allow-synthetic-write")))
        listOf(listOf("nimble-search"), listOf("rawtree-roundtrip"), listOf("--allow-synthetic-write"),
            listOf("nimble-roundtrip", "--allow-live-search"), listOf("config", "--allow-live-search"),
            listOf("rawtree-memory"), listOf("rawtree-roundtrip", "--allow-synthetic-write", "--all-history")).forEach { args ->
            assertThrows(ClearLineException::class.java) { smokeCommand(args) }
        }
    }

    @Test fun configurationChecksDoNotEchoOrAcceptMalformedSecrets() {
        assertEquals(ConfigState.MISSING, tokenState(null))
        assertEquals(ConfigState.PRESENT, tokenState("dummy-token"))
        assertEquals(ConfigState.INVALID, tokenState("token\nheader"))
        assertEquals(ConfigState.INVALID, tokenState("a".repeat(4097)))
        assertEquals(ConfigState.PRESENT, databaseState("test_database-1"))
        assertEquals(ConfigState.INVALID, databaseState("test; DROP TABLE clearline_events"))
    }

    @Test fun queryUsesActualBuilderWithoutSendingTranscript() {
        val request = SmokeFixture.research(1)
        assertEquals("everyday forgetfulness memory support caregiver support groups near San Francisco CA", request.queryDraft!!.query)
        assertEquals(CallConcern.MEMORY, request.queryDraft.basis.concern)
        assertFalse(request.queryDraft.query.contains(SmokeFixture.TRANSCRIPT))
    }

    @Test fun exportAuthorizationOnlyAllowsExactSyntheticFixtureAndBoundedReadback() = runTest {
        val event = SmokeFixture.event(100)
        val auth = SmokeAuthorization(export = event)
        val query = SmokeFixture.query(event)
        denied { auth.requireHistory(query) }
        denied { auth.requireExport(event.copy(inputRevision = 1)) }
        denied { auth.requireExport(event.copy(dataOrigin = DataOrigin.CONSENTED_DEMO)) }
        auth.requireExport(event)
        denied { auth.requireExport(event) }
        denied { auth.requireHistory(query.copy(exportId = null)) }
        denied { auth.requireHistory(query.copy(limit = 2)) }
        repeat(3) { auth.requireHistory(query) }
        denied { auth.requireHistory(query) }
        denied { auth.requireResearch(SmokeFixture.research(100)) }
    }

    @Test fun sourceNeedsExactCompletedSearchSelectionAndOneExtraction() = runTest {
        val request = SmokeFixture.research(100)
        val candidate = ResourceCandidate("test", "Test", "https://example.org/", null, null, 100)
        val selected = ApprovedSource(request, candidate)
        val auth = SmokeAuthorization(research = request)
        denied { auth.requireSource(selected) }
        denied { auth.requireResearch(request.copy(city = "Other city")) }
        auth.requireResearch(request)
        auth.requireResearch(request)
        denied { auth.requireResearch(request) }
        auth.selectSource(candidate)
        denied { auth.requireSource(selected.copy(candidate = candidate.copy(url = "https://other.example/"))) }
        auth.requireSource(selected)
        auth.requireSource(selected)
        denied { auth.requireSource(selected) }
        auth.close()
        denied { auth.requireResearch(request) }
    }

    @Test fun credentialsAreProviderScopedAndTemporaryBytesWiped() = runTest {
        val credentials = SmokeCredentials(Sponsor.NIMBLE, "dummy-token")
        var copy: ByteArray? = null
        credentials.withCredential(Sponsor.NIMBLE) { token, requireCurrent -> copy = token; requireCurrent(); assertEquals("dummy-token", token.toString(Charsets.UTF_8)) }
        assertTrue(copy!!.all { it == 0.toByte() })
        denied { credentials.withCredential(Sponsor.RAWTREE) { _, _ -> } }
        credentials.close()
        denied { credentials.withCredential(Sponsor.NIMBLE) { _, _ -> } }
    }

    @Test fun readbackRequiresFullEventNotOnlyMatchingExportId() {
        val event = SmokeFixture.event(100)
        val record = ExportedHistoryRecord(event.exportId, event.sessionId.value, event.inputRevision, event.dataOrigin,
            event.createdAtMs, ExportedHistoryProjection.Event(event.projection as ExportProjection.Event))
        assertTrue(SmokeFixture.matches(record, event))
        assertFalse(SmokeFixture.matches(record.copy(createdAtMs = 101), event))
        assertFalse(SmokeFixture.matches(record.copy(dataOrigin = DataOrigin.CONSENTED_DEMO), event))
    }

    @Test fun memoryFixtureGrantsOnlyFourExactWritesThenNineScopedQueries() = runTest {
        val fixture = SmokeMemoryFixture(1_000_000_000_000)
        val auth = SmokeMemoryAuthorization(fixture)
        denied { auth.requireMemory(fixture.query) }
        denied { auth.requireExport(fixture.exports.first().copy(dataOrigin = DataOrigin.CONSENTED_DEMO)) }
        for (event in fixture.exports) auth.requireExport(event)
        denied { auth.requireMemory(fixture.query) }
        denied { auth.requireExport(fixture.exports.first()) }
        auth.requireExport(fixture.replay)
        denied { auth.requireExport(fixture.replay) }
        denied { auth.requireMemory(fixture.query.copy(profileId = ProfileId.new())) }
        denied { auth.requireMemory(fixture.query.copy(currentSessionId = SessionId.new())) }
        denied { auth.requireHistory(SmokeFixture.query(fixture.replay)) }
        repeat(9) { auth.requireMemory(fixture.query) }
        denied { auth.requireMemory(fixture.query) }
    }

    @Test fun memoryValidationRequiresThreeLogicalSessionsAndExactMetrics() {
        val fixture = SmokeMemoryFixture(1_000_000_000_000)
        val sessions = fixture.exports.map { event ->
            val value = event.projection as ExportProjection.Measurements
            assertNull(value.transcriptSnippet)
            assertNull(value.topKeyword)
            assertEquals(DataOrigin.SYNTHETIC, value.metrics.dataOrigin)
            MemorySession(event.sessionId, event.profileId, value.sessionCreatedAtMs!!, 1, 0,
                DataOrigin.SYNTHETIC, value.task, value.metrics, completedAtMs = value.completedAtMs)
        }
        val snapshot = RawTreeMemoryMath.snapshot(fixture.query, sessions.take(2), 3, 3,
            1_000_000_000_000, RawTreeMemoryDiagnostics(returnedRows = 3), sessions.last())
        assertTrue(fixture.matches(snapshot))
        assertFalse(fixture.matches(snapshot.copy(dataPointCount = 4, sessionCount = 4)))
        assertFalse(fixture.matches(snapshot.copy(meanRecordingWpm = 125.0)))
        assertFalse(fixture.matches(snapshot.copy(currentSession = null)))
        assertFalse(fixture.matches(snapshot.copy(cached = true)))
    }

    @Test fun shapeDiagnosticsIncludeOnlyKnownFieldLengths() {
        val shape = nimbleResponseShape(buildJsonObject {
            put("request_id", "secret-request")
            put("private_field", "private-value")
            put("results", buildJsonArray { add(buildJsonObject {
                put("title", "secret-title")
                put("url", "https://example.org/private")
                put("description", "x".repeat(4_001))
            }) })
        })
        assertTrue(shape.contains("results[0].description=string(4001)"))
        assertFalse(shape.contains("secret"))
        assertFalse(shape.contains("private"))
        assertFalse(shape.contains("example.org"))
    }

    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("Expected explicit fixture authorization rejection") }
        catch (error: ClearLineException) { assertEquals(ErrorCode.CONSENT_REQUIRED, error.error.code) }
    }
}
