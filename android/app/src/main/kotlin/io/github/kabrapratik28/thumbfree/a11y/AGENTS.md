# a11y: the accessibility service, the bubble and insertion

`DictationAccessibilityService` follows focus and shows the bubble next to editable fields. It also owns an
accessibility input-method session (`EditorSession`, Android 13 and newer) that types the take's text into the field.
`FocusTracker` decides when the bubble shows, `BubbleWindow`/`BubbleView` draw it, `Inserter` and `EditorPort` write
the text, and `PreviewPanel` is the experimental live preview's window.

## Invariants (accessibility policy limits)

- ThumbFree is not an accessibility tool. It reads only the focused field (the cursor and the text around it), to place
  text and spacing, and nothing else on screen. Nothing it reads leaves the phone; History keeps only the take's own
  text and the name of the app it went into.
- The service asks for the minimum: focus, window state and window list events, the input-method flag, interactive
  windows and view ids, a 100 ms notification timeout. `ServiceConfigTest` pins this; more event types would wake the
  service on every scroll and click. Changing it also changes what the Play declaration and the welcome disclosure say.
- Text is written only after the user's own tap, with one write (`commitText`) at the cursor and one check that it
  landed. A miss is never written again: the chip offers Copy and Insert here. There is no ACTION_SET_TEXT and no
  automatic paste; Insert here pastes only when the user taps it.
- Clean up (a test build) rewrites only the last take's own words, after a tap on its sparkle, and only while they sit
  right before a cursor with nothing selected in the take's field (a new session of the same node counts): a selection
  over them, read back to cover exactly them, one commitText, one read back. Never deleteSurroundingText.
- No bubble and no write for password, number, phone or date fields (`FieldKind`). The pinned field is checked again
  right before the write, since focus can move to a password field in between.
- A take types into the field pinned at touch-down: its input session and the node its focus event named. Another
  field, even in the same view, refuses the write.
- The bubble and the preview window never take focus, so the field keeps its keyboard. Main thread only for windows;
  every `EditorPort` call runs off the main thread.
- The preview panel is never a live region: TalkBack reading it aloud would go into the open microphone. For the same
  reason the bubble's label stays one while it listens; it changes only with the microphone closed (transcribing, grey).
- Before the chosen speech model is usable a tap on the bubble never listens: AndroidPorts shows the not-ready panel
  (`BubbleUi.NotReady`, from the shared download state the screens read too), whose Open opens the app's speech models.
  It is the bubble's one live region, redrawn at most every 10% and once a second, since no microphone is open while it
  shows. No timer puts it away, only Open, a tap outside it (the window watches outside touches only then) or a second
  tap on the bubble.
- Clean up's sparkle stands beside the idle circle, its size and idle transparency, toward the middle of the screen, in
  the bubble's own window, so it moves with every drag.
- The bubble keeps its size and place in every state; its listening ring and stop mark fade (220 and 180 ms), and the
  start and stop of listening tick the same, through the phone's haptic setting. While a tap can't listen (the
  microphone off, the chosen model not usable) it is grey, with a ring for how far and a badge for why.
- If the service goes away during a take, the take stops and keeps its text for Copy.

## Testing

- Host: `FocusTrackerTest`, `InserterTest` (with `FakeEditorPort`), `BubbleViewTest`, `BubbleWindowTest`,
  `PreviewPanelTest`, `SelectionWaitTest`, `VirtualNodePinTest`, `EditorSnapshotTest`, `CodeMessagesTest`.
- Device (emulator): `ServiceConfigTest`, `EditorSessionTest`, `AccessibilityEditorPortTest`, `A11yHarnessTest`, then
  the `e2e/` classes. Device tests go through `A11yRule`, which enables the service and the system keyboard and restores both.

## Pitfalls

- A `UiAutomation` without `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` unbinds every accessibility service, ours too.
  `A11yRule` sets it before the first `UiDevice`; new tests must do the same.
- An `AccessibilityNodeInfo`'s `toString` carries the field's text. Never log a node; log ids and codes.
- One view can move its input connection between several text nodes without a new session. Only the focus event
  tells, which is why the pin records the node.
- A node's cached text can predate the write: refresh it before reading back.
- After an aborted device run the service can stay listed but crashed; the system rebinds it only once it leaves the
  list (`A11yRule` handles this).
