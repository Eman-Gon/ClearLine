package com.clearline.sponsors

import com.clearline.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RawTreeHistoryClientTest {
    private class Gate : SponsorAuthorization {
        var allowed = false
        var expectedRevision = 2L
        var approved: ApprovedExport? = null
        override suspend fun requireResearch(request: ApprovedResourceRequest) = Unit
        override suspend fun requireSource(source: ApprovedSource) = Unit
        override suspend fun requireHistory(request: BoundedHistoryQuery) = Unit
        override suspend fun requireMemory(request: MemoryQuery) {
            if (!allowed) sponsorFailure(ErrorCode.CONSENT_REQUIRED, "Memory read not approved.")
        }
        override suspend fun requireExport(event: ApprovedExport) {
            approved = event
            if (!allowed) sponsorFailure(ErrorCode.CONSENT_REQUIRED, "Export not approved.")
            if (event.consentRevision != expectedRevision) sponsorFailure(ErrorCode.STALE_REVISION, "Consent changed.")
        }
    }

    private class Http : SponsorHttp {
        var beforeDispatch: suspend () -> Unit = {}
        var response = buildJsonObject { put("inserted", 1) }
        val sent = mutableListOf<JsonObject>()
        val paths = mutableListOf<String>()
        var selectedDatabase: String? = null
        override suspend fun post(service: Sponsor, path: String, body: JsonObject, database: String?, authorize: suspend () -> Unit): JsonObject {
            assertEquals(Sponsor.RAWTREE, service)
            beforeDispatch()
            authorize()
            paths += path
            selectedDatabase = database
            sent += body
            return response
        }
    }

    private fun event(origin: DataOrigin = DataOrigin.SYNTHETIC, projection: ExportProjection = ExportProjection.Event(ActionId.new().value, ExportEventName.ACTION_COMPLETED, ExportEventStatus.COMPLETED, 100)) = ApprovedExport(
        ExportId.new(), ProfileId.new(), SessionId.new(), 1, 2, origin, 100, projection,
    )

    private fun client(http: Http, gate: Gate, configuration: suspend () -> SponsorConfiguration = { SponsorConfiguration(rawTreeEnabled = true, rawTreeDatabase = "clearline_demo") }) =
        RawTreeHistoryClient(http, gate, configuration) { 500 }

    @Test fun syntheticAndLiveExportsBothNeedCurrentApproval() = runTest {
        for (origin in DataOrigin.entries) {
            val http = Http()
            val gate = Gate()
            val failure = failure { client(http, gate).append(event(origin)) }
            assertEquals(ErrorCode.CONSENT_REQUIRED, failure.error.code)
            assertTrue(http.sent.isEmpty())
        }
    }

    @Test fun sendsOnlySelectedProjectionAndStableIdentityOnReplay() = runTest {
        val http = Http()
        val gate = Gate().apply { allowed = true }
        val input = event()
        val adapter = client(http, gate)
        val receipt = adapter.append(input)
        adapter.append(input)
        assertEquals(listOf("/v1/tables/clearline_events", "/v1/tables/clearline_events"), http.paths)
        assertEquals("clearline_demo", http.selectedDatabase)
        assertEquals(http.sent[0], http.sent[1])
        assertEquals(input.exportId, receipt.exportId)
        assertEquals(500L, receipt.deliveredAtMs)
        val text = http.sent[0].toString()
        assertFalse(text.contains(input.profileId.value))
        assertEquals(input.sessionId.value, http.sent[0]["session_ref"]!!.jsonPrimitive.content)
        assertFalse(text.contains("consent_revision"))
        assertEquals(setOf("schema_version", "export_id", "session_ref", "profile_ref", "input_revision", "data_origin", "created_at_ms", "projection_type", "event_id", "event_name", "event_status", "timestamp_ms"), http.sent[0].keys)
    }

    @Test fun revokedOrChangedConsentAtDispatchDoesNotSend() = runTest {
        for (revoke in listOf(true, false)) {
            val http = Http()
            val gate = Gate().apply { allowed = true }
            http.beforeDispatch = { if (revoke) gate.allowed = false else gate.expectedRevision++ }
            val failure = failure { client(http, gate).append(event()) }
            assertEquals(if (revoke) ErrorCode.CONSENT_REQUIRED else ErrorCode.STALE_REVISION, failure.error.code)
            assertTrue(http.sent.isEmpty())
        }
    }

    @Test fun changedDatabaseAtDispatchDoesNotSendToOldDestination() = runTest {
        val http = Http()
        val gate = Gate().apply { allowed = true }
        var database = "clearline_demo"
        http.beforeDispatch = { database = "different_database" }
        val adapter = client(http, gate) { SponsorConfiguration(rawTreeEnabled = true, rawTreeDatabase = database) }
        assertEquals(ErrorCode.STALE_REVISION, failure { adapter.append(event()) }.error.code)
        assertTrue(http.sent.isEmpty())
    }

    @Test fun callerMutableCollectionsCannotChangeApprovedDispatch() = runTest {
        val reasons = mutableListOf<String>()
        val metrics = RecordingMetrics(20.0, 40, 120.0, .1, qualityReasons = reasons, dataOrigin = DataOrigin.SYNTHETIC)
        val http = Http()
        val gate = Gate().apply { allowed = true }
        http.beforeDispatch = { reasons += "private transcript canary" }
        client(http, gate).append(event(projection = ExportProjection.Measurements(1, metrics)))
        assertFalse(http.sent.single().toString().contains("private transcript canary"))
        assertTrue((gate.approved!!.projection as ExportProjection.Measurements).metrics.qualityReasons.isEmpty())
        assertEquals(JsonNull, http.sent.single()["metrics"]!!.jsonObject["pause_count"])
    }

    @Test fun unapprovedFreeTextMetadataIsRejectedBeforeHttp() = runTest {
        val invalidMetrics = RecordingMetrics(20.0, 40, 120.0, .1, qualityReasons = listOf("private transcript canary"), dataOrigin = DataOrigin.SYNTHETIC)
        val http = Http()
        val gate = Gate().apply { allowed = true }
        assertEquals(ErrorCode.INVALID_INPUT, failure { client(http, gate).append(event(projection = ExportProjection.Measurements(1, invalidMetrics))) }.error.code)
        assertTrue(http.sent.isEmpty())
    }

    @Test fun publicEvidenceExportsOnlySupportedPublicFields() = runTest {
        val evidence = SourceEvidence(EvidenceId.new(), 1, ApprovalId.new(), 1, "Local display title", "Local display description", "https://example.org/support", 123, "a".repeat(64), listOf(SupportingPassage("passage-1", "Phone: (555) 123-4567")), PublicFacts(phone = SourcedFact("(555) 123-4567", listOf("passage-1"))))
        val http = Http()
        val gate = Gate().apply { allowed = true }
        client(http, gate).append(event(projection = ExportProjection.PublicResource(evidence)))
        val body = http.sent.single()
        assertEquals("/v1/tables/clearline_resource_versions", http.paths.single())
        assertEquals(JsonNull, body["facts"]!!.jsonObject["address"])
        assertFalse(body.toString().contains(evidence.approvalId.value))
        assertEquals("Local display title", body["title"]!!.jsonPrimitive.content)
        assertEquals("Local display description", body["description"]!!.jsonPrimitive.content)
        val invented = evidence.copy(facts = PublicFacts(phone = SourcedFact("(555) 000-0000", listOf("passage-1"))))
        assertEquals(ErrorCode.INVALID_INPUT, failure { client(http, gate).append(event(projection = ExportProjection.PublicResource(invented))) }.error.code)
    }

    @Test fun invalidInsertResponseIsNotSuccess() = runTest {
        val http = Http().apply { response = buildJsonObject { put("inserted", 0) } }
        val gate = Gate().apply { allowed = true }
        assertEquals(ErrorCode.BAD_RESPONSE, failure { client(http, gate).append(event()) }.error.code)
    }

    @Test fun boundedHistoryDeduplicatesAndRejectsWrongSession() = runTest {
        val input = event()
        val http = Http()
        val gate = Gate().apply { allowed = true }
        val encoded = RawTreeExportWire.encode(input)
        http.response = buildJsonObject { put("data", JsonArray(listOf(buildJsonObject { put("payload", encoded) }, buildJsonObject { put("payload", encoded) }))) }
        val result = client(http, gate).query(BoundedHistoryQuery(input.sessionId, limit = 5))
        assertEquals(1, result.records.size)
        val sql = http.sent.single()["sql"]!!.jsonPrimitive.content
        assertTrue(sql.contains("row_number() OVER (PARTITION BY event_id"))
        assertTrue(sql.endsWith("LIMIT 5"))
        assertFalse(sql.contains(input.profileId.value))
        assertEquals(ErrorCode.BAD_RESPONSE, failure { client(http, gate).query(BoundedHistoryQuery(SessionId.new())) }.error.code)
    }

    @Test fun measurementHistoryRedactsSnippetUnlessSeparatelyRequested() = runTest {
        val metrics = RecordingMetrics(20.0, 40, 120.0, .1, dataOrigin = DataOrigin.SYNTHETIC)
        val input = event(projection = ExportProjection.Measurements(1, metrics, "Explicitly approved snippet", "sleep"))
        val row = buildJsonObject { put("payload", RawTreeExportWire.encode(input)) }
        val normal = RawTreeExportWire.decode(row, BoundedHistoryQuery(input.sessionId, field = ExportField.MEASUREMENTS))
        assertNull((normal.projection as ExportedHistoryProjection.Measurements).value.transcriptSnippet)
        val selected = RawTreeExportWire.decode(row, BoundedHistoryQuery(input.sessionId, field = ExportField.TRANSCRIPT_SNIPPET))
        assertEquals("Explicitly approved snippet", (selected.projection as ExportedHistoryProjection.Measurements).value.transcriptSnippet)
    }

    @Test fun memoryAcceptsUnavailableMeasurementsOmittedByJsonStorage() {
        val metrics = RecordingMetrics(20.0, 40, 120.0, .1, dataOrigin = DataOrigin.SYNTHETIC)
        val input = event(projection = ExportProjection.Measurements(1, metrics, sessionCreatedAtMs = 10, completedAtMs = 30))
        val encoded = RawTreeExportWire.encode(input)
        val storedMetrics = JsonObject(encoded.getValue("metrics").jsonObject.filterKeys { it !in setOf("pause_count", "pitch_mean_hz") })
        val stored = JsonObject(encoded + ("metrics" to storedMetrics))
        val query = MemoryQuery(input.profileId, SessionId.new(), input.dataOrigin, RecordingTask.CHECK_IN, metrics.measurementVersion, metrics.lexicalVersion, 1000)
        val result = RawTreeExportWire.decodeMemory(buildJsonObject { put("payload", stored) }, query)
        assertEquals(metrics, result.metrics)
        assertNull(result.metrics.pauseCount)
        assertNull(result.metrics.pitchMeanHz)
        assertEquals(10L, result.timestampMs)
        assertEquals(30L, result.completedAtMs)
    }

    @Test fun unexpectedMeasuredPitchOrPausesStillRejectInsteadOfBecomingUnknown() = runTest {
        val metrics = RecordingMetrics(20.0, 40, 120.0, .1, dataOrigin = DataOrigin.SYNTHETIC)
        val input = event(projection = ExportProjection.Measurements(1, metrics))
        val encoded = RawTreeExportWire.encode(input)
        for (field in listOf("pause_count", "pitch_mean_hz")) {
            val storedMetrics = JsonObject(encoded.getValue("metrics").jsonObject + (field to JsonPrimitive(0)))
            val stored = JsonObject(encoded + ("metrics" to storedMetrics))
            assertEquals(ErrorCode.BAD_RESPONSE, failure {
                RawTreeExportWire.decode(buildJsonObject { put("payload", stored) }, BoundedHistoryQuery(input.sessionId, field = ExportField.MEASUREMENTS))
            }.error.code)
        }
    }

    @Test fun memorySqlCountsSeparatelyAndSelectsLatestBeforeEligibilityAndLimit() {
        val query = MemoryQuery(ProfileId.new(), SessionId.new(), DataOrigin.SYNTHETIC, RecordingTask.CHECK_IN, "android-pcm-v1", "english-lexical-v1", MemoryQuery.WINDOW_DURATION_MS + 1000)
        val sql = RawTreeExportWire.memorySql(query)
        assertTrue(sql.counts.contains("uniqExact(session_ref) AS data_point_count"))
        assertFalse(sql.counts.contains("uniqExact(export_id)"))
        assertTrue(sql.counts.contains("uniqExact(session_ref)"))
        assertFalse(sql.counts.contains("LIMIT"))
        assertTrue(sql.previous.contains("ORDER BY summary_version DESC, input_revision DESC"))
        assertTrue(sql.previous.contains(")\nWHERE latest_rank = 1 AND data_origin = 'synthetic'"))
        assertTrue(sql.previous.contains("session_created_at_ms >= 1000"))
        assertTrue(sql.previous.contains("completed_at_ms < ${query.beforeCreatedAtMs}"))
        assertTrue(sql.previous.endsWith("LIMIT 8"))
        assertTrue(sql.current.contains("session_ref = '${query.currentSessionId.value}'"))
        assertTrue(sql.current.contains("completed_at_ms >= session_created_at_ms"))
    }

    @Test fun unavailableMemoryDoesNotInventZeroCounts() = runTest {
        val query = MemoryQuery(ProfileId.new(), SessionId.new(), DataOrigin.SYNTHETIC, RecordingTask.CHECK_IN, "android-pcm-v1", "english-lexical-v1", 1000)
        val http = Http()
        val gate = Gate()
        val result = client(http, gate).memory(query)
        assertEquals(RawTreeMemoryStatus.UNAVAILABLE, result.status)
        assertNull(result.dataPointCount)
        assertNull(result.sessionCount)
        assertEquals(ErrorCode.CONSENT_REQUIRED, result.diagnostics.error)
        assertTrue(http.sent.isEmpty())
    }

    @Test fun memoryReturnsExactProviderCountsAndBoundedPreviousAndCurrent() = runTest {
        val cutoff = MemoryQuery.WINDOW_DURATION_MS + 10_000L
        val query = MemoryQuery(ProfileId.new(), SessionId.new(), DataOrigin.SYNTHETIC, RecordingTask.CHECK_IN, "android-pcm-v1", "english-lexical-v1", cutoff)
        fun row(sessionId: SessionId, timestamp: Long, wpm: Double): JsonObject {
            val metrics = RecordingMetrics(20.0, 40, wpm, .1, dataOrigin = DataOrigin.SYNTHETIC)
            val input = event(projection = ExportProjection.Measurements(1, metrics, sessionCreatedAtMs = timestamp, completedAtMs = timestamp, task = query.task)).copy(profileId = query.profileId, sessionId = sessionId, createdAtMs = timestamp + 100)
            return buildJsonObject { put("payload", RawTreeExportWire.encode(input)) }
        }
        val prior = (0 until 8).map { row(SessionId.new(), cutoff - (it + 1) * 1000, 100.0 + it) }
        val current = row(query.currentSessionId, cutoff, 115.0)
        val http = Http()
        val responses = ArrayDeque(listOf(
            buildJsonObject { put("data", JsonArray(listOf(buildJsonObject { put("data_point_count", "20"); put("session_count", "20") }))) },
            buildJsonObject { put("data", JsonArray(prior)) },
            buildJsonObject { put("data", JsonArray(listOf(current))) },
        ))
        http.beforeDispatch = { http.response = responses.removeFirst() }
        val result = client(http, Gate().apply { allowed = true }).memory(query)
        assertEquals(RawTreeMemoryStatus.AVAILABLE, result.status)
        assertEquals(20L, result.dataPointCount)
        assertEquals(20L, result.sessionCount)
        assertEquals(8, result.previousSessions.size)
        assertEquals(query.currentSessionId, result.currentSession!!.sessionId)
        assertEquals(103.5, result.meanRecordingWpm!!, .000001)
        assertEquals(9, result.diagnostics.returnedRows)
        assertEquals(3, http.sent.size)
        assertTrue(http.sent.none { it.toString().contains(query.profileId.value) })
        assertTrue(result.diagnostics.latencyMs!! >= 0)
    }

    @Test fun memoryNeverSubstitutesExportTimeForMissingSessionChronology() = runTest {
        val metrics = RecordingMetrics(20.0, 40, 120.0, .1, dataOrigin = DataOrigin.SYNTHETIC)
        val input = event(projection = ExportProjection.Measurements(1, metrics)).copy(createdAtMs = 999)
        val encoded = RawTreeExportWire.encode(input)
        assertEquals(JsonNull, encoded["session_created_at_ms"])
        assertEquals(JsonNull, encoded["completed_at_ms"])
        val query = MemoryQuery(input.profileId, SessionId.new(), input.dataOrigin, RecordingTask.CHECK_IN, "android-pcm-v1", "english-lexical-v1", 1000)
        val row = buildJsonObject { put("payload", encoded) }
        val history = RawTreeExportWire.decode(row, BoundedHistoryQuery(input.sessionId, field = ExportField.MEASUREMENTS))
        assertNull((history.projection as ExportedHistoryProjection.Measurements).value.sessionCreatedAtMs)
        assertEquals(ErrorCode.BAD_RESPONSE, failure { RawTreeExportWire.decodeMemory(row, query) }.error.code)
    }

    private suspend fun failure(block: suspend () -> Unit): ClearLineException {
        try { block() } catch (error: ClearLineException) { return error }
        throw AssertionError("Expected a typed failure")
    }
}
