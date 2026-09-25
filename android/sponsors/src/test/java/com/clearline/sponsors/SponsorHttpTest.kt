package com.clearline.sponsors

import com.clearline.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class SponsorHttpTest {
    private class Credentials : CredentialProvider {
        var lastCopy: ByteArray? = null
        var current = true
        override suspend fun <T> withCredential(service: Sponsor, block: suspend (ByteArray, () -> Unit) -> T): T {
            val bytes = "synthetic-credential".toByteArray(); lastCopy = bytes
            return try { block(bytes) { if (!current) sponsorFailure(ErrorCode.STALE_REVISION, "Credential was cleared.") } } finally { bytes.fill(0) }
        }
    }
    private fun transport(credentials: Credentials = Credentials(), response: (Request) -> Response): OkHttpSponsorTransport =
        OkHttpSponsorTransport(credentials, OkHttpClient.Builder().addInterceptor { response(it.request()) }.build())
    private fun response(request: Request, code: Int = 200, body: String = "{}") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body.toResponseBody()).build()

    @Test fun fixedEndpointAndAuthorizationStayOutOfBodyAndAwaitConsentAfterVault() = runTest {
        var approved = false; var requests = 0
        val credentials = Credentials()
        val http = transport(credentials) { request ->
            requests++
            assertTrue(approved)
            assertEquals("https://api.rawtree.com/v1/query", request.url.toString())
            assertEquals("synthetic_db", request.header("x-rawtree-database"))
            assertEquals("Bearer synthetic-credential", request.header("Authorization"))
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            assertFalse(buffer.readUtf8().contains("synthetic-credential"))
            response(request, body = "{\"data\":[]}")
        }
        try {
            val result = http.post(Sponsor.RAWTREE, "/v1/query", buildJsonObject { put("sql", "SELECT 1") }, "synthetic_db") {
                assertNotNull(credentials.lastCopy); approved = true
            }
            assertTrue(result["data"] is JsonArray); assertEquals(1, requests)
            assertTrue(credentials.lastCopy!!.all { it == 0.toByte() })
        } finally { http.close() }
    }

    @Test fun revokedApprovalDoesNotDispatch() = runTest {
        var requests = 0
        val http = transport { requests++; response(it) }
        try {
            try { http.post(Sponsor.NIMBLE, "/v2/search", buildJsonObject {}) { sponsorFailure(ErrorCode.STALE_REVISION, "Consent revoked.") }; fail() }
            catch (error: ClearLineException) { assertEquals(ErrorCode.STALE_REVISION, error.error.code) }
            assertEquals(0, requests)
        } finally { http.close() }
    }

    @Test fun credentialClearedWhileAuthorizationRunsDoesNotDispatch() = runTest {
        var requests = 0
        val credentials = Credentials()
        val http = transport(credentials) { requests++; response(it) }
        try {
            try { http.post(Sponsor.NIMBLE, "/v2/search", buildJsonObject {}) { credentials.current = false }; fail() }
            catch (error: ClearLineException) { assertEquals(ErrorCode.STALE_REVISION, error.error.code) }
            assertEquals(0, requests)
            assertTrue(credentials.lastCopy!!.all { it == 0.toByte() })
        } finally { http.close() }
    }

    @Test fun responseErrorsAreTypedAndNeverEchoBodyOrCredential() = runTest {
        for ((status, code) in listOf(401 to ErrorCode.UNAUTHORIZED, 429 to ErrorCode.RATE_LIMITED, 503 to ErrorCode.BAD_RESPONSE, 302 to ErrorCode.BAD_RESPONSE)) {
            val http = transport { response(it, status, "private upstream body synthetic-credential") }
            try {
                try { http.post(Sponsor.NIMBLE, "/v2/search", buildJsonObject {}) {}; fail() }
                catch (error: ClearLineException) {
                    assertEquals(code, error.error.code)
                    assertFalse(error.message!!.contains("private upstream")); assertFalse(error.message!!.contains("synthetic-credential"))
                    if (status == 401) assertFalse(error.error.retryable)
                }
            } finally { http.close() }
        }
    }

    @Test fun malformedOversizedAndTimeoutResponsesFailExplicitly() = runTest {
        for (body in listOf("not json", "[]", "a".repeat(2 * 1024 * 1024 + 1))) {
            val http = transport { response(it, body = body) }
            try {
                try { http.post(Sponsor.NIMBLE, "/v2/extract", buildJsonObject {}) {}; fail() }
                catch (error: ClearLineException) { assertEquals(ErrorCode.BAD_RESPONSE, error.error.code) }
            } finally { http.close() }
        }
        val http = transport { throw SocketTimeoutException("secret-bearing-error-must-not-leak") }
        try {
            try { http.post(Sponsor.NIMBLE, "/v2/search", buildJsonObject {}) {}; fail() }
            catch (error: ClearLineException) { assertEquals(ErrorCode.TIMEOUT, error.error.code); assertFalse(error.message!!.contains("secret-bearing")) }
        } finally { http.close() }
    }
}
