# ios/App: the app

The ThumbFree app. `AppEnvironment` builds it all and holds the Debug test hooks; `Session/` runs sessions and takes
(`SessionHost`), `Audio/` the microphone, `Models/` the model downloads, and `UI/` the Try, History, Dictionary and
Settings tabs in SwiftUI. `Intents/` and `Widgets/` add Start ThumbFree, a Shortcut and a Control Center control.

## Invariants

- Starting: with no live session, the first mic tap opens the app with `thumbfree://dictate?take=<id>`; the app starts
  the session and the take, then reopens the app the user came from if `ReturnTargets` lets this build open it
  (Messages, Notes and Signal in store builds), or shows how to swipe back. Later taps need no switch.
- A session keeps the model warm and the mic open with a 300 ms pre-roll. It ends after its length (2, 5, 15 or 60
  minutes after the last take), a call, a lost route, a memory warning while idle, or End session. Tap to lock, hold
  to talk. While a take records, every mic is a red stop key and the bar reads "Recording 0:07"
  (`HostStatus.takeStartedAt`).
- A take's text reaches History and the outbox before the keyboard is told.
- The App Group is `group.io.github.kabrapratik28.thumbfree`; its `IPC/` folder is excluded from backups and every
  write in it is atomic (a temporary file, then a rename). The app handles each keyboard command once, then deletes it.
- Only `Models/` uses the network, and only for the model download from `huggingface.co` at pinned revisions.
- Every control has a VoiceOver label, text uses Dynamic Type, and every page scrolls at the largest size. The look
  comes from `UI/Theme.swift`. What users read follows the Android app's words, in the second person: say what happens
  and what is kept, and never blame the user. No live word preview as a user option: it felt slow.

## Test

App unit tests live in `AppTests/` (Swift Testing) and run with the UI tests through `tools/test-app.sh`. They never
use the network (`StubServer`); the real download test runs only with `TF_NETWORK_TESTS`.

## Pitfalls

- A new Debug test hook needs its 8-byte piece in `tools/store-check.sh`, or the Release build can ship it unnoticed.
- `Intents/StartSessionIntent.swift` starts no Live Activity, and iOS may stop an intent's recording without one: the
  store listing leaves Start ThumbFree out until a phone check shows the mic stays on.
