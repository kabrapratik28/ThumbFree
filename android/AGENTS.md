# android: the Android app

The Android app's Gradle project. It builds the engine from `third_party/` at the repository root. Commands here
start at the repository root.

## Build

You need Android Studio's bundled JDK 21 (set `JAVA_HOME` to it; on macOS it is
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`), Android SDK platform 37, NDK 30.0.16248370 and
CMake 4.1.2. Open `android/` in Android Studio. Gradle finds the SDK through `android/local.properties` (`sdk.dir`,
written by Android Studio and ignored by git) or `ANDROID_HOME`.

```sh
cd android
./gradlew :app:assembleDebug            # the debug APK, arm64-v8a only
./gradlew :app:testDebugUnitTest        # host tests
./gradlew :app:bundleRelease            # the Play bundle; assembleRelease for an APK
```

- Release builds use R8 and resource shrinking, signed with the upload key named in
  `~/.thumbfree-keys/keystore.properties` (`-Pthumbfree.keystore=<file>` for another), or unsigned without it
  (the file's keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). What C++ finds by name, or code runs by reflection, needs `app/proguard-rules.pro`.
- Don't apply the `org.jetbrains.kotlin.android` plugin: AGP 9 builds Kotlin itself.
- User-facing strings go in `android/app/src/main/res/values/strings_<area>.xml`, never in code.

## Test

- Host tests run on the JVM, need no device, and cover the logic: the `core` packages are plain Kotlin for that reason.
  Run all of them before you ask for a review.
- Device tests need an arm64 emulator (on an Apple silicon Mac or an arm64 Linux host; Android 13 or newer; a Google
  Play image, whose keyboard the tests type with) or a test phone of your own. Most need a speech model on the device:

```sh
export ANDROID_SERIAL=emulator-5554
android/tools/push-test-model.sh "$ANDROID_SERIAL"
cd android
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.kabrapratik28.thumbfree.e2e.DictationE2ETest
```

- Run the device classes your change touches, not the whole suite at once: the end-to-end tests take minutes each.
  The benchmark classes (`bench/`) skip unless a tool passes their arguments.
- `android/gradle.properties` keeps the app installed after a device run, so a pushed model stays until you uninstall.
- After a change to the R8 rules, R8 or a dependency, run the device tests on release's R8 code (debuggable, signed with
  the debug key): `connectedMinifiedTestAndroidTest -Pthumbfree.testBuildType=minifiedTest` in place of
  `connectedDebugAndroidTest`. The tests call app code that R8 removes, which `app/minified-test-rules.pro` keeps.
  After changing tests, build `assembleMinifiedTest assembleMinifiedTestAndroidTest` with the same flag and run
  `tools/minified-test-rules.py`, again until it adds no rule.

## Device safety

- Gradle's connected tasks install on every attached device when `ANDROID_SERIAL` is unset, and that has put test
  builds on a personal phone. Set it before any Gradle device task, and use `adb -s <serial>` for adb.
- Unplug phones you don't mean to test on.
- Agents that share an emulator hold one lock file around each device command: `lockf` on macOS, `flock` on Linux.
- Phone scripts in `android/tools/` take the serial as an argument, work on a separate bench copy of the app and put
  the phone back as they found it. Read a script's header before you run it ([tools](tools/AGENTS.md)).
