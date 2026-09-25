package com.clearline.audio

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.clearline.core.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WhisperAsrRuntime(context: Context, private val arbiter: SharedModelArbiter) : AsrModelRuntime {
    private val context = context.applicationContext
    private val directory = File(context.noBackupFilesDir, "models/whisper")
    private val target = File(directory, WhisperModelManifest.identity.filename)
    private val installer = AtomicModelInstaller()
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(ModelStatus(ModelKind.WHISPER))
    override val status: StateFlow<ModelStatus> = mutableStatus.asStateFlow()
    @Volatile private var handle = 0L

    /** Only checks local bytes. No network or inference is triggered by readiness observation. */
    suspend fun refreshInstalledStatus() = mutex.withLock { withContext(Dispatchers.IO) {
        if (handle != 0L) return@withContext
        directory.mkdirs(); File(directory, "${target.name}.part").delete()
        if (!target.exists()) { set(ModelPhase.MISSING); return@withContext }
        set(ModelPhase.VERIFYING)
        if (installer.verified(target, WhisperModelManifest.identity.sizeBytes, WhisperModelManifest.identity.sha256)) set(ModelPhase.INSTALLED)
        else set(ModelPhase.FAILED, AppError(ErrorCode.MODEL_FAILED, "Transcription model integrity check failed. Install it again."))
    } }
    override suspend fun install(artifact: ApprovedModelArtifact) = mutex.withLock { withContext(Dispatchers.IO) {
        require(artifact.identity == WhisperModelManifest.identity && artifact.approvedAtMs > 0) { "Only the approved pinned transcription model can be installed" }
        require(handle == 0L) { "Unload the transcription model before replacing it" }
        set(ModelPhase.INSTALLING)
        try {
            val job = currentCoroutineContext().job
            openApprovedSource(artifact.sourceUri).use { input ->
                installer.install(input, target, artifact.identity.sizeBytes, artifact.identity.sha256) { count ->
                    job.ensureActive(); mutableStatus.value = ModelStatus(ModelKind.WHISPER, if (count == artifact.identity.sizeBytes) ModelPhase.VERIFYING else ModelPhase.INSTALLING, artifact.identity, count)
                }
            }
            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
            set(ModelPhase.INSTALLED)
        } catch (cancelled: CancellationException) { set(ModelPhase.MISSING); throw cancelled }
        catch (failure: Exception) { val error = AppError(ErrorCode.MODEL_FAILED, "Transcription model setup failed. Check the file, storage and connection.", true); set(ModelPhase.FAILED, error); throw ClearLineException(error) }
    } }
    override suspend fun load(modelId: ModelId) = mutex.withLock { arbiter.withExclusiveModelUse { withContext(Dispatchers.IO) {
        require(modelId == WhisperModelManifest.identity.modelId)
        if (handle != 0L) return@withContext
        try {
            set(ModelPhase.VERIFYING)
            if (!installer.verified(target, WhisperModelManifest.identity.sizeBytes, WhisperModelManifest.identity.sha256))
                throw ClearLineException(AppError(ErrorCode.MISSING_MODEL, "Install the verified English transcription model first."))
            currentCoroutineContext().ensureActive(); set(ModelPhase.LOADING)
            handle = NativeWhisperBridge.load(target.canonicalPath)
            if (handle == 0L) throw IOException("Load failed")
            set(ModelPhase.READY)
        } catch (cancelled: CancellationException) { set(ModelPhase.INSTALLED); throw cancelled }
        catch (failure: Throwable) {
            val error = (failure as? ClearLineException)?.error ?: AppError(ErrorCode.MODEL_FAILED, "Local transcription model could not load.", true)
            set(ModelPhase.FAILED, error); throw ClearLineException(error)
        }
    } } }
    override suspend fun unload() { cancel(); mutex.withLock { arbiter.withExclusiveModelUse { withContext(Dispatchers.IO) {
        set(ModelPhase.UNLOADING)
        if (handle != 0L) { NativeWhisperBridge.unload(handle); handle = 0L }
        set(if (target.exists()) ModelPhase.INSTALLED else ModelPhase.MISSING)
    } } } }
    override fun cancel() { val current = handle; if (current != 0L) NativeWhisperBridge.cancel(current) }
    internal suspend fun transcribe(pcm: PcmSamples): String = mutex.withLock { arbiter.withExclusiveModelUse {
        if (handle == 0L || status.value.phase != ModelPhase.READY) throw ClearLineException(AppError(ErrorCode.MISSING_MODEL, "Load the local English transcription model before processing.", true))
        currentCoroutineContext().ensureActive()
        NativeWhisperBridge.resetCancellation(handle)
        coroutineScope {
            // Cancellation remains observable while the JNI worker is computing. unload waits on mutex.
            val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { this@WhisperAsrRuntime.cancel() } }
            try {
                val bytes = withContext(Dispatchers.IO) { NativeWhisperBridge.transcribe(handle, pcm.normalized()) }
                currentCoroutineContext().ensureActive()
                bytes.toString(Charsets.UTF_8).trim().also {
                    if (it.isBlank() || it.length > 20000) throw ClearLineException(AppError(ErrorCode.NO_SPEECH, "No usable English speech was found. Please record again.", true))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ClearLineException) { throw failure }
            catch (failure: Exception) { throw ClearLineException(AppError(ErrorCode.MODEL_FAILED, "Local transcription failed or exceeded its time limit. Please retry.", true)) }
            finally { cancellation.cancelAndJoin() }
        }
    } }
    private fun set(phase: ModelPhase, error: AppError? = null) { mutableStatus.value = ModelStatus(ModelKind.WHISPER, phase, WhisperModelManifest.identity, error = error) }
    private fun openApprovedSource(source: String): InputStream {
        val uri = Uri.parse(source)
        if (uri.scheme == "content") return context.contentResolver.openInputStream(uri) ?: throw IOException("Unreadable model")
        require(source == WhisperModelManifest.downloadUrl) { "Choose the pinned download or an imported document" }
        var url = URL(source)
        repeat(6) {
            require(url.protocol == "https" && (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co") || url.host.endsWith(".hf.co")))
            val connection = (url.openConnection() as HttpsURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false }
            val status = connection.responseCode
            if (status in 300..399) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                if (next == null) throw IOException("Missing model redirect")
                url = URL(url, next)
            } else {
                if (status != 200) { connection.disconnect(); throw IOException("Model download unavailable") }
                val underlying = connection.inputStream
                return object : java.io.FilterInputStream(underlying) { override fun close() { try { super.close() } finally { connection.disconnect() } } }
            }
        }
        throw IOException("Too many model redirects")
    }
}

class WhisperAudioProcessor(context: Context, private val runtime: WhisperAsrRuntime) : LocalAudioProcessor {
    private val directory = File(context.applicationContext.noBackupFilesDir, "audio")
    override suspend fun process(clip: CompletedLocalClip): AudioResult = withContext(Dispatchers.Default) {
        val file = File(clip.privatePath)
        val expected = File(File(directory, clip.sessionId.value), "${clip.clipId.value}.wav")
        if (file.canonicalFile != expected.canonicalFile || !file.isFile || PcmWave.sha256(file) != clip.sha256)
            throw ClearLineException(AppError(ErrorCode.INVALID_AUDIO, "The accepted recording is missing or has changed."))
        val pcm = PcmWave.read(file)
        if (pcm.durationSeconds != clip.durationSeconds) throw ClearLineException(AppError(ErrorCode.INVALID_AUDIO, "Recording duration does not match its receipt."))
        pcm.requireUsable()
        val transcript = runtime.transcribe(pcm)
        AudioResult(clip.clipId, transcript, AudioMeasurements.calculate(pcm, transcript, clip.dataOrigin), WhisperModelManifest.identity.modelId, System.currentTimeMillis())
        // The store schedules file deletion only after a successful durable processing commit.
    }
}
