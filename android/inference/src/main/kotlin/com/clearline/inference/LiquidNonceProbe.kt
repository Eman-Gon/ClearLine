package com.clearline.inference

import com.clearline.core.*
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * Two actual engine turns, with a nonce generated only after the first valid call.
 * A fake engine can test protocol behavior but does not establish device acceptance.
 * This local diagnostic creates no workflow jobs, exports, network calls or log output.
 */
class LiquidNonceProbe(
    private val engine: LocalGenerationEngine,
    private val parser: StrictToolParser = StrictToolParser(),
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun run(): NonceProbeResult {
        val started = System.nanoTime()
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "You are testing local tool use. Return exactly one JSON tool call, without prose or code. First call echo_nonce with empty arguments. After its tool result, call confirm_nonce with exactly the nonce returned by the tool. Never guess a nonce."),
            ChatMessage(ChatRole.USER, "Begin the local tool round trip by calling echo_nonce."),
        )
        val first = turn(messages)
        val call = parser.parse(first.rawText, setOf("echo_nonce"), !first.stoppedNormally)
        if (call.arguments.isNotEmpty()) throw ToolOutputRejected("nonce_call_arguments_must_be_empty")
        // This must remain after all validation of the first generated call.
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val callId = UUID.randomUUID().toString()
        val second = turn(messages + listOf(
            ChatMessage(ChatRole.ASSISTANT, LiquidChatTemplate.assistantContent(first.rawText), callId),
            ChatMessage(ChatRole.TOOL, LiquidChatTemplate.evidenceJson(buildJsonObject { put("nonce", nonce) }), callId),
        ))
        val confirmation = parser.parse(second.rawText, setOf("confirm_nonce"), !second.stoppedNormally)
        val returned = (confirmation.arguments["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (confirmation.arguments.keys != setOf("nonce") || returned != nonce) throw ToolOutputRejected("nonce_not_used")
        return NonceProbeResult(first.inputTokens, first.outputTokens, second.inputTokens, second.outputTokens, System.nanoTime() - started)
    }

    private suspend fun turn(messages: List<ChatMessage>): GenerationOutput {
        val rendered = engine.renderPrompt(messages, TOOLS)
        val tokens = engine.countTokens(rendered)
        if (tokens !in 1..OnDeviceLocalAgent.MAX_INPUT_TOKENS) throw ClearLineException(AppError(ErrorCode.CONTEXT_CAPACITY_EXCEEDED, "The nonce probe exceeds the model input budget."))
        return withTimeout(OnDeviceLocalAgent.TURN_TIMEOUT_MS) { engine.generate(rendered, OnDeviceLocalAgent.MAX_OUTPUT_TOKENS) }.also {
            if (it.inputTokens != tokens || it.outputTokens > OnDeviceLocalAgent.MAX_OUTPUT_TOKENS) {
                throw ClearLineException(AppError(ErrorCode.AGENT_UNAVAILABLE, "Local inference returned inconsistent token accounting."))
            }
        }
    }

    private companion object {
        val TOOLS = listOf(
            ToolSchema("echo_nonce", "Return a fresh unpredictable nonce generated locally after this call is validated.", """{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
            ToolSchema("confirm_nonce", "Prove the prior local tool result was consumed by returning its exact nonce.", """{"type":"object","properties":{"nonce":{"type":"string"}},"required":["nonce"],"additionalProperties":false}"""),
        )
    }
}

/** Measured engine round-trip data only. Device/runtime identity is recorded separately by the caller. */
data class NonceProbeResult(
    val firstInputTokens: Int,
    val firstOutputTokens: Int,
    val secondInputTokens: Int,
    val secondOutputTokens: Int,
    val elapsedNanos: Long,
)
