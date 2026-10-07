# Probes

Two throwaway apps used to test what each platform allows. Neither is ThumbFree; both keep everything on the device.

- `android-genaiprobe/`: calls Gemini Nano (ML Kit Prompt, Proofreading and Rewriting) from an activity in front, a
  translucent activity that takes no focus, a focusable sheet, a broadcast receiver and after Home; `EvalActivity`
  runs a list of cases through the Prompt API. Build with the Android Gradle plugin 9 (`./gradlew :app:assembleDebug`,
  a Gradle wrapper and `local.properties` from the main project), then start each screen with
  `adb shell am start -n io.github.kabrapratik28.genaiprobe/probe.<Activity>` while another app's text box has the
  keyboard up, and read `files/probe.log` with `adb shell run-as io.github.kabrapratik28.genaiprobe cat files/probe.log`.
- `ios-fmprobe/`: an app plus a keyboard extension (Full Access) that call Foundation Models; the keyboard's Clean and
  Burst keys test the call from an extension, and Arm runs back-to-back calls from the background for up to 60 s,
  logging each result, the first rate limit and its `resetDate`. Generate with `xcodegen`, set your team in
  `project.yml`, install on a phone (the Simulator cannot run the model), and copy `Documents/results.txt` off the
  phone with `xcrun devicectl device copy from ... --domain-type appDataContainer`.
