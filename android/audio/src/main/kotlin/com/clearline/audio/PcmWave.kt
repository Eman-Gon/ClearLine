package com.clearline.audio

import com.clearline.core.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Canonical, deliberately narrow WAV encoding: RIFF/PCM16/mono/16 kHz, 44-byte header. */
object PcmWave {
    const val SAMPLE_RATE = 16000
    const val MIN_SAMPLES = SAMPLE_RATE * 2
    const val MAX_SAMPLES = SAMPLE_RATE * 60
    const val TARGET_SAMPLES = SAMPLE_RATE * 30
    fun header(sampleCount: Int): ByteArray {
        require(sampleCount in 0..MAX_SAMPLES)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + sampleCount * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(sampleCount * 2)
        }.array()
    }
    fun read(file: File): PcmSamples {
        if (file.name.endsWith(".part") || file.length() !in (44L + MIN_SAMPLES * 2)..(44L + MAX_SAMPLES * 2)) invalid()
        RandomAccessFile(file, "r").use { input ->
            val hdr = ByteArray(44); input.readFully(hdr)
            val count = ((file.length() - 44) / 2).toInt()
            if (!hdr.contentEquals(header(count)) || file.length() != 44L + count * 2) invalid()
            val bytes = ByteArray(count * 2); input.readFully(bytes)
            val shorts = ShortArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return PcmSamples(shorts)
        }
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    /** A receipt is only created after a complete header, fsync, exact validation and atomic rename. */
    @Synchronized
    fun finalize(part: File, target: File, sampleCount: Int): PcmSamples {
        if (sampleCount !in MIN_SAMPLES..MAX_SAMPLES || part.length() != 44L + sampleCount * 2) invalid()
        RandomAccessFile(part, "rw").use { it.seek(0); it.write(header(sampleCount)); it.fd.sync() }
        if (target.exists()) {
            if (sha256(part) != sha256(target)) throw ClearLineException(AppError(ErrorCode.IDENTITY_CONFLICT, "Clip identity already belongs to different audio."))
            part.delete()
        } else Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return read(target)
    }
    private fun invalid(): Nothing = throw ClearLineException(AppError(ErrorCode.INVALID_AUDIO, "Audio must be complete mono 16 kHz PCM16 WAV, between 2 and 60 seconds."))
}

class PcmSamples(val samples: ShortArray) {
    val durationSeconds: Double = samples.size.toDouble() / PcmWave.SAMPLE_RATE
    val energyRms: Double = rms(samples, samples.size)
    val peak: Double = samples.maxOfOrNull { abs(it.toInt()) / 32768.0 } ?: 0.0
    val audibleFrameCount: Int = run {
        var audible = 0
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + 320, samples.size)
            var sum = 0.0
            for (index in start until end) { val v = samples[index] / 32768.0; sum += v * v }
            if (sqrt(sum / (end - start)) >= 0.003) audible++
            start = end
        }
        audible
    }
    fun requireUsable() {
        // Engineering filters only; not voice diagnosis or calibrated speech activity detection.
        if (energyRms < 0.0005 || peak < 0.003 || audibleFrameCount < 10)
            throw ClearLineException(AppError(ErrorCode.SILENT_AUDIO, "Too little audible audio. Please make a new recording.", true))
    }
    fun normalized() = FloatArray(samples.size) { samples[it] / 32768f }
    companion object {
        fun rms(samples: ShortArray, count: Int): Double {
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) { val s = samples[i] / 32768.0; sum += s * s }
            return sqrt(sum / count)
        }
    }
}

object AudioMeasurements {
    /** english-lexical-v1: Unicode letters/numbers, internal straight/curly apostrophes; no underscores. */
    fun wordCount(transcript: String): Int = MeasurementMath.countEnglishWords(transcript)
    fun calculate(pcm: PcmSamples, transcript: String, origin: DataOrigin): RecordingMetrics {
        pcm.requireUsable()
        val words = wordCount(transcript)
        if (words == 0) throw ClearLineException(AppError(ErrorCode.NO_SPEECH, "No usable English speech was found. Please record again.", true))
        return RecordingMetrics(pcm.durationSeconds, words, words * 60.0 / pcm.durationSeconds, pcm.energyRms, dataOrigin = origin)
    }
}
