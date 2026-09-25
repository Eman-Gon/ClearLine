package com.clearline.inference

/** JNI boundary only. All calls except cancellation must hold SharedModelArbiter. */
internal class NativeLiquidBridge {
    external fun nativeCreate(): Long
    external fun nativeLoad(handle: Long, pathUtf8: ByteArray, contextSize: Int, threads: Int)
    external fun nativeTokenCount(handle: Long, promptUtf8: ByteArray): Int
    external fun nativeGenerate(handle: Long, promptUtf8: ByteArray, maxOutputTokens: Int, requestId: Long): NativeGeneration
    external fun nativeCancel(handle: Long, requestId: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeDestroy(handle: Long)

    companion object {
        fun loadLibrary() = System.loadLibrary("clearline_liquid")
    }
}

/** Raw UTF-8 deliberately includes control tokens; truncated output is never a tool. */
internal data class NativeGeneration(
    val rawUtf8: ByteArray,
    val promptTokens: Int,
    val generatedTokens: Int,
    val stopReason: Int,
    val prefillNanos: Long,
    val decodeNanos: Long,
)
