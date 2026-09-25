package com.clearline.inference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

// JVM-only JNI ABI/lifetime probe against the real linked llama.cpp runtime.
// No model weights, mocked inference, or claim of on-device execution.
class NativeLiquidBridge {
    native long nativeCreate();
    native void nativeLoad(long handle, byte[] path, int contextSize, int threads);
    native int nativeTokenCount(long handle, byte[] prompt);
    native NativeGeneration nativeGenerate(long handle, byte[] prompt, int maximum, long request);
    native void nativeCancel(long handle, long request);
    native void nativeUnload(long handle);
    native void nativeDestroy(long handle);
}
class NativeGeneration {
    final byte[] raw;
    NativeGeneration(byte[] raw, int promptTokens, int generatedTokens, int stopReason, long prefillNanos, long decodeNanos) {
        this.raw = raw;
    }
}
public final class NativeSmoke {
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IllegalStateException | IllegalArgumentException expected) {
            if (!code.equals(expected.getMessage())) throw new AssertionError(expected);
        }
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        NativeLiquidBridge bridge = new NativeLiquidBridge();
        long initial = bridge.nativeCreate();
        if (initial <= 0) throw new AssertionError("Expected opaque positive ID");
        expect("native_runtime_already_exists", bridge::nativeCreate);
        expect("model_not_loaded", () -> bridge.nativeTokenCount(initial, utf8("<|startoftext|>test")));
        expect("model_not_loaded", () -> bridge.nativeGenerate(initial, utf8("test"), 20, 1));
        expect("model_path_invalid", () -> bridge.nativeLoad(initial, utf8("relative.gguf"), 4096, 2));
        expect("model_path_invalid", () -> bridge.nativeLoad(initial, utf8("/tmp/x\0.gguf"), 4096, 2));
        expect("runtime_config_invalid", () -> bridge.nativeLoad(initial, utf8("/tmp/x.gguf"), 2048, 2));
        expect("model_load_failed", () -> bridge.nativeLoad(initial, utf8("/clearline-does-not-exist.gguf"), 4096, 2));
        expect("request_id_invalid", () -> bridge.nativeCancel(initial, 0));
        bridge.nativeCancel(initial, 2);
        bridge.nativeCancel(initial, 1);
        bridge.nativeUnload(initial);
        bridge.nativeUnload(initial);
        bridge.nativeDestroy(initial);
        expect("invalid_native_handle", () -> bridge.nativeCancel(initial, 3));
        expect("invalid_native_handle", () -> bridge.nativeDestroy(initial));

        for (int iteration = 0; iteration < 100; ++iteration) {
            long handle = bridge.nativeCreate();
            if (handle <= initial) throw new AssertionError("Reused stale ID");
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int worker = 0; worker < 5; ++worker) {
                final int operation = worker;
                Thread thread = new Thread(() -> {
                    try {
                        go.await();
                        switch (operation) {
                            case 0: bridge.nativeCancel(handle, 1); break;
                            case 1: bridge.nativeTokenCount(handle, utf8("test")); break;
                            case 2: bridge.nativeUnload(handle); break;
                            case 3: bridge.nativeDestroy(handle); break;
                            case 4: bridge.nativeGenerate(handle, utf8("test"), 20, 1); break;
                            default: throw new AssertionError();
                        }
                    } catch (IllegalStateException expected) {
                        if (!List.of("invalid_native_handle", "native_runtime_closed", "model_not_loaded").contains(expected.getMessage()))
                            unexpected.set(expected);
                    } catch (Throwable error) { unexpected.set(error); }
                });
                threads.add(thread);
                thread.start();
            }
            go.countDown();
            for (Thread thread : threads) thread.join();
            if (unexpected.get() != null) throw new AssertionError(unexpected.get());
        }
        System.out.println("Real-library JNI smoke: invalid input, failed load, singleton and 100 lifecycle races passed");
    }
}
