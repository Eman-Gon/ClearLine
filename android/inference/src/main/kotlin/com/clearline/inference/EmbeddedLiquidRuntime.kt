package com.clearline.inference

import android.content.Context
import com.clearline.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** CPU-only, in-process model owner. It has no inference networking client. */
class EmbeddedLiquidRuntime(
    context: Context,
    private val arbiter: SharedModelArbiter,
    private val installer: PrivateModelInstaller = PrivateModelInstaller(
        File(context.noBackupFilesDir, "liquid-models").canonicalFile,
        contentStream = { context.contentResolver.openInputStream(android.net.Uri.parse(it)) },
    ),
) : ModelRuntime, LocalGenerationEngine {
    private val mutableStatus = MutableStateFlow(ModelStatus(ModelKind.LIQUID))
    override val status: StateFlow<ModelStatus> = mutableStatus.asStateFlow()
    @Volatile private var bridge: NativeLiquidBridge? = null
    @Volatile private var handle = 0L
    private val requestSequence = AtomicLong(0)
    private val activeRequest = AtomicLong(0)
    private val mutableTimings = MutableStateFlow<RuntimeTimings?>(null)
    val timings: StateFlow<RuntimeTimings?> = mutableTimings.asStateFlow()

    /** Explicit startup verification only; collecting readiness never loads a model or uses network. */
    suspend fun refreshInstalledStatus() = arbiter.withExclusiveModelUse {
        withContext(Dispatchers.IO) {
            if (status.value.phase == ModelPhase.READY) return@withContext
            val identity = LiquidModelCatalog.DEFAULT
            try {
                val file = installer.verifiedFile(identity.modelId) { mutableStatus.value = it }
                if (file == null) mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.MISSING)
            } catch (cancelled: CancellationException) {
                cancelledSetup("Local model verification was cancelled.")
                throw cancelled
            } catch (error: Throwable) {
                val safe = (error as? ClearLineException)?.error
                    ?: AppError(ErrorCode.MODEL_FAILED, "The installed Liquid model could not be verified.", true)
                mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.FAILED, identity, error = safe)
                throw ClearLineException(safe)
            }
        }
    }

    override suspend fun install(artifact: ApprovedModelArtifact) = arbiter.withExclusiveModelUse {
        withContext(Dispatchers.IO) {
            if (status.value.phase == ModelPhase.READY) unloadLocked()
            try {
                installer.install(artifact) { mutableStatus.value = it }
            } catch (cancelled: CancellationException) {
                cancelledSetup("Local model setup was cancelled.")
                throw cancelled
            }
            Unit
        }
    }

    override suspend fun load(modelId: ModelId) = arbiter.withExclusiveModelUse {
        withContext(Dispatchers.IO) {
            val identity = LiquidModelCatalog.DEFAULT
            if (identity.modelId != modelId) fail(ErrorCode.INVALID_INPUT, "This model is not in the pinned Liquid catalog.")
            if (status.value.phase == ModelPhase.READY && status.value.identity?.modelId == modelId) return@withContext
            unloadLocked()
            try {
                val file = installer.verifiedFile(modelId) { mutableStatus.value = it }
                    ?: fail(ErrorCode.MISSING_MODEL, "Import or download the verified Liquid model first.")
                mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.LOADING, identity)
                if (bridge == null) {
                    NativeLiquidBridge.loadLibrary()
                    val createdBridge = NativeLiquidBridge()
                    val createdHandle = createdBridge.nativeCreate()
                    if (createdHandle <= 0) fail(ErrorCode.MODEL_FAILED, "The embedded Liquid runtime could not create a native context owner.")
                    // Publish only after successful creation so a failed attempt can retry.
                    handle = createdHandle
                    bridge = createdBridge
                }
                val started = System.nanoTime()
                bridge!!.nativeLoad(handle, file.canonicalPath.toByteArray(Charsets.UTF_8), 4096,
                    (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4))
                mutableTimings.value = RuntimeTimings(coldLoadNanos = System.nanoTime() - started)
                mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.READY, identity)
            } catch (cancelled: CancellationException) {
                cancelledSetup("Local model loading was cancelled.")
                throw cancelled
            }
            catch (error: Throwable) {
                val safe = (error as? ClearLineException)?.error ?: AppError(ErrorCode.MODEL_FAILED, "The embedded Liquid runtime could not load. Check the native library and verified model.")
                mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.FAILED, identity, error = safe)
                throw ClearLineException(safe)
            }
        }
    }

    override suspend fun unload() = arbiter.withExclusiveModelUse { withContext(Dispatchers.IO) { unloadLocked() } }

    private fun unloadLocked() {
        val previous = status.value
        if (handle != 0L) {
            mutableStatus.value = previous.copy(phase = ModelPhase.UNLOADING)
            bridge?.nativeUnload(handle)
        }
        // A failed/missing artifact is not installed merely because its expected
        // identity is known. Only a previously ready model becomes installed.
        mutableStatus.value = if (previous.phase == ModelPhase.READY) previous.copy(phase = ModelPhase.INSTALLED) else previous
    }

    suspend fun close() = arbiter.withExclusiveModelUse {
        withContext(Dispatchers.IO) {
            if (handle != 0L) bridge?.nativeDestroy(handle)
            handle = 0L
            bridge = null
            mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.MISSING)
        }
    }

    override suspend fun renderPrompt(messages: List<ChatMessage>, tools: List<ToolSchema>): String = LiquidChatTemplate.render(messages, tools)

    override suspend fun countTokens(renderedPrompt: String): Int = arbiter.withExclusiveModelUse {
        withContext(Dispatchers.IO) { requireReady().nativeTokenCount(handle, boundedPrompt(renderedPrompt)) }
    }

    override suspend fun generate(renderedPrompt: String, maxOutputTokens: Int): GenerationOutput = arbiter.withExclusiveModelUse {
        require(maxOutputTokens in 1..768)
        val native = requireReady()
        val prompt = boundedPrompt(renderedPrompt)
        val request = requestSequence.incrementAndGet()
        activeRequest.set(request)
        try {
            coroutineScope {
                val operation = async(Dispatchers.IO) { native.nativeGenerate(handle, prompt, maxOutputTokens, request) }
                val result = try { operation.await() } catch (cancelled: CancellationException) {
                    native.nativeCancel(handle, request)
                    // Keep the shared arbiter held until the C++ context reaches a safe boundary.
                    withContext(NonCancellable) { operation.join() }
                    throw cancelled
                }
                if (result.stopReason == 2) throw CancellationException("Local inference cancelled")
                val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result.rawUtf8)).toString()
                mutableTimings.value = (mutableTimings.value ?: RuntimeTimings()).copy(
                    prefillNanos = result.prefillNanos, decodeNanos = result.decodeNanos,
                    inputTokens = result.promptTokens, outputTokens = result.generatedTokens,
                )
                GenerationOutput(raw, result.promptTokens, result.generatedTokens, result.stopReason == 0)
            }
        } finally { activeRequest.compareAndSet(request, 0) }
    }

    override fun cancel() {
        val request = activeRequest.get()
        if (request <= 0) return
        val native = bridge ?: return
        val currentHandle = handle
        if (currentHandle <= 0) return
        try {
            native.nativeCancel(currentHandle, request)
        } catch (_: IllegalStateException) {
            // Generation may finish and close may destroy the handle between the
            // atomic request read and JNI. Its stale-handle rejection is harmless;
            // lifecycle cancellation must not throw on the foreground thread.
        }
    }

    private fun requireReady(): NativeLiquidBridge {
        if (status.value.phase != ModelPhase.READY || handle == 0L) fail(ErrorCode.MISSING_MODEL, "Load the installed Liquid model before starting the agent.")
        return bridge ?: fail(ErrorCode.MODEL_FAILED, "Embedded runtime is unavailable.")
    }

    private fun boundedPrompt(prompt: String): ByteArray {
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        if (bytes.size > 256_000) fail(ErrorCode.CONTEXT_CAPACITY_EXCEEDED, "The required prompt is too large.")
        return bytes
    }

    private fun fail(code: ErrorCode, message: String): Nothing = throw ClearLineException(AppError(code, message))

    private fun cancelledSetup(message: String) {
        mutableStatus.value = status.value.copy(phase = ModelPhase.FAILED, error = AppError(ErrorCode.CANCELLED, message, true))
    }
}

/** Only values actually measured by this runtime are populated. No S24 guesses. */
data class RuntimeTimings(
    val coldLoadNanos: Long? = null,
    val prefillNanos: Long? = null,
    val decodeNanos: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)
