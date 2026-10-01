# ios/UITests: the app's UI tests

XCTest UI tests of the app and the keyboard on a Simulator, run by `tools/test-app.sh` together with the unit tests in
`AppTests/`. `StoreScreenshotsUITests` and `StoreVideoUITests` skip in a normal run: their tools
(`tools/store-screenshots.sh`, `tools/store-video.sh`) run them to make the App Store media.

## Invariants

- Every test launches through `ThumbFreeUI.launch` (`UITestSupport.swift`): `-TFResetState YES` (no history, model,
  settings or kept words), JFK instead of the mic, and the fixed-text engine unless `realModel`.
- `simctl` cannot grant Full Access, so `KeyboardSetup.ensureReady()` turns it on in Settings once per run.
- Wait on predicates (`ThumbFreeUI.wait`), keep negative probes short, and never race a timer.
- A test deletes the files and defaults suites it creates, and never glob-deletes shared temp folders.

## Test

`TF_SIM="iPhone 17 Pro" tools/test-app.sh -only-testing:ThumbFreeUITests/<Class>` runs one class; `tools/test-app.sh`
adds the keyboard to the Simulator first (`tools/sim-enable-keyboard.sh`).

## Pitfalls

- A `KeyboardSetup` failure with Settings on another page is a flake: run again. Any other intermittent failure is a
  test bug.
- UI tests run serially: the Full Access setup does not carry over to parallel clones.
- The landscape tests fail to rotate on a Simulator that has been up for days: shut it down and boot it again.
