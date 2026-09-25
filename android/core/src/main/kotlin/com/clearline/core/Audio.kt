package com.clearline.core

import kotlinx.serialization.Serializable

@Serializable data class PcmFormat(val sampleRateHz: Int = 16000, val channels: Int = 1, val bitsPerSample: Int = 16, val encoding: String = "PCM_SIGNED_LE") {
    init { require(sampleRateHz == 16000 && channels == 1 && bitsPerSample == 16 && encoding == "PCM_SIGNED_LE") }
}
@Serializable data class CompletedLocalClip(
    val sessionId: SessionId,
    val clipId: ClipId,
    val privatePath: String,
    val sha256: String,
    val durationSeconds: Double,
    val dataOrigin: DataOrigin,
    val format: PcmFormat = PcmFormat(),
    val measurementVersion: String = "android-pcm-v1",
    val createdAtMs: Long,
    val supersedesClipId: ClipId? = null,
) {
    init { require(privatePath.isNotBlank() && !privatePath.endsWith(".part")); require(sha256.matches(Regex("[0-9a-f]{64}"))); require(durationSeconds.isFinite() && durationSeconds in 2.0..60.0); require(measurementVersion.length in 1..64) }
}
@Serializable data class ClipReceipt(val sessionId: SessionId, val clipId: ClipId, val sha256: String, val acceptedAtMs: Long, val duplicate: Boolean = false)
@Serializable enum class AudioQuality { ACCEPTED, REJECTED }
@Serializable data class RecordingMetrics(
    val durationSeconds: Double,
    val wordCount: Int,
    val recordingWpm: Double,
    val energyRms: Double,
    val pauseCount: Int? = null,
    val pitchMeanHz: Double? = null,
    val quality: AudioQuality = AudioQuality.ACCEPTED,
    val qualityReasons: List<String> = emptyList(),
    val dataOrigin: DataOrigin,
    val measurementVersion: String = "android-pcm-v1",
    val lexicalVersion: String = "english-lexical-v1",
) {
    init { require(durationSeconds.isFinite() && durationSeconds > 0); require(wordCount >= 0); require(recordingWpm.isFinite() && recordingWpm >= 0); require(energyRms.isFinite() && energyRms in 0.0..1.0); require(pauseCount == null && pitchMeanHz == null); require(qualityReasons.size <= 16 && qualityReasons.all { it.length <= 128 }); require(measurementVersion.length in 1..64 && lexicalVersion.length in 1..64) }
}
@Serializable data class AudioResult(val clipId: ClipId, val transcript: String, val metrics: RecordingMetrics, val asrModelId: ModelId, val processedAtMs: Long) { init { require(transcript.length <= 20000) } }
@Serializable data class StoredClip(val clip: CompletedLocalClip, val receipt: ClipReceipt, val result: AudioResult? = null, val supersededBy: ClipId? = null, val audioDeleted: Boolean = false)
interface LocalAudioProcessor { suspend fun process(clip: CompletedLocalClip): AudioResult }
