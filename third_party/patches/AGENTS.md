# third_party/patches: the engine patch series

ThumbFree's changes to transcribe.cpp and its ggml, one `git format-patch` file per change, applied in name order to
the submodule `third_party/transcribe.cpp`, which stays pinned to one upstream commit (its gitlink). Nothing is forked.
What each patch does, its switch back and its measurements are in `docs/decisions/native-build.md`.
`transcribe-patches.cmake` here is the step that applies the series, beside it so that every app's build can include it.

## Rules for the series

- Every CMake configure of the Android app includes `transcribe-patches.cmake`, and so do the Mac tools: it finds the
  longest prefix already applied, then checks and applies the rest as one input to `git apply`.
- The configure stops, on purpose, when the patch folder is empty, when the series doesn't apply, when the submodule's
  checkout isn't the pinned commit, and when its files aren't exactly the pinned commit plus the whole series (a patch
  dropped while still applied, a patch edited after it was applied, any local edit in the submodule). It prints the fix
  on a line of its own, with absolute paths so it works from any folder (Gradle runs in `android/`):
  `git -C <repository>/third_party/transcribe.cpp checkout -- .`, then build again.
- Experiments inside the submodule never build silently: move them into a patch.
- A patch that only makes the engine faster keeps its output the same: identical texts on the 11 public clips (and on
  FLEURS for the multilingual model), and bit-identical encoder output where the patch doesn't touch the numbers.
- A patch that changes the numbers (for example Q8_0 weights) may not make WER worse by more than 0.3 points against
  the build without it: JFK stays exact, and WER is measured on the 11 public clips and on the 1,113 LibriSpeech
  test-clean and test-other utterances, with dithered passes, and for the multilingual model on FLEURS too
  (`tools/v3-check.py wer`). The patches so far moved LibriSpeech WER by 0.03 points or less.
- Each patch has an environment switch that restores the old path, unless it only adds a function or acts only on an
  abort. Document the switch in the decision record's table.
- The patched engine must stay under `NativeEngineTest`'s peak memory bound (1.2 GB; it peaks near 1.0 GB).

## Changing a patch

1. Clone the submodule at the pinned commit (`git ls-files --stage third_party/transcribe.cpp` shows it) and apply
   the series there, one commit per patch.
2. Edit, then regenerate the files with
   `git format-patch -N --zero-commit --no-signature --filename-max-length=80`.
3. Run `tools/normalize-patches.py` on the new files, so `git diff --check` passes.
4. Reset the app's submodule with the checkout command above, since a tree holding the old series stops the configure.
5. Run `TranscribePatchesTest`, the device engine tests and the gate that fits the patch, and update the decision
   record in the same change.

## Testing

- Host: `TranscribePatchesTest` runs the patch step on a clone of the pinned commit: the whole series from any prefix,
  nothing on a second run, and a stop on each bad tree above, with its fix on a line of its own.
- Device: `NativeEngineTest`, `EngineServiceTest`.
- Measuring: `android/tools/engine-bench.py` (device, patches switched on and off in one APK), `tools/v3-check.py`
  (a Mac), and `android/tools/live-spike.py verify`, `abort` and `trim` for the stream patches 0013 to 0015.

## Pitfalls

- A submodule bump can break a patch: `TranscribePatchesTest` fails then, before the native build does.
- The patch authors are `ThumbFree <thumbfree@localhost>`: keep personal names and addresses out of new patches.
- `git apply` checks each file of a multi-file input against the untouched tree, which is why the series goes in as one
  concatenated input.
