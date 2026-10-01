# android/tools: the Android app's checks, phone scripts and benchmarks

| Tool | What it does | Runs on |
|---|---|---|
| `check-16kb.sh <apk>` | 16 KB alignment of every native library, and `zipalign -P 16` | Any computer |
| `minified-test-rules.py` | Keeps in `app/minified-test-rules.pro` what the device tests need from R8's code | Any computer |
| `push-test-model.sh <serial> [file]` | Copies a GGUF from the Hugging Face cache into the debug app's files | A device |
| `install-pixel.sh <serial>` | Builds and installs the debug app, grants permissions, pushes models, turns the service on | Your own test device |
| `fgs-gate.sh <serial>` | A bubble tap over another app starts the microphone service and captures real audio | A device |
| `engine-bench.py <serial> <tag> <config>...` | Engine speed, memory and WER for engine builds, patches switched in one APK | A device |
| `live-phone-check.sh <serial>` | The live preview's rules for turning it on by default; `live-phone-summary.py` scores the run | A phone |
| `live-phone-check-selftest.sh <serial>` | Proves the phone check's safety rules | An emulator |
| `vad-parity.py parity`, `replay` | The app's Silero against official Silero; the speech check replayed through the planner | A Mac |
| `live-spike.py` | The live preview's stream: `verify`, `abort` and `trim` check patches 0013 to 0015 | A Mac |

## Invariants

- A device is always an argument, never a default. Scripts name it in every `adb` call.
- Phone scripts never touch the phone's own copy of ThumbFree. They build a bench copy (`-Pthumbfree.benchApp`, which
  adds `.bench` to the application id), check both APKs before the first change on the phone, and let Gradle only
  assemble, never run a connected task.
- A trap undoes everything a phone script changed, however it ends: bench APKs uninstalled, temp files removed, the
  phone sent Home. Read-only checks before and after (the app's version and install time, the enabled accessibility
  services) fail the run if anything else moved.
- `install-pixel.sh` and `push-test-model.sh` do change the debug app on the device they name, so point them only at a
  device you use for testing.
- The Mac tools (`live-spike/`, `vad-parity/`) build the engine and the app's Silero VAD as one macOS library and load
  it with `ctypes`, with the same patch series as the app. They need CMake, Ninja, Xcode's clang and numpy;
  `vad-parity.py` also needs onnxruntime.
- Checks that run on any computer need only the Android SDK and the Python standard library. Measurement tools print
  numbers; text from private clips is only counted, never printed.

## Data the tools read

Paths can be changed with the environment variables each tool's header names.

- `testdata/public-bench/`, in the repository: `jfk.wav`, 10 LibriSpeech dev-clean clips, a tone and `refs.tsv`
  (sources in `THIRD_PARTY_NOTICES.md`). A `results/` folder beside them is for local outputs and git ignores it.
- Not in the repository: a LibriSpeech test-clean and test-other sample (`BAKEOFF`) and public noise clips
  (`QUALITY`), which `live-spike.py` and parts of `vad-parity.py` need.
- `testdata/private/`, ignored: the maintainer's own recordings. `engine-bench.py` and `vad-parity.py replay` need
  them; `vad-parity.py parity` runs without.

## Testing

`python3 android/tools/live-phone-summary.py --self-test` runs anywhere. `android/tools/live-phone-check-selftest.sh`
needs an emulator. `vad-parity.py` checks its own contract every time it starts.

## Pitfalls

- `lockf -k /tmp/thumbfree-emulator.lock ...` in a header is for machines where several people or agents share one
  emulator; use `flock` on Linux.
- The Python tools' `ROOT` is the repository root (for `testdata/` and `third_party/`); the shell tools' `ROOT` is
  `android/` (for Gradle and its outputs).
- A phone locks its screen during long runs. The phone check pokes it every 25 s instead of changing a setting, so
  there is nothing to restore.
