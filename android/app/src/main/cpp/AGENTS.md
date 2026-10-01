# android/app/src/main/cpp: the native engine bridge

`engine_jni.cpp` is the JNI bridge over transcribe.cpp's C API, called only from `engine/NativeEngine.kt` in the
`:engine` process. `vad/` is Silero VAD on ggml, taken from whisper.cpp. `CMakeLists.txt` builds both with the patched
`third_party/transcribe.cpp`, and `third_party/patches/transcribe-patches.cmake` applies the patch series at configure
time. Why the build looks the way it does is in `docs/decisions/native-build.md`.

## Invariants

- One ggml in the APK: the VAD links transcribe.cpp's `ggml` target, it does not bring its own.
- The dynamic backend build: `TRANSCRIBE_BUILD_SHARED`, `TRANSCRIBE_GGML_BACKEND_DL` and `GGML_CPU_ALL_VARIANTS` on, so
  ggml loads the best CPU module for the phone at run time. `android/app/build.gradle.kts` chooses which modules ship.
- Pinned off, and checked by host tests: `GGML_NATIVE` (host-tuned code crashes other CPUs, `CmakeFlagsTest`),
  `GGML_OPENMP`, and `GGML_RPC`, the one socket client in the engine's tree (`NetworkRegressionGuardTest`). No native
  source here includes a socket or resolver header or an HTTP library.
- Debug and release builds compile with the same flags, `-O3` with asserts and `-g`: unoptimized ggml is many times
  slower, and release's default `-O2` crashes NDK 30's clang (`CmakeFlagsTest`).
- One shared C++ runtime (`-DANDROID_STL=c++_shared`), and native libraries extracted at install
  (`useLegacyPackaging`), because the backend scan lists `nativeLibraryDir`.
- Every LOAD segment is 16 KB aligned, which Google Play requires; `android/tools/check-16kb.sh <apk>` checks an APK.
- Changes to vendored VAD code are marked `ThumbFree:` in the source, and `vad/LICENSE` stays with it.
- Aborts go by run token: an abort naming run T stops runs up to T and never a later one. An aborted run returns status
  13 and no text.
- Text crosses JNI as UTF-8 bytes, not `NewStringUTF`: an aborted decode can cut a character in half.
- Logs give timings, counts and status codes, never text.

## Testing

- Host: `CmakeFlagsTest`, `NetworkRegressionGuardTest`, `TranscribePatchesTest`.
- Device: `NativeEngineTest` (the CPU module loaded, packaging, peak memory), `SileroVadTest`, `EngineServiceTest`.
- On a Mac, `android/tools/vad-parity.py parity` builds the VAD as a library and compares it with official Silero on
  ONNX Runtime: mean error at most 0.005, max 0.02, no keep or drop disagreement.

## Pitfalls

- The JNI function names spell the Kotlin package (`Java_io_github_kabrapratik28_thumbfree_...`). Renaming the package
  or `NativeEngine` means renaming them all.
- `GGML_CPU_KLEIDIAI` stays off: code that picks SME kernels from HWCAP2 alone crashes on the emulator, which
  advertises SME and traps it.
- CPU backend functions come from the backend module's registry, loaded at run time; check each for a missing symbol.
- A CMake configure reruns when a patch or a patched file changes; a reset submodule is patched again, never built
  unpatched.
