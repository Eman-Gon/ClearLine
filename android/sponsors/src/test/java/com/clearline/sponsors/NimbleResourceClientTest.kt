package com.clearline.sponsors

import com.clearline.core.*
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Contract/validation tests use inert fixtures, never real sponsor credentials. */
class NimbleResourceClientTest {
    private val url = "https://care.example.org/support"
    private val request = ApprovedResourceRequest(SessionId.new(), 2, ApprovalId.new(), ResourceCategory.CAREGIVER_SUPPORT, "Oakland", 1000)
    private val dns = PublicSourceDns { listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) }

    private fun candidate() = ResourceCandidate(nimbleCandidateId(url), "Example support", url, "Organization description", "search-123", 2000)
    private fun source() = ApprovedSource(request, candidate())
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private class Authorization : SponsorAuthorization {
        var checks = 0
        var denied = false
        private fun check() {
            checks++
            if (denied) sponsorFailure(ErrorCode.CONSENT_REQUIRED, "Approval was revoked.")
        }
        override suspend fun requireResearch(request: ApprovedResourceRequest) = check()
        override suspend fun requireSource(source: ApprovedSource) = check()
        override suspend fun requireExport(event: ApprovedExport) = check()
        override suspend fun requireHistory(request: BoundedHistoryQuery) = check()
    }

    private class Http(var response: JsonObject) : SponsorHttp {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var error: Exception? = null
        override suspend fun post(service: Sponsor, path: String, body: JsonObject, database: String?, authorize: suspend () -> Unit): JsonObject {
            assertEquals(Sponsor.NIMBLE, service)
            assertNull(database)
            authorize()
            error?.let { throw it }
            requests += path to body
            return response
        }
    }

    private suspend fun fails(code: ErrorCode, block: suspend () -> Unit): AppError {
        try { block() } catch (error: ClearLineException) { assertEquals(code, error.error.code); return error.error }
        fail("Expected $code")
        throw AssertionError()
    }

    @Test fun searchSendsOnlyApprovedPublicFieldsAndPreservesDescription() { runBlocking {
        val http = Http(json("""{"request_id":"search-123","results":[{"title":"Example support","url":"$url","description":"Organization description"}]}"""))
        val auth = Authorization()
        val result = NimbleResourceClient(http, auth, dns) { 3000 }.search(request)
        assertEquals(2, auth.checks)
        assertEquals(1, http.requests.size)
        assertEquals("/v2/search", http.requests.single().first)
        assertEquals(json("""{"query":"caregiver support groups in Oakland","country":"US","max_results":3,"full_content":false}"""), http.requests.single().second)
        assertEquals(request.approvalId, result.approvalId)
        assertEquals(2L, result.inputRevision)
        assertEquals("Organization description", result.candidates.single().description)
        assertEquals("search-123", result.candidates.single().requestId)
        assertEquals(3000L, result.retrievedAtMs)
        assertEquals(nimbleCandidateId(url), result.candidates.single().candidateId)
    } }

    @Test fun sourceCandidatesAreBoundedDeduplicatedAndPublic() { runBlocking {
        val http = Http(json("""{"results":[{"title":"One","url":"$url"},{"title":"Duplicate","url":"$url"},{"title":"Private","url":"http://127.0.0.1/"},{"title":"Too many","url":"https://extra.example.org/"}]}"""))
        val result = NimbleResourceClient(http, Authorization(), dns).search(request)
        assertEquals(1, result.candidates.size)
        assertNull(result.candidates.single().description)
    } }

    @Test fun reviewedQueryIsExactWhileTranscriptAndBasisStayLocal() { runBlocking {
        val query = "community communication caregiver education in Oakland"
        val draft = CallQueryDraft(query, request.city, request.category,
            QueryBasis(CallConcern.WORD_FINDING, transcriptExcerpt = "Private name Canary Person cannot find words", topKeyword = "word finding"),
            "a".repeat(64))
        val approved = request.copy(queryDraft = draft)
        val http = Http(json("""{"results":[]}"""))
        NimbleResourceClient(http, Authorization(), dns).search(approved)
        val body = http.requests.single().second
        assertEquals(query, body["query"]?.jsonPrimitive?.content)
        assertEquals(setOf("query", "country", "max_results", "full_content"), body.keys)
        assertFalse(body.toString().contains("Canary"))
        assertFalse(body.toString().contains("transcript"))
        assertFalse(body.toString().contains("basis"))
        assertFalse(body.toString().contains("a".repeat(64)))
    } }

    @Test fun mismatchedReviewedQueryFailsBeforeNetwork() { runBlocking {
        val draft = CallQueryDraft("reviewed query near Boston", "Boston", request.category,
            QueryBasis(CallConcern.GENERAL, fallback = true), "b".repeat(64))
        val http = Http(json("""{"results":[]}"""))
        fails(ErrorCode.INVALID_INPUT) { NimbleResourceClient(http, Authorization(), dns).search(request.copy(queryDraft = draft)) }
        assertTrue(http.requests.isEmpty())
    } }

    @Test fun deniedApprovalNeverDispatchesOrResolvesDns() { runBlocking {
        val http = Http(json("{}"))
        val auth = Authorization().apply { denied = true }
        val neverDns = PublicSourceDns { error("DNS must not run before authorization") }
        val client = NimbleResourceClient(http, auth, neverDns)
        fails(ErrorCode.CONSENT_REQUIRED) { client.search(request) }
        fails(ErrorCode.CONSENT_REQUIRED) { client.extract(source()) }
        assertTrue(http.requests.isEmpty())
    } }

    @Test fun approvalRevokedDuringDnsIsRecheckedAtDispatch() { runBlocking {
        val http = Http(json("{}"))
        val auth = Authorization()
        val revokingDns = PublicSourceDns { host -> auth.denied = true; dns.resolve(host) }
        fails(ErrorCode.CONSENT_REQUIRED) { NimbleResourceClient(http, auth, revokingDns).extract(source()) }
        assertTrue(http.requests.isEmpty())
    } }

    @Test fun extractsExactSourceBackedFactsAndImmutableContentIdentity() { runBlocking {
        val content = "# Organization\nPhone: (415) 555-0123\nHours: Monday 9am–5pm\n"
        val http = Http(buildJsonObject {
            put("status", "success"); put("status_code", 200); put("url", url)
            put("task_id", "task-123"); put("request_id", "extract-123")
            put("data", buildJsonObject { put("markdown", content) })
        })
        val client = NimbleResourceClient(http, Authorization(), dns) { 3000 }
        val result = client.extract(source())
        assertEquals(json("""{"url":"$url","render":true}"""), http.requests.single().second)
        assertEquals("Organization description", result.description)
        assertEquals("(415) 555-0123", result.facts.phone?.value)
        assertEquals("Monday 9am–5pm", result.facts.hours?.value)
        assertNull(result.facts.address)
        assertEquals("task-123", result.taskId)
        assertEquals("extract-123", result.requestId)
        assertEquals("search-123", result.searchRequestId)
        assertEquals(VerificationStatus.EXTRACTED, result.verificationStatus)
        assertTrue(result.contentHash.matches(Regex("[0-9a-f]{64}")))
        result.passages.forEach { passage ->
            val offset = Regex("#chars=([0-9]+)-([0-9]+)$").find(passage.passageId)!!
            assertEquals(content.substring(offset.groupValues[1].toInt(), offset.groupValues[2].toInt()), passage.text)
        }
        assertEquals(result.evidenceId, client.extract(source()).evidenceId)
        http.response = buildJsonObject { put("data", buildJsonObject { put("markdown", "New source content") }) }
        val changed = client.extract(source())
        assertNotEquals(result.contentHash, changed.contentHash)
        assertNotEquals(result.evidenceId, changed.evidenceId)
        assertNull(changed.facts.phone)
        assertNull(changed.facts.address)
        assertNull(changed.facts.hours)
    } }

    @Test fun htmlFallbackDiscardsScriptsStylesAndUsesVisiblePassages() { runBlocking {
        val html = "<script>Phone: 111-555-2222</script><style>Hours: fake</style><h1>Support &amp; help</h1><p>Phone: 415-555-0123</p><p>Address: 123 Main St, Oakland</p><iframe>unsafe</iframe>"
        val http = Http(buildJsonObject { put("status", "success"); put("data", buildJsonObject { put("html", html) }) })
        val evidence = NimbleResourceClient(http, Authorization(), dns).extract(source())
        assertEquals("415-555-0123", evidence.facts.phone?.value)
        assertEquals("123 Main St, Oakland", evidence.facts.address?.value)
        assertNull(evidence.facts.hours)
        assertTrue(evidence.passages.first().text.contains("Support & help"))
        assertFalse(evidence.passages.any { "unsafe" in it.text || "111-555-2222" in it.text })
    } }

    @Test fun taskIdentityAloneAndUnsuccessfulTargetAreNotEvidence() { runBlocking {
        for (body in listOf("""{"task_id":"pending"}""", """{"status":"running","task_id":"pending"}""", """{"status":"failed","data":{"markdown":"Error"}}""", """{"status":"success","status_code":404,"data":{"markdown":"Not found"}}""", """{"status":"success","status_code":{},"data":{"markdown":"Invalid"}}""")) {
            fails(ErrorCode.BAD_RESPONSE) { NimbleResourceClient(Http(json(body)), Authorization(), dns).extract(source()) }
        }
    } }

    @Test fun unrelatedAndPrivateRedirectsAreRejected() { runBlocking {
        for (target in listOf("https://unselected.example.org/", "http://127.0.0.1/")) {
            val response = buildJsonObject { put("data", buildJsonObject { put("markdown", "Content"); put("redirects", buildJsonArray { add(buildJsonObject { put("url", target) }) }) }) }
            try { NimbleResourceClient(Http(response), Authorization(), dns).extract(source()); fail("Redirect must fail") }
            catch (error: ClearLineException) { assertTrue(error.error.code in setOf(ErrorCode.BAD_RESPONSE, ErrorCode.INVALID_INPUT)) }
        }
    } }

    @Test fun privateAndReservedUrlFormsAreRejected() {
        for (target in listOf("file:///etc/passwd", "https://user:password@example.org/", "https://@example.org/", "http://localhost/", "http://127.0.0.1/", "http://127.1/", "http://012.0.0.1/", "http://10.0.0.1/", "http://169.254.169.254/", "http://100.64.0.1/", "http://198.18.0.1/", "http://192.0.2.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/", "http://[2001:db8::1]/", "http://[2002:7f00:1::]/", "http://site.local/", "https://example.org:8080/", "https://example.org\\@localhost/")) {
            try { PublicSourceUrls.normalize(target); fail("Target must fail: $target") }
            catch (error: ClearLineException) { assertEquals(ErrorCode.INVALID_INPUT, error.error.code) }
        }
    }

    @Test fun mixedPrivateDnsAndForgedCandidateAreRejected() { runBlocking {
        val http = Http(json("{}"))
        val mixedDns = PublicSourceDns { dns.resolve(it) + InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)) }
        fails(ErrorCode.INVALID_INPUT) { NimbleResourceClient(http, Authorization(), mixedDns).extract(source()) }
        fails(ErrorCode.INVALID_INPUT) { NimbleResourceClient(http, Authorization(), dns).extract(source().copy(candidate = candidate().copy(candidateId = "invented"))) }
        assertTrue(http.requests.isEmpty())
        assertTrue(PublicSourceUrls.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
    } }

    @Test fun cancellationAndTypedNetworkErrorsPropagateWithoutHiddenRetries() { runBlocking {
        val http = Http(json("{}")).apply { error = CancellationException("cancelled by test") }
        try { NimbleResourceClient(http, Authorization(), dns).search(request); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        http.error = ClearLineException(AppError(ErrorCode.NETWORK_UNAVAILABLE, "Network unavailable.", true))
        val error = fails(ErrorCode.NETWORK_UNAVAILABLE) { NimbleResourceClient(http, Authorization(), dns).search(request) }
        assertTrue(error.retryable)
        assertTrue(http.requests.isEmpty())
    } }

    @Test fun excessiveContentAndMalformedTextFieldsFailClosed() { runBlocking {
        val response = buildJsonObject { put("data", buildJsonObject { put("markdown", "x".repeat(150_001)) }) }
        fails(ErrorCode.BAD_RESPONSE) { NimbleResourceClient(Http(response), Authorization(), dns).extract(source()) }
        fails(ErrorCode.BAD_RESPONSE) { NimbleResourceClient(Http(json("""{"results":[],"request_id":{}}""")), Authorization(), dns).search(request) }
    } }
}
