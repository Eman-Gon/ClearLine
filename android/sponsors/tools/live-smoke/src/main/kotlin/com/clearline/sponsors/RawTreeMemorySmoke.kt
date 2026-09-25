package com.clearline.sponsors

import com.clearline.core.*
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Fresh IDs isolate these synthetic summaries from every phone profile. */
internal class SmokeMemoryFixture(now: Long) {
    val profile = ProfileId.new()
    private val start = now - 60_000L
    val exports = listOf(
        summary(start - 14 * DAY_MS, 140, 0.6, now),
        summary(start - 7 * DAY_MS, 120, 0.4, now),
        summary(start, 100, 0.2, now),
    )
    val replay get() = exports.last()
    val query = MemoryQuery(profile, replay.sessionId, DataOrigin.SYNTHETIC, RecordingTask.CHECK_IN,
        "android-pcm-v1", "english-lexical-v1", start)

    init { require(now >= 14 * DAY_MS + 60_000) }

    private fun summary(created: Long, words: Int, energy: Double, now: Long): ApprovedExport = ApprovedExport(
        ExportId.new(), profile, SessionId.new(), 0, 0, DataOrigin.SYNTHETIC, now,
        ExportProjection.Measurements(1,
            RecordingMetrics(60.0, words, words.toDouble(), energy, dataOrigin = DataOrigin.SYNTHETIC),
            sessionCreatedAtMs = created, completedAtMs = created + 60_000),
    )

    fun matches(snapshot: RawTreeMemorySnapshot): Boolean {
        fun expected(event: ApprovedExport): MemorySession {
            val value = event.projection as ExportProjection.Measurements
            return MemorySession(event.sessionId, profile, checkNotNull(value.sessionCreatedAtMs), value.summaryVersion,
                event.inputRevision, DataOrigin.SYNTHETIC, value.task, value.metrics,
                completedAtMs = value.completedAtMs)
        }
        return snapshot.profileId == profile && snapshot.currentSessionId == replay.sessionId &&
            snapshot.status == RawTreeMemoryStatus.AVAILABLE && snapshot.dataPointCount == 3L && snapshot.sessionCount == 3L &&
            snapshot.previousSessions == exports.take(2).reversed().map(::expected) && snapshot.currentSession == expected(replay) &&
            snapshot.meanRecordingWpm?.let { abs(it - 130.0) < 1e-9 } == true &&
            snapshot.meanEnergyRms?.let { abs(it - 0.5) < 1e-9 } == true &&
            snapshot.diagnostics.error == null && !snapshot.cached
    }

    companion object { private const val DAY_MS = 24 * 60 * 60 * 1000L }
}

/** The one approved replay is deliberate: the logical count must remain three. */
internal class SmokeMemoryAuthorization(private val fixture: SmokeMemoryFixture) : SponsorAuthorization {
    private val writes = mutableMapOf<ExportId, Int>()
    private var memoryChecks = 0
    private var closed = false
    override suspend fun requireExport(event: ApprovedExport) {
        val approved = fixture.exports.singleOrNull { it == event }
        val maximum = if (event == fixture.replay) 2 else 1
        if (closed || approved == null || writes.getOrDefault(event.exportId, 0) >= maximum) denied()
        writes[event.exportId] = writes.getOrDefault(event.exportId, 0) + 1
    }
    override suspend fun requireMemory(request: MemoryQuery) {
        if (closed || request != fixture.query || writes.values.sum() != 4 || memoryChecks >= 9) denied()
        memoryChecks++ // Three fixed queries per memory attempt; at most 3 attempts.
    }
    override suspend fun requireResearch(request: ApprovedResourceRequest): Unit = denied()
    override suspend fun requireSource(source: ApprovedSource): Unit = denied()
    override suspend fun requireHistory(request: BoundedHistoryQuery): Unit = denied()
    fun close() { closed = true }
    private fun denied(): Nothing = sponsorFailure(ErrorCode.CONSENT_REQUIRED, "The memory probe allows only its isolated synthetic profile and exact summaries.")
}

/** Typed counters and diagnostic enums only; no source bodies, identities or text. */
internal fun memorySmokeDiagnostics(snapshot: RawTreeMemorySnapshot): String =
    "status=${snapshot.status}; dataPointCount=${snapshot.dataPointCount}; sessionCount=${snapshot.sessionCount}; " +
        "priorRows=${snapshot.previousSessions.size}; currentPresent=${snapshot.currentSession != null}; " +
        "error=${snapshot.diagnostics.error}; returnedRows=${snapshot.diagnostics.returnedRows}; latencyMs=${snapshot.diagnostics.latencyMs}"

internal suspend fun rawTreeMemoryProbe(key: String, database: String) {
    if (databaseState(database) != ConfigState.PRESENT)
        sponsorFailure(ErrorCode.INVALID_INPUT, "RAWTREE_DATABASE is missing or invalid.")
    val fixture = SmokeMemoryFixture(System.currentTimeMillis())
    val authorization = SmokeMemoryAuthorization(fixture)
    val credentials = SmokeCredentials(Sponsor.RAWTREE, key)
    val http = OkHttpSponsorTransport(credentials)
    val client = RawTreeHistoryClient(http, authorization, { SponsorConfiguration(rawTreeEnabled = true, rawTreeDatabase = database) })
    try {
        println("LIVE RawTree memory: three synthetic summaries and one exact replay; no transcript text.")
        println("Synthetic profile_id=${fixture.profile.value}")
        println("Synthetic profile_ref=${RawTreeExportWire.profileRef(fixture.profile)}")
        fixture.exports.forEachIndexed { index, event ->
            println("Fixture ${index + 1}: session_id=${event.sessionId.value}; export_id=${event.exportId.value}")
        }
        println("Sending four write requests to the configured database; expected logical sessions=3. Synthetic rows remain.")
        for (event in fixture.exports + fixture.replay) client.append(event)
        repeat(3) { attempt ->
            if (attempt > 0) delay(2_000)
            val snapshot = client.memory(fixture.query)
            if (fixture.matches(snapshot)) {
                println("PASS RawTree memory: 3 logical sessions after 4 write requests; 2 prior calls; current call matched.")
                println("Synthetic baseline: 130 recording wpm; energy RMS 0.5. Attempt ${attempt + 1}.")
                return
            }
            println("RawTree memory attempt ${attempt + 1} did not match: ${memorySmokeDiagnostics(snapshot)}")
            if (snapshot.diagnostics.error != null && snapshot.diagnostics.error !in setOf(ErrorCode.BAD_RESPONSE, ErrorCode.TIMEOUT, ErrorCode.NETWORK_UNAVAILABLE))
                sponsorFailure(snapshot.diagnostics.error, "RawTree memory query failed; synthetic writes remain in the database.")
        }
        sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree memory did not match the synthetic count, baseline and current call after three bounded reads. Synthetic writes remain in the database.")
    } finally { authorization.close(); http.close(); credentials.close() }
}
