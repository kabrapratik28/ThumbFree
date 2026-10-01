// For tools/v3-check.py: a Parakeet GGUF through transcribe.cpp's C API on the CPU, run as
// android/app/src/main/cpp/engine_jni.cpp runs it: the same run parameters with a language hint or none, and in a
// patched build the persistent pool (transcribe_session_set_threads, made through the CPU backend's registry and paused
// after every run). macOS has no CPU affinity, so the pool is unpinned. The caller pads the audio as Padding.forEngine
// does.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include "ggml-backend.h"
#include "ggml.h"
#include "transcribe.h"

static transcribe_model * model = nullptr;
static transcribe_session * session = nullptr;
static ggml_threadpool_t pool = nullptr;
static std::atomic<bool> abort_now{false};

static void * cpu_proc(const char * name) {
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
    return reg != nullptr ? ggml_backend_reg_get_proc_address(reg, name) : nullptr;
}

// Loads the model on `threads` CPU threads, on a persistent pool with `with_pool` (a patched build only); with `verbose`
// transcribe.cpp's log goes to stderr. Returns a transcribe_status.
extern "C" int asr_load(const char * path, int threads, int with_pool, int verbose) {
    if (verbose) {
        transcribe_log_set([](transcribe_log_level, const char * msg, void *) { std::fputs(msg, stderr); }, nullptr);
    } else {
        transcribe_log_set([](transcribe_log_level, const char *, void *) {}, nullptr);
    }
    transcribe_model_load_params load;
    transcribe_model_load_params_init(&load);
    load.backend = TRANSCRIBE_BACKEND_CPU;
    transcribe_status status = transcribe_model_load_file(path, &load, &model);
    if (status != TRANSCRIBE_OK) return status;
    transcribe_session_params params;
    transcribe_session_params_init(&params);
    params.n_threads = threads;
    status = transcribe_session_init(model, &params, &session);
    if (status != TRANSCRIBE_OK || !with_pool) return status;
#ifdef V3_PATCHED
    auto make = reinterpret_cast<ggml_threadpool_t (*)(ggml_threadpool_params *)>(cpu_proc("ggml_threadpool_new"));
    if (make == nullptr) return TRANSCRIBE_ERR_BACKEND;
    ggml_threadpool_params pool_params = ggml_threadpool_params_default(threads);
    pool_params.paused = true;  // as engine_jni.cpp: the first graph wakes it, and every run ends by pausing it again
    pool = make(&pool_params);
    return transcribe_session_set_threads(session, threads, pool);
#else
    return TRANSCRIBE_ERR_INVALID_ARG;  // no transcribe_session_set_threads without patch 0011
#endif
}

// Transcribes samples[0, n) with `language` (nullptr: none); the text goes to out (at most cap bytes, NUL-terminated)
// and {mel_ms, encode_ms, decode_ms} to ms. Returns the transcribe_status.
extern "C" int asr_run(const float * samples, int n, const char * language, char * out, int cap, float * ms) {
    transcribe_run_params params;
    transcribe_run_params_init(&params);
    params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;
    params.pnc = transcribe_model_supports(model, TRANSCRIBE_FEATURE_PNC) ? TRANSCRIBE_PNC_MODE_ON : TRANSCRIBE_PNC_MODE_DEFAULT;
    params.language = language;
    const transcribe_status status = transcribe_run(session, samples, n, &params);
    if (pool != nullptr) {
        if (auto pause = reinterpret_cast<void (*)(ggml_threadpool_t)>(cpu_proc("ggml_threadpool_pause"))) pause(pool);
    }
    const bool ran = status == TRANSCRIBE_OK || status == TRANSCRIBE_ERR_ABORTED || status == TRANSCRIBE_ERR_OUTPUT_TRUNCATED;
    std::strncpy(out, ran ? transcribe_full_text(session) : "", cap - 1);
    out[cap - 1] = '\0';
    transcribe_timings timings;
    transcribe_timings_init(&timings);
    if (ran) transcribe_get_timings(session, &timings);
    ms[0] = timings.mel_ms;
    ms[1] = timings.encode_ms;
    ms[2] = timings.decode_ms;
    return status;
}

// Patch 0007's check: transcribes samples[0, n) and asks for an abort after_ms into the run, as the app's abort does
// (transcribe_set_abort_callback). ms gets how long the run went on after the ask (-1 when it ended first) and the whole
// run's time. Returns the transcribe_status: 13 for an aborted run.
extern "C" int asr_abort_after(const float * samples, int n, int after_ms, float * ms) {
    transcribe_set_abort_callback(session, [](void *) { return abort_now.load(); }, nullptr);
    abort_now = false;
    using clock = std::chrono::steady_clock;
    const clock::time_point start = clock::now();
    clock::time_point asked{};
    std::thread asker([&] {
        std::this_thread::sleep_for(std::chrono::milliseconds(after_ms));
        asked = clock::now();
        abort_now = true;
    });
    transcribe_run_params params;
    transcribe_run_params_init(&params);
    params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;
    const transcribe_status status = transcribe_run(session, samples, n, &params);
    const clock::time_point end = clock::now();
    asker.join();
    if (pool != nullptr) {
        if (auto pause = reinterpret_cast<void (*)(ggml_threadpool_t)>(cpu_proc("ggml_threadpool_pause"))) pause(pool);
    }
    transcribe_set_abort_callback(session, nullptr, nullptr);
    abort_now = false;
    ms[0] = asked < end ? std::chrono::duration<float, std::milli>(end - asked).count() : -1.0f;
    ms[1] = std::chrono::duration<float, std::milli>(end - start).count();
    return status;
}

// "arch variant lang_detect languages" of the loaded model.
extern "C" void asr_info(char * out, int cap) {
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    transcribe_model_get_capabilities(model, &caps);
    std::string info = std::string(transcribe_model_arch_string(model)) + " " + transcribe_model_variant_string(model) +
                       " " + (caps.supports_language_detect ? "1" : "0") + " ";
    for (int i = 0; i < caps.n_languages; i++) info += (i ? "," : "") + std::string(caps.languages[i]);
    std::snprintf(out, cap, "%s", info.c_str());
}
