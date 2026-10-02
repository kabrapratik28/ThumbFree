# ios/App: the app

The ThumbFree app. `AppEnvironment` builds it all and holds the Debug test hooks; `Session/` runs sessions and takes
(`SessionHost`), `Audio/` the microphone, `Models/` the model downloads, and `UI/` the Home, History, Dictionary and
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
- A first run starts with the model for the iPhone's languages and saves that choice (`SpeechModels`). An installed
  model, a begun download or a saved choice is never changed by it, so an update keeps its model. The welcome's
  language choice is a choice too: a download it cancelled that still ends ready never takes over.
- Ready means usable now (`SetupRows.ready`): the microphone allowed, the keyboard seen with Full Access and the engine
  loaded. Only then does Home say ready. What stops dictation shows one at a time (`SetupBlocker`), its fix the big
  button. The welcome's step 1 waits for speech, downloaded and loaded, before step 2; iOS asks for the microphone at
  the first take in the try, whose box comes while the engine loads.
- Every control has a VoiceOver label, text uses Dynamic Type, and every page scrolls at the largest sizes; the
  welcome, the try and Home never scroll below them. Every drawing sits in an `IllustrationFrame`: labelled, no taps,
  one VoiceOver image; a `TapCue` shows where to tap, in the floating guide's video too, and Reduce Motion stills it.
  The look comes from `UI/Theme.swift`. What users read follows the Android app's words, in the second person: say what
  happens and what is kept, and never blame the user. A title never leaves one word alone on its last line
  (`keepingLastWordsTogether`). No live word preview.

## Test

App unit tests live in `AppTests/` (Swift Testing) and run with the UI tests through `tools/test-app.sh`. They never
use the network (`StubServer`); the real download test runs only with `TF_NETWORK_TESTS`.

## Pitfalls

- A new Debug test hook needs its 8-byte piece in `tools/store-check.sh`, or the Release build can ship it unnoticed.
- iPhone Simulators have no Picture in Picture (their device profiles turn it off), so the keyboard step's floating
  guide (`UI/FloatingGuide.swift`) only floats on an iPad Simulator, where the app runs in compatibility mode, or a phone.
- Nothing that shows the keyboard step's guide (`SetupGuideView`, `FloatingGuideView`, `GuideVideo`) may be removed or
  made again while the app is away in Settings, or the floating window closes: the welcome's page 2 decides what it shows
  only with the app in front (`WelcomeView.keyboardPage(from:status:wentAway:active:)`), however the trip flag and the
  keyboard's status change meanwhile (iOS lists the keyboard before any trip after a reinstall). The guide's look and
  the page's text size, which a change in Settings would swap, hold their last values in front (`FloatingGuide.shown`),
  and the guide's video is set only in front.
- A `ViewThatFits` that leaves out a frame it was showing keeps it, unseen, in the accessibility tree, VoiceOver
  element included: turn the frame's `voiced` off while it has no room, as the try's HOW TO SWITCH does (`helperFits`).
- `Intents/StartSessionIntent.swift` starts no Live Activity, and iOS may stop an intent's recording without one: the
  store listing leaves Start ThumbFree out until a phone check shows the mic stays on.
