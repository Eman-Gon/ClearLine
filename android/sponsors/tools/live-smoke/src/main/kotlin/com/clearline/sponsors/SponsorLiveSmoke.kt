package com.clearline.sponsors

import com.clearline.core.*
import java.util.UUID
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

internal enum class SmokeCommand { CONFIG, NIMBLE, NIMBLE_ROUNDTRIP, RAWTREE, RAWTREE_MEMORY, HELP }

/** Exact command matching prevents accidental network operations or wider queries. */
internal fun smokeCommand(args: List<String>): SmokeCommand = when (args) {
    emptyList<String>(), listOf("config") -> SmokeCommand.CONFIG
    listOf("nimble-search", "--allow-live-search") -> SmokeCommand.NIMBLE
    listOf("nimble-roundtrip", "--allow-live-search", "--allow-live-extract") -> SmokeCommand.NIMBLE_ROUNDTRIP
    listOf("rawtree-roundtrip", "--allow-synthetic-write") -> SmokeCommand.RAWTREE
    listOf("rawtree-memory", "--allow-synthetic-write") -> SmokeCommand.RAWTREE_MEMORY
    listOf("--help"), listOf("help") -> SmokeCommand.HELP
    else -> sponsorFailure(ErrorCode.INVALID_INPUT, "Use --help for exact opt-in probe commands.")
}

internal object SmokeFixture {
    // Entirely invented input. Neither this string nor any phone transcript is
    // sent to Nimble: the production builder selects its bounded public topic.
    const val TRANSCRIPT = "I keep forgetting where I put things."
    const val CITY = "San Francisco CA"
    val draft: CallQueryDraft get() = CallInsights.buildQuery(TRANSCRIPT, CITY, ResourceCategory.CAREGIVER_SUPPORT)

    fun research(now: Long) = ApprovedResourceRequest(
        SessionId.new(), 0, ApprovalId.new(), ResourceCategory.CAREGIVER_SUPPORT,
        CITY, now, draft,
    )

    fun event(now: Long) = ApprovedExport(
        ExportId.new(), ProfileId.new(), SessionId.new(), 0, 0,
        DataOrigin.SYNTHETIC, now,
        ExportProjection.Event(UUID.randomUUID().toString(), ExportEventName.SESSION_CREATED, ExportEventStatus.COMPLETED, now),
    )

    fun query(event: ApprovedExport) = BoundedHistoryQuery(event.sessionId, event.exportId, 1, ExportField.EVENTS)

    fun matches(record: ExportedHistoryRecord, event: ApprovedExport): Boolean =
        record.exportId == event.exportId && record.sessionRef == event.sessionId.value &&
            record.inputRevision == event.inputRevision && record.createdAtMs == event.createdAtMs &&
            record.dataOrigin == DataOrigin.SYNTHETIC &&
            (record.projection as? ExportedHistoryProjection.Event)?.value == event.projection
}

/** Host-only, exact fixture authorization. Never linked into the Android app. */
internal class SmokeAuthorization(
    private val research: ApprovedResourceRequest? = null,
    private val export: ApprovedExport? = null,
) : SponsorAuthorization {
    private var closed = false
    private var writes = 0
    private var reads = 0
    private var researchChecks = 0
    private var sourceChecks = 0
    private var source: ApprovedSource? = null
    init { require((research != null) xor (export != null)); require(export == null || export.dataOrigin == DataOrigin.SYNTHETIC) }
    override suspend fun requireResearch(request: ApprovedResourceRequest) {
        checkAllowed(!closed && research != null && request == research && researchChecks < 2)
        researchChecks++ // Adapter check plus its transport dispatch check.
    }
    override suspend fun requireExport(event: ApprovedExport) {
        checkAllowed(!closed && export != null && event == export && writes == 0)
        writes++
    }
    override suspend fun requireHistory(request: BoundedHistoryQuery) {
        checkAllowed(!closed && export != null && writes == 1 && request == SmokeFixture.query(export) && reads < 3)
        reads++
    }
    fun selectSource(candidate: ResourceCandidate) {
        checkAllowed(!closed && research != null && researchChecks == 2 && source == null)
        source = ApprovedSource(checkNotNull(research), candidate)
    }
    override suspend fun requireSource(source: ApprovedSource) {
        checkAllowed(!closed && this.source != null && source == this.source && sourceChecks < 2)
        sourceChecks++
    }
    override suspend fun requireMemory(request: MemoryQuery): Unit = denied()
    fun close() { closed = true }
    private fun checkAllowed(value: Boolean) { if (!value) denied() }
    private fun denied(): Nothing = sponsorFailure(ErrorCode.CONSENT_REQUIRED, "The probe allows only its exact synthetic fixture operation.")
}

internal enum class ConfigState { MISSING, INVALID, PRESENT }
internal fun tokenState(value: String?): ConfigState = when {
    value.isNullOrEmpty() -> ConfigState.MISSING
    value.length !in 1..CredentialCipher.MAX_TOKEN_BYTES || value.any { it.code !in 33..126 } -> ConfigState.INVALID
    else -> ConfigState.PRESENT
}
internal fun databaseState(value: String?): ConfigState = when {
    value.isNullOrEmpty() -> ConfigState.MISSING
    !value.matches(Regex("[A-Za-z0-9_-]{1,128}")) -> ConfigState.INVALID
    else -> ConfigState.PRESENT
}

/** Only the selected provider's key is copied, and every mutable copy is wiped. */
internal class SmokeCredentials(service: Sponsor, token: String) : CredentialProvider, AutoCloseable {
    private val allowedService = service
    private val bytes: ByteArray
    private var closed = false
    init {
        if (tokenState(token) != ConfigState.PRESENT)
            sponsorFailure(ErrorCode.INVALID_INPUT, "Selected sponsor key is missing or invalid.")
        bytes = token.toByteArray(Charsets.US_ASCII)
    }
    override suspend fun <T> withCredential(service: Sponsor, block: suspend (ByteArray, () -> Unit) -> T): T {
        requireCurrent(service)
        val temporary = bytes.copyOf()
        return try { block(temporary) { requireCurrent(service) } } finally { temporary.fill(0) }
    }
    override fun close() { closed = true; bytes.fill(0) }
    private fun requireCurrent(service: Sponsor) {
        if (closed || service != allowedService)
            sponsorFailure(ErrorCode.CONSENT_REQUIRED, "Probe credential is not authorized for this operation.")
    }
}

private suspend fun nimbleProbe(key: String, extract: Boolean = false) {
    val request = SmokeFixture.research(System.currentTimeMillis())
    val authorization = SmokeAuthorization(research = request)
    val credentials = SmokeCredentials(Sponsor.NIMBLE, key)
    val http = OkHttpSponsorTransport(credentials)
    val diagnostics = SmokeShapeTransport(http)
    try {
        println("LIVE Nimble: one search; synthetic topic query: ${request.queryDraft!!.query}")
        val client = NimbleResourceClient(diagnostics, authorization, PublicSourceDns { host ->
            withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
        })
        val result = client.search(request)
        if (result.candidates.isEmpty()) sponsorFailure(ErrorCode.BAD_RESPONSE, "Nimble returned no usable candidates; search is not verified.")
        println("PASS Nimble: ${result.candidates.size} candidate(s) parsed by the production Android adapter.")
        if (extract) {
            val candidate = result.candidates.first()
            authorization.selectSource(candidate)
            println("LIVE Nimble: one extraction of the first returned candidate, bound to the completed search.")
            val evidence = client.extract(ApprovedSource(request, candidate))
            if (evidence.verificationStatus != VerificationStatus.EXTRACTED || evidence.passages.isEmpty())
                sponsorFailure(ErrorCode.BAD_RESPONSE, "Nimble did not return verified source evidence.")
            println("PASS Nimble extract: ${evidence.passages.size} bounded passage(s) parsed; content is not logged.")
        }
    } catch (error: Exception) {
        diagnostics.lastShape?.let { println("Nimble response shape (no values): $it") }
        throw error
    } finally { authorization.close(); http.close(); credentials.close() }
}

/** Known field names, types and lengths only; never inspect arbitrary error fields. */
internal fun nimbleResponseShape(response: JsonObject): String {
    val fields = mutableListOf<String>()
    fun shape(path: String, value: JsonElement?) {
        val type = when (value) {
            null -> "missing"
            JsonNull -> "null"
            is JsonPrimitive -> if (value.isString) "string(${value.content.length})" else "scalar"
            is JsonArray -> "array(${value.size})"
            else -> "object"
        }
        fields += "$path=$type"
    }
    for (key in listOf("request_id", "task_id", "url", "status")) shape(key, response[key])
    shape("results", response["results"])
    (response["results"] as? JsonArray)?.take(3)?.forEachIndexed { index, row ->
        for (key in listOf("title", "url", "description")) shape("results[$index].$key", (row as? JsonObject)?.get(key))
    }
    val data = response["data"] as? JsonObject
    for (key in listOf("markdown", "html", "redirects")) shape("data.$key", data?.get(key))
    return fields.joinToString("; ")
}

private class SmokeShapeTransport(private val delegate: SponsorHttp) : SponsorHttp {
    var lastShape: String? = null
        private set
    override suspend fun post(service: Sponsor, path: String, body: JsonObject, database: String?, authorize: suspend () -> Unit): JsonObject {
        lastShape = null
        return delegate.post(service, path, body, database, authorize).also {
            if (service == Sponsor.NIMBLE) lastShape = nimbleResponseShape(it)
        }
    }
}

private suspend fun rawTreeProbe(key: String, database: String) {
    if (databaseState(database) != ConfigState.PRESENT)
        sponsorFailure(ErrorCode.INVALID_INPUT, "RAWTREE_DATABASE is missing or invalid.")
    val event = SmokeFixture.event(System.currentTimeMillis())
    val query = SmokeFixture.query(event)
    val authorization = SmokeAuthorization(export = event)
    val credentials = SmokeCredentials(Sponsor.RAWTREE, key)
    val http = OkHttpSponsorTransport(credentials)
    val client = RawTreeHistoryClient(http, authorization, { SponsorConfiguration(rawTreeEnabled = true, rawTreeDatabase = database) })
    try {
        println("LIVE RawTree: one synthetic event insert, then at most three exact-ID readbacks.")
        println("Synthetic session_id=${event.sessionId.value}; export_id=${event.exportId.value}")
        println("This row remains in the configured database; no cleanup or real session reads are performed.")
        val receipt = client.append(event)
        if (receipt.exportId != event.exportId) sponsorFailure(ErrorCode.BAD_RESPONSE, "Insert receipt identity did not match the fixture.")
        println("RawTree insert accepted; awaiting exact stored event verification.")
        repeat(3) { attempt ->
            if (attempt > 0) delay(2_000)
            val result = client.query(query)
            val row = result.records.singleOrNull()
            if (row != null) {
                if (!SmokeFixture.matches(row, event)) sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree readback did not match the complete synthetic event.")
                println("PASS RawTree: exact synthetic event read back and verified on attempt ${attempt + 1}.")
                return
            }
        }
        sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree accepted the insert but the bounded readback found no matching row. It may appear later; the row was not deleted.")
    } finally { authorization.close(); http.close(); credentials.close() }
}

fun main(args: Array<String>) {
    try {
        when (smokeCommand(args.toList())) {
            SmokeCommand.HELP -> println("""
                Usage: android/sponsors/tools/live-smoke.sh [command]
                  config                                      Local configuration presence/shape only (default).
                  nimble-search --allow-live-search            One fixed synthetic topic search; may consume Nimble credits.
                  nimble-roundtrip --allow-live-search --allow-live-extract
                                                              Search then extract exactly the first result.
                  rawtree-roundtrip --allow-synthetic-write    One persistent synthetic event + at most 3 exact-ID queries.
                  rawtree-memory --allow-synthetic-write       Three synthetic summaries + one replay; scoped memory check.
                  self-test                                   Offline probe safety tests (handled by the shell launcher).
                Environment: NIMBLE_API_KEY, RAWTREE_API_KEY, RAWTREE_DATABASE.
                No .env files are loaded; no key, raw response body or real transcript is printed.
                Search query: ${SmokeFixture.draft.query}
                RawTree writes clearline_events (roundtrip) or clearline_session_summaries (memory).
                All writes use data_origin=synthetic and fresh random fixture UUIDs, without transcript text.
                Use a designated test database. This does not configure or authorize the phone app.
            """.trimIndent())
            SmokeCommand.CONFIG -> {
                println("Local configuration only; no sponsor request sent and credentials are not authenticated.")
                println("NIMBLE_API_KEY: ${tokenState(System.getenv("NIMBLE_API_KEY"))}")
                println("RAWTREE_API_KEY: ${tokenState(System.getenv("RAWTREE_API_KEY"))}")
                println("RAWTREE_DATABASE: ${databaseState(System.getenv("RAWTREE_DATABASE"))}")
                println("Use --help for explicit live probe commands.")
            }
            SmokeCommand.NIMBLE -> runBlocking { nimbleProbe(System.getenv("NIMBLE_API_KEY").orEmpty()) }
            SmokeCommand.NIMBLE_ROUNDTRIP -> runBlocking { nimbleProbe(System.getenv("NIMBLE_API_KEY").orEmpty(), extract = true) }
            SmokeCommand.RAWTREE -> runBlocking { rawTreeProbe(System.getenv("RAWTREE_API_KEY").orEmpty(), System.getenv("RAWTREE_DATABASE").orEmpty()) }
            SmokeCommand.RAWTREE_MEMORY -> runBlocking { rawTreeMemoryProbe(System.getenv("RAWTREE_API_KEY").orEmpty(), System.getenv("RAWTREE_DATABASE").orEmpty()) }
        }
    } catch (error: ClearLineException) {
        System.err.println("FAIL ${error.error.code}: ${error.error.message}")
        exitProcess(1)
    } catch (_: Exception) {
        // Do not expose exception payloads, raw responses or environment values.
        System.err.println("FAIL: Probe could not complete; no credential or response details are logged.")
        exitProcess(1)
    }
}
