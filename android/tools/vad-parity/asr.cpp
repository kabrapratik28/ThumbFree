// For android/tools/vad-parity.py: Parakeet through transcribe.cpp's C API on the CPU, run with the parameters
// android/app/src/main/cpp/engine_jni.cpp uses (no Canary retry). The caller pads the audio as Padding.forEngine does.
#include <cstring>

#include "transcribe.h"

static transcribe_model * model = nullptr;
static transcribe_session * session = nullptr;

extern "C" int asr_load(const char * path, int threads) {
    transcribe_log_set([](transcribe_log_level, const char *, void *) {}, nullptr); // the tool prints numbers only
    transcribe_model_load_params load;
    transcribe_model_load_params_init(&load);
    load.backend = TRANSCRIBE_BACKEND_CPU;
    transcribe_status status = transcribe_model_load_file(path, &load, &model);
    if (status != TRANSCRIBE_OK) return status;
    transcribe_session_params params;
    transcribe_session_params_init(&params);
    params.n_threads = threads;
    return transcribe_session_init(model, &params, &session);
}

// Writes the text of samples[0, n) to out (at most cap bytes, NUL-terminated); returns the transcribe_status.
extern "C" int asr_run(const float * samples, int n, char * out, int cap) {
    transcribe_run_params params;
    transcribe_run_params_init(&params);
    params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;
    params.pnc = transcribe_model_supports(model, TRANSCRIBE_FEATURE_PNC) ? TRANSCRIBE_PNC_MODE_ON : TRANSCRIBE_PNC_MODE_DEFAULT;
    params.language = "en";
    const transcribe_status status = transcribe_run(session, samples, n, &params);
    const bool ran = status == TRANSCRIBE_OK || status == TRANSCRIBE_ERR_ABORTED || status == TRANSCRIBE_ERR_OUTPUT_TRUNCATED;
    std::strncpy(out, ran ? transcribe_full_text(session) : "", cap - 1);
    out[cap - 1] = '\0';
    return status;
}
