package com.clearline.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.clearline.core.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Recording(val elapsedMs: Long, val levelRms: Double) : CaptureState
    data class Finalized(val clip: CompletedLocalClip) : CaptureState
    data class Interrupted(val reason: String = "Recording interrupted. Please record a replacement.") : CaptureState
    data class Failed(val error: AppError) : CaptureState
}

/** The Activity obtains permission first, and calls interrupt on backgrounding. Never a service. */
class PcmAudioRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val onFinalized: (CompletedLocalClip) -> Unit = {},
) {
    private val context = context.applicationContext
    val audioDirectory = File(context.noBackupFilesDir, "audio")
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    private val commands = Mutex()
    private val stopRequested = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private var task: Deferred<CompletedLocalClip?>? = null

    suspend fun start(sessionId: SessionId, clipId: ClipId, origin: DataOrigin = DataOrigin.CONSENTED_DEMO) = commands.withLock {
        check(task?.isActive != true) { "Recording already active" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = CaptureState.Failed(AppError(ErrorCode.PERMISSION_DENIED, "Microphone permission is required.")); return@withLock
        }
        require(origin == DataOrigin.CONSENTED_DEMO) { "Microphone audio cannot be synthetic" }
        stopRequested.set(false); interrupted.set(false)
        mutableState.value = CaptureState.Recording(0, 0.0)
        task = scope.async(Dispatchers.IO) { capture(sessionId, clipId, origin) }
    }
    suspend fun stop(): CompletedLocalClip? {
        val running = commands.withLock { stopRequested.set(true); task }
        return running?.await() ?: (state.value as? CaptureState.Finalized)?.clip
    }
    /** Nonblocking lifecycle edge: prevents a queued capture from activating the microphone. */
    fun requestInterruption() {
        interrupted.set(true)
        stopRequested.set(true)
    }
    suspend fun interrupt() {
        requestInterruption()
        val running = commands.withLock { task }
        running?.join()
    }
    /** Call once at startup before capture; complete audio is retained for durable recovery. */
    suspend fun cleanPartialFiles() = withContext(Dispatchers.IO) {
        commands.withLock { check(task?.isActive != true); audioDirectory.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() } }
    }
    private suspend fun capture(sessionId: SessionId, clipId: ClipId, origin: DataOrigin): CompletedLocalClip? {
        var recorder: AudioRecord? = null
        val directory = File(audioDirectory, sessionId.value)
        val part = File(directory, "${clipId.value}.wav.part")
        val target = File(directory, "${clipId.value}.wav")
        try {
            check(directory.mkdirs() || directory.isDirectory)
            if (target.exists()) throw ClearLineException(AppError(ErrorCode.IDENTITY_CONFLICT, "Use a new clip identity for a new recording."))
            if (directory.usableSpace < 4L * 1024 * 1024) throw ClearLineException(AppError(ErrorCode.STORAGE_FULL, "Free storage is needed to record audio.", true))
            val min = AudioRecord.getMinBufferSize(PcmWave.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw ClearLineException(AppError(ErrorCode.NO_MICROPHONE, "16 kHz microphone capture is unavailable."))
            val activeRecorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(PcmWave.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, 8192)).build()
            recorder = activeRecorder
            if (activeRecorder.state != AudioRecord.STATE_INITIALIZED || activeRecorder.sampleRate != PcmWave.SAMPLE_RATE)
                throw ClearLineException(AppError(ErrorCode.NO_MICROPHONE, "16 kHz microphone capture is unavailable."))
            if (interrupted.get()) throw CancellationException("Capture interrupted before microphone activation")
            activeRecorder.startRecording()
            if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw ClearLineException(AppError(ErrorCode.NO_MICROPHONE, "Microphone capture could not start."))
            val started = SystemClock.elapsedRealtime()
            var count = 0
            RandomAccessFile(part, "rw").use { output ->
                output.setLength(0); output.write(PcmWave.header(0))
                val buffer = ShortArray(1600)
                val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (!stopRequested.get() && count < PcmWave.TARGET_SAMPLES) {
                    currentCoroutineContext().ensureActive()
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        throw ClearLineException(AppError(ErrorCode.PERMISSION_DENIED, "Microphone permission was revoked."))
                    if (SystemClock.elapsedRealtime() - started > 45000) throw ClearLineException(AppError(ErrorCode.TIMEOUT, "Microphone capture timed out. Please record again.", true))
                    val read = activeRecorder.read(buffer, 0, minOf(buffer.size, PcmWave.TARGET_SAMPLES - count), AudioRecord.READ_NON_BLOCKING)
                    if (read < 0) throw ClearLineException(AppError(ErrorCode.INVALID_AUDIO, "Microphone capture failed. Please record again.", true))
                    if (read == 0) { delay(8); continue }
                    bytes.clear(); repeat(read) { bytes.putShort(buffer[it]) }; output.write(bytes.array(), 0, read * 2)
                    count += read
                    mutableState.value = CaptureState.Recording(count * 1000L / PcmWave.SAMPLE_RATE, PcmSamples.rms(buffer, read))
                }
                output.fd.sync()
            }
            activeRecorder.stop(); activeRecorder.release(); recorder = null
            if (interrupted.get()) { mutableState.value = CaptureState.Interrupted(); return null }
            val pcm = PcmWave.finalize(part, target, count)
            // Persist rename metadata before returning the durable complete-file receipt.
            val dirFd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
            try { Os.fsync(dirFd) } finally { Os.close(dirFd) }
            val clip = CompletedLocalClip(sessionId, clipId, target.canonicalPath, PcmWave.sha256(target), pcm.durationSeconds, origin, createdAtMs = System.currentTimeMillis())
            mutableState.value = CaptureState.Finalized(clip)
            // One hardware completion event, separate from replaying UI state. The app handler
            // only schedules durable admission. A handler failure cannot invalidate complete WAV.
            runCatching { onFinalized(clip) }
            return clip
        } catch (cancelled: CancellationException) {
            mutableState.value = CaptureState.Interrupted(); throw cancelled
        } catch (failure: Exception) {
            val error = when (failure) {
                is ClearLineException -> failure.error
                is SecurityException -> AppError(ErrorCode.PERMISSION_DENIED, "Microphone permission is required.")
                is IOException -> AppError(ErrorCode.STORAGE_FULL, "Audio could not be saved. Check free storage.", true)
                else -> AppError(ErrorCode.INVALID_AUDIO, "Recording failed. Please record again.", true)
            }
            mutableState.value = CaptureState.Failed(error)
            return null
        } finally {
            recorder?.let { runCatching { it.stop() }; it.release() }
            part.delete()
        }
    }
}
