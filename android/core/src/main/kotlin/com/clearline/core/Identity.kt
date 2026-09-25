package com.clearline.core

import java.util.UUID
import kotlinx.serialization.Serializable

private fun validUuid(value: String) { require(UUID.fromString(value).toString() == value) { "Canonical UUID required" } }
@Serializable @JvmInline value class ProfileId(val value: String) { init { validUuid(value) }; companion object { fun new() = ProfileId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class SessionId(val value: String) { init { validUuid(value) }; companion object { fun new() = SessionId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class ClipId(val value: String) { init { validUuid(value) }; companion object { fun new() = ClipId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class JobId(val value: String) { init { validUuid(value) }; companion object { fun new() = JobId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class ActionId(val value: String) { init { validUuid(value) }; companion object { fun new() = ActionId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class ExportId(val value: String) { init { validUuid(value) }; companion object { fun new() = ExportId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class EvidenceId(val value: String) { init { validUuid(value) }; companion object { fun new() = EvidenceId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class ApprovalId(val value: String) { init { validUuid(value) }; companion object { fun new() = ApprovalId(UUID.randomUUID().toString()) } }
@Serializable @JvmInline value class ModelId(val value: String) { init { require(value.length in 1..128 && value.matches(Regex("[A-Za-z0-9._-]+"))) } }

@Serializable enum class DataOrigin { CONSENTED_DEMO, SYNTHETIC }
@Serializable enum class ExecutionMode { REAL_ON_DEVICE, SYNTHETIC_FIXTURE }
@Serializable enum class RecordingTask { CHECK_IN }
@Serializable enum class Phase { RECORDING, PROCESSING, COMPARING, AWAITING_USER_CHOICE, RESEARCHING, READY, AWAITING_INPUT, WAITING_NETWORK, WAITING_RETRY, PAUSED, AGENT_UNAVAILABLE }
@Serializable enum class ErrorCode { NOT_FOUND, DELETED, INVALID_INPUT, STALE_REVISION, IDENTITY_CONFLICT, CONSENT_REQUIRED, MISSING_MODEL, MODEL_FAILED, CANCELLED, PERMISSION_DENIED, NO_MICROPHONE, INVALID_AUDIO, SILENT_AUDIO, NO_SPEECH, STORAGE_FULL, NETWORK_UNAVAILABLE, UNAUTHORIZED, RATE_LIMITED, TIMEOUT, BAD_RESPONSE, UNAVAILABLE, CONTEXT_CAPACITY_EXCEEDED, AGENT_UNAVAILABLE, RETRY_EXHAUSTED }
@Serializable data class AppError(val code: ErrorCode, val message: String, val retryable: Boolean = false) { init { require(message.length <= 512) } }
/** Safe, bounded errors only: never include source payloads, credentials or prompts. */
class ClearLineException(val error: AppError) : Exception(error.message)

@Serializable data class LocalProfile(val profileId: ProfileId, val label: String, val dataOrigin: DataOrigin, val createdAtMs: Long) { init { require(label.length in 1..80) } }
