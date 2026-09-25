package com.clearline.agent

import com.clearline.core.*
import kotlin.math.sqrt

/** Versioned descriptive math only. It never classifies the speaker. */
object LocalMeasurements {
    fun aggregate(results: List<AudioResult>): RecordingMetrics? {
        if (results.isEmpty()) return null
        require(results.all { it.metrics.quality == AudioQuality.ACCEPTED })
        val first = results.first().metrics
        require(results.all { it.metrics.dataOrigin == first.dataOrigin && it.metrics.measurementVersion == first.measurementVersion && it.metrics.lexicalVersion == first.lexicalVersion })
        val duration = results.sumOf { it.metrics.durationSeconds }
        val words = results.sumOf { it.metrics.wordCount }
        val energy = sqrt(results.sumOf { it.metrics.energyRms * it.metrics.energyRms * it.metrics.durationSeconds } / duration)
        return first.copy(durationSeconds = duration, wordCount = words, recordingWpm = words * 60.0 / duration, energyRms = energy)
    }

    fun correct(result: AudioResult, text: String, nowMs: Long): AudioResult {
        val count = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*").findAll(text).count()
        if (count == 0) throw ClearLineException(AppError(ErrorCode.INVALID_INPUT, "The corrected transcript must contain words."))
        return result.copy(transcript = text, metrics = result.metrics.copy(wordCount = count,
            recordingWpm = count * 60.0 / result.metrics.durationSeconds), processedAtMs = nowMs)
    }

    fun compare(current: RecordingMetrics, baseline: BaselineSummary): DescriptiveComparison {
        if (baseline.status == BaselineStatus.INSUFFICIENT_HISTORY) return DescriptiveComparison(baseline, null, null)
        fun difference(value: Double, values: List<Double>): MetricDifference {
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            val deviation = sqrt(variance)
            return MetricDifference(value, mean, value - mean, if (deviation == 0.0) null else (value - mean) / deviation)
        }
        return DescriptiveComparison(baseline,
            difference(current.recordingWpm, baseline.previousSessions.map { it.metrics.recordingWpm }),
            difference(current.energyRms, baseline.previousSessions.map { it.metrics.energyRms }))
    }
}
