# Contributing to ThumbFree

Thanks for helping. `AGENTS.md` has the full rules for people and AI agents alike; this page is the short version. The
repository holds two apps side by side, the Android app in `android/` and the iOS app in `ios/`: set up the one your
change touches.

## Android

### Set up

1. Install Android Studio. Use its bundled JDK 21 for Gradle (`JAVA_HOME`), and install Android SDK platform 37, NDK
   30.0.16248370 and CMake 4.1.2 from its SDK Manager.
2. Clone with the engine submodule:

   ```sh
   git clone --recursive <repository-url>
   cd ThumbFree
   ```

   In a clone made without `--recursive`, run `git submodule update --init`.
3. The Android app is the Gradle project in `android/`: open that folder in Android Studio, and run Gradle there.

### Build and test

From the repository root:

```sh
python3 tools/check-agent-docs.py   # the rules for AGENTS.md and CLAUDE.md files
cd android
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:testDebugUnitTest    # host tests, no device needed
```

Device tests run on an arm64 emulator with Android 13 or newer and a Google Play image. From the repository root, name
the device, push a speech model, then run the test classes your change touches:

```sh
export ANDROID_SERIAL=emulator-5554
android/tools/push-test-model.sh "$ANDROID_SERIAL"
cd android
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.kabrapratik28.thumbfree.e2e.DictationE2ETest
```

Keep `ANDROID_SERIAL` set for every Gradle device task, so nothing installs on a phone you didn't mean to use.

## iOS

### Set up

1. On a Mac with Apple silicon and macOS 26 or newer, install Xcode 27 with the iOS 26.5 Simulator runtime, and
   XcodeGen: `brew install xcodegen`.
2. Clone the repository; the iOS app doesn't use the engine submodule. In `ios/`, run `xcodegen generate` and open
   `ThumbFree.xcodeproj`. Git ignores the project: `ios/project.yml` is its source, and the test scripts regenerate it.
3. For the tests that run the real speech model, fetch the pinned Core ML models to your Mac: in `ios/`, run
   `python3 tools/fetch-models.py` (about 950 MB). Without them those tests skip; everything else runs.
4. Simulator builds need no Apple team. For your iPhone, put `DEVELOPMENT_TEAM = <your team id>` in
   `ios/Config/Signing.local.xcconfig`, which git ignores (`ios/AGENTS.md`, "On a phone").

### Build and test

From `ios/`:

```sh
tools/test-kit.sh                                    # ThumbFreeKit tests on the Mac
TF_SIM="iPhone 17 Pro" tools/test-app.sh             # app unit and UI tests on that Simulator (iOS 26.5)
TF_SIM="iPhone 17 Pro" tools/test-app.sh -only-testing:ThumbFreeTests/KeyplaneTests
tools/store-check.sh                                 # the Release build's App Store checks
```

Run the tests your change touches while you work, and the whole suite once before you ask for a review. One Swift
Testing test needs its parentheses and quotes, for example
`"-only-testing:ThumbFreeTests/KeyplaneTests/theLettersLayerHasApplesRows()"`; without them nothing runs and the run
still says it passed, which `tools/test-app.sh` turns into a failure. Use a Simulator of your own, and never run the UI
tests on a phone you use: they reset the app.

## Pull request checklist

- [ ] The change has tests: tests for logic, device or UI tests for what users see, and a failing test first for a
      bug fix.
- [ ] `python3 tools/check-agent-docs.py` passes, and a folder's `AGENTS.md` is updated if its rules changed.
- [ ] No private recordings, transcripts, personal data, keys or local paths in the change.
- [ ] Nothing new leaves the phone: no analytics, no new network use (only model downloads may use the network).
- [ ] Text, comments and docs use plain words, no em-dashes, and don't name or compare other dictation apps.
- [ ] Commit messages are one line: the area, a colon, what changed and why.

For an Android change:

- [ ] `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` pass in `android/`, and the description lists
      the device test classes you ran and on which device.
- [ ] Engine patch changes follow `third_party/patches/AGENTS.md`, including its accuracy gate.

For an iOS change:

- [ ] `tools/test-kit.sh` and `tools/test-app.sh` pass in `ios/`, and the description names the Simulator and any phone
      checks.
- [ ] `tools/store-check.sh` passes if the change touches a target's code, `project.yml`, a privacy manifest or a test
      hook (add `CODE_SIGNING_ALLOWED=NO` if signing blocks it).
- [ ] No generated files (no `*.xcodeproj`, Info.plist or entitlements file) and no `Config/Signing.local.xcconfig`.
- [ ] Logs hold no typed or dictated text, and any new store of text or audio is excluded from backups.
- [ ] Debug-only code sits inside `#if DEBUG`, and no Release code needs `TF_AUTO_RETURN`.
- [ ] A change to what the app, the keyboard and the package rely on from each other updates the contract,
      `ios/docs/contract.md`.

By contributing, you agree that your contribution is licensed under the Apache License 2.0 (`LICENSE`).
