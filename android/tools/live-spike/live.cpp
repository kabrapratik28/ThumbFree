// For android/tools/live-spike.py: Parakeet through transcribe.cpp's C API on the CPU, offline with the parameters
// android/app/src/main/cpp/engine_jni.cpp uses, and as a buffered stream (transcribe_stream_*, parakeet-unified's
// (left, chunk, right) extension) with the same run parameters.
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>

#include "arch/parakeet/parakeet.h"  // live_pcm: the engine's own session type
#include "ggml-backend.h"
#include "ggml.h"
#include "transcribe.h"
#include "transcribe/parakeet.h"

static transcribe_model * model = nullptr;
static transcribe_session * sessions[2] = {nullptr, nullptr};  // the offline session, and a second one for a stream
static transcribe_session * session = nullptr;                 // the one the calls below use
static int n_threads = 0;
static std::atomic<bool> abort_flag{false};  // read by both sessions' abort callbacks (live_abort)
static ggml_threadpool_t pool = nullptr;     // live_pool's persistent pool, set on both sessions
static std::atomic<int64_t> polls{0};        // abort polls since live_polls_reset
static std::atomic<int64_t> abort_at{-1};    // the poll from which the callback aborts (-1: none)
static std::atomic<int64_t> aborted_ns{0};   // steady clock ns at the first poll that aborted (0: none yet)

// Both sessions' abort callback: counts every poll, and aborts on live_abort or from poll abort_at on.
static bool poll_cb(void *) {
    const int64_t n = ++polls;
    const int64_t at = abort_at.load();
    if (!abort_flag.load() && (at < 0 || n < at)) return false;
    if (aborted_ns.load() == 0) {
        aborted_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                         std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    return true;
}

static transcribe_run_params run_params(transcribe_timestamp_kind timestamps) {
    transcribe_run_params params;
    transcribe_run_params_init(&params);
    params.timestamps = timestamps;
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;
    params.pnc = transcribe_model_supports(model, TRANSCRIBE_FEATURE_PNC) ? TRANSCRIBE_PNC_MODE_ON : TRANSCRIBE_PNC_MODE_DEFAULT;
    params.language = "en";
    return params;
}

static void copy(const char * text, uint64_t n, char * out, int cap) {
    const uint64_t m = text == nullptr ? 0 : (n < static_cast<uint64_t>(cap - 1) ? n : static_cast<uint64_t>(cap - 1));
    if (m) std::memcpy(out, text, m);
    out[m] = '\0';
}

extern "C" int live_load(const char * path, int threads) {
    transcribe_log_set([](transcribe_log_level, const char *, void *) {}, nullptr);
    transcribe_model_load_params load;
    transcribe_model_load_params_init(&load);
    load.backend = TRANSCRIBE_BACKEND_CPU;
    transcribe_status status = transcribe_model_load_file(path, &load, &model);
    if (status != TRANSCRIBE_OK) return status;
    n_threads = threads;
    transcribe_session_params params;
    transcribe_session_params_init(&params);
    params.n_threads = threads;
    status = transcribe_session_init(model, &params, &sessions[0]);
    session = sessions[0];
    if (status == TRANSCRIBE_OK) transcribe_set_abort_callback(session, poll_cb, nullptr);
    return status;
}

// Sets (1) or clears (0) the abort flag both sessions' abort callbacks read; callable while another thread runs.
extern "C" void live_abort(int on) { abort_flag = on != 0; }

// Restarts the poll count; with at > 0 the callback aborts from the at-th poll on (-1: never).
extern "C" void live_polls_reset(int64_t at) {
    polls = 0;
    aborted_ns = 0;
    abort_at = at;
}

// Polls since live_polls_reset, the steady clock ns at which the callback first aborted (0: it has not), and now.
extern "C" int64_t live_polls() { return polls.load(); }
extern "C" int64_t live_aborted_ns() { return aborted_ns.load(); }
extern "C" int64_t live_now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// The current session's buffered-stream audio: {samples held, samples allocated (capacity)}.
extern "C" void live_pcm(int64_t * out) {
    const auto * pc = static_cast<const transcribe::parakeet::ParakeetSession *>(session);
    out[0] = static_cast<int64_t>(pc->stream_pcm_buffer.size());
    out[1] = static_cast<int64_t>(pc->stream_pcm_buffer.capacity());
}

// A persistent ggml CPU pool of the session's thread count, made through the CPU backend's registry as
// android/app/src/main/cpp/engine_jni.cpp makes the app's, and set on both sessions (on = 0: none, a pool per graph).
extern "C" int live_pool(int on) {
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
    auto make = reinterpret_cast<ggml_threadpool_t (*)(ggml_threadpool_params *)>(
        ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"));
    auto free_pool = reinterpret_cast<void (*)(ggml_threadpool_t)>(
        ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free"));
    if (make == nullptr || free_pool == nullptr) return TRANSCRIBE_ERR_BACKEND;
    for (auto * s : sessions) {
        if (s != nullptr) transcribe_session_set_threads(s, n_threads, nullptr);
    }
    if (pool != nullptr) free_pool(pool);
    pool = nullptr;
    if (on) {
        ggml_threadpool_params params = ggml_threadpool_params_default(n_threads);
        params.paused = true;
        pool = make(&params);
    }
    for (auto * s : sessions) {
        if (s != nullptr) transcribe_session_set_threads(s, n_threads, pool);
    }
    return TRANSCRIBE_OK;
}

// Makes session i (0 or 1) the one the other calls use, creating it on first use: a stream beside offline runs
// needs a session of its own.
extern "C" int live_select(int i) {
    if (sessions[i] == nullptr) {
        transcribe_session_params params;
        transcribe_session_params_init(&params);
        params.n_threads = n_threads;
        const transcribe_status status = transcribe_session_init(model, &params, &sessions[i]);
        if (status != TRANSCRIBE_OK) return status;
        transcribe_set_abort_callback(sessions[i], poll_cb, nullptr);
        transcribe_session_set_threads(sessions[i], n_threads, pool);
    }
    session = sessions[i];
    return TRANSCRIBE_OK;
}

// Offline: the text of samples[0, n) into out; with tokens set, token timestamps ("t0_ms t1_ms word_index text" lines).
extern "C" int live_run(const float * samples, int n, int tokens, char * out, int cap) {
    const transcribe_run_params params = run_params(tokens ? TRANSCRIBE_TIMESTAMPS_TOKEN : TRANSCRIBE_TIMESTAMPS_NONE);
    const transcribe_status status = transcribe_run(session, samples, n, &params);
    if (status != TRANSCRIBE_OK) return status;
    std::string text;
    if (!tokens) {
        text = transcribe_full_text(session);
    } else {
        for (int i = 0; i < transcribe_n_tokens(session); ++i) {
            transcribe_token t;
            transcribe_token_init(&t);
            transcribe_get_token(session, i, &t);
            text += std::to_string(t.t0_ms) + " " + std::to_string(t.t1_ms) + " " + std::to_string(t.word_index) + " " +
                    (t.text ? t.text : "") + "\n";
        }
    }
    copy(text.data(), text.size(), out, cap);
    return TRANSCRIBE_OK;
}

// Begins a buffered stream with (left, chunk, right) in ms and the commit policy (transcribe_stream_commit_policy).
extern "C" int live_begin(int left_ms, int chunk_ms, int right_ms, int policy) {
    const transcribe_run_params params = run_params(TRANSCRIBE_TIMESTAMPS_NONE);
    transcribe_parakeet_buffered_stream_ext ext;
    transcribe_parakeet_buffered_stream_ext_init(&ext);
    ext.left_ms = left_ms;
    ext.chunk_ms = chunk_ms;
    ext.right_ms = right_ms;
    transcribe_stream_params stream;
    transcribe_stream_params_init(&stream);
    stream.family = &ext.ext;
    stream.commit_policy = static_cast<transcribe_stream_commit_policy>(policy);
    return transcribe_stream_begin(session, &params, &stream);
}

// Ends the session's stream, finished or not (a begin needs no stream open).
extern "C" void live_reset() { transcribe_stream_reset(session); }

// Feeds samples (n > 0) or, with n == 0, finalizes. progress gets {input_received_ms, audio_committed_ms, buffered_ms}.
extern "C" int live_feed(const float * samples, int n, int64_t * progress) {
    transcribe_stream_update update;
    transcribe_stream_update_init(&update);
    const transcribe_status status = n > 0 ? transcribe_stream_feed(session, samples, n, &update)
                                           : transcribe_stream_finalize(session, &update);
    progress[0] = update.input_received_ms;
    progress[1] = update.audio_committed_ms;
    progress[2] = update.buffered_ms;
    return status;
}

// The stream's committed, tentative and full text, each into its own buffer of cap bytes.
extern "C" int live_text(char * committed, char * tentative, char * full, int cap) {
    transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    const transcribe_status status = transcribe_stream_get_text(session, &text);
    if (status != TRANSCRIBE_OK) return status;
    copy(text.committed_text, text.committed_text_bytes, committed, cap);
    copy(text.tentative_text, text.tentative_text_bytes, tentative, cap);
    copy(text.full_text, text.full_text_bytes, full, cap);
    return TRANSCRIBE_OK;
}

// {mel_ms, encode_ms, decode_ms} of the last run or stream.
extern "C" void live_timings(float * out) {
    transcribe_timings t;
    transcribe_timings_init(&t);
    transcribe_get_timings(session, &t);
    out[0] = t.mel_ms;
    out[1] = t.encode_ms;
    out[2] = t.decode_ms;
}
