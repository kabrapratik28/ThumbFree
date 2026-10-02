# App packages

The Kotlin app. `a11y/`, `audio/`, `core/`, `engine/` and `ui/` have their own `AGENTS.md`; this file covers the map
and the thin packages `app/`, `data/` and `models/`.

## Map

| Package | Job |
|---|---|
| `a11y/` | Accessibility service, focus tracking, the bubble and preview windows, text insertion |
| `app/` | Wiring: `AppGraph` builds the main process's objects once, `DictationController` runs the session |
| `audio/` | Microphone capture, the recording foreground service, audio focus |
| `core/` | Plain Kotlin logic: session state machine, speech gate, chunking, models catalog, text cleanup |
| `data/` | History (`history.db` and one WAV per take in `filesDir/recordings/`), settings, crash recovery |
| `engine/` | The `:engine` process, its Binder client and the transcription queue |
| `models/` | In-app model download (`DownloadWorker` on WorkManager) and its state for the screens |
| `ui/` | Compose screens: welcome flow and the four tabs |

## Invariants

- `DictationController` and `AndroidPorts` run on the main thread. A port never calls the controller back; recorder,
  queue and service callbacks are posted to the main thread first.
- `ThumbFreeApp` builds `AppGraph` in the main process only; `:engine` hosts nothing but `EngineService`. One
  `RemoteEngine` per process.
- History writes go through `DbThread`, one at a time and in order. `HistoryDb` blocks: never call it on the main
  thread. A failed write throws `HistoryWriteException`, so a lost row never reads as saved text.
- `Recovery` runs once per process before the first take and never retries: a take that died recording or
  transcribing becomes INTERRUPTED with its unconfirmed text cleared, STAGED becomes NOT_INSERTED, INSERTING becomes
  NEEDS_REVIEW, and WAVs no row names are deleted, with whatever the welcome's try left in `filesDir/trial/`.
- A take started from the welcome's try (`AndroidPorts.trialTouch`) runs the same machine, microphone and model as
  any other but keeps nothing: no History row, its WAV in `filesDir/trial/` only until it ends, no text after its
  screen. Until the chosen model is known to be usable, a tap on the floating bubble never listens (the not-ready panel).
- A schema change raises `user_version` with a migration and a test in `HistoryDbTest`.
- Settings keys keep their names across releases; a value a later build no longer knows reads as the default.
- The transcription queue reads the model path once per take, so a finished download or a model switch applies from
  the next take.
- A download resumes its `.part` file, and only a file whose on-disk SHA-256 matches the catalog is marked usable.
- The live preview is experimental and hidden: `Settings.LIVE_PREVIEW_DEFAULT` stays false and the screens don't offer
  it. Its code and tests stay and must keep passing.

## Testing

- Host: `AppGraphTest`, `DictationControllerTest` (ports faked in `FakePorts`), `DbThreadTest`, `HistoryDbTest`,
  `RecoveryTest`, `SettingsTest`, `DownloadWorkerTest`, `ModelDownloadsTest`, `ReadinessTest`.
- Guards for the whole app, in `android/app/src/test/.../build/`: `CorePurityTest`, `EngineIsolationTest`,
  `ManifestContractTest`, `NetworkRegressionGuardTest`, `CmakeFlagsTest`, `TranscribePatchesTest`, `BubbleArtGuardTest`
  (no screen draws the bubble's art but the bubble itself, its settings' preview and About's logo).
- Device: `WiringSmokeTest`, and `DictationE2ETest` for the whole path. `RealDownloadTest` downloads a real model and
  runs only with `-e real_download 1`.

## Pitfalls

- Host tests use a plain `Application` (`robolectric.properties`), so the real graph is never built by accident;
  `AppGraphTest` builds it on purpose.
- Log an exception's class, not its message: a message can carry the text of a take.
- `DownloadWorker` may be refused a foreground start from the background; that is a retry later, not a failure.
