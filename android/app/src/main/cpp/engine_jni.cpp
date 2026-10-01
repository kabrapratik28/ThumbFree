// JNI bridge over transcribe.cpp's C API. Called only from io.github.kabrapratik28.thumbfree.engine.NativeEngine.
#include <android/api-level.h>
#include <android/log.h>
#include <android/performance_hint.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
#include "transcribe.h"
#include "transcribe/parakeet.h"
#include "vad/silero_vad.h"

namespace {

constexpr const char* kTag = "ThumbFree";

// The CPU backend's threadpool functions, for the persistent pool. The backend is a module loaded at run time, so they
// come from its registry (docs/decisions/native-build.md); each use checks for a missing one.
struct PoolApi {
    ggml_threadpool_t (*make)(ggml_threadpool_params*) = nullptr;
    void (*free)(ggml_threadpool_t) = nullptr;
    void (*pause)(ggml_threadpool_t) = nullptr;
    int (*tids)(ggml_threadpool_t, int32_t*, int) = nullptr;
};

// Resolved once, after the backends are loaded (the first nativeSetThreads), and logged.
const PoolApi& pool_api() {
    static const PoolApi api = [] {
        PoolApi a;
        if (ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU")) {
            auto proc = [reg](const char* name) { return ggml_backend_reg_get_proc_address(reg, name); };
            a.make = reinterpret_cast<decltype(a.make)>(proc("ggml_threadpool_new"));
            a.free = reinterpret_cast<decltype(a.free)>(proc("ggml_threadpool_free"));
            a.pause = reinterpret_cast<decltype(a.pause)>(proc("ggml_threadpool_pause"));
            a.tids = reinterpret_cast<decltype(a.tids)>(proc("ggml_threadpool_get_tids"));
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "asr_pool_api new=%d free=%d pause=%d tids=%d",
                            a.make != nullptr, a.free != nullptr, a.pause != nullptr, a.tids != nullptr);
        return a;
    }();
    return api;
}

std::string join(const std::vector<int>& values) {
    std::string out;
    for (int v : values) out += (out.empty() ? "" : ",") + std::to_string(v);
    return out.empty() ? "-" : out;
}

// Sets the calling thread's CPUs: [cpus], or every CPU when empty (the process's cpuset still applies).
bool set_thread_cpus(const std::vector<int>& cpus) {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (cpus.empty()) {
        for (long c = 0; c < std::min<long>(sysconf(_SC_NPROCESSORS_CONF), CPU_SETSIZE); ++c) CPU_SET(c, &set);
    } else {
        for (int c : cpus) {
            if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
        }
    }
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// Hint modes, as EngineKnobs names them.
constexpr int kHintTarget = 1;    // the target duration before each run, the actual one after
constexpr int kHintIncrease = 2;  // a workload increase just before each run (API 36)

// One loaded model and its session. transcribe.cpp's abort callback reads the abort state (aborted()).
struct Engine {
    transcribe_model* model = nullptr;
    transcribe_session* session = nullptr;
    // Live preview: the bubble's preview stream, a second session of the model that lives only from a take's begin to
    // its end, so with the preview off it costs nothing. It shares the pool and the thread count: every call runs on
    // EngineService's asr thread, so the two sessions never compute at once.
    transcribe_session* stream = nullptr;
    std::atomic<int64_t> stream_token{0};        // the take whose stream is begun (0: none)
    std::atomic<int64_t> stream_abort_token{0};  // the highest stream token an end named (nativeStreamAbort)
    int threads = 0;                             // both sessions' thread count
    std::atomic<bool> abort{false};          // an abort naming no run: stops the run in progress; each run clears it
    std::atomic<int64_t> run_token{0};       // the token the run in progress was given (0: none)
    std::atomic<int64_t> abort_token{0};     // the highest run token an abort named
    bool canary = false;  // gets the PnC-off retry in nativeTranscribe
    bool takes_pool = false;  // runs its graphs on a caller's pool (Parakeet, patch 0011); others take only a count
    int pnc_retries = 0;  // retries run on this handle; EngineService serializes every call on it
    // The thread profile, all set and used on the thread that runs the transcribes (EngineService's asr thread):
    ggml_threadpool_t pool = nullptr;          // the persistent ASR pool; its thread 0 is that thread
    std::vector<int> cpus;                     // where the pool's threads run (empty: anywhere)
    bool strict = false;                       // one CPU per thread
    APerformanceHintSession* hints = nullptr;  // the ADPF session for the pool's threads
    int hint_mode = 0;
    float hint_rtf = 0.1f;                     // the hint's target work duration per second of audio

    ~Engine() {
        transcribe_session_free(stream);   // first: the sessions run on the pool
        transcribe_session_free(session);
        if (hints != nullptr) APerformanceHint_closeSession(hints);  // before the pool whose threads it names
        if (pool != nullptr && pool_api().free != nullptr) pool_api().free(pool);
        transcribe_model_free(model);
    }

    // Run tokens only grow, so an abort naming run T stops the runs up to T and never a later one: a late abort, whose
    // run has ended, leaves the next run alone, and one that lands before its run starts stops it at its first check.
    bool aborted() const {
        const int64_t run = run_token.load();
        return abort.load() || (run != 0 && abort_token.load() >= run);
    }

    void abort_upto(int64_t token) { raise(abort_token, token); }

    // The same for the live preview stream's takes, whose tokens also only grow: an end that lands after the next
    // take's begin leaves that stream alone, and one that lands before its own begin stops it there.
    bool stream_aborted() const {
        const int64_t take = stream_token.load();
        return take != 0 && stream_abort_token.load() >= take;
    }

    static void raise(std::atomic<int64_t>& named, int64_t token) {
        int64_t now = named.load();
        while (now < token && !named.compare_exchange_weak(now, token)) {
        }
    }
};

// Android tags heap pointers in the top byte, so a raw pointer can be a negative jlong. A handle
// drops the pointer's always-zero low bit instead, which keeps it positive (negative means an error).
template <typename T>
jlong to_handle(T* object) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(object) >> 1); }
template <typename T = Engine>
T* from_handle(jlong handle) { return reinterpret_cast<T*>(static_cast<uintptr_t>(handle) << 1); }

// The pool's worker thread ids (thread 0, the caller, not included).
std::vector<int> pool_tids(const Engine* engine) {
    std::vector<int> out;
    if (engine->pool != nullptr && pool_api().tids != nullptr) {
        int32_t tids[64];
        const int n = pool_api().tids(engine->pool, tids, 64);
        out.assign(tids, tids + n);
    }
    return out;
}

void close_hints(Engine* engine) {
    if (engine->hints != nullptr) APerformanceHint_closeSession(engine->hints);
    engine->hints = nullptr;
}

// (Re)makes the ADPF session for the calling thread and the pool's workers: only with a persistent pool, whose thread
// ids are the ones that compute (a model without one makes its workers per graph). Fails open: without ADPF, or on
// an error, the engine runs as before and the log says so.
void make_hints(Engine* engine) {
    close_hints(engine);
    if (engine->hint_mode == 0) return;
    std::vector<int> tids = pool_tids(engine);
    tids.insert(tids.begin(), gettid());
    APerformanceHintManager* manager = engine->pool != nullptr ? APerformanceHint_getManager() : nullptr;
    if (manager != nullptr) {
        std::vector<int32_t> ids(tids.begin(), tids.end());
        // Increase-only mode reports no durations, so it starts with no target.
        const int64_t target = (engine->hint_mode & kHintTarget) ? 500'000'000 : 0;
        engine->hints = APerformanceHint_createSession(manager, ids.data(), ids.size(), target);
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "asr_hints mode=%d session=%d pool=%d tids=%s", engine->hint_mode,
                        engine->hints != nullptr, engine->pool != nullptr, join(tids).c_str());
}

// A nonzero ADPF result, logged (the call fails open).
void check_hint(const char* call, int result) {
    if (result != 0) __android_log_print(ANDROID_LOG_WARN, kTag, "asr_hints %s=%d", call, result);
}

using NotifyWorkload = int (*)(APerformanceHintSession*, bool, bool, const char*);

// API 36's workload increase, looked up so the library still loads on API 33 to 35. Missing on 36 or later: logged once.
NotifyWorkload notify_increase() {
    static const auto fn = [] {
        auto found = reinterpret_cast<NotifyWorkload>(dlsym(RTLD_DEFAULT, "APerformanceHint_notifyWorkloadIncrease"));
        if (found == nullptr && android_get_device_api_level() >= 36) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "asr_hints no APerformanceHint_notifyWorkloadIncrease");
        }
        return found;
    }();
    return fn;
}

android_LogPriority priority(transcribe_log_level level) {
    switch (level) {
        case TRANSCRIBE_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case TRANSCRIBE_LOG_LEVEL_WARN: return ANDROID_LOG_WARN;
        case TRANSCRIBE_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default: return ANDROID_LOG_INFO;
    }
}

// The ggml CPU module the engine registered (the one that won the feature score), e.g. "android_armv8.2_2": the file
// that holds its registry's functions. Other CPU modules can be mapped too (nativeCpuConvCheck opens them all).
std::string cpu_variant() {
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
    void* fn = reg != nullptr ? ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features") : nullptr;
    Dl_info info{};
    if (fn == nullptr || dladdr(fn, &info) == 0 || info.dli_fname == nullptr) return "";
    const std::string path = info.dli_fname;
    const std::string prefix = "libggml-cpu-";
    const auto start = path.rfind(prefix);
    if (start == std::string::npos) return "";
    return path.substr(start + prefix.size(), path.find(".so", start) - start - prefix.size());
}

// Peak resident memory of this process.
jlong vm_hwm_kb() {
    std::ifstream status("/proc/self/status");
    for (std::string line; std::getline(status, line);) {
        if (line.rfind("VmHWM:", 0) == 0) return std::stoll(line.substr(6));
    }
    return 0;
}

bool blank(const char* text) {
    for (; *text; ++text) {
        if (!std::isspace(static_cast<unsigned char>(*text))) return false;
    }
    return true;
}

// A run left a result in the session: full, or partial when aborted or cut short.
bool has_result(transcribe_status status) {
    return status == TRANSCRIBE_OK || status == TRANSCRIBE_ERR_ABORTED || status == TRANSCRIBE_ERR_OUTPUT_TRUNCATED;
}

// NewStringUTF takes modified UTF-8 and CheckJNI aborts on malformed input (for example a character
// cut in half by an aborted decode). String(byte[], "UTF-8") replaces bad bytes with U+FFFD instead.
jstring utf8(JNIEnv* env, const char* text) {
    const auto n = static_cast<jsize>(strlen(text));
    jbyteArray bytes = env->NewByteArray(n);
    env->SetByteArrayRegion(bytes, 0, n, reinterpret_cast<const jbyte*>(text));
    jclass string = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string, "<init>", "([BLjava/lang/String;)V");
    return static_cast<jstring>(env->NewObject(string, ctor, bytes, env->NewStringUTF("UTF-8")));
}

// As utf8, for [n] bytes that need not end in a NUL.
jstring utf8(JNIEnv* env, const char* text, uint64_t n) {
    const auto size = static_cast<jsize>(text != nullptr ? n : 0);
    jbyteArray bytes = env->NewByteArray(size);
    if (size > 0) env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte*>(text));
    jclass string = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string, "<init>", "([BLjava/lang/String;)V");
    return static_cast<jstring>(env->NewObject(string, ctor, bytes, env->NewStringUTF("UTF-8")));
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    // Once per process, before any model or worker thread exists (transcribe.h threading contract).
    transcribe_log_set([](transcribe_log_level level, const char* msg, void*) {
        __android_log_write(priority(level), "transcribe", msg);
    }, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(transcribe_version());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeInit(JNIEnv* env, jobject, jstring lib_dir) {
    const char* dir = env->GetStringUTFChars(lib_dir, nullptr);
    const transcribe_status status = transcribe_init_backends(dir);
    env->ReleaseStringUTFChars(lib_dir, dir);
    __android_log_print(ANDROID_LOG_INFO, kTag, "init_backends status=%d cpu_variant=%s", status, cpu_variant().c_str());
    return status;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeCpuVariant(JNIEnv* env, jobject) {
    return env->NewStringUTF(cpu_variant().c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeLoad(JNIEnv* env, jobject, jstring path, jint threads) {
    auto engine = std::make_unique<Engine>();

    transcribe_model_load_params load;
    transcribe_model_load_params_init(&load);
    load.backend = TRANSCRIBE_BACKEND_CPU;
    const char* file = env->GetStringUTFChars(path, nullptr);
    transcribe_status status = transcribe_model_load_file(file, &load, &engine->model);
    env->ReleaseStringUTFChars(path, file);
    if (status != TRANSCRIBE_OK) return -status;
    engine->canary = std::strcmp(transcribe_model_arch_string(engine->model), "canary") == 0;
    engine->takes_pool = std::strcmp(transcribe_model_arch_string(engine->model), "parakeet") == 0;

    transcribe_session_params session;
    transcribe_session_params_init(&session);
    session.n_threads = threads;
    status = transcribe_session_init(engine->model, &session, &engine->session);
    if (status != TRANSCRIBE_OK) return -status;
    engine->threads = threads;

    transcribe_set_abort_callback(engine->session, [](void* e) { return static_cast<Engine*>(e)->aborted(); },
                                  engine.get());
    return to_handle(engine.release());
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeTranscribe(JNIEnv* env, jobject, jlong handle, jfloatArray pcm, jint n,
                                                          jstring lang, jboolean allow_retry, jlong token) {
    Engine* engine = handle > 0 ? from_handle(handle) : nullptr;
    transcribe_status status = TRANSCRIBE_ERR_INVALID_ARG;
    bool retried = false;
    if (engine != nullptr && n <= env->GetArrayLength(pcm)) {
        engine->run_token = token;
        engine->abort = false;  // an abort naming no run that arrives before the run starts is dropped
        transcribe_run_params params;
        transcribe_run_params_init(&params);
        params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
        // Set, not left to the zero defaults. With no target language Canary's target is its source, so it transcribes,
        // never translates, with punctuation and capitals on. Parakeet has no PNC switch: DEFAULT keeps it exactly as it
        // was, where ON would only log a warning on every run.
        params.task = TRANSCRIBE_TASK_TRANSCRIBE;
        params.pnc = transcribe_model_supports(engine->model, TRANSCRIBE_FEATURE_PNC) ? TRANSCRIBE_PNC_MODE_ON
                                                                                     : TRANSCRIBE_PNC_MODE_DEFAULT;
        params.language = lang ? env->GetStringUTFChars(lang, nullptr) : nullptr;
        jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
        // A strict pool leaves thread 0 on one CPU after each run; the mel's threads, which it starts, get the set.
        if (engine->strict) set_thread_cpus(engine->cpus);
        if (engine->hints != nullptr && (engine->hint_mode & kHintTarget)) {
            const auto target = static_cast<int64_t>(engine->hint_rtf * 1e9 * n / 16000.0);
            check_hint("update_target",
                       APerformanceHint_updateTargetWorkDuration(engine->hints, std::max<int64_t>(target, 100'000'000)));
        }
        if (engine->hints != nullptr && (engine->hint_mode & kHintIncrease) && notify_increase() != nullptr) {
            check_hint("notify_increase", notify_increase()(engine->hints, true, false, "asr"));
        }
        const int64_t start = now_ns();
        status = transcribe_run(engine->session, samples, n, &params);
        // With PnC on, Canary 180M Flash gives end-of-text as its first token on many real noisy takes (7 of 23 private
        // clips over 10 s, in NeMo as well); with PnC off they transcribe. So an empty Canary run that was neither
        // aborted nor cut short runs once more without PnC instead of typing nothing, but only when the caller allows
        // it (TranscriptionQueue: a chunk with enough gate speech), since on noise PnC off invents words about twice as
        // often. An abort that came during the first run stops the second at its start.
        if (allow_retry && engine->canary && status == TRANSCRIBE_OK && blank(transcribe_full_text(engine->session)) &&
            !transcribe_was_aborted(engine->session) && !transcribe_was_truncated(engine->session)) {
            params.pnc = TRANSCRIBE_PNC_MODE_OFF;
            status = transcribe_run(engine->session, samples, n, &params);
            retried = true;
            // empty: no text comes back, whether the retry found none or failed without a result.
            const bool empty = !has_result(status) || blank(transcribe_full_text(engine->session));
            __android_log_print(ANDROID_LOG_INFO, kTag, "canary_pnc_retry n=%d status=%d empty=%d", ++engine->pnc_retries,
                                status, empty);
        }
        if (engine->hints != nullptr && (engine->hint_mode & kHintTarget)) {
            check_hint("report_actual", APerformanceHint_reportActualWorkDuration(engine->hints, now_ns() - start));
        }
        if (engine->pool != nullptr && pool_api().pause != nullptr) pool_api().pause(engine->pool);  // idle: no polling
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        if (lang) env->ReleaseStringUTFChars(lang, params.language);
    }

    // A rejected call leaves the previous run's result in the session, so read results only from a run that made one.
    const bool ran = has_result(status);
    transcribe_timings timings;
    transcribe_timings_init(&timings);
    if (ran) transcribe_get_timings(engine->session, &timings);

    jclass result = env->FindClass("io/github/kabrapratik28/thumbfree/engine/NativeResult");
    jmethodID ctor = env->GetMethodID(result, "<init>", "(ILjava/lang/String;Ljava/lang/String;ZZZFFFJ)V");
    return env->NewObject(result, ctor, status, utf8(env, ran ? transcribe_full_text(engine->session) : ""),
                          utf8(env, ran ? transcribe_raw_text(engine->session) : ""),
                          ran && transcribe_was_truncated(engine->session), ran && transcribe_was_aborted(engine->session),
                          retried, timings.mel_ms, timings.encode_ms, timings.decode_ms, vm_hwm_kb());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeAbort(JNIEnv*, jobject, jlong handle, jlong token) {
    if (handle <= 0) return;
    if (token == 0) {
        from_handle(handle)->abort = true;
    } else {
        from_handle(handle)->abort_upto(token);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeSetThreads(JNIEnv* env, jobject, jlong handle, jint threads,
                                                          jintArray cpus, jboolean strict, jboolean persistent) {
    if (handle <= 0 || threads < 1) return TRANSCRIBE_ERR_INVALID_ARG;
    Engine* engine = from_handle(handle);
    std::vector<int> list;
    if (cpus != nullptr) {
        list.resize(env->GetArrayLength(cpus));
        env->GetIntArrayRegion(cpus, 0, static_cast<jsize>(list.size()), list.data());
    }
    // The old pool goes first, so two pools' workers never run at once, and its hint session before it.
    close_hints(engine);
    transcribe_session_set_threads(engine->session, threads, nullptr);
    if (engine->stream != nullptr) transcribe_session_set_threads(engine->stream, threads, nullptr);
    if (engine->pool != nullptr && pool_api().free != nullptr) pool_api().free(engine->pool);
    engine->pool = nullptr;
    // Only a model that runs its graphs on the pool gets one, and CPUs for it; the others take only the count.
    if (!engine->takes_pool) list.clear();
    engine->cpus = list;
    engine->strict = strict && !list.empty();
    const bool placed = set_thread_cpus(list);  // this thread is the pool's thread 0
    if (persistent && engine->takes_pool && pool_api().make != nullptr) {
        ggml_threadpool_params params = ggml_threadpool_params_default(threads);
        for (int c : list) {
            if (c >= 0 && c < GGML_MAX_N_THREADS) params.cpumask[c] = true;
        }
        params.strict_cpu = engine->strict;
        params.paused = true;  // the first graph wakes it, and every run ends by pausing it again
        engine->pool = pool_api().make(&params);
    }
    const transcribe_status status = transcribe_session_set_threads(engine->session, threads, engine->pool);
    if (engine->stream != nullptr) transcribe_session_set_threads(engine->stream, threads, engine->pool);
    engine->threads = threads;
    __android_log_print(ANDROID_LOG_INFO, kTag, "asr_pool threads=%d cpus=%s strict=%d persistent=%d placed=%d tids=%s "
                        "status=%d takes_pool=%d", threads, join(list).c_str(), engine->strict, engine->pool != nullptr,
                        placed, join(pool_tids(engine)).c_str(), status, engine->takes_pool);
    if (engine->hint_mode != 0) make_hints(engine);  // the new pool's threads
    return status;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeSetHints(JNIEnv*, jobject, jlong handle, jint mode, jfloat rtf) {
    if (handle <= 0) return false;
    Engine* engine = from_handle(handle);
    engine->hint_mode = mode;
    engine->hint_rtf = rtf;
    make_hints(engine);
    return engine->hints != nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeThreadIds(JNIEnv* env, jobject, jlong handle) {
    const std::vector<int> tids = handle > 0 ? pool_tids(from_handle(handle)) : std::vector<int>();
    jintArray out = env->NewIntArray(static_cast<jsize>(tids.size()));
    if (out != nullptr) env->SetIntArrayRegion(out, 0, static_cast<jsize>(tids.size()), tids.data());
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeSetCurrentThreadCpus(JNIEnv* env, jobject, jintArray cpus) {
    std::vector<int> list;
    if (cpus != nullptr) {
        list.resize(env->GetArrayLength(cpus));
        env->GetIntArrayRegion(cpus, 0, static_cast<jsize>(list.size()), list.data());
    }
    return set_thread_cpus(list);
}

// Test hook for patch 0009: in every CPU module in libDir that this CPU can run, the pre-encode's first conv (3x3,
// stride 2, one mel channel in, 256 out, on 128 bins by an odd and an even frame count) on fixed pseudo-random data,
// with the direct kernel and with TRANSCRIBE_CONV2D_GENERIC=1, compared byte for byte. One line per module:
// "<file> same|DIFFERENT <direct ms> <generic ms>", or "<file> skipped <reason>".
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeCpuConvCheck(JNIEnv* env, jobject, jstring libDir) {
    const char* chars = env->GetStringUTFChars(libDir, nullptr);
    const std::string dir = chars;
    env->ReleaseStringUTFChars(libDir, chars);
    std::vector<std::string> modules;
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string name = e->d_name;
            if (name.rfind("libggml-cpu", 0) == 0 && name.size() > 3 && name.compare(name.size() - 3, 3, ".so") == 0) {
                modules.push_back(name);
            }
        }
        closedir(d);
    }
    std::sort(modules.begin(), modules.end());
    std::string out;
    for (const std::string& name : modules) {
        // Left loaded: a module may be the engine's own, and ggml keeps no unload for one it did not register.
        void* lib = dlopen((dir + "/" + name).c_str(), RTLD_NOW | RTLD_LOCAL);
        auto score = lib ? reinterpret_cast<int (*)()>(dlsym(lib, "ggml_backend_score")) : nullptr;
        auto init = lib ? reinterpret_cast<ggml_backend_reg_t (*)()>(dlsym(lib, "ggml_backend_init")) : nullptr;
        if (init == nullptr || (score != nullptr && score() == 0)) {
            out += name + (init == nullptr ? " skipped no module\n" : " skipped unsupported on this CPU\n");
            continue;
        }
        ggml_backend_reg_t reg = init();
        ggml_backend_t backend = ggml_backend_dev_init(ggml_backend_reg_dev_get(reg, 0), nullptr);
        if (auto set_threads = reinterpret_cast<void (*)(ggml_backend_t, int)>(
                ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"))) {
            set_threads(backend, 4);
        }
        bool same = true;
        double ms[2] = {0, 0};
        for (int frames : {1101, 376}) {
            ggml_init_params params = {ggml_tensor_overhead() * 8 + ggml_graph_overhead(), nullptr, true};
            ggml_context* ctx = ggml_init(params);
            ggml_tensor* kernel = ggml_new_tensor_4d(ctx, GGML_TYPE_F32, 3, 3, 1, 256);
            ggml_tensor* mel = ggml_new_tensor_4d(ctx, GGML_TYPE_F32, 128, frames, 1, 1);
            ggml_tensor* conv = ggml_conv_2d_direct(ctx, kernel, mel, 2, 2, 1, 1, 1, 1);
            ggml_cgraph* graph = ggml_new_graph(ctx);
            ggml_build_forward_expand(graph, conv);
            ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, backend);
            uint32_t seed = 12345;
            for (ggml_tensor* t : {kernel, mel}) {
                std::vector<float> v(ggml_nelements(t));
                for (float& x : v) x = static_cast<int32_t>(seed = seed * 1664525u + 1013904223u) / 2147483648.0f;
                ggml_backend_tensor_set(t, v.data(), 0, ggml_nbytes(t));
            }
            std::vector<uint8_t> result[2];
            for (int generic = 0; generic < 2; generic++) {
                if (generic) setenv("TRANSCRIBE_CONV2D_GENERIC", "1", 1);
                const int64_t start = now_ns();
                same = ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS && same;
                ms[generic] += (now_ns() - start) / 1e6;
                if (generic) unsetenv("TRANSCRIBE_CONV2D_GENERIC");
                result[generic].resize(ggml_nbytes(conv));
                ggml_backend_tensor_get(conv, result[generic].data(), 0, result[generic].size());
            }
            same = same && result[0] == result[1];
            ggml_backend_buffer_free(buf);
            ggml_free(ctx);
        }
        ggml_backend_free(backend);
        char line[160];
        snprintf(line, sizeof(line), "%s %s %.1f %.1f\n", name.c_str(), same ? "same" : "DIFFERENT", ms[0], ms[1]);
        out += line;
    }
    return utf8(env, out.c_str());
}

// Live preview: begins the buffered stream for take [token] (> 0, growing) on a new stream session, with the offline
// run's parameters and parakeet-unified's (left, chunk, right) window in ms. A stream a take never ended is reset and
// its session used again. All or nothing: any status but 0 leaves no session, and a take already ended makes none.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeStreamBegin(JNIEnv* env, jobject, jlong handle, jlong token,
                                                           jint left_ms, jint chunk_ms, jint right_ms, jstring lang) {
    Engine* engine = handle > 0 ? from_handle(handle) : nullptr;
    if (engine == nullptr || token <= 0) return TRANSCRIBE_ERR_INVALID_ARG;
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    transcribe_model_get_capabilities(engine->model, &caps);
    if (!caps.supports_streaming) return TRANSCRIBE_ERR_NOT_IMPLEMENTED;  // before any allocation (Canary)
    // A take whose end came first gets nothing made: the end named its token before this begin ran.
    if (engine->stream_abort_token.load() >= token) return TRANSCRIBE_ERR_ABORTED;
    const int64_t start = now_ns();
    // Every failure from here on frees the session, so a begin that does not open a stream leaves nothing behind.
    const auto failed = [engine](transcribe_status status) {
        transcribe_session_free(engine->stream);
        engine->stream = nullptr;
        engine->stream_token = 0;
        return status;
    };
    if (engine->stream == nullptr) {
        transcribe_session_params session;
        transcribe_session_params_init(&session);
        session.n_threads = engine->threads;
        const transcribe_status status = transcribe_session_init(engine->model, &session, &engine->stream);
        if (status != TRANSCRIBE_OK) return failed(status);
        transcribe_set_abort_callback(engine->stream, [](void* e) { return static_cast<Engine*>(e)->stream_aborted(); },
                                      engine);
        transcribe_session_set_threads(engine->stream, engine->threads, engine->pool);
    }
    transcribe_stream_reset(engine->stream);
    engine->stream_token = token;
    if (engine->stream_aborted()) return failed(TRANSCRIBE_ERR_ABORTED);  // the end came while it was being made
    transcribe_run_params params;
    transcribe_run_params_init(&params);
    params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;
    params.pnc = transcribe_model_supports(engine->model, TRANSCRIBE_FEATURE_PNC) ? TRANSCRIBE_PNC_MODE_ON
                                                                                 : TRANSCRIBE_PNC_MODE_DEFAULT;
    params.language = lang ? env->GetStringUTFChars(lang, nullptr) : nullptr;
    transcribe_parakeet_buffered_stream_ext window;
    transcribe_parakeet_buffered_stream_ext_init(&window);
    window.left_ms = left_ms;
    window.chunk_ms = chunk_ms;
    window.right_ms = right_ms;
    transcribe_stream_params stream;
    transcribe_stream_params_init(&stream);
    stream.family = &window.ext;  // begin copies it
    const transcribe_status status = transcribe_stream_begin(engine->stream, &params, &stream);
    if (lang) env->ReleaseStringUTFChars(lang, params.language);
    __android_log_print(ANDROID_LOG_INFO, kTag, "stream_begin window=%d/%d/%d status=%d ms=%.1f", left_ms, chunk_ms,
                        right_ms, status, (now_ns() - start) / 1e6);
    return status == TRANSCRIBE_OK ? status : failed(status);
}

// Live preview: feeds the first [n] samples of 16 kHz mono [pcm] (floats, as Wav.readFloat) to the stream and returns
// its text as a StreamUpdate. n may be 0: the text as it is.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeStreamFeed(JNIEnv* env, jobject, jlong handle, jfloatArray pcm,
                                                          jint n) {
    Engine* engine = handle > 0 ? from_handle(handle) : nullptr;
    transcribe_status status = TRANSCRIBE_ERR_INVALID_ARG;
    transcribe_stream_update update;
    transcribe_stream_update_init(&update);
    transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    int64_t ns = 0;
    if (engine != nullptr && engine->stream != nullptr && n >= 0 && n <= env->GetArrayLength(pcm)) {
        status = TRANSCRIBE_OK;
        if (n > 0) {
            std::vector<float> samples(static_cast<size_t>(n));
            env->GetFloatArrayRegion(pcm, 0, n, samples.data());
            if (engine->strict) set_thread_cpus(engine->cpus);  // as nativeTranscribe: the mel's threads get the set
            const int64_t start = now_ns();
            status = transcribe_stream_feed(engine->stream, samples.data(), n, &update);
            ns = now_ns() - start;
            if (engine->pool != nullptr && pool_api().pause != nullptr) pool_api().pause(engine->pool);  // idle
        }
        if (status == TRANSCRIBE_OK) transcribe_stream_get_text(engine->stream, &text);
    }
    jclass result = env->FindClass("io/github/kabrapratik28/thumbfree/engine/StreamUpdate");
    jmethodID ctor = env->GetMethodID(result, "<init>", "(ILjava/lang/String;Ljava/lang/String;JJF)V");
    return env->NewObject(result, ctor, status, utf8(env, text.committed_text, text.committed_text_bytes),
                          utf8(env, text.tentative_text, text.tentative_text_bytes), static_cast<jlong>(update.input_received_ms),
                          static_cast<jlong>(update.audio_committed_ms), static_cast<jfloat>(ns / 1e6));
}

// Live preview: ends the stream and frees its session, its audio, text and compute buffers with it: none of it stays
// between takes.
extern "C" JNIEXPORT void JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeStreamFree(JNIEnv*, jobject, jlong handle) {
    Engine* engine = handle > 0 ? from_handle(handle) : nullptr;
    if (engine == nullptr) return;
    transcribe_session_free(engine->stream);
    engine->stream = nullptr;
    engine->stream_token = 0;
}

// Live preview: ends take [token]'s stream from any thread: its chunk in progress stops at the next graph node
// (patch 0014) and that feed returns 13, as does its begin if it comes later. An earlier take's end never touches a
// later take's stream.
extern "C" JNIEXPORT void JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeStreamAbort(JNIEnv*, jobject, jlong handle, jlong token) {
    if (handle > 0) Engine::raise(from_handle(handle)->stream_abort_token, token);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeInfo(JNIEnv* env, jobject, jlong handle) {
    if (handle <= 0) return utf8(env, "");
    const Engine* engine = from_handle(handle);
    const transcribe_model* model = engine->model;
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    transcribe_model_get_capabilities(model, &caps);

    std::string languages;
    for (int i = 0; i < caps.n_languages; i++) languages += (i ? "," : "") + std::string(caps.languages[i]);
    const std::string info =
        std::string("arch=") + transcribe_model_arch_string(model) + " variant=" + transcribe_model_variant_string(model) +
        " languages=" + languages + " max_audio_ms=" + std::to_string(caps.max_audio_ms) +
        " supports_streaming=" + (caps.supports_streaming ? "true" : "false") +
        " cancellation=" + (transcribe_model_supports(model, TRANSCRIBE_FEATURE_CANCELLATION) ? "true" : "false") +
        " cpu_variant=" + cpu_variant() + " pnc_retries=" + std::to_string(engine->pnc_retries) +
        " stream_session=" + (engine->stream != nullptr ? "1" : "0") + " stream_active=" +
        (engine->stream != nullptr && transcribe_stream_get_state(engine->stream) == TRANSCRIBE_STREAM_ACTIVE ? "1" : "0");
    return utf8(env, info.c_str());  // GGUF metadata can hold malformed UTF-8
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeFree(JNIEnv*, jobject, jlong handle) {
    if (handle > 0) delete from_handle(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeVadLoad(JNIEnv* env, jobject, jstring path) {
    const char* file = env->GetStringUTFChars(path, nullptr);
    whisper_vad_context* vad = silero_vad_init(file);
    env->ReleaseStringUTFChars(path, file);
    return vad != nullptr ? to_handle(vad) : 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeVadProbs(JNIEnv* env, jobject, jlong handle, jfloatArray pcm, jint n) {
    if (handle <= 0 || n < 0 || n > env->GetArrayLength(pcm)) return nullptr;
    auto* vad = from_handle<whisper_vad_context>(handle);
    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
    const bool ok = silero_vad_detect(vad, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (!ok) return nullptr;
    const jsize count = silero_vad_n_probs(vad);
    jfloatArray probs = env->NewFloatArray(count);
    if (probs != nullptr) env->SetFloatArrayRegion(probs, 0, count, silero_vad_probs(vad));
    return probs;
}

// Live preview: Silero on the next [n] samples of a stream (whole 512-sample windows), its state going on from the last
// call: one probability per window, or null on failure.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeVadStream(JNIEnv* env, jobject, jlong handle, jfloatArray pcm, jint n) {
    if (handle <= 0 || n < 0 || n > env->GetArrayLength(pcm)) return nullptr;
    auto* vad = from_handle<whisper_vad_context>(handle);
    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
    const bool ok = silero_vad_detect_stream(vad, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (!ok) return nullptr;
    const jsize count = silero_vad_n_probs(vad);
    jfloatArray probs = env->NewFloatArray(count);
    if (probs != nullptr) env->SetFloatArrayRegion(probs, 0, count, silero_vad_probs(vad));
    return probs;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kabrapratik28_thumbfree_engine_NativeEngine_nativeVadFree(JNIEnv*, jobject, jlong handle) {
    if (handle > 0) silero_vad_free(from_handle<whisper_vad_context>(handle));
}
