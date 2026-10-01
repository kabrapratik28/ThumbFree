# ui: the Compose screens

`MainActivity` hosts the welcome flow (`WelcomeScreen`) and four tabs: Try, History, Dictionary and Settings
(`HomeScreen`). `ModelScreen` downloads and deletes speech models, `BubbleSettings` sets the bubble's size,
transparency and position, and `Theme` and `Icons` hold the look.

## Invariants

- Exactly four tabs, Try first. `HomeScreenTest.fourTabsTryFirst` checks it; a new screen goes inside a tab.
- The welcome flow is three screens, then the Try tab, which shows the model's download: the welcome, whose Get started
  starts that download; the microphone; the accessibility step, whose one sentence is the disclosure Google Play
  requires (what the service reads and why) before it sends anyone to the system setting. Its words must match the
  store listing and `docs/privacy-policy.md`.
- That disclosure is the only way to the setting, first run or not: the Dictation bubble setup row, on the Try tab and
  in Settings, opens it as a revisit instead of the setting directly, so it shows every time, not only on first run.
- A welcome step never scrolls, at any font size: a title of 5 words or fewer, one sentence and the buttons always show
  whole, and the looping drawn picture takes what is left, shrinking or hiding
  (`stepsFitWithoutScrollingAtAnyFontSize`). With animations off (animator duration scale 0) the pictures show stills.
- The microphone is the one permission the app asks for. The recording and download services run without the
  notification permission, which no manifest declares (`ManifestContractTest`).
- Every string lives in `res/values/strings_<area>.xml`. The app's name comes from `brand.properties` through
  `R.string.app_name`; no other resource spells it (`BrandTest`).
- Plain words on screen: short sentences, no model jargon before the tabs, one recommended model under a plain name.
- The live preview's settings stay hidden while the feature is experimental (`Settings.LIVE_PREVIEW_DEFAULT`).
- Settings > About links the published privacy policy (`PRIVACY_POLICY`, the address the store listing gives) and lists
  the open-source credits for what the app ships. Keep the credits in step with `THIRD_PARTY_NOTICES.md`; the
  multilingual model's CC BY 4.0 credit keeps its links to the source, the GGUF file and the license.
- No disk or network work on the main thread: the end-to-end tests run under `StrictModeRule`, which kills the app
  for it.
- A history read that fails shows a message instead of crashing: SQLite errors in a refresh must not reach the
  uncaught handler.

## Testing

- Host (Robolectric): `HistoryScreenTest`, `DictionaryTest`, `ModelChoiceTest`, `ModelQueueTest`, `ReadHistoryTest`,
  `SetupStateTest`, `FormatSizeTest`, `OpenAccessibilityTest`.
- Device: `HomeScreenTest`, `ModelScreenTest`. `UiStateShots` takes screenshots of every download state and of the
  first run, and `WelcomeShots` of the real welcome screens with the system bars, only with `-e ui_shots 1`; use them
  to review a visual change.

## Pitfalls

- Brand text in a new strings file fails `BrandTest`; take the name from `R.string.app_name` as the other screens do.
- `Icons.kt` holds Material icon path data (Apache 2.0) because the extended icon set is a large dependency for a
  dozen shapes. Add a shape there rather than a dependency.
- The bubble itself is not a Compose view: it lives in `a11y/` and draws with `BubbleView`.
- Android's page for one accessibility service (`ACCESSIBILITY_DETAILS_SETTINGS`) is a system API that refuses apps:
  it needs a permission only the system and the app store hold. So the button opens the accessibility list, scrolled
  to the app on Pixel, and the welcome picture shows users to tap the app there.
- In a device test, an ActivityMonitor that answers a start makes Android pause and resume the activity to deliver
  the result, and the welcome reads that resume as the return from Accessibility settings.
- Screenshots in `docs/brand/` show made-up content only. Retake them when a screen they show changes.
