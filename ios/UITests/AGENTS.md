# ios/UITests: the app's UI tests

XCTest UI tests of the app and the keyboard on a Simulator, run by `tools/test-app.sh` together with the unit tests in
`AppTests/`. `StoreScreenshotsUITests` and `StoreVideoUITests` skip in a normal run: their tools
(`tools/store-screenshots.sh`, `tools/store-video.sh`) run them to make the App Store media.

## Invariants

- Every test launches through `ThumbFreeUI.launch` (`UITestSupport.swift`): `-TFResetState YES` (no history, model,
  settings or kept words), JFK instead of the mic, and the fixed-text engine unless `realModel`.
- Keyboard tests and the dump and store tools type in the try screen's box (`try.field`), opened with
  `ThumbFreeUI.launchTry` and held at its mic stage, so a take that gives text never takes the keyboard down.
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
- The try screen shows "Microphone is off" in place of its box while the microphone is refused, which another test may
  leave: `ThumbFreeUI.launchTry` then resets the permission and launches again (`TryItUITests.launch` resets it first).
- Home offers Try it and the walkthrough only once ready, the microphone allowed among the rest, which `-TFResetState`
  leaves as it was. Home has no microphone card ("Try ThumbFree" until the try asks): a test allows it in Settings'
  Setup row (`ThumbFreeUI.allowMicrophone`), and one that only needs Home waits with `ThumbFreeUI.onHome`. iOS's prompt
  at the try's first take needs a launch without `-TFAudioFile`; answer it Don't Allow, so the Mac's microphone is never used.
- A fresh Simulator shows iOS's keyboard tips once in the keys' place; `KeyboardSetup.switchToThumbFree` closes them.
- The try screen is a sheet over Home: leave it (Not now) before the tab bar or Home's End session. Leaving it mid-take
  cancels the take; a test that needs the session over with the keyboard still up passes `-TFEndSessions YES`.
- The landscape tests fail to rotate on a Simulator that has been up for days: shut it down and boot it again.
- An iPad Simulator opens ThumbFree in a window beside the app it came from, whose keyboard can cover ThumbFree's tab
  bar and End session: close that app before tapping there.
