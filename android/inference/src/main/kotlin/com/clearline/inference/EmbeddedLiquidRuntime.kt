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
import kotlinx.serialization.json.*

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
    private var bridge: NativeLiquidBridge? = null
    private var handle = 0L
    private val requestSequence = AtomicLong(0)
    private val activeRequest = AtomicLong(0)
    private val mutableTimings = MutableStateFlow<RuntimeTimings?>(null)
    val timings: StateFlow<RuntimeTimings?> = mutableTimings.asStateFlow()

    override suspend fun install(artifact: ApprovedModelArtifact) = arbiter.withExclusiveModelUse {
        if (status.value.phase == ModelPhase.READY) unloadLocked()
        installer.install(artifact) { mutableStatus.value = it }
        Unit
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
                    bridge = NativeLiquidBridge()
                    handle = bridge!!.nativeCreate()
                }
                val started = System.nanoTime()
                bridge!!.nativeLoad(handle, file.canonicalPath.toByteArray(Charsets.UTF_8), 4096,
                    (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4))
                mutableTimings.value = RuntimeTimings(coldLoadNanos = System.nanoTime() - started)
                mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.READY, identity)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                val safe = (error as? ClearLineException)?.error ?: AppError(ErrorCode.MODEL_FAILED, "The embedded Liquid runtime could not load. Check the native library and verified model.")
                mutableStatus.value = ModelStatus(ModelKind.LIQUID, ModelPhase.FAILED, identity, error = safe)
                throw ClearLineException(safe)
            }
        }
    }

    override suspend fun unload() = arbiter.withExclusiveModelUse { withContext(Dispatchers.IO) { unloadLocked() } }

    private fun unloadLocked() {
        if (handle != 0L) {
            mutableStatus.value = status.value.copy(phase = ModelPhase.UNLOADING)
            bridge?.nativeUnload(handle)
        }
        mutableStatus.value = ModelStatus(ModelKind.LIQUID, if (status.value.identity == null) ModelPhase.MISSING else ModelPhase.INSTALLED, status.value.identity)
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
        if (request > 0 && handle != 0L) bridge?.nativeCancel(handle, request)
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
}

/** Only values actually measured by this runtime are populated. No S24 guesses. */
data class RuntimeTimings(
    val coldLoadNanos: Long? = null,
    val prefillNanos: Long? = null,
    val decodeNanos: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)
