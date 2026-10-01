# Parakeet v2 on a real iPhone (2026-09-27)

iPhone 16 (A18), iOS 26.5.2, Debug build, cable attached. Measured by `AppTests/DeviceBenchmarkTests.swift`, which runs inside the app on the phone with the model in the app's container. Three runs; the numbers agree within a few ms.

## The engine on JFK (11 s of speech, one 15 s window), median of 10

| Encoder on | Total | Preprocess | Encoder | Decode | Faster than real time |
|---|---:|---:|---:|---:|---:|
| Neural Engine | 55 to 57 ms | 10 ms | 29 ms | 16 ms | about 200 times |
| CPU only | 139 to 144 ms | 11 ms | 112 to 116 ms | 16 ms | about 80 times |

The text was right in every run. The speech check (Silero) took 7.4 ms for the same 11 s. For comparison, this Mac's Neural Engine runs the Encoder in 27 ms, and the Android app's engine takes 546 ms for the same clip on a Pixel 10.

## Stop tap to text, through the live transcriber (the app's path), median of 3

| When you tap stop | Time | Path |
|---|---:|---|
| Mid-word (150 ms early) | 401 to 405 ms | the final window runs after the stop tail |
| Right at the end of speech | 121 to 124 ms | optimistic: the run at the stop stands |
| 300 ms after you stop talking | 121 to 124 ms | nothing left to run; the stop tail is the wait |
| 1 s after you stop talking | 0 ms | done before the tap |

The 121 ms is the stop tail (it listens at least 100 ms past the last sound so the last word is not cut); the engine hides behind it. The Android app shows text 0.7 to 0.9 s after the stop.

## Loading the model

| | Time |
|---|---:|
| Neural Engine, first load after an install: placement check (`MLComputePlan`) | 21.9 s |
| Neural Engine, first load after an install: the model itself | 18.3 s |
| The same two again in the same run | 0.25 s and 0.11 s |
| The whole engine (4 models) once warm, Neural Engine | 0.55 s |
| The whole engine, CPU only | 7.2 to 10.1 s |

- Every test run reinstalls the app, and every first load after a reinstall took about 40 s. Whether a normal relaunch (no reinstall) keeps Core ML's compiled model is not measured yet.
- The placement check compiles the model a second time. Running it after the model is ready, in the background, and remembering its answer would cut a first load to about 18 s.
- Once, the Neural Engine compiler service failed ("Couldn't communicate with a helper application") and the next run worked. The engine's CPU fallback covers this in the app.
