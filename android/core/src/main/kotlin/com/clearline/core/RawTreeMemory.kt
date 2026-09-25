package com.clearline.core

import kotlinx.serialization.Serializable

/** A fixed analytics query, separate from the phone-local recovery/baseline authority. */
@Serializable
data class MemoryQuery(
    val profileId: ProfileId,
    val currentSessionId: SessionId,
    val dataOrigin: DataOrigin,
    val task: RecordingTask,
    val measurementVersion: String,
    val lexicalVersion: String,
    val beforeCreatedAtMs: Long,
    val limit: Int = 8,
) {
    init {
        require(measurementVersion.length in 1..64 && lexicalVersion.length in 1..64)
        require(beforeCreatedAtMs >= 0 && limit in 1..8)
    }

    val windowStartMs: Long get() = (beforeCreatedAtMs - WINDOW_DURATION_MS).coerceAtLeast(0)

    companion object {
        const val WINDOW_DURATION_MS: Long = 56L * 24 * 60 * 60 * 1000
    }
}

/** Only explicitly approved, bounded text may enter this remote-history projection. */
@Serializable
data class MemorySession(
    val sessionId: SessionId,
    val profileId: ProfileId,
    val timestampMs: Long,
    val summaryVersion: Int,
    val inputRevision: Long,
    val dataOrigin: DataOrigin,
    val task: RecordingTask,
    val metrics: RecordingMetrics,
    val transcriptSnippet: String? = null,
    val topKeyword: String? = null,
    val completedAtMs: Long? = timestampMs,
) {
    init {
        require(timestampMs >= 0 && summaryVersion > 0 && inputRevision >= 0)
        require(completedAtMs == null || completedAtMs >= timestampMs)
        require(metrics.dataOrigin == dataOrigin)
        require(transcriptSnippet == null || transcriptSnippet.length <= 200)
        require(topKeyword == null || topKeyword.length in 1..40)
    }
}

@Serializable
enum class RawTreeMemoryStatus { AVAILABLE, INSUFFICIENT_HISTORY, UNAVAILABLE }

/** The provider maps this identifier to a checked-in query; callers cannot supply SQL. */
@Serializable
enum class MemoryQueryTemplate { BASELINE_VS_CURRENT_V1 }

/** Intentionally no free-form provider errors, request payloads, tokens, or transcript text. */
@Serializable
data class RawTreeMemoryDiagnostics(
    val templateId: MemoryQueryTemplate = MemoryQueryTemplate.BASELINE_VS_CURRENT_V1,
    val returnedRows: Int? = null,
    val latencyMs: Long? = null,
    val error: ErrorCode? = null,
) {
    init {
        require(returnedRows == null || returnedRows >= 0)
        require(latencyMs == null || latencyMs >= 0)
    }
}

@Serializable
data class RawTreeMemorySnapshot(
    val profileId: ProfileId,
    val currentSessionId: SessionId,
    val status: RawTreeMemoryStatus,
    /** Exact provider aggregates across this profile/origin, never the bounded page size. */
    val dataPointCount: Long?,
    val sessionCount: Long?,
    val previousSessions: List<MemorySession>,
    val currentSession: MemorySession? = null,
    val meanRecordingWpm: Double?,
    val meanEnergyRms: Double?,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val retrievedAtMs: Long,
    val cached: Boolean = false,
    val diagnostics: RawTreeMemoryDiagnostics,
) {
    init {
        require(windowStartMs >= 0 && windowEndMs >= windowStartMs && retrievedAtMs >= 0)
        require(windowEndMs - windowStartMs <= MemoryQuery.WINDOW_DURATION_MS)
        require(previousSessions.size <= 8)
        require(previousSessions.map { it.sessionId }.distinct().size == previousSessions.size)
        require(previousSessions.all {
            it.profileId == profileId && it.sessionId != currentSessionId &&
                it.metrics.quality == AudioQuality.ACCEPTED &&
                it.timestampMs >= windowStartMs && it.timestampMs < windowEndMs &&
                it.completedAtMs != null && it.completedAtMs < windowEndMs
        })
        require(currentSession == null ||
            (currentSession.profileId == profileId && currentSession.sessionId == currentSessionId))
        if (status == RawTreeMemoryStatus.UNAVAILABLE) {
            require(dataPointCount == null && sessionCount == null)
            require(previousSessions.isEmpty() && currentSession == null)
            require(meanRecordingWpm == null && meanEnergyRms == null)
        } else {
            require(dataPointCount != null && sessionCount != null)
            require(dataPointCount >= sessionCount && sessionCount >= 0)
            require((dataPointCount == 0L) == (sessionCount == 0L))
            require(sessionCount >= previousSessions.size + if (currentSession == null) 0 else 1)
            require(diagnostics.error == null)
            require((status == RawTreeMemoryStatus.AVAILABLE) == (previousSessions.size >= 2))
            if (status == RawTreeMemoryStatus.AVAILABLE) {
                require(meanRecordingWpm != null && meanRecordingWpm.isFinite() && meanRecordingWpm >= 0)
                require(meanEnergyRms != null && meanEnergyRms.isFinite() && meanEnergyRms in 0.0..1.0)
            } else {
                require(meanRecordingWpm == null && meanEnergyRms == null)
            }
        }
    }
}

object RawTreeMemoryMath {
    /**
     * Resolve revisions before eligibility filtering so an invalid or incomplete replacement
     * cannot resurrect an earlier summary. Aggregates must come from the provider separately.
     */
    fun snapshot(
        query: MemoryQuery,
        rows: List<MemorySession>,
        dataPointCount: Long?,
        sessionCount: Long?,
        retrievedAtMs: Long,
        diagnostics: RawTreeMemoryDiagnostics,
        currentSession: MemorySession? = null,
        cached: Boolean = false,
    ): RawTreeMemorySnapshot {
        if (diagnostics.error != null) return unavailable(query, retrievedAtMs, diagnostics, cached)
        if (dataPointCount == null || sessionCount == null || sessionCount < 0 ||
            dataPointCount < sessionCount || (dataPointCount == 0L) != (sessionCount == 0L)
        ) {
            return unavailable(query, retrievedAtMs, diagnostics.copy(error = ErrorCode.BAD_RESPONSE), cached)
        }

        val versionOrder = compareBy<MemorySession> { it.summaryVersion }
            .thenBy { it.inputRevision }.thenBy { it.completedAtMs ?: Long.MAX_VALUE }
        val latest = (rows + listOfNotNull(currentSession))
            .filter { it.profileId == query.profileId }
            .groupBy { it.sessionId }
            .values.map { versions -> versions.maxWith(versionOrder) }

        fun matches(row: MemorySession): Boolean = row.dataOrigin == query.dataOrigin &&
            row.task == query.task && row.metrics.quality == AudioQuality.ACCEPTED &&
            row.metrics.measurementVersion == query.measurementVersion &&
            row.metrics.lexicalVersion == query.lexicalVersion

        val previous = latest.filter {
            it.sessionId != query.currentSessionId && matches(it) &&
                it.timestampMs >= query.windowStartMs && it.timestampMs < query.beforeCreatedAtMs &&
                it.completedAtMs != null && it.completedAtMs < query.beforeCreatedAtMs
        }.sortedWith(compareByDescending<MemorySession> { it.timestampMs }.thenBy { it.sessionId.value })
            .take(query.limit)
        val current = latest.singleOrNull { it.sessionId == query.currentSessionId && matches(it) }
        if (sessionCount < previous.size + if (current == null) 0 else 1) {
            return unavailable(query, retrievedAtMs, diagnostics.copy(error = ErrorCode.BAD_RESPONSE), cached)
        }
        val enough = previous.size >= 2
        return RawTreeMemorySnapshot(
            profileId = query.profileId,
            currentSessionId = query.currentSessionId,
            status = if (enough) RawTreeMemoryStatus.AVAILABLE else RawTreeMemoryStatus.INSUFFICIENT_HISTORY,
            dataPointCount = dataPointCount,
            sessionCount = sessionCount,
            previousSessions = previous,
            currentSession = current,
            // Divide before summing to avoid overflow for otherwise finite input metrics.
            meanRecordingWpm = if (enough) previous.sumOf { it.metrics.recordingWpm / previous.size } else null,
            meanEnergyRms = if (enough) previous.sumOf { it.metrics.energyRms / previous.size } else null,
            windowStartMs = query.windowStartMs,
            windowEndMs = query.beforeCreatedAtMs,
            retrievedAtMs = retrievedAtMs,
            cached = cached,
            diagnostics = diagnostics,
        )
    }

    fun unavailable(
        query: MemoryQuery,
        retrievedAtMs: Long,
        diagnostics: RawTreeMemoryDiagnostics,
        cached: Boolean = false,
    ): RawTreeMemorySnapshot = RawTreeMemorySnapshot(
        profileId = query.profileId,
        currentSessionId = query.currentSessionId,
        status = RawTreeMemoryStatus.UNAVAILABLE,
        dataPointCount = null,
        sessionCount = null,
        previousSessions = emptyList(),
        meanRecordingWpm = null,
        meanEnergyRms = null,
        windowStartMs = query.windowStartMs,
        windowEndMs = query.beforeCreatedAtMs,
        retrievedAtMs = retrievedAtMs,
        cached = cached,
        diagnostics = diagnostics.copy(error = diagnostics.error ?: ErrorCode.UNAVAILABLE),
    )
}
