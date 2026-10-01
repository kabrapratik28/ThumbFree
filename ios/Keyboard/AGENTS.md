# ios/Keyboard: the keyboard extension, with ios/Shared

The ThumbFree keyboard: `KeyboardViewController`, every key in one UIKit view (`KeyplaneView`) under a SwiftUI bar, and
the emoji picker. `Shared/` is compiled into both the app and the keyboard, so its code stays extension-safe: the key
model (`Keyplane`), `KeyboardClient`, suggestions, emoji, commands and delivery.

## Invariants

- iOS holds a keyboard to a strict memory limit: no image files, data parsed only when needed, and emoji drawn as text,
  from smaller bitmaps as memory runs low (`EmojiMemory`). The keyboard links only `TFCore`.
- The keyboard never records, never loads a model and never goes online; it shows only the state the app sends it.
  In the App Group's `IPC/` folder it only creates `commands/<sentAt>-<id>.json` and its `keyboard-seen` mark; the app
  alone writes `status.json` (once a second while live; over 5 s old means the app is gone) and `outbox.json`. Darwin
  notifications carry no data and may be lost or doubled: they only prompt a reread.
- Delivery: the keyboard types a take by itself once, only into the field that pinned it (its `documentIdentifier` and
  a SHA-256 of the text around the cursor), and only if the pin came after the keyboard last appeared; the take that
  opened the app goes where the user taps stop. `insertionBegan` is on disk before `insertText`; a read-back answers
  `insertionConfirmed` or `insertionUnverified`. Anything else waits behind Insert here and Copy (`.localOnly`).
  Nothing is retried: an unknown outcome is "May already be in the field".
- Read `documentIdentifier` only through `FieldTraits.documentID(of:)`: some apps return nil, and Swift's bridge traps.
- Keys work as on Apple's iOS 26 keyboard: the layout follows the field (`KeyboardKind`; number fields get the digit
  pad), long-press alternatives in Apple's order (`KeyAlternates`), the space-bar trackpad, delete by words
  (`DeleteRepeat`), the 123 rule and quick slides (`KeyLayer`). `tools/dump-apple-keyboard.sh` reads Apple's layouts
  and alternatives into `tools/keyboard/`, and tests hold the code to them: read them again after each iOS release.
- Emoji: `Shared/EmojiData.swift` comes from `tools/gen-emoji.py` (Apple's order in `tools/emoji/`, read by
  `tools/dump-apple-emoji.sh`, and pinned Unicode and CLDR releases). `EmojiCatalog.drawnVersion` hides emoji the
  running iOS cannot draw.
- Suggestions (`Speller`) use Apple's `UITextChecker` on the main actor, so each key stays inside a frame
  (`SuggestionsTests.typingStaysInstant`). A clear misspelling is corrected at the word's end where `FieldTraits`
  allows, with Apple's undo, but never the user's words: kept words, text replacements, contacts' words (`Lexicon`) and
  the Dictionary (`TFDictionary` in the App Group). The bar's status place goes to the delivery chip, the recording
  line, the emoji search, then suggestions.
- The keyboard's own stores (`TFEmojiUsage`, `TFEmojiTones`, `TFLearnedWords`) stay in its own
  `UserDefaults.standard`: never the App Group, never logged.
- Automatic return (`HostArbiter*`) reads the host's bundle id through the keyboard arbiter, a private UIKit interface,
  so it compiles only with `TF_AUTO_RETURN` and puts the id in the dictate link. The app trusts it only when the
  keyboard's press for that take sits in the App Group (60 s, one trip), opens only `ReturnTargets` schemes, and
  outside Debug no entry marked `needsDeviceCheck`.

## Test

The keyboard's logic is tested in `AppTests/` (`KeyplaneTests`, `SuggestionsTests` and others) and its behavior in a
real field in `UITests/`, both with `tools/test-app.sh`. Suggestion tests read Apple's iOS 26.5 dictionary: check them
again on a new iOS.

## Pitfalls

- A keyboard process can outlive an install: switch keyboards to load the new build.
- Without Full Access the keyboard only types: no dictation, no App Group, no Dictionary in the suggestions.
