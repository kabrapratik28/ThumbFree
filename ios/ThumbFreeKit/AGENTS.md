# ios/ThumbFreeKit: the Swift package

`TFCore` is the logic, tested on the Mac: text rules (`Text/`), audio rules (`Audio/`: speech gate, chunk planner, stop
tail, WAV files), the take state machine (`Session/`), the App Group files (`IPC/`), History (`History/`), the live
take (`Live/`) and the model catalog (`Models/`). `TFEngine` runs Parakeet TDT on Core ML and the Silero speech check;
`tfbench` and `tfreplay` measure them. Logic that needs no UIKit belongs here, so it runs in `swift test`.

## Invariants

- `TFCore` imports Foundation (and CryptoKit, for file hashes) only; `TFEngine` adds Core ML and Accelerate. The
  keyboard links only `TFCore`, so nothing heavy goes there.
- The engine runs FluidInference's Core ML conversions (Preprocessor, Encoder, Decoder, JointDecision) with a 15 s
  window. The Encoder runs on `.cpuAndNeuralEngine` and the rest on `.cpuOnly`, never the GPU, which iOS forbids in the
  background, where keyboard takes run. A Neural Engine failure retries once on the CPU and stays there. On iOS 27 the
  background Neural Engine needs the Background Inference entitlement: add it once Apple grants it.
- Speed: takes are cut at pauses into 8 to 14.5 s chunks and transcribed while the user talks; a pause starts a
  speculative finish; the stop runs at once with zeros for the tail, and again only if the tail (at most 350 ms) held
  sound. The tail, not the engine, bounds stop to text.
- Text (`TextPipeline`): chunk join, Dictionary words with five guards, fillers, then normalize, in the Android app's
  order, each step a total function that never throws or traps. `CursorFormatter` fits spacing and capitals to the
  cursor at insertion. These files port the Android rules and their golden test rows: change a rule on both apps, with
  the same test rows, and keep the ported files' credit in `THIRD_PARTY_NOTICES.md` at the repository root.
- `TakeReducer` is a pure reducer, (state, event) to (state, effects); the app's `SessionHost` runs the effects.
- `Models/ModelCatalog.swift` is written by `tools/gen-catalog.py` from `docs/models/model-manifest.json`: change the
  manifest, then rerun the tool.

## Test

`tools/test-kit.sh` runs `swift test` here, about 250 tests; `tools/test-kit.sh --filter <Suite>` runs one suite. Two
opt-in tests skip unless you ask for them.

## Pitfalls

- The engine tests, `tfbench` and `tfreplay` read the models from `~/Library/Application Support/FluidAudio/Models/`
  (or `$TF_MODELS_DIR`), with the files the manifest pins. Without them those tests skip, and a green run proves less:
  fetch them with `python3 tools/fetch-models.py`.
- App tests on a Simulator find the same folder through `SIMULATOR_HOST_HOME`.
