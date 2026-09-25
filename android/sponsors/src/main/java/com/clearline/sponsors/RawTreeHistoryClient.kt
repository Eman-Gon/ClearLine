package com.clearline.sponsors

import com.clearline.core.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Optional exported history only. This client never reads or mutates Room. */
internal class RawTreeHistoryClient(
    private val http: SponsorHttp,
    private val authorization: SponsorAuthorization,
    private val configuration: suspend () -> SponsorConfiguration,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ApprovedHistoryClient {
    override suspend fun append(event: ApprovedExport): DeliveryReceipt {
        // Copy caller-owned collections before suspension. The approved value
        // passed to the dispatch guard describes exactly this immutable body.
        val snapshot = RawTreeExportWire.snapshot(event)
        val body = RawTreeExportWire.encode(snapshot)
        val database = enabledDatabase()
        val response = http.post(Sponsor.RAWTREE, "/v1/tables/${RawTreeExportWire.table(snapshot.requiredField)}", body, database) {
            requireSameDatabase(database)
            authorization.requireExport(snapshot) // Synthetic data requires this too.
        }
        if (response["error"] != null && response["error"] != JsonNull || response["success"] == JsonPrimitive(false))
            sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree did not accept the approved export.")
        response["inserted"]?.let {
            if (it != JsonNull && (it as? JsonPrimitive)?.longOrNull != 1L)
                sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree returned an unexpected inserted row count.")
        }
        return DeliveryReceipt(snapshot.exportId, nowMs())
    }

    override suspend fun query(request: BoundedHistoryQuery): HistoryResult {
        val database = enabledDatabase()
        val result = http.post(Sponsor.RAWTREE, "/v1/query", buildJsonObject { put("sql", RawTreeExportWire.historySql(request)) }, database) {
            requireSameDatabase(database)
            authorization.requireHistory(request)
        }
        val rows = result["data"] as? JsonArray
            ?: sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree query did not return the expected data rows.")
        if (rows.size > request.limit)
            sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree query exceeded its bounded row limit.")
        val records = rows.map { RawTreeExportWire.decode(it, request) }.distinctBy { it.exportId }
        return HistoryResult(records, nowMs())
    }

    override suspend fun memory(query: MemoryQuery): RawTreeMemorySnapshot {
        val started = System.nanoTime()
        fun diagnostics(rows: Int? = null, error: ErrorCode? = null) = RawTreeMemoryDiagnostics(
            returnedRows = rows, latencyMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0), error = error,
        )
        return try {
            val database = enabledDatabase()
            suspend fun rows(sql: String, maximum: Int): JsonArray {
                val response = http.post(Sponsor.RAWTREE, "/v1/query", buildJsonObject { put("sql", sql) }, database) {
                    requireSameDatabase(database)
                    authorization.requireMemory(query)
                }
                val data = response["data"] as? JsonArray
                    ?: sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree memory response has no data rows.")
                if (data.size > maximum)
                    sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree memory response exceeded its row bound.")
                return data
            }
            val sql = RawTreeExportWire.memorySql(query)
            val counts = rows(sql.counts, 1).singleOrNull()?.jsonObject
                ?: sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree memory counts were unavailable.")
            val dataPoints = RawTreeExportWire.count(counts["data_point_count"])
            val sessionCount = RawTreeExportWire.count(counts["session_count"])
            val previous = rows(sql.previous, query.limit).map { RawTreeExportWire.decodeMemory(it, query) }
            val current = rows(sql.current, 1).singleOrNull()?.let { RawTreeExportWire.decodeMemory(it, query) }
            RawTreeMemoryMath.snapshot(query, previous, dataPoints, sessionCount, nowMs(), diagnostics(previous.size + if (current == null) 0 else 1), current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ClearLineException) {
            RawTreeMemoryMath.unavailable(query, nowMs(), diagnostics(error = error.error.code))
        } catch (_: Exception) {
            RawTreeMemoryMath.unavailable(query, nowMs(), diagnostics(error = ErrorCode.BAD_RESPONSE))
        }
    }

    private suspend fun enabledDatabase(): String {
        val config = configuration()
        if (!config.rawTreeEnabled)
            sponsorFailure(ErrorCode.CONSENT_REQUIRED, "RawTree is disabled in sponsor settings.")
        return config.rawTreeDatabase?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: sponsorFailure(ErrorCode.INVALID_INPUT, "Select an intended RawTree database.")
    }

    private suspend fun requireSameDatabase(expected: String) {
        if (enabledDatabase() != expected)
            sponsorFailure(ErrorCode.STALE_REVISION, "RawTree destination changed before dispatch.")
    }
}

/** Manual typed allowlist. Never serialize ApprovedExport or SourceEvidence wholesale. */
internal object RawTreeExportWire {
    private val qualityCodes = setOf("too_short", "too_long", "silent", "no_speech", "empty_transcript", "invalid_audio", "decode_failed", "missing_audio", "unsupported_media", "empty_audio")
    private val hashPattern = Regex("[0-9a-f]{64}")

    data class MemorySql(val counts: String, val previous: String, val current: String)

    fun memorySql(query: MemoryQuery): MemorySql {
        valid(query.measurementVersion.matches(Regex("[A-Za-z0-9_.:-]{1,64}")) && query.lexicalVersion.matches(Regex("[A-Za-z0-9_.:-]{1,64}")))
        val scope = "schema_version = 3 AND projection_type = 'measurements' AND profile_ref = '${profileRef(query.profileId)}'"
        val origin = query.dataOrigin.name.lowercase()
        val latest = """SELECT __raw_data AS payload, session_ref, created_at_ms, data_origin,
  session_created_at_ms, completed_at_ms,
  recording_task, metrics.measurement_version AS method_version,
  metrics.lexical_version AS lexical_version, metrics.quality AS quality,
  row_number() OVER (PARTITION BY session_ref ORDER BY summary_version DESC, input_revision DESC, created_at_ms DESC, export_id DESC) AS latest_rank
FROM clearline_session_summaries WHERE $scope"""
        // A logical point is one current session summary, not an export/retry or
        // correction. Counts cover the whole profile/provenance, independently
        // of the small eight-week page; currently one point equals one session.
        val counts = "SELECT uniqExact(session_ref) AS data_point_count, uniqExact(session_ref) AS session_count FROM ($latest) WHERE latest_rank = 1 AND data_origin = '$origin'"
        val matching = "latest_rank = 1 AND data_origin = '$origin' AND recording_task = '${query.task.name.lowercase()}' AND method_version = '${query.measurementVersion}' AND lexical_version = '${query.lexicalVersion}' AND quality = 'accepted'"
        val previous = """SELECT payload FROM ($latest)
WHERE $matching AND session_ref != '${query.currentSessionId.value}'
  AND session_created_at_ms >= ${query.windowStartMs} AND session_created_at_ms < ${query.beforeCreatedAtMs}
  AND completed_at_ms >= session_created_at_ms AND completed_at_ms < ${query.beforeCreatedAtMs}
ORDER BY session_created_at_ms DESC, session_ref ASC LIMIT ${query.limit}"""
        val current = "SELECT payload FROM ($latest) WHERE $matching AND session_ref = '${query.currentSessionId.value}' AND session_created_at_ms IS NOT NULL AND completed_at_ms >= session_created_at_ms LIMIT 1"
        return MemorySql(counts, previous, current)
    }

    fun count(value: JsonElement?): Long = (value as? JsonPrimitive)?.let {
        // ClickHouse JSON may quote UInt64 to preserve precision.
        it.content.toLongOrNull()?.takeIf { count -> count >= 0 }
    } ?: sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree did not return exact nonnegative counts.")

    fun decodeMemory(row: JsonElement, query: MemoryQuery): MemorySession = try {
        val body = payload(row)
        require(body.string("profile_ref") == profileRef(query.profileId))
        val sessionId = SessionId(body.string("session_ref"))
        val history = decode(row, BoundedHistoryQuery(sessionId, limit = 1, field = ExportField.TRANSCRIPT_SNIPPET))
        val value = (history.projection as ExportedHistoryProjection.Measurements).value
        MemorySession(sessionId, query.profileId, value.sessionCreatedAtMs ?: error("Missing session chronology"), value.summaryVersion,
            history.inputRevision, history.dataOrigin, value.task,
            value.metrics, value.transcriptSnippet, value.topKeyword, value.completedAtMs ?: error("Missing completion chronology"))
    } catch (_: Exception) {
        sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree returned an invalid memory projection.")
    }

    fun table(field: ExportField): String = when (field) {
        ExportField.EVENTS -> "clearline_events"
        ExportField.MEASUREMENTS -> "clearline_session_summaries"
        ExportField.TRANSCRIPT_SNIPPET -> "clearline_session_summaries"
        ExportField.WORKFLOW_COUNTS -> "clearline_checkpoints"
        ExportField.PUBLIC_RESOURCES -> "clearline_resource_versions"
    }

    // SessionId is an app-owned random UUID, never a display name.
    fun sessionRef(sessionId: SessionId): String = sessionId.value

    fun profileRef(profileId: ProfileId): String = MessageDigest.getInstance("SHA-256")
        .digest(("clearline-profile-v1:" + profileId.value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun snapshot(event: ApprovedExport): ApprovedExport = event.copy(projection = when (val projection = event.projection) {
        is ExportProjection.Event -> projection.copy()
        is ExportProjection.Measurements -> projection.copy(metrics = projection.metrics.copy(qualityReasons = projection.metrics.qualityReasons.toList()))
        is ExportProjection.WorkflowCounts -> projection.copy()
        is ExportProjection.PublicResource -> projection.copy(evidence = projection.evidence.copy(
            passages = projection.evidence.passages.map { it.copy() },
            facts = projection.evidence.facts.let { facts -> PublicFacts(copyFact(facts.phone), copyFact(facts.address), copyFact(facts.hours)) },
        ))
    })

    private fun copyFact(fact: SourcedFact?): SourcedFact? = fact?.copy(passageIds = fact.passageIds.toList())

    fun encode(event: ApprovedExport): JsonObject {
        valid(event.createdAtMs >= 0 && event.inputRevision >= 0 && event.consentRevision >= 0)
        val result = buildJsonObject {
            put("schema_version", 3)
            put("export_id", event.exportId.value)
            put("session_ref", sessionRef(event.sessionId))
            put("profile_ref", profileRef(event.profileId))
            put("input_revision", event.inputRevision)
            put("data_origin", event.dataOrigin.name.lowercase())
            put("created_at_ms", event.createdAtMs)
            put("projection_type", event.requiredField.name.lowercase())
            when (val projection = event.projection) {
                is ExportProjection.Event -> {
                    valid(canonicalUuid(projection.eventId) && projection.timestampMs >= 0)
                    put("event_id", projection.eventId)
                    put("event_name", projection.name.name.lowercase())
                    put("event_status", projection.status.name.lowercase())
                    put("timestamp_ms", projection.timestampMs)
                }
                is ExportProjection.Measurements -> {
                    valid(projection.summaryVersion > 0)
                    val sessionCreated = projection.sessionCreatedAtMs
                    val completed = projection.completedAtMs
                    valid(sessionCreated == null || sessionCreated >= 0)
                    valid(completed == null || (sessionCreated != null && completed >= sessionCreated))
                    put("summary_version", projection.summaryVersion)
                    put("metrics", metrics(projection.metrics, event.dataOrigin))
                    // The concrete requireExport guard must require BOTH
                    // MEASUREMENTS and TRANSCRIPT_SNIPPET grants for these.
                    put("transcript_snippet", projection.transcriptSnippet?.let(::JsonPrimitive) ?: JsonNull)
                    put("top_keyword", projection.topKeyword?.let(::JsonPrimitive) ?: JsonNull)
                    put("recording_task", projection.task.name.lowercase())
                    put("session_created_at_ms", projection.sessionCreatedAtMs?.let(::JsonPrimitive) ?: JsonNull)
                    put("completed_at_ms", projection.completedAtMs?.let(::JsonPrimitive) ?: JsonNull)
                }
                is ExportProjection.WorkflowCounts -> {
                    valid(projection.completedCount >= 0 && projection.pendingCount >= 0)
                    put("phase", projection.phase.name.lowercase())
                    put("completed_count", projection.completedCount)
                    put("pending_count", projection.pendingCount)
                }
                is ExportProjection.PublicResource -> {
                    val evidence = projection.evidence
                    valid(evidence.inputRevision == event.inputRevision)
                    valid(evidence.verificationStatus != VerificationStatus.SIMULATED || event.dataOrigin == DataOrigin.SYNTHETIC)
                    evidence(evidence).forEach { (key, value) -> put(key, value) }
                }
            }
        }
        valid(result.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        return result
    }

    private fun metrics(metrics: RecordingMetrics, origin: DataOrigin): JsonObject {
        valid(metrics.dataOrigin == origin && metrics.measurementVersion == "android-pcm-v1" && metrics.lexicalVersion == "english-lexical-v1")
        valid(metrics.qualityReasons.all { it in qualityCodes })
        return buildJsonObject {
            put("duration_s", metrics.durationSeconds)
            put("word_count", metrics.wordCount)
            put("recording_wpm", metrics.recordingWpm)
            put("energy_rms", metrics.energyRms)
            put("pause_count", JsonNull)
            put("pitch_mean_hz", JsonNull)
            put("quality", metrics.quality.name.lowercase())
            put("quality_reasons", JsonArray(metrics.qualityReasons.map(::JsonPrimitive)))
            put("measurement_version", metrics.measurementVersion)
            put("lexical_version", metrics.lexicalVersion)
        }
    }

    private fun evidence(value: SourceEvidence): JsonObject {
        valid(value.version > 0 && value.retrievedAtMs >= 0 && hashPattern.matches(value.contentHash))
        val uri = try { java.net.URI(value.sourceUrl) } catch (_: Exception) { invalid() }
        valid(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.fragment == null && uri.port in setOf(-1, 80, 443))
        val host = uri.host.lowercase().trimEnd('.')
        valid(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal"))
        // Exported organization evidence uses public DNS names, never raw IPs
        // or single-label local names. Nimble performs the live DNS checks.
        valid(host.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}")))
        valid(value.passages.size <= 12 && value.passages.map { it.passageId }.distinct().size == value.passages.size)
        val passages = value.passages.associateBy { it.passageId }
        listOfNotNull(value.facts.phone, value.facts.address, value.facts.hours).forEach { fact ->
            valid(fact.passageIds.isNotEmpty() && fact.passageIds.size <= 8)
            valid(fact.passageIds.all { id -> passages[id]?.text?.contains(fact.value) == true })
        }
        return buildJsonObject {
            put("evidence_id", value.evidenceId.value)
            put("evidence_version", value.version)
            put("title", value.title)
            put("description", value.description?.let(::JsonPrimitive) ?: JsonNull)
            put("source_url", value.sourceUrl)
            put("retrieved_at_ms", value.retrievedAtMs)
            put("content_hash", value.contentHash)
            put("verification_status", value.verificationStatus.name.lowercase())
            put("passages", JsonArray(value.passages.map { passage ->
                valid(passage.passageId.length in 1..128 && passage.text.length in 1..4000)
                buildJsonObject { put("passage_id", passage.passageId); put("text", passage.text) }
            }))
            put("facts", buildJsonObject {
                put("phone", fact(value.facts.phone))
                put("address", fact(value.facts.address))
                put("hours", fact(value.facts.hours))
            })
        }
    }

    private fun fact(value: SourcedFact?): JsonElement = value?.let { fact -> buildJsonObject {
        put("value", fact.value)
        put("passage_ids", JsonArray(fact.passageIds.map(::JsonPrimitive)))
    } } ?: JsonNull

    fun historySql(request: BoundedHistoryQuery): String {
        val field = if (request.field == ExportField.TRANSCRIPT_SNIPPET) ExportField.MEASUREMENTS else request.field
        val partition = when (field) {
            ExportField.EVENTS -> "event_id"
            ExportField.MEASUREMENTS -> "session_ref"
            ExportField.PUBLIC_RESOURCES -> "source_url"
            else -> "export_id"
        }
        val version = when (field) {
            ExportField.MEASUREMENTS -> "summary_version DESC, "
            ExportField.PUBLIC_RESOURCES -> "evidence_version DESC, "
            else -> ""
        }
        val exact = request.exportId?.let { " AND export_id = '${it.value}'" } ?: ""
        return """SELECT payload FROM (
  SELECT __raw_data AS payload, created_at_ms, export_id,
    row_number() OVER (PARTITION BY $partition ORDER BY ${version}created_at_ms DESC, export_id DESC) AS latest_rank
  FROM ${table(field)}
  WHERE schema_version = 3 AND session_ref = '${sessionRef(request.sessionId)}'
    AND projection_type = '${field.name.lowercase()}'$exact
) WHERE latest_rank = 1 ORDER BY created_at_ms DESC, export_id DESC LIMIT ${request.limit}"""
    }

    fun decode(row: JsonElement, request: BoundedHistoryQuery): ExportedHistoryRecord = try {
        val body = payload(row)
        require(body.long("schema_version") == 3L)
        require(body.string("session_ref") == sessionRef(request.sessionId))
        val exportId = ExportId(body.string("export_id"))
        require(request.exportId == null || request.exportId == exportId)
        val origin = DataOrigin.valueOf(body.string("data_origin").uppercase())
        val field = if (request.field == ExportField.TRANSCRIPT_SNIPPET) ExportField.MEASUREMENTS else request.field
        require(body.string("projection_type") == field.name.lowercase())
        val projection = when (field) {
            ExportField.EVENTS -> ExportedHistoryProjection.Event(ExportProjection.Event(body.string("event_id").also { require(canonicalUuid(it)) }, ExportEventName.valueOf(body.string("event_name").uppercase()), ExportEventStatus.valueOf(body.string("event_status").uppercase()), body.long("timestamp_ms")))
            ExportField.MEASUREMENTS -> {
                val raw = body.getValue("metrics").jsonObject
                val metric = RecordingMetrics(raw.number("duration_s"), raw.int("word_count"), raw.number("recording_wpm"), raw.number("energy_rms"), quality = AudioQuality.valueOf(raw.string("quality").uppercase()), qualityReasons = raw.getValue("quality_reasons").jsonArray.map { it.jsonPrimitive.content }, dataOrigin = origin, measurementVersion = raw.string("measurement_version"), lexicalVersion = raw.string("lexical_version"))
                require(raw["pause_count"] == JsonNull && raw["pitch_mean_hz"] == JsonNull)
                metrics(metric, origin)
                // Snippets are revealed only through the separately authorized
                // snippet query field; ordinary measurement reads redact them.
                ExportedHistoryProjection.Measurements(ExportProjection.Measurements(body.int("summary_version"), metric,
                    if (request.field == ExportField.TRANSCRIPT_SNIPPET) body.optionalString("transcript_snippet") else null,
                    if (request.field == ExportField.TRANSCRIPT_SNIPPET) body.optionalString("top_keyword") else null,
                    sessionCreatedAtMs = body.optionalLong("session_created_at_ms"),
                    completedAtMs = body.optionalLong("completed_at_ms"),
                    task = RecordingTask.valueOf(body.string("recording_task").uppercase())))
            }
            ExportField.WORKFLOW_COUNTS -> ExportedHistoryProjection.WorkflowCounts(ExportProjection.WorkflowCounts(Phase.valueOf(body.string("phase").uppercase()), body.int("completed_count"), body.int("pending_count")))
            ExportField.PUBLIC_RESOURCES -> {
                val passages = body.getValue("passages").jsonArray.map { value -> value.jsonObject.let { SupportingPassage(it.string("passage_id"), it.string("text")) } }
                val rawFacts = body.getValue("facts").jsonObject
                val facts = PublicFacts(decodeFact(rawFacts["phone"]), decodeFact(rawFacts["address"]), decodeFact(rawFacts["hours"]))
                val source = ExportedSourceEvidence(EvidenceId(body.string("evidence_id")), body.int("evidence_version"), body.string("title"), body.optionalString("description"), body.string("source_url"), body.long("retrieved_at_ms"), body.string("content_hash"), passages, facts, VerificationStatus.valueOf(body.string("verification_status").uppercase()))
                require(source.version > 0 && hashPattern.matches(source.contentHash) && source.passages.size <= 12)
                val byId = source.passages.associateBy { it.passageId }
                require(byId.size == source.passages.size)
                listOfNotNull(facts.phone, facts.address, facts.hours).forEach { fact -> require(fact.passageIds.all { byId[it]?.text?.contains(fact.value) == true }) }
                ExportedHistoryProjection.PublicResource(source)
            }
            ExportField.TRANSCRIPT_SNIPPET -> error("Normalized field")
        }
        ExportedHistoryRecord(exportId, body.string("session_ref"), body.long("input_revision"), origin, body.long("created_at_ms"), projection)
    } catch (error: Exception) {
        sponsorFailure(ErrorCode.BAD_RESPONSE, "RawTree returned a record outside the selected history contract.")
    }

    private fun decodeFact(element: JsonElement?): SourcedFact? = if (element == null || element == JsonNull) null else element.jsonObject.let { value ->
        SourcedFact(value.string("value"), value.getValue("passage_ids").jsonArray.map { it.jsonPrimitive.content })
    }
    private fun payload(row: JsonElement): JsonObject {
        val element = row.jsonObject["payload"] ?: error("Missing payload")
        return if (element is JsonPrimitive && element.isString) Json.parseToJsonElement(element.content).jsonObject else element.jsonObject
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.optionalString(key: String): String? = if (get(key) == null || get(key) == JsonNull) null else string(key)
    private fun JsonObject.optionalLong(key: String): Long? = if (get(key) == null || get(key) == JsonNull) null else long(key)
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long.also { require(it >= 0) }
    private fun JsonObject.int(key: String): Int = long(key).also { require(it <= Int.MAX_VALUE) }.toInt()
    private fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.also { require(!it.isString) }.double.also { require(it.isFinite()) }

    private fun canonicalUuid(value: String): Boolean = try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }
    private fun valid(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = sponsorFailure(ErrorCode.INVALID_INPUT, "Approved export does not match the permitted typed projection.")
}
