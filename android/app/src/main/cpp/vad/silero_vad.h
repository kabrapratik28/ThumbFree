// Silero VAD v6.2 on ggml, vendored from whisper.cpp (see silero_vad.cpp and LICENSE).
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

struct whisper_vad_context;

// Loads a ggml Silero model (ggml-silero-v6.2.0.bin) on the ggml CPU device, one thread. The CPU backend must already
// be registered (transcribe_init_backends). Returns NULL on failure.
struct whisper_vad_context * silero_vad_init(const char * path_model);

// The speech probability of each 512-sample window of samples[0, n_samples), the last window zero-padded, as official
// Silero computes them: each window sees the 64 samples before it, and the recurrent state and that context start from
// zero at every call. Returns false on failure.
bool silero_vad_detect(struct whisper_vad_context * vctx, const float * samples, int n_samples);

// ThumbFree (live preview): as silero_vad_detect, but the recurrent state and the context go on from the previous call,
// so a stream is fed a batch at a time; n_samples should be whole 512-sample windows. silero_vad_reset starts it again.
bool silero_vad_detect_stream(struct whisper_vad_context * vctx, const float * samples, int n_samples);
void silero_vad_reset(struct whisper_vad_context * vctx);

// The probabilities of the last detect.
int silero_vad_n_probs(struct whisper_vad_context * vctx);
const float * silero_vad_probs(struct whisper_vad_context * vctx);

void silero_vad_free(struct whisper_vad_context * vctx);

#ifdef __cplusplus
}
#endif
