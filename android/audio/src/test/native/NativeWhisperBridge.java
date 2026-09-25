package com.clearline.audio;

/** Host JNI ABI/error smoke only; no model and no speech acceptance claim. */
public final class NativeWhisperBridge {
    static { System.loadLibrary("clearline_whisper"); }
    private static native long load(String path);
    private static native void resetCancellation(long handle);
    private static native byte[] transcribe(long handle, float[] samples);
    private static native void cancel(long handle);
    private static native void unload(long handle);
    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            try { load("/clearline-missing-test-model.bin"); throw new AssertionError("Missing model unexpectedly loaded"); }
            catch (IllegalStateException expected) { }
            try { transcribe(0, new float[32000]); throw new AssertionError("Missing context unexpectedly transcribed"); }
            catch (IllegalStateException expected) { }
            resetCancellation(0); cancel(0); unload(0);
        }
        System.out.println("PASS: real whisper JNI loads, rejects missing model/context, and safely handles empty cancellation/unload (10 cycles)");
    }
}
