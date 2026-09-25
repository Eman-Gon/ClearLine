package com.clearline.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clearline.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in capture tests need a consenting speaker and RECORD_AUDIO granted by the UI beforehand. */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun nativeLibraryLoadsAndMissingModelDoesNotPretendReady() = runBlocking {
        val runtime = WhisperAsrRuntime(context, MutexModelArbiter())
        runtime.refreshInstalledStatus()
        if (runtime.status.value.phase == ModelPhase.MISSING) {
            try { runtime.load(WhisperModelManifest.identity.modelId); fail("Missing model unexpectedly loaded") }
            catch (expected: ClearLineException) { assertEquals(ErrorCode.MISSING_MODEL, expected.error.code) }
            assertNotEquals(ModelPhase.READY, runtime.status.value.phase)
        }
        assertThrows(IllegalStateException::class.java) { NativeWhisperBridge.load("/does-not-exist/whisper.bin") }
    }
    @Test fun explicitLiveCaptureFinalizesRealPcmAndTranscribesLocally() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("consentedAudio") == "true")
        assumeTrue(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val runtime = WhisperAsrRuntime(context, MutexModelArbiter())
        runtime.refreshInstalledStatus()
        assumeTrue(runtime.status.value.phase == ModelPhase.INSTALLED)
        runtime.load(WhisperModelManifest.identity.modelId)
        val recorder = PcmAudioRecorder(context, this)
        recorder.start(SessionId.new(), ClipId.new())
        delay(10000) // Speaker reads a non-sensitive English sentence during this explicit test.
        val clip = recorder.stop()
        assertNotNull(clip)
        try {
            val result = WhisperAudioProcessor(context, runtime).process(clip!!)
            assertTrue(result.transcript.isNotBlank()); assertTrue(result.metrics.wordCount > 0)
            assertEquals(DataOrigin.CONSENTED_DEMO, result.metrics.dataOrigin)
            assertTrue(result.metrics.durationSeconds in 2.0..11.0)
        } finally { clip?.let { java.io.File(it.privatePath).delete() }; runtime.unload() }
    }
    @Test fun explicitCaptureInterruptionDiscardsPartialFile() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("consentedAudio") == "true")
        assumeTrue(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val recorder = PcmAudioRecorder(context, this)
        val sessionId = SessionId.new()
        recorder.start(sessionId, ClipId.new()); delay(1000); recorder.interrupt()
        assertTrue(recorder.state.value is CaptureState.Interrupted)
        assertFalse(java.io.File(recorder.audioDirectory, sessionId.value).walkTopDown().any { it.isFile })
    }
}
