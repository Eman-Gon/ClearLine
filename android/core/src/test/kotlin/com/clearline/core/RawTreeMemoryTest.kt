package com.clearline.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RawTreeMemoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val cutoff = 100 * day
    private val profile = ProfileId.new()
    private val current = SessionId.new()
    private val query = MemoryQuery(profile, current, DataOrigin.CONSENTED_DEMO,
        RecordingTask.CHECK_IN, "android-pcm-v1", "english-lexical-v1", cutoff)

    private fun row(
        sessionId: SessionId = SessionId.new(),
        timestamp: Long = cutoff - day,
        version: Int = 1,
        revision: Long = 0,
        wpm: Double = 120.0,
        rms: Double = .2,
        origin: DataOrigin = DataOrigin.CONSENTED_DEMO,
    ) = MemorySession(sessionId, profile, timestamp, version, revision, origin,
        RecordingTask.CHECK_IN, RecordingMetrics(30.0, 60, wpm, rms, dataOrigin = origin),
        transcriptSnippet = "had a good week", topKeyword = "week")

    private fun snapshot(
        rows: List<MemorySession>,
        points: Long? = 847,
        sessions: Long? = 99,
        request: MemoryQuery = query,
        diagnostics: RawTreeMemoryDiagnostics = RawTreeMemoryDiagnostics(returnedRows = rows.size, latencyMs = 84),
    ) = RawTreeMemoryMath.snapshot(request, rows, points, sessions, cutoff + day, diagnostics)

    @Test fun latestVersionsAreDeduplicatedBeforeComputingTheBaseline() {
        val first = row(wpm = 90.0)
        val replacement = first.copy(summaryVersion = 2, inputRevision = 1,
            metrics = first.metrics.copy(recordingWpm = 110.0), transcriptSnippet = "feeling okay")
        val second = row(timestamp = cutoff - 2 * day, wpm = 150.0, rms = .4)
        val result = snapshot(listOf(replacement, first, second, replacement))
        assertEquals(RawTreeMemoryStatus.AVAILABLE, result.status)
        assertEquals(listOf(replacement, second), result.previousSessions)
        assertEquals(130.0, result.meanRecordingWpm!!, 1e-9)
        assertEquals(.3, result.meanEnergyRms!!, 1e-9)
        assertEquals(847L, result.dataPointCount)
        assertEquals(99L, result.sessionCount)
    }

    @Test fun conflictingEqualVersionsFailRegardlessOfRowOrder() {
        val original = row()
        val otherSession = row(timestamp = cutoff - 2 * day)
        val conflicts = listOf(
            original.copy(metrics = original.metrics.copy(recordingWpm = 150.0)),
            original.copy(transcriptSnippet = "different content"),
        )
        for (conflict in conflicts) {
            for (rows in listOf(listOf(original, conflict, otherSession), listOf(otherSession, conflict, original))) {
                val result = snapshot(rows)
                assertEquals(RawTreeMemoryStatus.UNAVAILABLE, result.status)
                assertEquals(ErrorCode.BAD_RESPONSE, result.diagnostics.error)
                assertNull(result.dataPointCount)
                assertNull(result.meanRecordingWpm)
            }
        }
        // Structurally identical deliveries remain valid replays, including copied instances.
        val replay = snapshot(listOf(original, original.copy(), otherSession))
        assertEquals(RawTreeMemoryStatus.AVAILABLE, replay.status)
        assertEquals(listOf(original, otherSession), replay.previousSessions)
    }

    @Test fun conflictingCurrentSessionCannotChooseBetweenPageAndSeparateRow() {
        val currentRow = row(sessionId = current, timestamp = cutoff)
        val result = RawTreeMemoryMath.snapshot(query, listOf(row(), row(), currentRow),
            847, 99, cutoff + day, RawTreeMemoryDiagnostics(returnedRows = 3, latencyMs = 84),
            currentSession = currentRow.copy(topKeyword = "different"))
        assertEquals(RawTreeMemoryStatus.UNAVAILABLE, result.status)
        assertEquals(ErrorCode.BAD_RESPONSE, result.diagnostics.error)
        assertNull(result.currentSession)
    }

    @Test fun finiteExtremePositiveValuesProduceFiniteMeansWithoutOverflow() {
        val maximumRows = (1..3).map { row(timestamp = cutoff - it * day, wpm = Double.MAX_VALUE, rms = 1.0) }
        val result = snapshot(maximumRows)
        assertEquals(RawTreeMemoryStatus.AVAILABLE, result.status)
        assertEquals(Double.MAX_VALUE, result.meanRecordingWpm!!, 0.0)
        assertEquals(1.0, result.meanEnergyRms!!, 0.0)
        val zero = snapshot((1..3).map { row(timestamp = cutoff - it * day, wpm = 0.0, rms = 0.0) })
        assertEquals(0.0, zero.meanRecordingWpm!!, 0.0)
        assertEquals(0.0, zero.meanEnergyRms!!, 0.0)
    }

    @Test fun currentIsDisplayedSeparatelyAndWindowAndCompletionBoundariesAreStrict() {
        val boundary = row(timestamp = query.windowStartMs)
        val inWindow = row(timestamp = cutoff - 1)
        val present = row(sessionId = current, timestamp = cutoff, wpm = 900.0)
        val rows = listOf(boundary, inWindow, present,
            row(timestamp = query.windowStartMs - 1), row(timestamp = cutoff),
            row(timestamp = cutoff - day).copy(completedAtMs = cutoff),
            row(timestamp = cutoff - day).copy(completedAtMs = null))
        val result = snapshot(rows)
        assertEquals(listOf(inWindow, boundary), result.previousSessions)
        assertEquals(present, result.currentSession)
        assertEquals(120.0, result.meanRecordingWpm!!, 0.0)
        assertEquals(query.windowStartMs, result.windowStartMs)
        assertEquals(cutoff, result.windowEndMs)
    }

    @Test fun incompatibleAndRejectedRevisionsNeverFallBackToAnOlderSummary() {
        val first = row()
        val rejected = first.copy(summaryVersion = 2, metrics = first.metrics.copy(quality = AudioQuality.REJECTED))
        val second = row()
        val incomplete = second.copy(summaryVersion = 2, completedAtMs = null)
        val third = row()
        val newerMethod = third.copy(summaryVersion = 2, metrics = third.metrics.copy(measurementVersion = "next"))
        val fourth = row()
        val newerLexical = fourth.copy(summaryVersion = 2, metrics = fourth.metrics.copy(lexicalVersion = "next"))
        val result = snapshot(listOf(first, rejected, second, incomplete, third, newerMethod, fourth, newerLexical))
        assertEquals(RawTreeMemoryStatus.INSUFFICIENT_HISTORY, result.status)
        assertTrue(result.previousSessions.isEmpty())
        assertNull(result.meanRecordingWpm)
    }

    @Test fun profilesAndOriginsAreKeptSeparate() {
        val live = row()
        val foreign = live.copy(profileId = ProfileId.new(), summaryVersion = 10)
        val synthetic = row(origin = DataOrigin.SYNTHETIC)
        val liveResult = snapshot(listOf(live, foreign, synthetic))
        assertEquals(listOf(live), liveResult.previousSessions)
        assertEquals(RawTreeMemoryStatus.INSUFFICIENT_HISTORY, liveResult.status)
        assertNull(liveResult.meanEnergyRms)
        val syntheticResult = snapshot(listOf(live, foreign, synthetic), request = query.copy(dataOrigin = DataOrigin.SYNTHETIC))
        assertEquals(listOf(synthetic), syntheticResult.previousSessions)
    }

    @Test fun onlyTheLatestEightEligibleSessionsAreUsedAndCountsAreNotPageSizes() {
        val rows = (1..12).map { row(timestamp = cutoff - it * day, wpm = it * 10.0) }
        val result = snapshot(rows.reversed())
        assertEquals(rows.take(8), result.previousSessions)
        assertEquals(45.0, result.meanRecordingWpm!!, 0.0)
        assertEquals(847L, result.dataPointCount)
        assertEquals(99L, result.sessionCount)
        val limited = snapshot(rows, request = query.copy(limit = 1))
        assertEquals(RawTreeMemoryStatus.INSUFFICIENT_HISTORY, limited.status)
        assertEquals(rows.take(1), limited.previousSessions)
    }

    @Test fun zeroCountsAreAnEmptySuccessfulQueryAndMissingCountsAreUnavailable() {
        val empty = snapshot(emptyList(), points = 0, sessions = 0)
        assertEquals(RawTreeMemoryStatus.INSUFFICIENT_HISTORY, empty.status)
        assertEquals(0L, empty.dataPointCount)
        assertEquals(0L, empty.sessionCount)
        assertNull(empty.diagnostics.error)
        for ((points, sessions) in listOf(null to 2L, 2L to null, null to null,
            -1L to 0L, 1L to -1L, 1L to 2L, 1L to 0L, 0L to 0L)) {
            val unavailable = snapshot(listOf(row()), points, sessions)
            assertEquals(RawTreeMemoryStatus.UNAVAILABLE, unavailable.status)
            assertNull(unavailable.dataPointCount)
            assertNull(unavailable.sessionCount)
            assertTrue(unavailable.previousSessions.isEmpty())
            assertNull(unavailable.meanRecordingWpm)
            assertEquals(ErrorCode.BAD_RESPONSE, unavailable.diagnostics.error)
        }
    }

    @Test fun providerFailureIsUnavailableAndDoesNotFabricateCountsOrUseReturnedRows() {
        val diagnostics = RawTreeMemoryDiagnostics(returnedRows = 2, latencyMs = 84, error = ErrorCode.TIMEOUT)
        val result = snapshot(listOf(row(), row()), diagnostics = diagnostics)
        assertEquals(RawTreeMemoryStatus.UNAVAILABLE, result.status)
        assertNull(result.dataPointCount)
        assertNull(result.sessionCount)
        assertEquals(ErrorCode.TIMEOUT, result.diagnostics.error)
        val unavailable = RawTreeMemoryMath.unavailable(query, cutoff, RawTreeMemoryDiagnostics())
        assertEquals(ErrorCode.UNAVAILABLE, unavailable.diagnostics.error)
    }

    @Test fun boundedTypedContractsRejectOversizedTextAndUnknownQueryOrErrorFields() {
        assertThrows(IllegalArgumentException::class.java) { query.copy(limit = 9) }
        assertThrows(IllegalArgumentException::class.java) { query.copy(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { query.copy(beforeCreatedAtMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { row().copy(transcriptSnippet = "x".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { row().copy(topKeyword = "x".repeat(41)) }
        assertThrows(IllegalArgumentException::class.java) { RawTreeMemoryDiagnostics(returnedRows = -1) }
        assertThrows(IllegalArgumentException::class.java) { RawTreeMemoryDiagnostics(latencyMs = -1) }
        assertThrows(Exception::class.java) { Json.decodeFromString<RawTreeMemoryDiagnostics>("{\"templateId\":\"SELECT * FROM calls\"}") }
        assertThrows(Exception::class.java) { Json.decodeFromString<RawTreeMemoryDiagnostics>("{\"token\":\"private\"}") }
        assertEquals(0L, query.copy(beforeCreatedAtMs = day).windowStartMs)
    }

    @Test fun snapshotRoundTripsWithCurrentRowAndCacheProvenance() {
        val rows = listOf(row(), row(timestamp = cutoff - 2 * day), row(sessionId = current, timestamp = cutoff))
        val result = snapshot(rows).copy(cached = true)
        assertEquals(result, Json.decodeFromString<RawTreeMemorySnapshot>(Json.encodeToString(result)))
    }
}
