package com.clearline.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CoreContractsTest {
    private fun metrics(duration: Double = 20.0, words: Int = 40, rms: Double = .1) = RecordingMetrics(duration, words, words * 60.0 / duration, rms, dataOrigin = DataOrigin.CONSENTED_DEMO)
    @Test fun pooledWpmUsesFullDurationAndPooledRmsUsesSquaredEnergy() {
        val pooled = MeasurementMath.pool(listOf(metrics(10.0, 10, .2), metrics(30.0, 90, .6)))
        assertEquals(150.0, pooled.recordingWpm, 1e-9)
        assertEquals(kotlin.math.sqrt(.28), pooled.energyRms, 1e-9)
    }
    @Test fun correctionOnlyChangesWordDerivedMeasurements() {
        val original = metrics()
        val corrected = MeasurementMath.correctTranscript(original, "I’m well; don't worry. Twenty-one.")
        assertEquals(6, corrected.wordCount)
        assertEquals(original.energyRms, corrected.energyRms, 0.0)
        assertEquals(original.durationSeconds, corrected.durationSeconds, 0.0)
    }
    @Test fun baselineNeedsTwoAndZeroVarianceHasNoStandardizedDifference() {
        val p = ProfileId.new()
        fun summary() = SessionSummary(SessionId.new(), p, 1, 0, RecordingTask.CHECK_IN, DataOrigin.CONSENTED_DEMO, metrics(), 1, listOf(ClipId.new()))
        assertEquals(BaselineStatus.INSUFFICIENT_HISTORY, MeasurementMath.baseline(listOf(summary())).status)
        val result = MeasurementMath.compare(metrics(words = 50), MeasurementMath.baseline(listOf(summary(), summary())))
        assertEquals(30.0, result.recordingWpm!!.delta, 0.0)
        assertNull(result.recordingWpm.standardizedDifference)
    }
    @Test fun secretIsRedactedAndTemporaryBufferIsCleared() {
        val secret = SecretValue("canary-token".toCharArray())
        var copy: CharArray? = null
        secret.useSecret { copy = it }
        assertEquals("SecretValue([REDACTED])", secret.toString())
        assertTrue(copy!!.all { it == '\u0000' })
        secret.close()
        assertThrows(IllegalStateException::class.java) { secret.useSecret { } }
    }
    @Test fun typedActionsRoundTripAndUnknownFieldsFailClosed() {
        val action: ProposedAction = ProposedAction.ExtractPublicPage("candidate-one")
        val json = Json.encodeToString(action)
        assertEquals(action, Json.decodeFromString<ProposedAction>(json))
        assertThrows(Exception::class.java) { Json.decodeFromString<ProposedAction>(json.dropLast(1) + ",\"sql\":\"SELECT secret\"}") }
    }
}
