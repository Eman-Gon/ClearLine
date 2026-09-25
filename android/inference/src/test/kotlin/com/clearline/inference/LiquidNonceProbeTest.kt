package com.clearline.inference

import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class LiquidNonceProbeTest {
    @Test fun `nonce is generated after valid call then consumed in second inference`() = runBlocking {
        val random = ObservedRandom()
        val engine = FakeLiquidEngine(mutableListOf(
            { prompt -> assertEquals(0, random.calls); assertFalse(prompt.contains("f1".repeat(16))); """{"name":"echo_nonce","arguments":{}}""" },
            { prompt ->
                assertEquals(1, random.calls)
                assertTrue(prompt.contains("<|im_start|>tool\n{\"nonce\":\"${"f1".repeat(16)}\"}"))
                """{"name":"confirm_nonce","arguments":{"nonce":"${"f1".repeat(16)}"}}"""
            },
        ))
        val result = LiquidNonceProbe(engine, random = random).run()
        assertEquals(2, engine.generated.size)
        assertEquals(7, result.firstOutputTokens)
        assertEquals(7, result.secondOutputTokens)
        assertTrue(result.elapsedNanos > 0)
        assertEquals(engine.lastMessages.takeLast(2)[0].toolCallId, engine.lastMessages.last().toolCallId)
    }

    @Test fun `invalid first call never generates nonce or runs second inference`() = runBlocking {
        val random = ObservedRandom()
        val engine = FakeLiquidEngine(mutableListOf({ """{"name":"echo_nonce","arguments":{"nonce":"preselected"}}""" }))
        try { LiquidNonceProbe(engine, random = random).run(); fail("Bad arguments accepted") }
        catch (error: ToolOutputRejected) { assertEquals("nonce_call_arguments_must_be_empty", error.reason) }
        assertEquals(0, random.calls)
        assertEquals(1, engine.generated.size)
    }

    @Test fun `invented nonce fails round trip`() = runBlocking {
        val engine = FakeLiquidEngine(mutableListOf(
            { """{"name":"echo_nonce","arguments":{}}""" },
            { """{"name":"confirm_nonce","arguments":{"nonce":"guessed"}}""" },
        ))
        try { LiquidNonceProbe(engine, random = ObservedRandom()).run(); fail("Incorrect nonce accepted") }
        catch (error: ToolOutputRejected) { assertEquals("nonce_not_used", error.reason) }
    }

    @Test fun `truncated first turn cannot pass probe`() = runBlocking {
        val random = ObservedRandom()
        val engine = FakeLiquidEngine(mutableListOf({ """{"name":"echo_nonce","arguments":{}}""" })).also { it.stoppedNormally = false }
        try { LiquidNonceProbe(engine, random = random).run(); fail("Truncated call accepted") }
        catch (error: ToolOutputRejected) { assertEquals("truncated_tool_output", error.reason) }
        assertEquals(0, random.calls)
    }

    private class ObservedRandom : SecureRandom() {
        var calls = 0
        override fun nextBytes(bytes: ByteArray) { calls++; bytes.fill(0xf1.toByte()) }
    }
}
