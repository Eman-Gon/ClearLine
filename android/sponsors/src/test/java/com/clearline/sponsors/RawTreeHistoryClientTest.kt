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
        assertFalse(text.contains(input.sessionId.value))
        assertFalse(text.contains("consent_revision"))
        assertEquals(setOf("schema_version", "export_id", "session_ref", "input_revision", "data_origin", "created_at_ms", "projection_type", "event_id", "event_name", "event_status", "timestamp_ms"), http.sent[0].keys)
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
        assertFalse(body.toString().contains("Local display"))
        val invented = evidence.copy(facts = PublicFacts(phone = SourcedFact("(555) 000-0000", listOf("passage-1"))))
        assertEquals(ErrorCode.INVALID_INPUT, failure { client(http, gate).append(event(projection = ExportProjection.PublicResource(invented))) }.error.code)
    }

    @Test fun invalidInsertResponseIsNotSuccess() = runTest {
        val http = Http().apply { response = buildJsonObject { put("inserted", 0) } }
        val gate = Gate().apply { allowed = true }
        assertEquals(ErrorCode.BAD_RESPONSE, failure { client(http, gate).append(event()) }.error.code)
    }

    private suspend fun failure(block: suspend () -> Unit): ClearLineException {
        try { block() } catch (error: ClearLineException) { return error }
        throw AssertionError("Expected a typed failure")
    }
}
