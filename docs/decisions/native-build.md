# Native build: runtime CPU dispatch (DL build)

Date: 2026-09-24.

## Decision

The app ships the dynamic-backend build of transcribe.cpp, not the static fallback. `android/app/src/main/cpp/CMakeLists.txt` adds `third_party/transcribe.cpp` with `add_subdirectory(... EXCLUDE_FROM_ALL)` and sets `TRANSCRIBE_BUILD_SHARED=ON`, `TRANSCRIBE_GGML_BACKEND_DL=ON` and `GGML_CPU_ALL_VARIANTS=ON`. At startup `NativeEngine.nativeInit(nativeLibraryDir)` calls `transcribe_init_backends`, and ggml loads the best CPU module this phone supports.

It works from an installed APK on emulator-5554 (`NativeEngineTest.initFindsBackends` and `loadsExpectedVariant`):

```
transcribe: ggml_backend_load_best: .../lib/arm64/libggml-cpu-android_armv8.0_1.so score: 1
transcribe: ggml_backend_load_best: .../lib/arm64/libggml-cpu-android_armv8.6_1.so score: 0
transcribe: ggml_backend_load_best: .../lib/arm64/libggml-cpu-android_armv9.0_1.so score: 0
transcribe: ggml_backend_load_best: .../lib/arm64/libggml-cpu-android_armv8.2_2.so score: 7
transcribe: load_backend: loaded CPU backend from .../lib/arm64/libggml-cpu-android_armv8.2_2.so
ThumbFree: init_backends status=0 cpu_variant=android_armv8.2_2
```

The static fallback (`TRANSCRIBE_BUILD_SHARED=OFF`, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`) was not needed.

## CPU variants shipped

ggml builds 7 Android variants. Each scores 0 on a CPU that lacks one of its features (`ggml/src/CMakeLists.txt`, `ggml-cpu/arch/arm/cpu-feats.cpp`). The APK keeps the ones a supported phone can pick and drops the rest with `packaging.jniLibs.excludes`:

| Variant | Features | Shipped | Who picks it |
|---|---|---|---|
| android_armv8.0_1 | none | yes | Cortex-A53/A73 class phones (baseline) |
| android_armv8.2_1 | dotprod | no | only a CPU with dotprod but no fp16; no Android 13 phone we know of |
| android_armv8.2_2 | dotprod, fp16 | yes | the emulator |
| android_armv8.6_1 | + i8mm | yes | the Pixel 10, and i8mm phones without SVE2 |
| android_armv9.0_1 | + i8mm, SVE2 | only with `-Pthumbfree.armv9` | runs on the Pixel 10, but measured about 20% slower there than armv8.6_1 |
| android_armv9.2_1 | + SVE, SME | no | needs SME; the Pixel 10 has none, and the emulator traps it |
| android_armv9.2_2 | + SVE, SVE2, SME | no | same |

A phone with dotprod but no fp16 falls back to `armv8.0_1`, which is correct and slower. On the Pixel 10 (2026-09-25), `armv9.0_1` loaded and gave identical transcripts, but `armv8.6_1` was about 20% faster, so the default build excludes `armv9.0_1` and `-Pthumbfree.armv9` (`THUMBFREE_ARMV9=1 android/tools/install-pixel.sh`) puts it back for comparison.

For module A/B runs, `-Pthumbfree.onlyCpu=<variant>` (`THUMBFREE_ONLY_CPU=<variant> android/tools/install-pixel.sh`) ships that one module plus the `armv8.0_1` fallback, so the phone has no choice to make; an unknown name fails the build. The modules differ in more than instructions: ggml compiles its llamafile tinyBLAS matmuls out of every module with i8mm or SVE (`ggml-cpu.c:46-48`), so `armv8.2_2` keeps them and `armv8.6_1` does not. NativeEngineTest's packaging test expects the default set, so it fails on such a build.

## Build details that matter

- `packaging.jniLibs.useLegacyPackaging = true`. The backend scan lists `nativeLibraryDir`, which is empty unless the libraries are extracted at install.
- `-DANDROID_STL=c++_shared` (Gradle argument, so AGP packages `libc++_shared.so`). The app has several C++ libraries; one shared runtime is the NDK's guidance.
- AGP builds the variant modules into `<cxx build dir>/bin` (transcribe.cpp forces `CMAKE_RUNTIME_OUTPUT_DIRECTORY`) and still copies them into the APK. No output-directory override was needed.
- transcribe.cpp sets its public include directory from `CMAKE_SOURCE_DIR`, which points at our CMake directory when it is a subproject. `CMakeLists.txt` adds `third_party/transcribe.cpp/include` to the `transcribe` target.
- AGP builds the debug variant as `CMAKE_BUILD_TYPE=Debug` with no `-O` flag. `CMakeLists.txt` sets `CMAKE_C_FLAGS_DEBUG` and `CMAKE_CXX_FLAGS_DEBUG` to `-O3`, so debug APKs and instrumented tests run optimized ggml (with asserts and `-g`). Adding `-DNDEBUG` was measured on the emulator (30 warm runs each, before the engine patches): encoder -3% on JFK and +1% on a 29.4 s clip, within noise, so the asserts stay.
- AGP builds the release variant as `CMAKE_BUILD_TYPE=RelWithDebInfo`, whose default `-O2 -g -DNDEBUG` crashed NDK 30's clang (21.0.0) on ggml's SVE2 and SME repack kernels (`ggml_gemm_q8_0_4x8_q8_0`, in the AArch64 assembly printer), seen on 30 September 2026; `-O3` compiles them. `CMakeLists.txt` gives release the debug flags, `-O3` with asserts and `-g`, so the released engine is the one the tests and benchmarks ran. AGP strips the symbols from the APK. `CmakeFlagsTest` checks both build types.
- `GGML_NATIVE=OFF` and `GGML_OPENMP=OFF` are pinned by `CmakeFlagsTest`. `GGML_CPU_KLEIDIAI` stays at its default (off): code choosing SME kernels from HWCAP2 alone, such as KleidiAI, would crash on the emulator, which advertises SME but traps it (seen in an early NDK test).
- Every LOAD segment is 16 KB aligned with NDK 30's defaults (`android/tools/check-16kb.sh`).

## Engine patches

Date: 2026-09-25. `third_party/transcribe.cpp` stays pinned to an upstream commit (7d37cea, v0.2.4), so clones keep working and nothing is forked. Our changes to it live in this repository as `third_party/patches/*.patch` (written with `git format-patch`, paths relative to the submodule).

- **How they apply.** Every CMake configure of the app includes `third_party/patches/transcribe-patches.cmake` (beside the series, so every app's build can), which applies the series to the submodule's files, in name order, before `add_subdirectory`. It finds the longest prefix of the series that is already applied (`git apply --reverse --check`), then checks and applies the rest. The patches go to `git apply` as one concatenated input: given several files, it checks each against the untouched tree, so a patch that edits a file an earlier patch changed would fail. So a fresh clone, a second build and a checkout that gained a patch all work.
- **What stops the configure.** An empty patch directory, a series that does not apply, a submodule whose checked-out commit is not the one this repository pins (its gitlink, `git ls-files --stage third_party/transcribe.cpp`), and any checkout that is not exactly the pinned commit plus the whole series: a patch removed from the series while still applied, a patch edited after it was applied, or a local edit anywhere in the submodule's tracked files. The last check builds the expected tree in a scratch index (`GIT_INDEX_FILE`, `read-tree <pinned commit>`, `apply --cached`) and compares the files with `git diff --quiet`. A standalone run outside this repository passes the commit as `-DTRANSCRIBE_PINNED=<commit>`. The error gives the fix on a line of its own, with absolute paths so it works from any folder (Gradle runs in `android/`): `git -C <repository>/third_party/transcribe.cpp checkout -- .`, which discards local edits in the submodule, then build again. The check stops the next configure; an edit to a file the patches don't touch is caught when CMake next reruns. So local experiments in the submodule no longer build silently; move them into a patch. An uninitialized submodule stops the configure too, with `git submodule update --init`.
- **Other details.** A file lock in the submodule's git directory keeps two configures from patching at once. The patch glob, the patch files and the files they touch are configure dependencies, so a new or changed patch, or a reset submodule, reruns CMake (without the last, a reset submodule would build unpatched without a word).
- **What that looks like.** After a build, `git status` shows `third_party/transcribe.cpp` with modified content. That is expected: never commit it, and never commit inside the submodule.
- **Check.** `TranscribePatchesTest` (host suite, about 11 s) runs `transcribe-patches.cmake` on a clone of the pinned commit (the gitlink's) inside a scratch superproject that pins it: it must apply the whole series (which then reverse-applies as a whole), change nothing on a second run, finish a tree that has only the first patch, and stop on a tree that holds a patch the series no longer has, on an empty patch directory, and on another engine commit that the series applies to. A submodule bump that breaks a patch fails there instead of in the native build. NativeEngineTest's peak-memory bound (under 1.2 GB; the patched engine peaks near 1.0 GB, the unpatched one near 1.3 GB) catches an APK whose engine lost its patches.
- **Changing a patch.** Clone the submodule at the pinned commit, apply the series, edit, commit one commit per patch, and regenerate the files with `git format-patch -N --zero-commit --no-signature --filename-max-length=80`, then run `tools/normalize-patches.py` on the new files so `git diff --check` passes (blank context lines lose their space, and a blank context line that ends a file goes, with its hunk's counts; `git apply` reads them the same). Then reset the submodule (the command above), because a checkout that holds the old series stops the configure.
- **Measuring.** `android/tools/engine-bench.py` (under the emulator lock) runs `EngineBenchTest` in alternating processes: encoder and decoder ms (median and p90 over 30 warm runs on JFK and a 29.4 s clip), PSS and peak RSS, and WER on the 11 public clips and on private clips that are not in the repository (23 over 10 s and 5 edge clips in `testdata/private/`, which the tool needs), with the words that changed against the first config. Each patch has an environment switch that restores the old path, so one APK can compare both.

| Patch | Change | Switch back |
|---|---|---|
| 0001 | The 48 conformer pointwise conv weights become Q8_0 2-D matrices at load instead of F32 copies, and their F16 sources load into a scratch buffer that is freed afterwards | `TRANSCRIBE_CONV_PW_F32=1` |
| 0002 | The encoder's Q8_0 matmul weights (217 tensors) and the Q8_0 pointwise copies go to the CPU backend's repack buffer type, when ggml says it runs their matmuls there (dotprod or i8mm), so they use ggml's tiled GEMM kernels, which also share each matmul between threads in chunks | `TRANSCRIBE_NO_REPACK=1` |
| 0003 | The RNN-T decoder uses the model's Q8_0 predictor, joint and embedding weights in place instead of fp32 mirrors (about 36 MB) | `TRANSCRIBE_DECODER_F32=1` |
| 0004 | Without a BLAS (Android), the mel filterbank product skips each filter's all-zero bin groups and runs right after each frame's FFT on the STFT threads, which take frames in chunks from a shared counter | `TRANSCRIBE_MEL_DENSE=1` (both parts: the fixed stride and the dense product after the FFT) |
| 0005 | The conformer's flash mask comes from one batched relative-position matmul while the full score tensor is under 64 MiB, instead of one matmul, copy, scale, cast and concat per head | `TRANSCRIBE_SPLIT_FLASH_MASK=1` |
| 0006 | The RNN-T decoder's step graphs get a thread count of their own on a persistent pool (one thread: a pool with no workers, so no pool per graph), the encoder projection runs on the session's count at every step count, and the joint's pred projection moves into the predictor graph, so blank steps reuse it | `TRANSCRIBE_DECODER_THREADS=N` (default: the session's count, until the phone sweep picks one) |
| 0007 | An abort stops the Parakeet encoder at the next graph node (ggml's CPU abort callback); the run returns 13 with no text | none: it only acts on an abort |
| 0008 | One-shot runs keep the full-attention positional table of the longest input so far and use its middle rows | none: bit-identical |
| 0009 | ggml CPU: the pre-encode's first conv (3x3, stride 2, one input channel, F32) runs as a direct NEON kernel instead of im2col and a K = 9 GEMM, with vec_dot's arithmetic per output; not in the SVE modules (armv9.x), whose vec_dot reduces SVE lanes by vector length | `TRANSCRIBE_CONV2D_GENERIC=1` |
| 0010 | ggml CPU registry: `ggml_threadpool_pause`, `_resume` and a new `ggml_threadpool_get_tids` (the workers' thread ids) | none: new functions |
| 0011 | `transcribe_session_set_threads(session, n, threadpool)`: a session's thread count and a persistent ggml CPU pool made by the caller, which Parakeet runs its encoder, its decoder's encoder projection, its step graphs from 2 threads up to the session's count, and its mel's thread count on | none: no pool unless the caller sets one (EngineKnobs `THUMBFREE_ASR_POOL=0` in :engine) |
| 0012 | A Parakeet session with a caller's pool can keep its scheduler between offline runs instead of rebuilding it each run; off by default, since it holds the high-water mark (127 MB after a 29.4 s clip on a Mac, 121 MB more RSS on the emulator) for a rebuild of at most about 10 ms, until the phone A/B says otherwise | `TRANSCRIBE_KEEP_SCRATCH=1` turns it on |
| 0013 | Live preview prototype: a buffered stream (parakeet-unified) decodes each chunk's right-context frames on a copy of the decoder state and publishes them as tentative text, which the next chunk replaces; the committed text is the same as without it (33 streams checked read by read). The live preview turns it on in :engine | `TRANSCRIBE_STREAM_TENTATIVE=1` turns it on |
| 0014 | Live preview: a buffered stream runs each chunk as a one-shot run does, the encoder, the decoder and the tentative tail on the session's pool (0011), the mel on the session's thread count; an abort stops the encoder at the next graph node and both decodes too (before each, and at every step: decode_rnnt_greedy_streaming takes an abort hook and leaves its tokens and state as they were), and the chunk returns 13, which ends the stream. The same text, commits and tentative tails with and without a pool on 51 clips; an abort in the encoder, the committed decode or the tentative decode returns within 0.1 ms on a Mac (`android/tools/live-spike.py verify`, `abort`) | none: without a pool and without an abort only the mel's thread count changes |
| 0015 | Live preview: a buffered stream drops the audio no later window can reach (each looks back at most left + chunk + right, 7.68 s at 70-13-4), so a long take holds about one window instead of 3.8 MB a minute. The same windows and text on 51 clips, and over 10.5 minutes of public speech (606 chunks) the same commits and tentative tails after every chunk as the untrimmed path, holding at most 127,680 samples (163,840 allocated) against the whole take (`android/tools/live-spike.py trim`) | `TRANSCRIBE_STREAM_KEEP_PCM=1` keeps every sample (read at stream_begin) |

The numbers below come from one interleaved emulator run (armv8.2_2, 4 threads, 30 warm runs per config, Mac load average 9 to 10):
- 0001 cuts PSS from 1,117 MB to 762 MB with identical text on all 39 clips, and encoder time by 5 to 6%. The emulator understates its speed side: ggml compiles its llamafile tinyBLAS matmuls out of every CPU module with i8mm or SVE (`ggml/src/ggml-cpu/ggml-cpu.c:46-48`). There, as on the Pixel's armv8.6_1, F32 weights take the slow per-row `vec_dot` path, and 0001 cuts a conformer block's matmul time by about 20% (Mac microbenchmark, armv8.6 build).
- 0002 then cuts encoder time by 31 to 33% (JFK 397 to 267 ms, 29.4 s clip 1,156 to 794 ms). One private clip that is mostly silence (10.2 s, 8 words) gains 2 inserted words: 3 words changed, private-clip WER 6.31% to 6.60%, public text identical.
- 0003 then halves decoder time (JFK 13 to 7 ms, 29.4 s clip 43 to 22 ms) and PSS drops another 34 MB to 728 MB, with identical text on all 39 clips.
- All three against the unpatched build: encoder JFK 419 to 271 ms (-35%), 29.4 s clip 1,228 to 800 ms (-35%); decoder 13 to 7 and 44 to 22 ms; PSS 1,117 to 728 MB; peak RSS 1,376 to 986 MB.
- Public accuracy gate, on LibriSpeech test-clean and test-other (a fixed sample of 1,113 utterances, 21,018 words, 73 speakers), a plain pass plus 2 dithered passes: all three patches against the unpatched paths is +0.01 points on each split (1.66% to 1.67% and 2.82% to 2.83%). No single pass exceeds +0.03, and the 95% bootstrap intervals end at +0.08 or below.
- On the Pixel 10 (armv8.6_1), JFK stop to text drops from 2,268 to 1,374 ms.

Patches 0004 to 0009: encoder output bit-identical on the 11 public clips on a Mac armv8.6-like build (texts identical for Canary 180M Flash and Parakeet TDT v2 too), public texts identical on the emulator. Mac: mel 9.4 to 1.0 ms for JFK, encoder graph 2,398 to 1,558 nodes, the first pre-encode conv 10.3 to 2.1 ms, an abort returns within 2 to 6 ms. The decoder's single thread is slower on a Mac and the emulator (JFK 5 to 11 ms), where barriers are cheap; it is meant for the Pixel, where 5 threads took 83 ms, and a phone measurement decides it there.

## Silero VAD

Date: 2026-09-25. `android/app/src/main/cpp/vad/` holds whisper.cpp's Silero VAD, vendored from `src/whisper.cpp` at ggml-org/whisper.cpp `d09f61a708f3487afa956ff578e60eae5e7a233c` (MIT, `vad/LICENSE`). It compiles into `libengine_jni.so` and links transcribe.cpp's `ggml` target, so the APK still has one `libggml.so` and one set of CPU modules (+29 KB of `libengine_jni.so`). The model is the asset `vad/ggml-silero-v6.2.0.bin` (885,098 bytes, SHA-256 pinned in `VadModel`, from ggml-org/whisper-vad at `9ffd54a1e1ee413ddf265af9913beaf518d1639b`), copied to `noBackupFilesDir` and checked before each load.

The changes, each marked `ThumbFree:` in the source:
- **Context.** Official Silero v6.2 feeds its model the previous window's last 64 samples in front of each 512-sample window and reflects only the right edge. The port read `n_context` and ignored it, reflecting 64 samples of the window itself on the left: over the parity clips, single probabilities were up to 0.53 off and one clip in 311 got the other keep/drop answer.
- **F32 convolutions.** `ggml_conv_1d` rounds its input to F16 before the matrix product (still up to 0.043 off with the context fixed). The model's F16 weights are widened to F32 at load and the convolutions run on an F32 `im2col`, which leaves only the weights' own rounding (under 0.01).
- **Contiguous LSTM input.** ggml counts the transposed `[128, 1]` input as contiguous and hands llamafile's GEMM a row stride of 1; its assertion stops the app's debug builds (asserts on) in modules that keep llamafile, such as the emulator's `armv8.2_2`.
- CPU only, found through the backend registry (the CPU backend is a module here), one thread, file loading without `whisper_context`, and a failed compute reported as a failure.

`android/tools/vad-parity.py parity` builds the VAD with transcribe.cpp as a macOS dylib (`android/tools/vad-parity/CMakeLists.txt`, loaded with ctypes) and compares it with official Silero on ONNX Runtime; `SileroVadTest` repeats the check on the device for jfk.wav. `android/tools/vad-parity.py replay` runs the speech check over the corpus through the app's planner.

## The patch series on Parakeet TDT 0.6B v3

Date: 2026-09-26. The multilingual model shares Unified's encoder shape (24 FastConformer layers, d_model 1024, 128 mel bins, the same 217 encoder matmul weights and 48 pointwise convs) but has no linear biases and no input scaling, a TDT decoder (durations 0 to 4) instead of RNN-T, and 8,192 tokens instead of 1,024. Every decoder weight is Q8_0 in its GGUF. No patch needed a change: 0006 and 0011 patched the TDT greedy loop along with the RNN-T ones (checked on TDT v2 then), and every patch sizes its tensors from the model.

`tools/v3-check.py` builds the engine twice as a macOS dylib (`tools/v3-check/`, loaded with ctypes): with the series, and without it from a clean clone of the pinned commit. On this Mac build ggml has no CPU_REPACK buffer, so 0002 is inert there; the emulator shows it in use for v3 (below). What it found:
- **Texts, 191 public clips** (the 11 public clips and 20 FLEURS clips in each of 9 languages): the series as the app runs it (with the persistent pool) gives the unpatched text on 174. Switching one patch back at a time, 0001 (the Q8_0 pointwise weights) accounts for most of the difference (16 texts) and 0003 (the decoder's Q8_0 weights in place) for 2; 0002, 0004, 0005, 0006 (the steps on one thread), 0009, 0012 (a kept scheduler) and the pool itself change none. Both 0001 and 0003 round to Q8_0 by design, as they do for Unified. **For these two patches on v3, WER parity replaces the output-identity rule**: the same accuracy-for-speed-and-memory trade accepted for Unified, gated on the WER parity measured below.
- **WER, 1,113 LibriSpeech utterances** (the same sample): the series against unpatched v3 is -0.03 points on test-clean (2.00% to 1.97%) and +0.01 on test-other (3.59% to 3.60%); 35 utterances change. `tools/v3-check.py wer` prints the FLEURS languages and the intervals.
- **Abort (0007)**: an abort asked 150 ms into a JFK run stops the patched run within 1 ms (median of 5; status 13), where the unpatched run ignores it and finishes (status 0), as for Unified.
- **Memory**: the Mac process peaks at 966 MB with the series against 1,432 MB unpatched; 0001 saves about 290 MB of that and 0003 about 110 MB.
- **On the emulator** (armv8.2_2), a v3 take through EngineService logs `217 encoder weights in CPU_REPACK`, `promoted 48 conv pointwise weights from F16 → q8_0`, the persistent pool (`takes_pool=1`) and the ADPF session, with 720 MB PSS once loaded.
- **Speed on the Pixel 10**, measured (engine only, `EngineBenchTest#remote` in a bench copy of the app, 3 rounds of 10 calls per clip, alternating with Unified): v3's call p50 is 222 / 427 / 568 / 1,459 ms for the 3.7 s / 8.4 s German / 11 s / 29.4 s clips, against Unified's 227 / 414 / 546 / 1,446 ms (0.98 to 1.04 times). The encoders cost the same; v3's decoder is 5 to 29 ms slower (its joint has 8,198 outputs against 1,025), as on the Mac.
- **Language**: the hint "en", or each clip's own language, gives the same text as none on all 191 clips. v3 takes no language prompt (the GGUF's `stt.capability.lang_detect` is true and it has no prompt tensors), so the app passes none (`ModelFile.languageHint`).
