package com.clearline.agent

import com.clearline.core.*

/** Versioned descriptive math only. It never classifies the speaker. */
object LocalMeasurements {
    fun aggregate(results: List<AudioResult>): RecordingMetrics? {
        if (results.isEmpty()) return null
        return MeasurementMath.pool(results.map { it.metrics })
    }

    fun correct(result: AudioResult, text: String): AudioResult {
        val count = MeasurementMath.countEnglishWords(text)
        if (count == 0) throw ClearLineException(AppError(ErrorCode.INVALID_INPUT, "The corrected transcript must contain words."))
        return result.copy(transcript = text, metrics = MeasurementMath.correctTranscript(result.metrics, text))
    }

    fun compare(current: RecordingMetrics, baseline: BaselineSummary) = MeasurementMath.compare(current, baseline)
}
