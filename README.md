# Clean up (issue #1): design notes

This branch holds only design notes for [issue #1](https://github.com/kabrapratik28/ThumbFree/issues/1), the optional
on-device AI clean-up of the transcript. It has no app code and is never merged. The summary, the recommendation and
code snippets are in the issue's comments; this branch keeps the full material behind them.

| File | What it is |
|---|---|
| `boards/` | Review boards of real emulator (Pixel 10 profile) and Simulator (iPhone 17 Pro) screenshots of prototype code, made-up content only. v2 (the round sparkle: tap = default style, hold = styles) is the recommended design; v1 (a "Clean up" text control, options A, B, C) is kept for comparison |
| `DESIGN.md` | The full design: what each platform allows (tested on real phones), the options with pros and cons, which words get tidied, when the sparkle shows and hides, edge cases, onboarding, styles, the measured quality and the decisions so far |
| `quality-check-gemini-nano-vs-apple.md` | 20 made-up dictations through Gemini Nano (Pixel 10) and Apple's on-device model, two instructions |
| `instruction-v2.txt`, `instruction-v3.txt` | The two Clean instructions tested; v2 is the current best |
| `research-parakeet-errors.md` | What the Parakeet models get wrong, from 350 clips through all four model paths |
| `research-on-device-apis.md` | ML Kit GenAI and Foundation Models: rules, terms, languages, limits, with sources |
| `review-codex.md` | An independent AI review of the design and what it asked to change |

File names inside the notes were shortened for this branch. Links to other dictation projects' issue trackers were
left out of this public copy. Private recordings appear only as counts.
