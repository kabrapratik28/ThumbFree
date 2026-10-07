I read all five files and four v2 boards. No files changed.

**My pick:** keep the sparkle. Use N2 for verified, untouched Clean takes. Use N1 for edits and other styles. Keep O1 plus O3.

## 1. Prove safe replacement first

The rules promise more than the screenshots establish. See [DESIGN.md, text and replacement rules](DESIGN.md).

- **iPhone needs narrower rules initially.** The keyboard sees limited nearby text and edits through the caret. Start with a verified take immediately before it. Cursor moves or missing context should lead to Copy until reliable range tracking is proven.
- **Clean the current text, including edits.** Code must protect corrected and added words. Never restore deleted words from the old transcript. Change “Your changes stay in” to “Includes your edits. Review before replacing” until that promise is enforced.
- **Merge takes only by verified adjacency.** “No typing between” is insufficient. Cursor moves, selections, paste and autocorrect can break the chain. Keep existing text and boundary spaces outside the replacement.
- **Expand cancellation beyond typing.** Include paste, autocorrect, app formatting, selection changes, new takes, switching boxes and sending the message. Recheck the destination and text before applying. Ignore late results. Verify insertion before showing “Tidied.”
- **Treat rich editors separately.** Whole-field replacement can remove formatting, links or mentions. A preview does not make an uncertain replacement safe. Use Copy for unproven editors and WebViews. “Insert here” could duplicate the raw take.
- **Undo should restore the immediate pre-clean version**, including manual corrections. Remove the fixed ten-second expiry while restoration remains safe. Later edits should invalidate automatic Undo. After Undo, restore the sparkle.

Also distinguish N1 taking focus from the user leaving the destination. Recheck the original box when the sheet closes.

## 2. Strengthen the prompt and checks

The failures in [Quality check, #3, #7, #10 and #19](quality-check-gemini-nano-vs-apple.md) are more serious than imperfect tidying.

- `555-121-2` contains the correct digits. Digit matching misses its malformed grouping. Check formatting separately.
- Keeping `7` instead of the final correction `8` passes “the number appeared in the input.” Checks need correction scope, values and units.
- Length and word overlap cannot establish meaning. Losing “not,” “might” or a short sentence can pass them. Preserve negation, uncertainty, names, dates, questions and requests. Unclear cases need preview.

For [instruction-v2.txt](instruction-v2.txt) and [instruction-v3](instruction-v3.txt):

- Keep v2 short. Put Apple’s rules in session instructions. Send the current target separately as data. Escape delimiters and still validate output.
- Make filler removal conditional. Preserve meaningful “like,” intentional repeats, uncertainty and emotion.
- Remove homophone guessing from Clean. Avoid a global `Kama → comma` rule. Kama can be a name.
- Give each style separate edit permissions. “Never add words” conflicts with Professional and Simple.
- Reject empty, incomplete, refused, echoed or unrelated output. Leave the field unchanged.

The [Parakeet research](research-parakeet-errors.md) also identifies a pipeline trap: running `normalize` afterward would flatten the new line breaks. Budget input and output together. Avoid splitting a correction across calls.

## 3. Keep tap and hold, with clearer exits

**Tap for the default and hold for styles is reasonable.** Hold needs another accessible route.

- **Resolve Android’s two hold flows.** The board shows a floating list and a sheet. My choice is hold directly opens N1: pick a style, generate, preview, Replace. Generate each style from the same pre-clean text. See [Android v2 interaction board](boards/android-v2-1-while-using.png).
- **Fix iPhone’s style row.** It removes the mic and status and has no visible Cancel. Use an adaptive menu inside the keyboard. Test smaller phones and large text. See [iPhone style screen](ios-v2%20(sparkle)/v2-03-hold-styles.png).
- **Move Android progress off the yellow mic.** Keep progress and a full-size Cancel on the sparkle control. The yellow bubble should retain its recording meaning. See [Android N2 working screen](android-v2%20(sparkle)/v2-04-tap-float-card.png).
- Verify **48 dp Android and 44 pt iPhone targets** for styles, Undo and Cancel too. Extend the existing TalkBack action to VoiceOver and Switch Access. Labels should name the chosen style and current action. Verify accessibility focus survives N2.
- Announce “Cleaned. Undo available.” Read the full text on request. Automatic reading can expose private text or reach an open mic.

Keep the hint until used or dismissed. Pair the Android sparkle visibly with ThumbFree. Keep it away from Send, selection handles and keyboard AI controls.

Reconsider the three-word minimum. It excludes “yes yes” and “thanks um.” Preserve contrast when the icon fades.

## 4. Close platform and policy gaps

See [DESIGN.md, platform findings](DESIGN.md).

- A successful iPhone extension call would **not prove unlimited usage**. Test quotas and termination in either execution path. Background inference permission does not keep the containing app alive. Select the on-device backend explicitly.
- Separate temporary Busy from battery-quota exhaustion. “Try again in a moment” is misleading for both. Keep one active request. Avoid generating unused styles. Use runtime availability checks.
- Get Play review of this exact accessibility-plus-AI flow. Check AI-directed action restrictions, the service declaration, disclosure and accessibility-tool classification. Check AI-content policy scope for the rewrite styles.
- For App Store review, document the keyboard-to-app launch path, background recording and any Full Access requirement. Preserve ordinary typing without Full Access. Avoid keeping audio active solely to support cleanup.

**Current store-policy pages were blocked by this environment. These policy points need verification before submission.**

Replace “Nothing leaves your phone” with a scoped claim: **“ThumbFree processes speech and clean-up on your phone. It sends no recordings or text to an AI server.”** Audit History backups, clipboard use and diagnostic logs.

## 5. Keep O1 plus O3, with a download branch

Agree with the recommendation in the [Android onboarding board](boards/android-v2-2-edge-cases-settings-onboarding.png) and [iPhone onboarding board](boards/iphone-v2-2-edge-cases-settings-onboarding.png).

- Keep the try optional. Teach Undo. Android O1 should place the sparkle beside the bubble, matching actual use.
- Downloadable Nano needs an optional consent branch in O1 or Home. “Turn on Clean up” is insufficient. Show Download and Not now, storage size when available, and network use. Promise Wi-Fi only if enforced.
- Resolve the conflicting downloading rules. Prefer progress in Home and Settings, with an actionable sparkle only when ready.

## 6. Set the release gate around harmful mistakes

The twenty invented inputs are useful failure probes. They are not an accuracy estimate. Apple’s results are from a Mac.

Before shipping N2, test the complete flow on phones. Include real speech, all styles, edited text, duplicate text, long takes, emoji, rich fields, app rewrites and changes during inference. Measure **wrong results that the checks accept**. That is the main trust risk.