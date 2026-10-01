// Silero VAD on ggml, vendored from whisper.cpp src/whisper.cpp ("Voice Activity Detection (VAD)" and the ggml helpers
// it uses), https://github.com/ggml-org/whisper.cpp at d09f61a708f3487afa956ff578e60eae5e7a233c.
// MIT License, Copyright (c) 2023-2026 The ggml authors: see LICENSE in this directory.
//
// Changed for ThumbFree where marked "ThumbFree:":
//  - The 64-sample context. Official Silero v6.2 feeds its model each 512-sample window with the last 64 samples of the
//    previous window in front (zeros before the first) and reflects only the right edge (F.pad(x, (0, 64), "reflect")).
//    Upstream reads n_context from the model file and ignores it, reflecting 64 samples of the window itself on the left
//    instead. The context now lives in the context with the recurrent state and is reset with it.
//  - The ggml CPU device only, found through the backend registry (the app loads its CPU backend as a module), with one
//    thread. Upstream also looks for a GPU and for extra CPU buffer types, which never hold these weights.
//  - Loads from a file path, with no whisper_context. The speech segment code is left out.
//  - The convolutions in F32. ggml_conv_1d rounds the audio and every activation to F16 on the way into its matrix
//    product, which put single probabilities up to 0.043 away from official Silero; in F32 only the F16 weights of the
//    model file remain, under 0.01 (android/tools/vad-parity.py). The F16 weights are widened to F32 at load.
//  - The LSTM input is made contiguous before its matrix product (see whisper_vad_build_lstm_layer).
//  - A failed graph compute fails the detect; upstream stopped and still reported success.
//  - silero_vad_detect_stream and silero_vad_reset: the no-reset detect in the C API, for the live preview's gate. The
//    recurrent state is zeroed when the context is made (upstream left it as allocated: every detect reset it first).

#include "silero_vad.h"

#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <functional>
#include <map>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define VAD_LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, "silero_vad", __VA_ARGS__)
#else
#define VAD_LOG_ERROR(...) fprintf(stderr, __VA_ARGS__)
#endif

#define WHISPER_MAX_NODES 4096

namespace {

enum vad_tensor {
    VAD_TENSOR_STFT_BASIS,
    VAD_TENSOR_ENC_0_WEIGHT,
    VAD_TENSOR_ENC_0_BIAS,
    VAD_TENSOR_ENC_1_WEIGHT,
    VAD_TENSOR_ENC_1_BIAS,
    VAD_TENSOR_ENC_2_WEIGHT,
    VAD_TENSOR_ENC_2_BIAS,
    VAD_TENSOR_ENC_3_WEIGHT,
    VAD_TENSOR_ENC_3_BIAS,
    VAD_TENSOR_LSTM_WEIGHT_IH,
    VAD_TENSOR_LSTM_WEIGHT_HH,
    VAD_TENSOR_LSTM_BIAS_IH,
    VAD_TENSOR_LSTM_BIAS_HH,
    VAD_TENSOR_FINAL_CONV_WEIGHT,
    VAD_TENSOR_FINAL_CONV_BIAS,
};

const std::map<vad_tensor, const char *> VAD_TENSOR_NAMES = {
    {VAD_TENSOR_STFT_BASIS,          "_model.stft.forward_basis_buffer"},
    {VAD_TENSOR_ENC_0_WEIGHT,        "_model.encoder.0.reparam_conv.weight"},
    {VAD_TENSOR_ENC_0_BIAS,          "_model.encoder.0.reparam_conv.bias"},
    {VAD_TENSOR_ENC_1_WEIGHT,        "_model.encoder.1.reparam_conv.weight"},
    {VAD_TENSOR_ENC_1_BIAS,          "_model.encoder.1.reparam_conv.bias"},
    {VAD_TENSOR_ENC_2_WEIGHT,        "_model.encoder.2.reparam_conv.weight"},
    {VAD_TENSOR_ENC_2_BIAS,          "_model.encoder.2.reparam_conv.bias"},
    {VAD_TENSOR_ENC_3_WEIGHT,        "_model.encoder.3.reparam_conv.weight"},
    {VAD_TENSOR_ENC_3_BIAS,          "_model.encoder.3.reparam_conv.bias"},
    {VAD_TENSOR_LSTM_WEIGHT_IH,      "_model.decoder.rnn.weight_ih"},
    {VAD_TENSOR_LSTM_WEIGHT_HH,      "_model.decoder.rnn.weight_hh"},
    {VAD_TENSOR_LSTM_BIAS_IH,        "_model.decoder.rnn.bias_ih"},
    {VAD_TENSOR_LSTM_BIAS_HH,        "_model.decoder.rnn.bias_hh"},
    {VAD_TENSOR_FINAL_CONV_WEIGHT,   "_model.decoder.decoder.2.weight"},
    {VAD_TENSOR_FINAL_CONV_BIAS,     "_model.decoder.decoder.2.bias"}
};

// ggml_backend_sched wrapper for whisper usage
struct whisper_sched {
    ggml_backend_sched_t sched = nullptr;

    std::vector<uint8_t> meta;
};

// measure the memory usage of a graph and prepare the allocr's internal data buffer
bool whisper_sched_graph_init(struct whisper_sched & allocr, std::vector<ggml_backend_t> backends, std::function<struct ggml_cgraph *()> && get_graph) {
    auto & sched = allocr.sched;
    auto & meta  = allocr.meta;

    sched = ggml_backend_sched_new(backends.data(), nullptr, backends.size(), WHISPER_MAX_NODES, false, true);

    meta.resize(ggml_tensor_overhead()*WHISPER_MAX_NODES + ggml_graph_overhead());

    // since there are dependencies between the different graphs,
    // we need to allocate them instead of only reserving to get the correct compute buffer size
    if (!ggml_backend_sched_alloc_graph(sched, get_graph())) {
        // failed to allocate the compute buffer
        VAD_LOG_ERROR("%s: failed to allocate the compute buffer\n", __func__);
        return false;
    }

    ggml_backend_sched_reset(sched);

    return true;
}

bool ggml_graph_compute_helper(
      ggml_backend_sched_t   sched,
        struct ggml_cgraph * graph,
                       int   n_threads,
                      bool   sched_reset = true) {
    for (int i = 0; i < ggml_backend_sched_get_n_backends(sched); ++i) {
        ggml_backend_t backend = ggml_backend_sched_get_backend(sched, i);
        ggml_backend_dev_t dev = ggml_backend_get_device(backend);
        ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;

        auto * fn_set_n_threads = (ggml_backend_set_n_threads_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads");
        if (fn_set_n_threads) {
            fn_set_n_threads(backend, n_threads);
        }
    }

    const bool t = (ggml_backend_sched_graph_compute(sched, graph) == GGML_STATUS_SUCCESS);

    if (!t || sched_reset) {
        ggml_backend_sched_reset(sched);
    }

    return t;
}

// ThumbFree: ggml_conv_1d with an F32 im2col, for an F32 kernel a; ggml_conv_1d itself uses an F16 im2col.
ggml_tensor * conv_1d_f32(ggml_context * ctx, ggml_tensor * a, ggml_tensor * b, int s0, int p0, int d0) {
    ggml_tensor * im2col = ggml_im2col(ctx, a, b, s0, 0, p0, 0, d0, 0, false, GGML_TYPE_F32); // [N, OL, IC * K]

    ggml_tensor * result =
        ggml_mul_mat(ctx,
                ggml_reshape_2d(ctx, im2col, im2col->ne[0], (im2col->ne[2] * im2col->ne[1])), // [N, OL, IC * K] => [N*OL, IC * K]
                ggml_reshape_2d(ctx, a, (a->ne[0] * a->ne[1]), a->ne[2]));                    // [OC, IC, K] => [OC, IC * K]

    return ggml_reshape_3d(ctx, result, im2col->ne[1], a->ne[2], im2col->ne[2]); // [N, OC, OL]
}

template<typename T>
void read_safe(std::ifstream & fin, T & dest) {
    fin.read(reinterpret_cast<char *>(&dest), sizeof(T));
}

}  // namespace

//////////////////////////////////
// Voice Activity Detection (VAD)
//////////////////////////////////

struct whisper_vad_hparams {
    int32_t n_encoder_layers;
    // ThumbFree: fixed arrays (the loader accepts exactly 4 layers) instead of new[] arrays that leaked on a failed load.
    int32_t encoder_in_channels[4];
    int32_t encoder_out_channels[4];
    int32_t kernel_sizes[4];
    int32_t lstm_input_size;
    int32_t lstm_hidden_size;
    int32_t final_conv_in;
    int32_t final_conv_out;
};

struct whisper_vad_model {
    std::string type;
    std::string version;
    whisper_vad_hparams hparams;

    struct ggml_tensor * stft_forward_basis; // [256, 1, 258]

    // Encoder tensors - 4 convolutional layers
    struct ggml_tensor * encoder_0_weight;  // [3, 129, 128]
    struct ggml_tensor * encoder_0_bias;    // [128]

    // Second encoder layer
    struct ggml_tensor * encoder_1_weight;  // [3, 128, 64]
    struct ggml_tensor * encoder_1_bias;    // [64]

    // Third encoder layer
    struct ggml_tensor * encoder_2_weight;  // [3, 64, 64]
    struct ggml_tensor * encoder_2_bias;    // [64]

    // Fourth encoder layer
    struct ggml_tensor * encoder_3_weight;  // [3, 64, 128]
    struct ggml_tensor * encoder_3_bias;    // [128]

    // LSTM decoder tensors
    struct ggml_tensor * lstm_ih_weight;    // [128, 512] input-to-hidden
    struct ggml_tensor * lstm_ih_bias;      // [512]
    struct ggml_tensor * lstm_hh_weight;    // [128, 512] hidden-to-hidden
    struct ggml_tensor * lstm_hh_bias;      // [512]

    // Final conv layer
    struct ggml_tensor * final_conv_weight; // [128]
    struct ggml_tensor * final_conv_bias;   // [1]

    // ggml contexts
    std::vector<ggml_context *> ctxs;

    // buffer for the model tensors
    std::vector<ggml_backend_buffer_t> buffers;

    // tensors
    int n_loaded;
    std::map<std::string, struct ggml_tensor *> tensors;
};

struct whisper_vad_context {
    int64_t t_vad_us = 0;

    int     n_window;
    int     n_context;
    int     n_threads;

    std::vector<ggml_backend_t> backends;
    ggml_backend_buffer_t       buffer = nullptr;
    std::vector<uint8_t>        ctx_buf;
    whisper_sched               sched;

    whisper_vad_model    model;
    std::string          path_model;
    struct ggml_tensor * h_state;
    struct ggml_tensor * c_state;
    std::vector<float>   context; // ThumbFree: the last n_context samples the model saw, reset with the recurrent state
    std::vector<float>   probs;
};

static ggml_tensor * whisper_vad_build_stft_layer(ggml_context * ctx0,
        const whisper_vad_model & model, ggml_tensor * cur) {
    // ThumbFree: the input already holds the 64 samples of context on its left; official Silero pads only the right edge,
    // by reflection, with a quarter of the 256-sample filter.
    ggml_tensor * padded = ggml_pad_reflect_1d(ctx0, cur, 0, 64);

    struct ggml_tensor * stft = conv_1d_f32(ctx0, model.stft_forward_basis, padded, model.hparams.lstm_input_size, 0, 1);

    // Calculate cutoff for real/imaginary parts
    int cutoff = model.stft_forward_basis->ne[2] / 2;

    // Extract real part (first half of the STFT output).
    struct ggml_tensor * real_part = ggml_view_2d(ctx0, stft, 4, cutoff, stft->nb[1], 0);
    // Extract imaginary part (second half of the STFT output).
    struct ggml_tensor * img_part = ggml_view_2d(ctx0, stft, 4, cutoff, stft->nb[1], cutoff * stft->nb[1]);

    // Calculate magnitude: sqrt(real^2 + imag^2)
    struct ggml_tensor * real_squared = ggml_mul(ctx0, real_part, real_part);
    struct ggml_tensor * img_squared  = ggml_mul(ctx0, img_part, img_part);
    struct ggml_tensor * sum_squares  = ggml_add(ctx0, real_squared, img_squared);
    struct ggml_tensor * magnitude    = ggml_sqrt(ctx0, sum_squares);
    return magnitude;
}

static ggml_tensor * whisper_vad_build_encoder_layer(ggml_context * ctx0,
        const whisper_vad_model & model, ggml_tensor * cur) {
    // First Conv1D: expands to 128 channels.
    cur = conv_1d_f32(ctx0, model.encoder_0_weight, cur, 1, 1, 1);
    cur = ggml_add(ctx0, cur, ggml_reshape_3d(ctx0, model.encoder_0_bias, 1, 128, 1));
    cur = ggml_relu(ctx0, cur);

    // Second Conv1D: reduces to 64 channels.
    cur = conv_1d_f32(ctx0, model.encoder_1_weight, cur, 2, 1, 1);
    cur = ggml_add(ctx0, cur, ggml_reshape_3d(ctx0, model.encoder_1_bias, 1, 64, 1));
    cur = ggml_relu(ctx0, cur);

    // Third Conv1D: maintains 64 channels
    cur = conv_1d_f32(ctx0, model.encoder_2_weight, cur, 2, 1, 1);
    cur = ggml_add(ctx0, cur, ggml_reshape_3d(ctx0, model.encoder_2_bias, 1, 64, 1));
    cur = ggml_relu(ctx0, cur);

    // Fourth Conv1D: expands to 128 channels
    cur = conv_1d_f32(ctx0, model.encoder_3_weight, cur, 1, 1, 1);
    cur = ggml_add(ctx0, cur, ggml_reshape_3d(ctx0, model.encoder_3_bias, 1, 128, 1));
    cur = ggml_relu(ctx0, cur);

    return cur;
}

static ggml_tensor * whisper_vad_build_lstm_layer(ggml_context * ctx0,
        const whisper_vad_context & vctx, ggml_tensor * cur, ggml_cgraph * gf) {
    const whisper_vad_model & model = vctx.model;
    const int hdim = model.hparams.lstm_hidden_size;

    // ThumbFree: made contiguous. ggml counts this [128, 1] transposed view as contiguous (a dimension of 1) and hands
    // llamafile_sgemm a row stride of 1, which its "ldb >= k" assertion stops in a build with asserts, like the app's.
    struct ggml_tensor * x_t = ggml_cont(ctx0, ggml_transpose(ctx0, cur));

    // Create operations using the input-to-hidden weights.
    struct ggml_tensor * inp_gate = ggml_mul_mat(ctx0, model.lstm_ih_weight, x_t);
    inp_gate = ggml_add(ctx0, inp_gate, model.lstm_ih_bias);

    // Create operations using the hidden-to-hidden weights.
    struct ggml_tensor * hid_gate = ggml_mul_mat(ctx0, model.lstm_hh_weight, vctx.h_state);
    hid_gate = ggml_add(ctx0, hid_gate, model.lstm_hh_bias);

    // Create add operation to get preactivations for all gates.
    struct ggml_tensor * out_gate = ggml_add(ctx0, inp_gate, hid_gate);

    const size_t hdim_size = ggml_row_size(out_gate->type, hdim);

    // Create sigmoid for input gate (using the first 128 bytes from the preactivations).
    struct ggml_tensor * i_t = ggml_sigmoid(ctx0, ggml_view_1d(ctx0, out_gate, hdim, 0 * hdim_size));

    // Create sigmoid for the forget gate (using the second 128 bytes from the preactivations).
    struct ggml_tensor * f_t = ggml_sigmoid(ctx0, ggml_view_1d(ctx0, out_gate, hdim, 1 * hdim_size));

    // Create sigmoid for the cell gate (using the third 128 bytes from the preactivations).
    struct ggml_tensor * g_t = ggml_tanh(ctx0, ggml_view_1d(ctx0, out_gate, hdim, 2 * hdim_size));

    // Create sigmoid for the output gate (using the fourth 128 bytes from the preactivations).
    struct ggml_tensor * o_t = ggml_sigmoid(ctx0, ggml_view_1d(ctx0, out_gate, hdim, 3 * hdim_size));

    // Update cell state
    struct ggml_tensor * c_out = ggml_add(ctx0,
        ggml_mul(ctx0, f_t, vctx.c_state),
        ggml_mul(ctx0, i_t, g_t));
    ggml_build_forward_expand(gf, ggml_cpy(ctx0, c_out, vctx.c_state));

    // Update hidden state
    struct ggml_tensor * out = ggml_mul(ctx0, o_t, ggml_tanh(ctx0, c_out));
    ggml_build_forward_expand(gf, ggml_cpy(ctx0, out,   vctx.h_state));

    return out;
}

static struct ggml_cgraph * whisper_vad_build_graph(whisper_vad_context & vctx) {
    const auto & model = vctx.model;

    struct ggml_init_params params = {
        /*.mem_size   =*/ vctx.sched.meta.size(),
        /*.mem_buffer =*/ vctx.sched.meta.data(),
        /*.no_alloc   =*/ true,
    };

    struct ggml_context * ctx0 = ggml_init(params);

    ggml_cgraph * gf = ggml_new_graph(ctx0);

    // ThumbFree: the window with its context in front, as official Silero feeds it.
    struct ggml_tensor * frame = ggml_new_tensor_2d(ctx0, GGML_TYPE_F32, vctx.n_context + vctx.n_window, 1);
    ggml_set_name(frame, "frame");
    ggml_set_input(frame);

    struct ggml_tensor * cur = nullptr;
    {
        cur = whisper_vad_build_stft_layer(ctx0, model, frame);

        cur = whisper_vad_build_encoder_layer(ctx0, model, cur);

        // Extract the first element of the first dimension
        // (equivalent to pytorch's [:, :, 0])
        cur = ggml_view_2d(ctx0, cur, 1, 128, cur->nb[1], 0);

        cur = whisper_vad_build_lstm_layer(ctx0, vctx, cur, gf);
        cur = ggml_relu(ctx0, cur);
        cur = conv_1d_f32(ctx0, model.final_conv_weight, cur, 1, 0, 1);
        cur = ggml_add(ctx0, cur, model.final_conv_bias);
        cur = ggml_sigmoid(ctx0, cur);
        ggml_set_name(cur, "prob");
        ggml_set_output(cur);
    }

    ggml_build_forward_expand(gf, cur);

    ggml_free(ctx0);

    return gf;
}

static bool whisper_vad_init_context(whisper_vad_context * vctx) {
    // ThumbFree: the CPU device only.
    ggml_backend_t backend_cpu = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (backend_cpu == nullptr) {
        VAD_LOG_ERROR("%s: failed to initialize the CPU backend\n", __func__);
        return false;
    }
    vctx->backends.push_back(backend_cpu);

    const int32_t lstm_hidden_size = vctx->model.hparams.lstm_hidden_size;

    vctx->ctx_buf.resize(2u*ggml_tensor_overhead());

    struct ggml_init_params params = {
        /*.mem_size   =*/ vctx->ctx_buf.size(),
        /*.mem_buffer =*/ vctx->ctx_buf.data(),
        /*.no_alloc   =*/ true,
    };

    ggml_context * ctx = ggml_init(params);
    if (!ctx) {
        VAD_LOG_ERROR("%s: failed to init LSTM state ggml context\n", __func__);
        return false;
    }

    // LSTM Hidden state
    vctx->h_state = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, lstm_hidden_size);
    ggml_set_name(vctx->h_state, "h_state");

    // LSTM Cell state
    vctx->c_state = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, lstm_hidden_size);
    ggml_set_name(vctx->c_state, "c_state");

    vctx->buffer = ggml_backend_alloc_ctx_tensors(ctx, vctx->backends[0]);
    ggml_free(ctx);
    if (!vctx->buffer) {
        VAD_LOG_ERROR("%s: failed to allocate memory for the VAD state\n", __func__);
        return false;
    }
    ggml_backend_buffer_clear(vctx->buffer, 0); // ThumbFree: a stream's first detect starts from a zero state too

    {
        bool ok = whisper_sched_graph_init(vctx->sched, vctx->backends,
                [&]() {
                    return whisper_vad_build_graph(*vctx);
                });

        if (!ok) {
            VAD_LOG_ERROR("%s: failed to init VAD allocator\n", __func__);
            return false;
        }
    }

    return true;
}

static void whisper_vad_free(whisper_vad_context * ctx) {
    if (ctx) {
        if (ctx->buffer) {
            ggml_backend_buffer_free(ctx->buffer);
        }
        for (ggml_context * context : ctx->model.ctxs) {
            ggml_free(context);
        }

        for (ggml_backend_buffer_t buf : ctx->model.buffers) {
            ggml_backend_buffer_free(buf);
        }

        if (ctx->sched.sched) {
            ggml_backend_sched_free(ctx->sched.sched);
        }

        for (auto & backend : ctx->backends) {
            ggml_backend_free(backend);
        }

        delete ctx;
    }
}

// ThumbFree: reads the file directly (upstream took a whisper_model_loader); returns nullptr and frees on every failure.
static struct whisper_vad_context * whisper_vad_init_from_file(const char * path_model) {
    auto fin = std::ifstream(path_model, std::ios::binary);
    if (!fin) {
        VAD_LOG_ERROR("%s: failed to open VAD model '%s'\n", __func__, path_model);
        return nullptr;
    }

    // Read the VAD model
    {
        uint32_t magic = 0;
        read_safe(fin, magic);
        if (magic != 0x67676d6c) { // GGML_FILE_MAGIC, "ggml"
            VAD_LOG_ERROR("%s: invalid model data (bad magic)\n", __func__);
            return nullptr;
        }
    }

    whisper_vad_context * vctx = new whisper_vad_context;
    vctx->n_threads = 1; // ThumbFree: one thread; the per-frame graph is too small to share
    vctx->path_model = path_model;
    auto fail = [&]() -> whisper_vad_context * {
        whisper_vad_free(vctx);
        return nullptr;
    };

    auto & model = vctx->model;
    auto & hparams = model.hparams;

    // load model context params.
    {
        int32_t str_len = 0;
        read_safe(fin, str_len);
        if (str_len < 0 || str_len > 256) {
            VAD_LOG_ERROR("%s: invalid model type length %d\n", __func__, str_len);
            return fail();
        }
        std::vector<char> buffer(str_len + 1, 0);
        fin.read(buffer.data(), str_len);
        std::string model_type(buffer.data(), str_len);
        model.type = model_type;

        int32_t major = 0, minor = 0, patch = 0;
        read_safe(fin, major);
        read_safe(fin, minor);
        read_safe(fin, patch);
        std::string version_str = std::to_string(major) + "." +
                                  std::to_string(minor) + "." +
                                  std::to_string(patch);
        model.version = version_str;

        read_safe(fin, vctx->n_window);
        read_safe(fin, vctx->n_context);
        if (vctx->n_window != 512 || vctx->n_context != 64) { // ThumbFree: the only shape official 16 kHz Silero has
            VAD_LOG_ERROR("%s: unexpected window %d or context %d\n", __func__, vctx->n_window, vctx->n_context);
            return fail();
        }
    }

    // load model hyper params (hparams).
    {
        read_safe(fin, hparams.n_encoder_layers);

        if (hparams.n_encoder_layers != 4) {
            VAD_LOG_ERROR("%s: invalid n_encoder_layers %d in VAD model file (expected 4)\n", __func__, hparams.n_encoder_layers);
            return fail();
        }

        for (int32_t i = 0; i < hparams.n_encoder_layers; i++) {
            read_safe(fin, hparams.encoder_in_channels[i]);
            read_safe(fin, hparams.encoder_out_channels[i]);
            read_safe(fin, hparams.kernel_sizes[i]);
        }

        read_safe(fin, hparams.lstm_input_size);
        read_safe(fin, hparams.lstm_hidden_size);
        read_safe(fin, hparams.final_conv_in);
        read_safe(fin, hparams.final_conv_out);
        if (!fin) {
            VAD_LOG_ERROR("%s: truncated VAD model header\n", __func__);
            return fail();
        }
    }

    // 1 STFT tensor, 4*2 encoder tensors, 4 LSTM tensors, 2 final output tensors
    const size_t n_tensors = hparams.n_encoder_layers * 2 + 4 + 2 + 1;

    // ThumbFree: one context and one buffer, of the CPU device's own buffer type.
    ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpu_dev == nullptr) {
        VAD_LOG_ERROR("%s: no CPU device is registered\n", __func__);
        return fail();
    }
    ggml_init_params ctx_params = {
        /*.mem_size   =*/ n_tensors * ggml_tensor_overhead(),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };
    ggml_context * ctx = ggml_init(ctx_params);
    if (!ctx) {
        VAD_LOG_ERROR("%s: failed to create ggml context\n", __func__);
        return fail();
    }
    model.ctxs.emplace_back(ctx);

    auto create_tensor = [&](vad_tensor type, ggml_tensor * tensor) -> ggml_tensor * {
        model.tensors[VAD_TENSOR_NAMES.at(type)] = tensor;
        return tensor;
    };

    // create tensors (ThumbFree: the file's F16 tensors as F32, widened as they load)
    {
        // SFTF precomputed basis matrix
        model.stft_forward_basis = create_tensor(VAD_TENSOR_STFT_BASIS,
            ggml_new_tensor_3d(ctx, GGML_TYPE_F32, 256, 1, 258));

        model.encoder_0_weight = create_tensor(VAD_TENSOR_ENC_0_WEIGHT,
            ggml_new_tensor_3d(
                ctx,
                GGML_TYPE_F32,
                hparams.kernel_sizes[0],
                hparams.encoder_in_channels[0],
                hparams.encoder_out_channels[0]
        ));
        model.encoder_0_bias = create_tensor(VAD_TENSOR_ENC_0_BIAS,
            ggml_new_tensor_1d(ctx, GGML_TYPE_F32, hparams.encoder_out_channels[0]));

        model.encoder_1_weight = create_tensor(VAD_TENSOR_ENC_1_WEIGHT,
            ggml_new_tensor_3d(
                ctx,
                GGML_TYPE_F32,
                hparams.kernel_sizes[1],
                hparams.encoder_in_channels[1],
                hparams.encoder_out_channels[1]
        ));
        model.encoder_1_bias = create_tensor(VAD_TENSOR_ENC_1_BIAS,
            ggml_new_tensor_1d(ctx, GGML_TYPE_F32, hparams.encoder_out_channels[1]));

        model.encoder_2_weight = create_tensor(VAD_TENSOR_ENC_2_WEIGHT,
            ggml_new_tensor_3d(
                ctx,
                GGML_TYPE_F32,
                hparams.kernel_sizes[2],
                hparams.encoder_in_channels[2],
                hparams.encoder_out_channels[2]
        ));
        model.encoder_2_bias = create_tensor(VAD_TENSOR_ENC_2_BIAS,
            ggml_new_tensor_1d(ctx, GGML_TYPE_F32, hparams.encoder_out_channels[2]));

        model.encoder_3_weight = create_tensor(VAD_TENSOR_ENC_3_WEIGHT,
            ggml_new_tensor_3d(
                ctx,
                GGML_TYPE_F32,
                hparams.kernel_sizes[3],
                hparams.encoder_in_channels[3],
                hparams.encoder_out_channels[3]
        ));
        model.encoder_3_bias = create_tensor(VAD_TENSOR_ENC_3_BIAS,
                ggml_new_tensor_1d(ctx, GGML_TYPE_F32, hparams.encoder_out_channels[3]));

        // Hidden State dimension (input gate, forget gate, cell gate, output gate)
        const int hstate_dim = hparams.lstm_hidden_size * 4;

        // LSTM weights - input to hidden
        model.lstm_ih_weight = create_tensor(
            VAD_TENSOR_LSTM_WEIGHT_IH,
            ggml_new_tensor_2d(ctx, GGML_TYPE_F32, hparams.lstm_hidden_size, hstate_dim)
        );
        model.lstm_ih_bias = create_tensor(
            VAD_TENSOR_LSTM_BIAS_IH,
            ggml_new_tensor_1d(ctx, GGML_TYPE_F32, hstate_dim)
        );

        // LSTM weights - hidden to hidden
        model.lstm_hh_weight = create_tensor(
            VAD_TENSOR_LSTM_WEIGHT_HH,
            ggml_new_tensor_2d(ctx, GGML_TYPE_F32, hparams.lstm_hidden_size, hstate_dim)
        );
        model.lstm_hh_bias = create_tensor(
            VAD_TENSOR_LSTM_BIAS_HH,
            ggml_new_tensor_1d(ctx, GGML_TYPE_F32, hstate_dim)
        );

        // Final conv layer weight
        model.final_conv_weight = create_tensor(
            VAD_TENSOR_FINAL_CONV_WEIGHT,
            ggml_new_tensor_2d(ctx, GGML_TYPE_F32, hparams.final_conv_in, 1)
        );
        model.final_conv_bias = create_tensor(
            VAD_TENSOR_FINAL_CONV_BIAS,
            ggml_new_tensor_1d(ctx, GGML_TYPE_F32, 1)
        );
    }

    // allocate tensors in the backend buffers
    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, ggml_backend_dev_buffer_type(cpu_dev));
    if (!buf) {
        VAD_LOG_ERROR("%s: failed to allocate the model buffer\n", __func__);
        return fail();
    }
    model.buffers.emplace_back(buf);

    // load weights
    {
        model.n_loaded = 0;
        std::vector<char> read_buf;

        while (true) {
            int32_t n_dims;
            int32_t length;
            int32_t ttype;

            read_safe(fin, n_dims);
            read_safe(fin, length);
            read_safe(fin, ttype);

            if (fin.eof()) {
                break;
            }

            if (n_dims < 0 || n_dims > 4) {
                VAD_LOG_ERROR("%s: invalid n_dims %d in model file (expected 0 <= n_dims <= 4)\n", __func__, n_dims);
                return fail();
            }
            if (length <= 0 || length > 256 || ttype < 0 || ttype >= GGML_TYPE_COUNT) { // ThumbFree: bounds
                VAD_LOG_ERROR("%s: invalid tensor record in model file\n", __func__);
                return fail();
            }

            int32_t nelements = 1;
            int32_t ne[4] = { 1, 1, 1, 1 };
            for (int i = 0; i < n_dims; ++i) {
                read_safe(fin, ne[i]);
                nelements *= ne[i];
            }

            std::string name;
            std::vector<char> tmp(length);
            fin.read(&tmp[0], tmp.size());
            name.assign(&tmp[0], tmp.size());

            if (model.tensors.find(name) == model.tensors.end()) {
                VAD_LOG_ERROR("%s: unknown tensor '%s' in model file\n", __func__, name.data());
                return fail();
            }

            auto tensor = model.tensors[name.data()];

            if (ggml_nelements(tensor) != nelements) {
                VAD_LOG_ERROR("%s: tensor '%s' has wrong size in model file\n", __func__, name.data());
                return fail();
            }

            if (tensor->ne[0] != ne[0] || tensor->ne[1] != ne[1] || tensor->ne[2] != ne[2]) {
                VAD_LOG_ERROR("%s: tensor '%s' has wrong shape in model file\n", __func__, name.data());
                return fail();
            }

            // ThumbFree: F32 tensors take F32 or F16 data; F16 data is widened.
            if (tensor->type != GGML_TYPE_F32 || (ttype != GGML_TYPE_F32 && ttype != GGML_TYPE_F16)) {
                VAD_LOG_ERROR("%s: tensor '%s' has type %d in model file\n", __func__, name.data(), ttype);
                return fail();
            }

            if (ttype == GGML_TYPE_F32) {
                read_buf.resize(ggml_nbytes(tensor));
                fin.read(read_buf.data(), read_buf.size());
            } else {
                std::vector<ggml_fp16_t> half(nelements);
                fin.read(reinterpret_cast<char *>(half.data()), half.size() * sizeof(ggml_fp16_t));
                read_buf.resize(ggml_nbytes(tensor));
                ggml_fp16_to_fp32_row(half.data(), reinterpret_cast<float *>(read_buf.data()), nelements);
            }
            if (!fin) {
                VAD_LOG_ERROR("%s: truncated tensor '%s' in model file\n", __func__, name.data());
                return fail();
            }
            ggml_backend_tensor_set(tensor, read_buf.data(), 0, ggml_nbytes(tensor));

            model.n_loaded++;
        }

        if (model.n_loaded != (int) model.tensors.size()) { // ThumbFree: an empty model is an error here
            VAD_LOG_ERROR("%s: ERROR not all tensors loaded from model file - expected %zu, got %d\n", __func__, model.tensors.size(), model.n_loaded);
            return fail();
        }
    }

    if (!whisper_vad_init_context(vctx)) {
        return fail();
    }
    vctx->context.assign(vctx->n_context, 0.0f);

    return vctx;
}

static void whisper_vad_reset_state(whisper_vad_context * vctx) {
    ggml_backend_buffer_clear(vctx->buffer, 0);
    std::fill(vctx->context.begin(), vctx->context.end(), 0.0f); // ThumbFree
}

static bool whisper_vad_detect_speech_no_reset(
        struct whisper_vad_context * vctx,
        const float * samples,
        int n_samples) {
    int n_chunks = n_samples / vctx->n_window;
    if (n_samples % vctx->n_window != 0) {
        n_chunks += 1;  // Add one more chunk for remaining samples.
    }

    vctx->probs.resize(n_chunks);

    // ThumbFree: the model input is the context followed by the window.
    const int n_context = vctx->n_context;
    std::vector<float> window(n_context + vctx->n_window, 0.0f);

    auto & sched = vctx->sched.sched;

    ggml_cgraph * gf = whisper_vad_build_graph(*vctx);

    if (!ggml_backend_sched_alloc_graph(sched, gf)) {
        VAD_LOG_ERROR("%s: failed to allocate the compute buffer\n", __func__);
        return false;
    }

    struct ggml_tensor * frame = ggml_graph_get_tensor(gf, "frame");
    struct ggml_tensor * prob  = ggml_graph_get_tensor(gf, "prob");

    // we are going to reuse the graph multiple times for each chunk
    const int64_t t_start_vad_us = ggml_time_us();

    bool ok = true;
    for (int i = 0; i < n_chunks; i++) {
        const int idx_start = i * vctx->n_window;
        const int idx_end = std::min(idx_start + vctx->n_window, n_samples);

        // ThumbFree: the last n_context samples the model saw, then this window, zero-padded when it is the partial last one.
        std::copy(vctx->context.begin(), vctx->context.end(), window.begin());
        std::fill(window.begin() + n_context, window.end(), 0.0f);
        std::copy(samples + idx_start, samples + idx_end, window.begin() + n_context);
        std::copy(window.end() - n_context, window.end(), vctx->context.begin());

        // Set the frame tensor data with the samples.
        ggml_backend_tensor_set(frame, window.data(), 0, ggml_nelements(frame) * sizeof(float));

        // do not reset the scheduler - we will reuse the graph in the next chunk
        if (!ggml_graph_compute_helper(sched, gf, vctx->n_threads, false)) {
            VAD_LOG_ERROR("%s: failed to compute VAD graph\n", __func__);
            ok = false; // ThumbFree: upstream stopped here and still reported success
            break;
        }

        // Get the probability for this chunk.
        ggml_backend_tensor_get(prob, &vctx->probs[i], 0, sizeof(float));
    }

    vctx->t_vad_us += ggml_time_us() - t_start_vad_us;

    ggml_backend_sched_reset(sched);

    return ok;
}

static bool whisper_vad_detect_speech(
        struct whisper_vad_context * vctx,
        const float * samples,
        int n_samples) {
    whisper_vad_reset_state(vctx);
    return whisper_vad_detect_speech_no_reset(vctx, samples, n_samples);
}

// The C API of silero_vad.h.

struct whisper_vad_context * silero_vad_init(const char * path_model) {
    return whisper_vad_init_from_file(path_model);
}

bool silero_vad_detect(struct whisper_vad_context * vctx, const float * samples, int n_samples) {
    return vctx != nullptr && n_samples >= 0 && whisper_vad_detect_speech(vctx, samples, n_samples);
}

bool silero_vad_detect_stream(struct whisper_vad_context * vctx, const float * samples, int n_samples) {
    return vctx != nullptr && n_samples >= 0 && whisper_vad_detect_speech_no_reset(vctx, samples, n_samples);
}

void silero_vad_reset(struct whisper_vad_context * vctx) {
    if (vctx != nullptr) whisper_vad_reset_state(vctx);
}

int silero_vad_n_probs(struct whisper_vad_context * vctx) {
    return vctx->probs.size();
}

const float * silero_vad_probs(struct whisper_vad_context * vctx) {
    return vctx->probs.data();
}

void silero_vad_free(struct whisper_vad_context * vctx) {
    whisper_vad_free(vctx);
}
