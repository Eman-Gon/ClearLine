# Embedded Liquid native runtime

This is the JNI library used by `EmbeddedLiquidRuntime`, not a model server.
There is no inference URL, HTTP client, child process, or model download in the
native library. The Kotlin installer verifies the approved model and supplies an
app-private filesystem path. No weights are included in this repository.

## Source and build identity

The source pin is `ggml-org/llama.cpp` commit
`4b1a27fa0eb875bbca4f6cfe936e3d65adc685c0`. The immutable archive and independently
computed SHA-256 are in [llama-pin.cmake](llama-pin.cmake). CMake always verifies
that digest, including when `CLEARLINE_LLAMA_ARCHIVE` points to a local copy. It
does not fetch a mutable branch. The archive was retrieved and the actual C API
inspected on September 25, 2026. The source is MIT licensed; see
[LICENSE.llama.cpp](LICENSE.llama.cpp). The model has its separate license.

The Android target is `arm64-v8a`, CPU only, with conservative `armv8-a` kernels,
1–8 configured threads, 4096 context tokens, batches of 256, and one sequence.
Kotlin chooses the initial thread count. GPU/Metal/Vulkan/OpenCL, OpenMP, BLAS,
KleidiAI downloads, native-host ISA selection, common/server/tools, subprocesses,
and dynamic backend loading are disabled. These choices favor compatibility;
their S24 speed and memory cost remain unmeasured. The initially supported model
architecture is `lfm2`. The default llama model load mode remains upstream's
automatic memory mapping selection.

`llama`, `ggml`, and their CPU backend are private static libraries inside
`libclearline_liquid.so`. The ELF version script exports only ClearLine JNI
entry points and `--exclude-libs,ALL` hides static-library symbols. There are no
separately packaged `libllama.so` or `libggml.so` dependencies to collide with
whisper.cpp. The linker sets a 16 KiB maximum ELF page size. The final APK and
its coexistence with `clearline_whisper` still require inspection and S24 tests.
Native dependencies must continue to be built separately by their module CMake
projects; a single combined CMake project would need target renaming too.

## JNI contract

Class: `com.clearline.inference.NativeLiquidBridge`, instance methods. Library:
`clearline_liquid`. Preserve JNI names and `NativeGeneration`'s constructor in
shrunk builds. The method signatures match `NativeLiquidBridge.kt`.

| Call | Behavior |
| --- | --- |
| `nativeCreate(): Long` | Creates the only runtime handle in this process; a second owner fails. |
| `nativeLoad(handle, pathUtf8, 4096, threads)` | Loads one model/context; rejects a second loaded model. |
| `nativeTokenCount(handle, promptUtf8): Int` | Uses the loaded vocabulary on the complete rendered prompt. |
| `nativeGenerate(handle, promptUtf8, maxOutputTokens, requestId)` | Returns a bounded `NativeGeneration`; no callbacks/text filtering. |
| `nativeCancel(handle, requestId)` | Thread-safe atomic cancellation, independent of the operation mutex. |
| `nativeUnload(handle)` | Requests cancellation, waits for native work, and then frees model/context. |
| `nativeDestroy(handle)` | Closes and removes the handle after native work finishes. |

Request IDs must be positive and strictly increasing throughout a handle's life,
including across unload/reload. Pre-start cancellation is remembered; an older
late cancellation cannot cancel the next request. Native operations other than
cancel share a mutex. Handles are identities looked up in a shared-ownership
registry, never raw pointers exposed to Java. A racing call holds the runtime
alive until it returns; unload/destroy cannot free an active context. The CPU
abort callback polls cancellation during decode. Model loading polls an abort
callback where upstream supports it. Cancellation is cooperative, with no claim
of an instantaneous bound. Kotlin also serializes ASR and inference through the
shared arbiter.

The caller renders the complete prompt, including exactly one leading
`<|startoftext|>`, roles, tools, and persisted assistant/tool results. Both count
and generate use `llama_tokenize(add_special=false, parse_special=true)`; no
implicit BOS/EOS is added. Native code limits input to 256 KiB and 3072 actual
tokens, output to 768 tokens and 64 KiB, within a 4096-token context. The Kotlin
parser must treat every non-EOG termination as incomplete.

`NativeGeneration` has constructor `([BIIIJJ)V`: raw UTF-8 bytes, prompt token
count, sampled output token count, stop reason, prefill nanoseconds, decode
nanoseconds. Stop reasons are `0` EOG, `1` token limit, `2` cancelled, and `3`
output-byte limit. Output token counts include a sampled EOG. Pieces use
`llama_token_to_piece(..., special=true)`, including the terminating EOG, so
control/tool-call tokens survive JNI. Kotlin strictly decodes UTF-8 and parses
the complete envelope before any tool may execute.

Each invocation clears KV/recurrent memory before prefill and at exit (including
failed/aborted decode), and builds a fresh sampler. Recovery reconstructs the
prompt from durable state rather than relying on a surviving native cache.
Sampling currently uses top-k 50, repeat penalty 1.05 over 64 tokens, temperature
0.1, and upstream's random default seed. These are starting settings, not proven
tool reliability. No generated code is executed here. Native errors are bounded
codes; log callbacks discard llama/ggml logs to avoid model paths/private input.

## Host checks and limitations

The host probe compiles the **actual pinned C API and full CPU library**, then
loads JNI into a JVM with `-Xcheck:jni`. It checks failed model load, invalid
inputs, single-owner enforcement, stale identities and 100 concurrent
cancel/count/generate/unload/destroy races without loaded weights. A separate
C++ test stresses pre-start, delayed, and concurrent cancellation updates.
Neither test is inference, a loaded-context cancellation test, or S24 evidence.

Executed on September 25, 2026 with macOS arm64, AppleClang 15.0.0,
CMake 3.31.3 and OpenJDK 21.0.11: full real-library build passed; CTest passed;
the JVM probe passed. `nm -gU` showed exactly the seven JNI entry points above,
with no public llama/ggml symbols. `otool -L` showed only system C/C++ libraries.
This is host evidence; it does not establish Android ELF packaging or S24
inference. The Android CMake entry point remains compatible with the module's
configured 3.22.1 syntax (newer extraction policy is enabled only if available).

Example on this Mac, using the previously retrieved archive:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  bash android/inference/native/tests/run-host.sh \
  /tmp/clearline-llama-4b1a27fa.tar.gz /tmp/clearline-liquid-native-build
```

For a fresh machine obtain the exact archive URL in `llama-pin.cmake`; the build
checks its digest. `JAVA_HOME` must point to the actual JDK home, not a package
prefix. Host builds are test artifacts only. The Android Gradle module invokes
the same CMake project through the configured NDK. Source archive extraction
may cause upstream's Git metadata probe to report `unknown`; the authoritative
dependency identity remains the verified commit/archive pin above.

Before claiming runtime readiness on an S24, install the verified model, inspect
the installed native libraries, run offline nonce call/result/second inference,
exercise malformed/truncated calls and loaded-context cancellation/unload, and
record hardware/OS/ABI plus cold load, token counts, prefill/decode latency,
memory and repeated-run thermal results. No S24 measurements or successful model
inference have been fabricated here.

## API references inspected

- [Pinned llama.h](https://github.com/ggml-org/llama.cpp/blob/4b1a27fa0eb875bbca4f6cfe936e3d65adc685c0/include/llama.h)
- [Pinned simple C API example](https://github.com/ggml-org/llama.cpp/blob/4b1a27fa0eb875bbca4f6cfe936e3d65adc685c0/examples/simple/simple.cpp)
- [Pinned upstream Android CMake](https://github.com/ggml-org/llama.cpp/blob/4b1a27fa0eb875bbca4f6cfe936e3d65adc685c0/examples/llama.android/lib/src/main/cpp/CMakeLists.txt)
- [Liquid mobile guidance](https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile)

The public streaming example filters special tokens; this bridge deliberately
uses the raw C token API instead. App-owned strict template/parser validation
remains required and is implemented in Kotlin, not assumed to exist in the
basic llama C API.
