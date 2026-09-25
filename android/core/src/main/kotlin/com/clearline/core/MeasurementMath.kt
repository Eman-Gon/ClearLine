package com.clearline.core

import kotlin.math.sqrt

object MeasurementMath {
    /** Unicode letters/numbers; an apostrophe inside a word stays one word. No punctuation-only tokens. */
    private val word = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    fun countEnglishWords(transcript: String): Int = word.findAll(transcript).count()
    fun correctTranscript(metrics: RecordingMetrics, transcript: String): RecordingMetrics {
        val words = countEnglishWords(transcript)
        return metrics.copy(wordCount = words, recordingWpm = words * 60.0 / metrics.durationSeconds)
    }
    fun pool(metrics: List<RecordingMetrics>): RecordingMetrics {
        require(metrics.isNotEmpty() && metrics.size <= 20)
        val first = metrics.first()
        require(metrics.all { it.quality == AudioQuality.ACCEPTED && it.dataOrigin == first.dataOrigin && it.measurementVersion == first.measurementVersion && it.lexicalVersion == first.lexicalVersion })
        val duration = metrics.sumOf { it.durationSeconds }
        val words = metrics.sumOf { it.wordCount }
        val energy = sqrt(metrics.sumOf { it.energyRms * it.energyRms * it.durationSeconds } / duration)
        return first.copy(durationSeconds = duration, wordCount = words, recordingWpm = words * 60.0 / duration, energyRms = energy, qualityReasons = emptyList())
    }
    fun baseline(previousSessions: List<SessionSummary>): BaselineSummary {
        require(previousSessions.size <= 5)
        val enough = previousSessions.size >= 2
        return BaselineSummary(if (enough) BaselineStatus.AVAILABLE else BaselineStatus.INSUFFICIENT_HISTORY, previousSessions, if (enough) previousSessions.map { it.metrics.recordingWpm }.average() else null, if (enough) previousSessions.map { it.metrics.energyRms }.average() else null)
    }
    fun compare(current: RecordingMetrics, baseline: BaselineSummary): DescriptiveComparison {
        if (baseline.status != BaselineStatus.AVAILABLE) return DescriptiveComparison(baseline, null, null)
        fun difference(value: Double, values: List<Double>): MetricDifference {
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            return MetricDifference(value, mean, value - mean, if (variance > 0.0) (value - mean) / sqrt(variance) else null)
        }
        return DescriptiveComparison(baseline, difference(current.recordingWpm, baseline.previousSessions.map { it.metrics.recordingWpm }), difference(current.energyRms, baseline.previousSessions.map { it.metrics.energyRms }))
    }
}
