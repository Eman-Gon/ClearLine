package com.clearline.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class ModelKind { LIQUID, WHISPER }
@Serializable enum class ModelPhase { MISSING, INSTALLING, VERIFYING, INSTALLED, LOADING, READY, UNLOADING, FAILED }
@Serializable data class ModelIdentity(val modelId: ModelId, val repository: String, val revision: String, val filename: String, val sha256: String, val sizeBytes: Long, val modelKind: ModelKind, val quantization: String, val templateIdentity: String, val language: String, val licenseReference: String) {
    init { require(revision.matches(Regex("[0-9a-f]{40,64}"))); require(sha256.matches(Regex("[0-9a-f]{64}"))); require(sizeBytes > 0); require(filename.length in 1..200 && '/' !in filename && '\\' !in filename) }
}
@Serializable data class ApprovedModelArtifact(val identity: ModelIdentity, val sourceUri: String, val approvedAtMs: Long) { init { require(sourceUri.length in 1..4096) } }
@Serializable data class ModelStatus(val kind: ModelKind, val phase: ModelPhase = ModelPhase.MISSING, val identity: ModelIdentity? = null, val progressBytes: Long = 0, val error: AppError? = null)
interface ModelRuntime { val status: StateFlow<ModelStatus>; suspend fun install(artifact: ApprovedModelArtifact); suspend fun load(modelId: ModelId); suspend fun unload() }
interface AsrModelRuntime { val status: StateFlow<ModelStatus>; suspend fun install(artifact: ApprovedModelArtifact); suspend fun load(modelId: ModelId); suspend fun unload(); fun cancel() }
/** Both native owners use the same instance. No two native models execute/load/unload concurrently. */
interface SharedModelArbiter { suspend fun <T> withExclusiveModelUse(block: suspend () -> T): T }
class MutexModelArbiter : SharedModelArbiter { private val mutex = Mutex(); override suspend fun <T> withExclusiveModelUse(block: suspend () -> T): T = mutex.withLock { block() } }
@Serializable enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }
@Serializable data class ChatMessage(val role: ChatRole, val content: String, val toolCallId: String? = null) { init { require(content.length <= 64000); require(toolCallId == null || toolCallId.length <= 128) } }
@Serializable data class ToolSchema(val name: String, val description: String, val parametersJson: String) { init { require(name.length in 1..80); require(description.length <= 2000); require(parametersJson.length <= 16000) } }
@Serializable data class GenerationOutput(val rawText: String, val inputTokens: Int, val outputTokens: Int, val stoppedNormally: Boolean) { init { require(rawText.length <= 64000); require(inputTokens >= 0 && outputTokens >= 0) } }
interface LocalGenerationEngine { suspend fun renderPrompt(messages: List<ChatMessage>, tools: List<ToolSchema>): String; suspend fun countTokens(renderedPrompt: String): Int; suspend fun generate(renderedPrompt: String, maxOutputTokens: Int): GenerationOutput; fun cancel() }
@Serializable data class ComponentReadiness(val liquid: ModelStatus, val asr: ModelStatus, val credentials: List<CredentialStatus> = emptyList())
