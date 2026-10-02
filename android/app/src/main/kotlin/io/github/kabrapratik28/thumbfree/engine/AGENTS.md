# engine: the :engine process and the transcription queue

`EngineService` runs in its own process, `:engine` (see the manifest), and holds the one native engine handle
(`NativeEngine`, the JNI bridge to `android/app/src/main/cpp`). The main process reaches it only through `RemoteEngine`
over Binder (`android/app/src/main/aidl/.../IEngine.aidl`). `TranscriptionQueue` sends a take's chunks to it one at a
time, `SpeechCheck` runs Silero on each chunk, and `PreviewFeed`, `StreamBacklog` and `AsrGate` serve the experimental
live preview.

## Invariants

- Only `:engine` loads a model or touches native code, so a native crash or the low-memory killer takes only that
  process. `EngineIsolationTest` fails if anything but `EngineService` names `NativeEngine`.
- A transcribe sends a WAV path and a sample range, never the audio: the PCM of the offline path does not cross Binder.
  Only the live preview's stream sends audio, as 16-bit windows of 512 samples.
- Every transcribe carries a run token, and an abort names it, so a late abort stops only the chunk it was meant for.
  Parakeet stops at the next graph node (patch 0007); an aborted run returns status 13 and no text.
- The queue runs one job at a time, in arrival order. A take reads its model path, thread count and language once, at
  its first job, and keeps them for all its chunks.
- After the last take the model stays loaded for 5 minutes, then unloads, which ends `:engine` and returns its memory.
  The welcome's preload loads the chosen model with no take, so the try answers at once, and starts the same 5 minutes.
- The speech check fails open: no Silero model, or a failed run, counts as speech. It never changes the audio the engine
  hears, and the text of a chunk Silero hears no speech in is dropped.
- The Silero asset is pinned by size and SHA-256 (`VadModel`) and checked before each load.
- Offline calls always win: a preview call waits only for work admitted before the offline call came (`AsrGate`), and
  gives way with a busy status otherwise.
- `:engine` reads its environment switches (`files/engine-env.txt`) only in debug builds. Release builds use defaults.
- Logs from here carry numbers and codes only.

## Testing

- Host: `TranscriptionQueueTest` (with `FakeEngine`), `SpeechCheckTest`, `AsrGateTest`, `StreamBacklogTest`,
  `PreviewFeedTest`, `VadModelTest`.
- Device, with the model pushed: `NativeEngineTest` (packaged CPU modules, the variant loaded, peak memory under
  1.2 GB), `EngineServiceTest`, `SileroVadTest` (parity with official Silero on `jfk.wav`), `AsrPoolTest`.
  `CanaryEngineTest` needs Canary pushed by name:
  `android/tools/push-test-model.sh <serial> canary-180m-flash-Q8_0.gguf`.

## Pitfalls

- Use the process's one `RemoteEngine` (`AppGraph`). `:engine` ends only when its last binding goes, and a new bind
  waits for an old `:engine` to exit, so a second client can stall the first.
- A death of `:engine` surfaces once as `EngineDiedException`; the caller loads the model again, and the next call
  rebinds. Don't retry a transcribe blindly after a death.
- A model load takes seconds and several hundred MB. Device tests that don't need the engine should not load it.
- NativeEngineTest's packaging checks expect the default CPU module set; builds with `-Pthumbfree.onlyCpu` fail it.
