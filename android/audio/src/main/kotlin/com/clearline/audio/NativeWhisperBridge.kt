package com.clearline.audio

internal object NativeWhisperBridge {
    init { System.loadLibrary("clearline_whisper") }
    external fun load(path: String): Long
    external fun resetCancellation(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray): ByteArray
    external fun cancel(handle: Long)
    external fun unload(handle: Long)
}
