# ios: the iOS app

ThumbFree for iPhone, iOS 26 and newer. The ThumbFree keyboard, a full keyboard in Apple's layout, asks the ThumbFree
app to record; the app turns speech into text with NVIDIA Parakeet TDT 0.6B v2 (English) or v3 (multilingual) on its
own Core ML engine, and the keyboard types it at the cursor. iOS gives a keyboard no microphone and too little memory
for a model, so the keyboard never records and never loads one. Swift 6, SwiftUI and UIKit, no third-party packages,
not the transcribe.cpp engine in `third_party/`. Commands here run in `ios/`; five folders have files of their own.

## Invariants

- `docs/contract.md` holds what the app, the keyboard and the package rely on from each other, part by part. Read it
  before a change that crosses them, update it in the same change, and in a merge conflict keep every paragraph.
- The model download checks each file's size and SHA-256 (`ModelVerifier`) against `docs/models/model-manifest.json`.
  Recordings, transcripts, the IPC folder, models and any new store of text or audio are excluded from backups
  (`isExcludedFromBackup`). Console (`io.github.kabrapratik28.thumbfree`) never shows user text.
- Never commit `*.xcodeproj`, the Info.plists or `*.entitlements`: `project.yml` is their source, and keeps every
  target iPhone only. Never edit a file whose header says it is generated (`ModelCatalog.swift`, `EmojiData.swift`):
  change its input and rerun its tool.
- Automatic return finds the app being typed in through a private UIKit interface, the keyboard arbiter
  (`Keyboard/HostArbiter*`). It compiles only with `TF_AUTO_RETURN`, which `project.yml` sets in Debug and, since 1.0.1,
  Release; `tools/store-check.sh` then expects exactly its private names, and a build without it uses public API only.
  Test hooks (`-TFResetState`, `-TFAudioFile`, `-TFFakeEngine` and the rest) live inside `#if DEBUG`, and each new one
  adds an 8-byte piece of its name to the `hooks` pattern of `tools/store-check.sh`. Each `PrivacyInfo.xcprivacy`
  (`App/`, `Keyboard/`, `Widgets/`) declares every required-reason API its bundle uses, with the reason, and no other.
- Swift 6 with strict concurrency, `Sendable` value types, no force unwraps in production code. Every new type gets a
  `///` comment; a deliberate shortcut gets a `ponytail:` comment naming its limit and the upgrade path.
- A shell removal names its target through `"${d:?}"` (`rm -rf "${out:?}"`), so an empty variable stops the command.

## Build and test

You need a Mac with Apple silicon and macOS 26 or newer, Xcode 27 with the iOS 26.5 Simulator runtime, xcodegen
(`brew install xcodegen`) and Python 3. Simulator builds need no Apple team.

```sh
xcodegen generate && open ThumbFree.xcodeproj      # then run the ThumbFree scheme on an iPhone Simulator
tools/test-kit.sh --filter ChunkPlannerTests        # ThumbFreeKit tests on the Mac (swift test); no filter: all
TF_SIM="iPhone 17 Pro" tools/test-app.sh            # app unit and UI tests on that Simulator, about 30 minutes
TF_SIM="iPhone 17 Pro" tools/test-app.sh "-only-testing:ThumbFreeTests/KeyplaneTests/theLettersLayerHasApplesRows()"
tools/store-check.sh                                # the Release build's checks (with no team: CODE_SIGNING_ALLOWED=NO)
python3 tools/fetch-models.py                       # the pinned models for the real-model tests, about 950 MB
```

- Per change, run the focused tests and the suites of every area you touched. The whole suite runs once per branch, on
  its final tree with the target branch merged in, and earlier only after a change to `project.yml`, `AppEnvironment`,
  the App Group contract, a migration or the test harness.
- A Swift Testing id needs its parentheses and quotes, as above; XCTest ids have none. A filter that matches nothing
  still says TEST SUCCEEDED, so `tools/test-app.sh` fails a run that counted 0 tests. It prints 30 lines; the rest is
  in the newest `build/DerivedData/Logs/Test/*.xcresult` (`xcrun xcresulttool get test-results tests --path <it>`).

## Pitfalls

- One Simulator per person or agent (`TF_SIM`): never boot, erase or shut down another. Reboot your own before a whole
  suite, since the landscape tests fail to rotate on one that has run for days.
- Keep `build/` and `ThumbFreeKit/.build`, the build caches; `tools/test-app.sh` deletes the 1 to 2 GB Encoder copy
  Core ML compiles for each build.
- If macOS kills a freshly built binary (exit 137), stop and report it; never work around the Mac's security settings.
- On a phone: put `DEVELOPMENT_TEAM = <your team id>` in the git-ignored `Config/Signing.local.xcconfig`; another team
  needs its own bundle ids and App Group. Never run UI tests on a phone you use: they reset the app. Only a phone shows
  Neural Engine speed (`DeviceBenchmarkTests`), memory, the mic, Bluetooth, calls, battery, each return target and
  Start ThumbFree with the app closed; results go in `docs/benchmarks/`.
