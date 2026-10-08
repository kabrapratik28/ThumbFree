# ui: the Compose screens

`MainActivity` hosts the welcome flow (`WelcomeScreen`, its try in `TryOnce`) and four tabs: Home (`HomeTab`), History,
Dictionary and Settings (`HomeScreen`). `Pictures` draws the welcome's pictures, `ModelScreen` downloads and deletes
speech models, `BubbleSettings` sets the bubble, `Theme`, `Icons` and `Motion` the look; `CleanupActivity` and `CleanupSettings` are Clean up's.

## Invariants

- Exactly four tabs, Home first. `HomeScreenTest.fourTabsHomeFirst` checks it; a new screen goes inside a tab. Home
  has nothing to type into: the next thing a take needs, else Ready and how to use the bubble in other apps.
- The welcome flow is four steps, then Home: get ready (what ThumbFree does, then the language, whose tap starts the
  download; the step waits there, its bubble grey, through the download, the file's check and the engine's load, then
  turns yellow and goes on by itself); the try (`TryOnce`: the real `BubbleView` and microphone, asked at the first tap,
  one take that keeps nothing); the accessibility step: the Android Settings picture, its two rules, then the disclosure
  Google Play requires (what the service reads and why) right above the button to the system setting, in words that
  must match the store listing and `docs/privacy-policy.md`; and all set, only once all three work, the model loaded,
  else what a take still needs (`models.Readiness`, as Home and the bubble) with its remedy. Bookmark: `resumeAt`.
- A yellow bubble always listens: no screen draws it as a picture; one that can't is grey with a ring and a badge.
- That disclosure is the only way to the setting, first run or not: the Dictation bubble setup row, on Home and in
  Settings, opens it as a revisit instead of the setting directly, so it shows every time, not only on first run.
- A welcome step never scrolls at the default font size: its words and buttons show whole and its picture is scaled
  into what is left, never hidden (`StepBody`); at a large font steps 1 and 2 scroll their middle and step 3 its
  disclosure (`stepsFitWithoutScrollingAtTheDefaultFontSize`); the big button has one place on every step. Motion uses
  only `Motion.kt`'s tokens, on explicit timelines; with animations off pictures are stills, changes cut.
- The microphone is the one permission the app asks for. The recording and download services run without the
  notification permission, which no manifest declares (`ManifestContractTest`).
- Every string lives in `res/values/strings_<area>.xml`. The app's name comes from `brand.properties` through
  `R.string.app_name`; no other resource spells it (`BrandTest`).
- Plain words on screen: short sentences, no model jargon before the tabs, one recommended model under a plain name.
- The live preview's settings stay hidden while the feature is experimental (`Settings.LIVE_PREVIEW_DEFAULT`).
- Settings > About links the published privacy policy (`PRIVACY_POLICY`, the address the store listing gives) and lists
  the open-source credits for what the app ships. Keep the credits in step with `THIRD_PARTY_NOTICES.md`; the
  multilingual model's CC BY 4.0 credit keeps its links to the source, the GGUF file and the license.
- No disk or network work on main: the end-to-end tests run under `StrictModeRule`, which kills the app for it.
- A history read that fails shows a message instead of crashing: SQLite errors in a refresh must not reach the
  uncaught handler.

## Testing

- Host: `HistoryScreenTest`, `DictionaryTest`, `ModelChoiceTest`, `ModelQueueTest`, `ReadHistoryTest`, `SetupStateTest`,
  `FormatSizeTest`, `OpenAccessibilityTest`, `WelcomeOrderTest`, `TrialStateTest`, `SettingsPictureTest` (Robolectric).
- Device: `HomeScreenTest`, `ModelScreenTest`, `TryOnceTest` (real takes). With `-e ui_shots 1`, `UiStateShots` shoots
  every download state and the first run, `WelcomeShots` the real screens with the system bars; `-e owner_shots 1` adds
  a review set: both themes, Home, 200% font and recordings.

## Pitfalls

- Brand text in a new strings file fails `BrandTest`; take the name from `R.string.app_name` as the other screens do.
- `Icons.kt` holds Material icon path data (Apache 2.0) because the extended icon set is a large dependency for a
  dozen shapes. Add a shape there rather than a dependency.
- The bubble itself is not a Compose view: it lives in `a11y/` and draws with `BubbleView`.
- Android's page for one accessibility service (`ACCESSIBILITY_DETAILS_SETTINGS`) is a system API that refuses apps:
  it needs a permission only the system and the app store hold. So the button opens the accessibility list, scrolled
  to the app on Pixel, and the Settings picture's first screen shows users to find the app there.
- In a device test, an ActivityMonitor that answers a start makes Android pause and resume the activity to deliver
  the result, and the welcome reads that resume as the return from Accessibility settings.
- Agree keeps a wait in `Settings.accessibilityWait`; the service connecting within 10 minutes brings MainActivity
  back (`AndroidPorts.returnToApp`), and only MainActivity's resume ends the wait, so a start Android refuses leaves the
  manual return working. Instrumentation may allow background starts anyway: check a change by hand, switch and Allow.
- Screenshots in `docs/brand/` show made-up content only. Retake them when a screen they show changes.
