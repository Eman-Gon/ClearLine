package com.clearline.audio

import com.clearline.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PcmWaveTest {
    private fun wav(root: File, count: Int = 32000, sample: Short = 8192): File {
        val file = File(root, "clip.wav")
        val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { pcm.putShort(sample) }
        file.writeBytes(PcmWave.header(count) + pcm.array())
        return file
    }
    @Test fun actualSamplesDetermineRmsDurationAndRecordingWpm() {
        val root = Files.createTempDirectory("pcm-test").toFile()
        try {
            val pcm = PcmWave.read(wav(root))
            val result = AudioMeasurements.calculate(pcm, "We're checking in 2 times.", DataOrigin.CONSENTED_DEMO)
            assertEquals(2.0, result.durationSeconds, 0.0)
            assertEquals(0.25, result.energyRms, 0.0)
            assertEquals(5, result.wordCount)
            assertEquals(150.0, result.recordingWpm, 0.0)
            assertNull(result.pitchMeanHz); assertNull(result.pauseCount)
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsTruncatedAndRelabeledPcm() {
        val root = Files.createTempDirectory("pcm-test").toFile()
        try {
            val file = wav(root)
            file.appendBytes(byteArrayOf(1))
            assertThrows(ClearLineException::class.java) { PcmWave.read(file) }
            wav(root).let { val bytes = it.readBytes(); bytes[24] = 1; it.writeBytes(bytes) }
            assertThrows(ClearLineException::class.java) { PcmWave.read(file) }
        } finally { root.deleteRecursively() }
    }
    @Test fun silenceAndBriefClickCannotBeRescuedByInventedTranscript() {
        val silence = PcmSamples(ShortArray(32000))
        assertThrows(ClearLineException::class.java) { AudioMeasurements.calculate(silence, "Thanks for watching", DataOrigin.CONSENTED_DEMO) }
        val click = ShortArray(32000); click[0] = Short.MAX_VALUE
        assertThrows(ClearLineException::class.java) { PcmSamples(click).requireUsable() }
    }
    @Test fun noWordsRemainNoSpeech() {
        assertThrows(ClearLineException::class.java) { AudioMeasurements.calculate(PcmSamples(ShortArray(32000) { 2000 }), " ... ", DataOrigin.CONSENTED_DEMO) }
    }
    @Test fun partialCannotBeReadAndPromotionIsIdempotent() {
        val root = Files.createTempDirectory("pcm-test").toFile()
        try {
            val complete = wav(root); val bytes = complete.readBytes(); complete.delete()
            val part = File(root, "clip.wav.part"); part.writeBytes(bytes)
            assertThrows(ClearLineException::class.java) { PcmWave.read(part) }
            PcmWave.finalize(part, complete, 32000)
            assertFalse(part.exists()); assertTrue(complete.exists())
            part.writeBytes(bytes); PcmWave.finalize(part, complete, 32000)
            assertFalse(part.exists())
            val originalSha = PcmWave.sha256(complete)
            part.writeBytes(bytes.apply { this[44] = 42 })
            assertThrows(ClearLineException::class.java) { PcmWave.finalize(part, complete, 32000) }
            assertEquals(originalSha, PcmWave.sha256(complete))
        } finally { root.deleteRecursively() }
    }
    @Test fun lexicalVersionHandlesContractionsAndSeparators() {
        assertEquals(7, AudioMeasurements.wordCount("don't re-enter; Mary’s cat_2 2026"))
    }
}
