#include <jni.h>
#include "llama.h"
#include "cancellation.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr size_t kPromptByteLimit = 256 * 1024;
constexpr size_t kOutputByteLimit = 64 * 1024;
constexpr int32_t kInputTokenLimit = 3072;
constexpr int32_t kOutputTokenLimit = 768;
constexpr uint32_t kContextSize = 4096;
constexpr int32_t kBatchSize = 256;
enum StopReason { EndOfGeneration = 0, TokenLimit = 1, Cancelled = 2, ByteLimit = 3 };
using Clock = std::chrono::steady_clock;

struct StateError : std::runtime_error { using std::runtime_error::runtime_error; };
struct InputError : std::runtime_error { using std::runtime_error::runtime_error; };
struct JniPending {}; // Preserve the JVM's existing allocation/access exception.

struct Runtime {
    std::mutex operation;
    std::atomic<bool> closing{false};
    std::atomic<bool> unloading{false};
    clearline::Cancellation cancellation;
    std::atomic<int64_t> active_request{0};
    int64_t last_request = 0; // guarded by operation
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;

    bool aborted() const noexcept {
        return closing.load(std::memory_order_acquire) || unloading.load(std::memory_order_acquire) ||
               cancellation.cancelled(active_request.load(std::memory_order_acquire));
    }
    void release() noexcept { // must hold operation, or be the final shared owner
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        context = nullptr;
        model = nullptr;
        vocab = nullptr;
    }
    ~Runtime() { release(); }
};

// Numeric identities, never a Java-visible raw pointer. A shared owner keeps a
// runtime alive across concurrent cancel/unload/destroy and rejected stale calls.
std::mutex registry_mutex;
std::shared_ptr<Runtime> singleton;
int64_t singleton_id = 0;
int64_t next_id = 1;
std::once_flag backend_once;

std::shared_ptr<Runtime> acquire(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    if (!singleton || id <= 0 || id != singleton_id) throw StateError("invalid_native_handle");
    return singleton;
}
void usable(const Runtime & runtime, bool require_model = true) {
    if (runtime.closing.load(std::memory_order_acquire)) throw StateError("native_runtime_closed");
    if (require_model && (!runtime.model || !runtime.context)) throw StateError("model_not_loaded");
}
void quiet_log(ggml_log_level, const char *, void *) {} // no model, path or prompt logging
bool abort_decode(void * data) { return static_cast<Runtime *>(data)->aborted(); }
bool continue_load(float, void * data) { return !static_cast<Runtime *>(data)->aborted(); }

void throw_java(JNIEnv * env, const char * type, const char * code) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, code); env->DeleteLocalRef(cls); }
}
void translate_exception(JNIEnv * env) {
    try { throw; }
    catch (const JniPending &) {}
    catch (const InputError & e) { throw_java(env, "java/lang/IllegalArgumentException", e.what()); }
    catch (const StateError & e) { throw_java(env, "java/lang/IllegalStateException", e.what()); }
    catch (const std::bad_alloc &) { throw_java(env, "java/lang/OutOfMemoryError", "native_allocation_failed"); }
    catch (...) { throw_java(env, "java/lang/IllegalStateException", "native_runtime_failed"); }
}
std::string bytes(JNIEnv * env, jbyteArray value, size_t limit) {
    if (!value) throw InputError("missing_native_input");
    const jsize size = env->GetArrayLength(value);
    if (size < 1 || static_cast<size_t>(size) > limit) throw InputError("native_input_size_invalid");
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw JniPending{};
    return result;
}
std::vector<llama_token> tokenize(const Runtime & runtime, const std::string & prompt) {
    // Kotlin fully renders BOS + roles + tool definitions + exchange. Do not add
    // another implicit BOS/EOS; parse_special must preserve LFM control tokens.
    const int32_t needed = llama_tokenize(runtime.vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                         nullptr, 0, false, true);
    if (needed == std::numeric_limits<int32_t>::min() || needed >= 0) throw StateError("tokenization_failed");
    std::vector<llama_token> result(static_cast<size_t>(-needed));
    const int32_t count = llama_tokenize(runtime.vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                         result.data(), static_cast<int32_t>(result.size()), false, true);
    if (count < 1 || count != -needed) throw StateError("tokenization_failed");
    return result;
}
std::string piece(const llama_vocab * vocab, llama_token token) {
    char small[256];
    int32_t size = llama_token_to_piece(vocab, token, small, sizeof(small), 0, true);
    if (size >= 0) return std::string(small, static_cast<size_t>(size));
    if (size == std::numeric_limits<int32_t>::min() || -size > static_cast<int32_t>(kOutputByteLimit))
        throw StateError("token_piece_size_invalid");
    std::string result(static_cast<size_t>(-size), '\0');
    size = llama_token_to_piece(vocab, token, result.data(), static_cast<int32_t>(result.size()), 0, true);
    if (size < 0 || static_cast<size_t>(size) != result.size()) throw StateError("token_piece_failed");
    return result;
}
int64_t nanos(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
}
struct Result {
    std::string raw;
    int32_t prompt_tokens = 0;
    int32_t generated_tokens = 0; // includes sampled EOG
    StopReason stop = TokenLimit;
    int64_t prefill_ns = 0;
    int64_t decode_ns = 0;
};
// Always clear recurrent state/KV even when a decode is aborted or throws. Every
// invocation starts solely from the complete, durable Kotlin-rendered exchange.
struct Invocation {
    Runtime & runtime;
    ~Invocation() {
        llama_memory_clear(llama_get_memory(runtime.context), true);
        runtime.active_request.store(0, std::memory_order_release);
    }
};
Result generate(Runtime & runtime, const std::string & prompt, int32_t maximum, int64_t request) {
    usable(runtime);
    if (request <= 0 || request <= runtime.last_request) throw InputError("request_id_not_increasing");
    if (maximum < 1 || maximum > kOutputTokenLimit) throw InputError("output_token_limit_invalid");
    runtime.last_request = request;
    runtime.active_request.store(request, std::memory_order_release);
    Invocation invocation{runtime};
    llama_memory_clear(llama_get_memory(runtime.context), true);
    auto tokens = tokenize(runtime, prompt);
    Result result;
    result.prompt_tokens = static_cast<int32_t>(tokens.size());
    if (result.prompt_tokens > kInputTokenLimit || tokens.size() + maximum > llama_n_ctx(runtime.context))
        throw InputError("context_capacity_exceeded");
    if (runtime.aborted()) { result.stop = Cancelled; return result; }

    const auto prefill_start = Clock::now();
    for (size_t offset = 0; offset < tokens.size(); offset += kBatchSize) {
        if (runtime.aborted()) { result.stop = Cancelled; result.prefill_ns = nanos(prefill_start); return result; }
        const int32_t count = static_cast<int32_t>(std::min<size_t>(kBatchSize, tokens.size() - offset));
        const int32_t rc = llama_decode(runtime.context, llama_batch_get_one(tokens.data() + offset, count));
        if (rc == 2 || runtime.aborted()) { result.stop = Cancelled; result.prefill_ns = nanos(prefill_start); return result; }
        if (rc != 0) throw StateError("prefill_failed");
    }
    result.prefill_ns = nanos(prefill_start);
    const auto decode_start = Clock::now();
    auto params = llama_sampler_chain_default_params();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(params), llama_sampler_free);
    if (!sampler) throw StateError("sampler_init_failed");
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(50));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(runtime.vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    for (auto token : tokens) llama_sampler_accept(sampler.get(), token);
    for (int32_t index = 0; index < maximum; ++index) {
        if (runtime.aborted()) { result.stop = Cancelled; break; }
        llama_token token = llama_sampler_sample(sampler.get(), runtime.context, -1);
        ++result.generated_tokens;
        const auto raw_piece = piece(runtime.vocab, token);
        if (raw_piece.size() > kOutputByteLimit - result.raw.size()) { result.stop = ByteLimit; break; }
        result.raw += raw_piece; // special=true: do not filter tool/control/EOG tokens
        if (llama_vocab_is_eog(runtime.vocab, token)) { result.stop = EndOfGeneration; break; }
        if (index + 1 == maximum) break;
        const int32_t rc = llama_decode(runtime.context, llama_batch_get_one(&token, 1));
        if (rc == 2 || runtime.aborted()) { result.stop = Cancelled; break; }
        if (rc != 0) throw StateError("decode_failed");
    }
    result.decode_ns = nanos(decode_start);
    // An EOG racing cancellation must not become an executable successful call.
    if (runtime.aborted()) result.stop = Cancelled;
    return result;
}
jobject to_java(JNIEnv * env, const Result & result) {
    jbyteArray raw = env->NewByteArray(static_cast<jsize>(result.raw.size()));
    if (!raw) throw JniPending{};
    env->SetByteArrayRegion(raw, 0, static_cast<jsize>(result.raw.size()), reinterpret_cast<const jbyte *>(result.raw.data()));
    if (env->ExceptionCheck()) { env->DeleteLocalRef(raw); throw JniPending{}; }
    jclass cls = env->FindClass("com/clearline/inference/NativeGeneration");
    if (!cls) { env->DeleteLocalRef(raw); throw JniPending{}; }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BIIIJJ)V");
    if (!ctor) { env->DeleteLocalRef(cls); env->DeleteLocalRef(raw); throw JniPending{}; }
    jobject value = env->NewObject(cls, ctor, raw, result.prompt_tokens, result.generated_tokens,
                                   static_cast<jint>(result.stop), static_cast<jlong>(result.prefill_ns),
                                   static_cast<jlong>(result.decode_ns));
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(raw);
    if (!value) throw JniPending{};
    return value;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeCreate(JNIEnv * env, jobject) {
    try {
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (singleton) throw StateError("native_runtime_already_exists");
        if (next_id == std::numeric_limits<int64_t>::max()) throw StateError("native_identity_exhausted");
        std::call_once(backend_once, [] {
            llama_log_set(quiet_log, nullptr);
            ggml_log_set(quiet_log, nullptr);
            llama_backend_init(); // process lifetime; private static CPU backend
        });
        singleton = std::make_shared<Runtime>();
        singleton_id = next_id++;
        return singleton_id;
    } catch (...) { translate_exception(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeLoad(JNIEnv * env, jobject, jlong id, jbyteArray path,
                                                         jint context_size, jint threads) {
    try {
        const std::string filename = bytes(env, path, 4096);
        if (filename.front() != '/' || filename.find('\0') != std::string::npos) throw InputError("model_path_invalid");
        if (context_size != kContextSize || threads < 1 || threads > 8) throw InputError("runtime_config_invalid");
        auto runtime = acquire(id);
        std::lock_guard<std::mutex> lock(runtime->operation);
        usable(*runtime, false);
        if (runtime->model) throw StateError("model_already_loaded");
        runtime->unloading.store(false, std::memory_order_release);
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.progress_callback = continue_load;
        model_params.progress_callback_user_data = runtime.get();
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(filename.c_str(), model_params), llama_model_free);
        if (!model) throw StateError(runtime->aborted() ? "model_load_cancelled" : "model_load_failed");
        char architecture[32] = {};
        const int32_t size = llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
        if (size != 4 || std::string(architecture) != "lfm2") throw StateError("model_architecture_unsupported");
        auto context_params = llama_context_default_params();
        context_params.n_ctx = kContextSize;
        context_params.n_batch = kBatchSize;
        context_params.n_ubatch = kBatchSize;
        context_params.n_seq_max = 1;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        context_params.abort_callback = abort_decode;
        context_params.abort_callback_data = runtime.get();
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), context_params), llama_free);
        if (!context) throw StateError("context_init_failed");
        if (runtime->aborted()) throw StateError("model_load_cancelled");
        if (llama_n_ctx(context.get()) != kContextSize) throw StateError("context_size_mismatch");
        runtime->vocab = llama_model_get_vocab(model.get());
        runtime->model = model.release();
        runtime->context = context.release();
    } catch (...) { translate_exception(env); }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeTokenCount(JNIEnv * env, jobject, jlong id, jbyteArray prompt) {
    try {
        const auto rendered = bytes(env, prompt, kPromptByteLimit);
        auto runtime = acquire(id);
        std::lock_guard<std::mutex> lock(runtime->operation);
        usable(*runtime);
        return static_cast<jint>(tokenize(*runtime, rendered).size());
    } catch (...) { translate_exception(env); return 0; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeGenerate(JNIEnv * env, jobject, jlong id, jbyteArray prompt,
                                                             jint maximum, jlong request) {
    try {
        const auto rendered = bytes(env, prompt, kPromptByteLimit);
        auto runtime = acquire(id);
        std::lock_guard<std::mutex> lock(runtime->operation);
        return to_java(env, generate(*runtime, rendered, maximum, request));
    } catch (...) { translate_exception(env); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeCancel(JNIEnv * env, jobject, jlong id, jlong request) {
    try {
        if (request <= 0) throw InputError("request_id_invalid");
        auto runtime = acquire(id);
        runtime->cancellation.cancel(request); // deliberately never waits for operation
    } catch (...) { translate_exception(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeUnload(JNIEnv * env, jobject, jlong id) {
    try {
        auto runtime = acquire(id);
        runtime->unloading.store(true, std::memory_order_release);
        std::lock_guard<std::mutex> lock(runtime->operation);
        usable(*runtime, false);
        runtime->release(); // generation/load has returned before any memory is freed
    } catch (...) { translate_exception(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_inference_NativeLiquidBridge_nativeDestroy(JNIEnv * env, jobject, jlong id) {
    try {
        std::shared_ptr<Runtime> runtime;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            if (!singleton || id <= 0 || id != singleton_id) throw StateError("invalid_native_handle");
            runtime = singleton;
            runtime->closing.store(true, std::memory_order_release);
            // Keep the singleton until memory is released, so creation cannot
            // overlap two model instances while an old decode is unwinding.
        }
        {
            std::lock_guard<std::mutex> lock(runtime->operation);
            runtime->release();
        }
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (singleton == runtime) { singleton.reset(); singleton_id = 0; }
    } catch (...) { translate_exception(env); }
}
