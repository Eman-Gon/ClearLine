package com.clearline.sponsors

import com.clearline.core.AppError
import com.clearline.core.ClearLineException
import com.clearline.core.ErrorCode
import com.clearline.core.Sponsor
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun sponsorFailure(code: ErrorCode, message: String, retryable: Boolean = false): Nothing =
    throw ClearLineException(AppError(code, message, retryable))

internal interface CredentialProvider {
    /** A fresh temporary copy is wiped after the request finishes or is cancelled. */
    suspend fun <T> withCredential(service: Sponsor, block: suspend (ByteArray) -> T): T
}

internal interface SponsorHttp {
    suspend fun post(service: Sponsor, path: String, body: JsonObject, database: String? = null, authorize: suspend () -> Unit): JsonObject
}

/** The only sponsor network transport. Never installs logging or TLS overrides. */
internal class OkHttpSponsorTransport(
    private val credentials: CredentialProvider,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) : SponsorHttp {
    override suspend fun post(service: Sponsor, path: String, body: JsonObject, database: String?, authorize: suspend () -> Unit): JsonObject {
        val base = when (service) {
            Sponsor.NIMBLE -> {
                require(path in setOf("/v2/search", "/v2/extract"))
                require(database == null)
                "https://sdk.nimbleway.com"
            }
            Sponsor.RAWTREE -> {
                require(path == "/v1/query" || path in TABLES.map { "/v1/tables/$it" })
                require(database != null && database.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                "https://api.rawtree.com"
            }
        }
        val encoded = body.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_REQUEST_BYTES) sponsorFailure(ErrorCode.INVALID_INPUT, "Sponsor request exceeds its size limit.")
        return credentials.withCredential(service) { token ->
            authorize()
            // Authorization necessarily becomes a temporary JVM String for OkHttp.
            // It is never placed in errors, persisted state, URLs or logging.
            val tokenText = token.toString(Charsets.UTF_8)
            if (tokenText.isEmpty() || tokenText.any { it.code !in 33..126 })
                sponsorFailure(ErrorCode.UNAUTHORIZED, "Stored sponsor credential is invalid.")
            val request = Request.Builder().url(base + path)
                .header("Authorization", "Bearer $tokenText")
                .header("Accept", "application/json")
                .apply { database?.let { header("x-rawtree-database", it) } }
                .post(encoded.toRequestBody("application/json".toMediaType())).build()
            val bytes = execute(request)
            try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                    ?: sponsorFailure(ErrorCode.BAD_RESPONSE, "Sponsor returned an invalid object.")
            } catch (error: ClearLineException) {
                throw error
            } catch (_: Exception) {
                sponsorFailure(ErrorCode.BAD_RESPONSE, "Sponsor returned malformed JSON.")
            }
        }
    }

    private suspend fun execute(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isActive) return
                val code = if (error is SocketTimeoutException || error is java.io.InterruptedIOException) ErrorCode.TIMEOUT else ErrorCode.NETWORK_UNAVAILABLE
                continuation.resumeWithException(ClearLineException(AppError(code, "Sponsor request could not complete.", true)))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        when (it.code) {
                            401, 403 -> sponsorFailure(ErrorCode.UNAUTHORIZED, "Sponsor rejected the configured credential.")
                            429 -> sponsorFailure(ErrorCode.RATE_LIMITED, "Sponsor rate limit reached.", true)
                            408, 504 -> sponsorFailure(ErrorCode.TIMEOUT, "Sponsor request timed out.", true)
                        }
                        if (!it.isSuccessful) sponsorFailure(ErrorCode.BAD_RESPONSE, "Sponsor returned HTTP ${it.code}.", it.code >= 500)
                        val body = it.body ?: sponsorFailure(ErrorCode.BAD_RESPONSE, "Sponsor returned an empty response.")
                        if (body.contentLength() > MAX_RESPONSE_BYTES) sponsorFailure(ErrorCode.BAD_RESPONSE, "Sponsor response exceeds its size limit.")
                        body.byteStream().use { stream ->
                            try { stream.readBytesBounded(MAX_RESPONSE_BYTES) }
                            catch (_: IllegalArgumentException) { sponsorFailure(ErrorCode.BAD_RESPONSE, "Sponsor response exceeds its size limit.") }
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    if (!continuation.isActive) return
                    val safe = when (error) {
                        is ClearLineException -> error
                        is IOException -> ClearLineException(AppError(ErrorCode.NETWORK_UNAVAILABLE, "Sponsor response was interrupted.", true))
                        else -> ClearLineException(AppError(ErrorCode.BAD_RESPONSE, "Sponsor response could not be read."))
                    }
                    continuation.resumeWithException(safe)
                }
            }
        })
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        client.cache?.close()
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val TABLES = setOf("clearline_events", "clearline_session_summaries", "clearline_checkpoints", "clearline_resource_versions")
    }
}
