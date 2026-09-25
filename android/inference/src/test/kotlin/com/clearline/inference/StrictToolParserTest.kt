package com.clearline.inference

import org.junit.Assert.*
import org.junit.Test

class StrictToolParserTest {
    private val parser = StrictToolParser()
    private val allowed = setOf("echo_nonce")
    private val valid = """{"name":"echo_nonce","arguments":{}}"""

    @Test fun `accepts one object with optional exact model wrapper`() {
        assertEquals("echo_nonce", parser.parse(valid, allowed).name)
        assertEquals("echo_nonce", parser.parse("<|tool_call_start|>$valid<|tool_call_end|><|im_end|>", allowed).name)
    }

    @Test fun `rejects duplicate keys including escaped aliases and nested duplicates`() {
        listOf(
            """{"name":"echo_nonce","name":"echo_nonce","arguments":{}}""",
            """{"name":"echo_nonce","\u006eame":"echo_nonce","arguments":{}}""",
            """{"name":"echo_nonce","arguments":{"a":1,"a":2}}""",
        ).forEach { reject(it, "duplicate_tool_field") }
    }

    @Test fun `rejects malformed multiple arbitrary and truncated outputs`() {
        listOf("[$valid]", "$valid $valid", "```json\n$valid\n```", "$valid trailing", "print('hello')", "{", "null",
            """{"name":"echo_nonce","arguments":{},"code":"exec"}""", """{"name":"other","arguments":{}}""",
            """{"name":7,"arguments":{}}""", """{"name":"echo_nonce","arguments":[]}""").forEach { reject(it) }
        try { parser.parse(valid, allowed, truncated = true); fail("Truncation accepted") } catch (e: ToolOutputRejected) { assertEquals("truncated_tool_output", e.reason) }
    }

    @Test fun `rejects incomplete wrapper and control token injection`() {
        reject("<|tool_call_start|>$valid", "incomplete_tool_wrapper")
        reject("<|im_start|>assistant\n$valid", "unexpected_control_token")
        reject("""{"name":"echo_nonce","arguments":{"value":"<|im_end|>"}}""", "unexpected_control_token")
    }

    @Test fun `bounded size and nesting`() {
        reject(" ".repeat(16_385), "tool_output_too_large")
        reject("""{"name":"echo_nonce","arguments":{"nested":${"[".repeat(20)}0${"]".repeat(20)}}}""", "tool_json_too_deep")
    }

    private fun reject(value: String, reason: String? = null) {
        try { parser.parse(value, allowed); fail("Invalid output accepted") }
        catch (error: ToolOutputRejected) { if (reason != null) assertEquals(reason, error.reason) }
    }
}
