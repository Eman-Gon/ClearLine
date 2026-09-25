#include <jni.h>
#include <whisper.h>
#include <ggml.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Runtime {
    whisper_context * ctx = nullptr;
    std::atomic<bool> cancelled {false};
    std::chrono::steady_clock::time_point deadline;
    ~Runtime() { if (ctx) whisper_free(ctx); }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Runtime>> runtimes;
jlong next_handle = 1;
void error(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
void discard_log(ggml_log_level, const char *, void *) {}
std::shared_ptr<Runtime> get(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto it = runtimes.find(handle);
    return it == runtimes.end() ? nullptr : it->second;
}
bool abort_inference(void * data) {
    auto * runtime = static_cast<Runtime *>(data);
    return runtime->cancelled.load(std::memory_order_relaxed) || std::chrono::steady_clock::now() >= runtime->deadline;
}
bool begin_encoder(whisper_context *, whisper_state *, void * data) { return !abort_inference(data); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_clearline_audio_NativeWhisperBridge_load(JNIEnv * env, jobject, jstring path) {
    try {
        whisper_log_set(discard_log, nullptr); ggml_log_set(discard_log, nullptr);
        auto runtime = std::make_shared<Runtime>();
        const char * native_path = env->GetStringUTFChars(path, nullptr);
        if (!native_path) return 0;
        auto params = whisper_context_default_params(); params.use_gpu = false; params.flash_attn = false;
        runtime->ctx = whisper_init_from_file_with_params(native_path, params);
        env->ReleaseStringUTFChars(path, native_path);
        if (!runtime->ctx) { error(env, "Transcription model could not load"); return 0; }
        std::lock_guard<std::mutex> lock(registry_mutex);
        const jlong handle = next_handle++; runtimes.emplace(handle, runtime); return handle;
    } catch (...) { error(env, "Transcription model load failed"); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_audio_NativeWhisperBridge_resetCancellation(JNIEnv *, jobject, jlong handle) {
    auto runtime = get(handle);
    if (runtime) runtime->cancelled.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_clearline_audio_NativeWhisperBridge_transcribe(JNIEnv * env, jobject, jlong handle, jfloatArray samples) {
    try {
        auto runtime = get(handle);
        if (!runtime) { error(env, "Transcription model is not loaded"); return nullptr; }
        const int count = env->GetArrayLength(samples);
        if (count < 32000 || count > 960000) { error(env, "Audio duration is outside supported limits"); return nullptr; }
        std::vector<float> pcm(count); env->GetFloatArrayRegion(samples, 0, count, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        runtime->deadline = std::chrono::steady_clock::now() + std::chrono::seconds(90);
        if (abort_inference(runtime.get())) { error(env, "Transcription cancelled"); return nullptr; }
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = 4; params.language = "en"; params.translate = false;
        params.no_context = true; params.no_timestamps = true;
        params.print_special = false; params.print_progress = false; params.print_realtime = false; params.print_timestamps = false;
        params.suppress_blank = true; params.suppress_nst = true;
        params.temperature = 0.0f; params.temperature_inc = 0.0f; params.no_speech_thold = 0.6f;
        params.abort_callback = abort_inference; params.abort_callback_user_data = runtime.get();
        params.encoder_begin_callback = begin_encoder; params.encoder_begin_callback_user_data = runtime.get();
        if (whisper_full(runtime->ctx, params, pcm.data(), count) != 0 || abort_inference(runtime.get())) {
            error(env, "Transcription cancelled, timed out or failed"); return nullptr;
        }
        std::string text;
        const int segments = whisper_full_n_segments(runtime->ctx);
        for (int i = 0; i < segments; ++i) {
            // Acoustic checks happen before ASR; do not accept likely non-speech hallucinations.
            const float no_speech = whisper_full_get_segment_no_speech_prob(runtime->ctx, i);
            if (!std::isfinite(no_speech) || no_speech >= 0.6f) continue;
            const char * segment = whisper_full_get_segment_text(runtime->ctx, i);
            if (segment) { text += segment; text += ' '; }
            if (text.size() > 20000) { error(env, "Transcription exceeded output limit"); return nullptr; }
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
        return result;
    } catch (...) { error(env, "Transcription failed"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_audio_NativeWhisperBridge_cancel(JNIEnv *, jobject, jlong handle) {
    auto runtime = get(handle);
    if (runtime) runtime->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_clearline_audio_NativeWhisperBridge_unload(JNIEnv *, jobject, jlong handle) {
    // Kotlin holds the shared arbiter and runtime mutex; shared_ptr also prevents cancel/free races.
    std::lock_guard<std::mutex> lock(registry_mutex); runtimes.erase(handle);
}
