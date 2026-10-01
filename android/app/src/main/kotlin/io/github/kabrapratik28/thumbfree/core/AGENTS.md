# core: plain Kotlin logic

Everything here is plain Kotlin with no `android.` import (`CorePurityTest`), so all of it runs in host tests. Android
code in the other packages calls into it.

| Package | What is there |
|---|---|
| `audio/` | `SpeechGate` (30 ms frames against a noise floor), `ChunkPlanner`, `Padding`, WAV reading and writing, `Levels`, `PreviewGate` |
| `insert/` | `InsertionJudge`: did the text land (VERIFIED, MISS or UNREADABLE) |
| `models/` | `Catalog`, `Downloader`, `ModelStore`, `ModelLeases`, `ThreadPolicy`, `AsrPolicy` |
| `session/` | `Session.reduce`, the dictation state machine, plus `Gesture`, bubble placement and style, `Preview` |
| `text/` | Cleanup: custom words, filler removal, `normalize`, and the Unicode helpers they share |

## Invariants

- `Session.reduce` and `Preview.reduce` are pure: events in, a new state and effects out. `DictationController` carries
  out the effects in order and feeds results back as events. The reducers read no clock: time comes with the events.
- `SpeechGate` never drops, trims or changes audio. A frame is speech at 12 dB over the floor (8 dB in the first 3 s,
  judged once that floor is known) and above -55 dBFS.
- `ChunkPlanner` chunks tile the take with no gap and no overlap, cut on frame edges, and are at most 30 s: from 10 s
  a cut in the middle of 990 ms of silence, from 20 s at a 300 ms pause however loud, at 30 s a forced cut at the
  quietest 120 ms from 22 s on. `WavChunks.plan` cuts a finished take the same way, for Retry.
- `Catalog` pins each model's Hugging Face repository, file, revision, size and SHA-256. Change them together, and
  update `CatalogTest`, `THIRD_PARTY_NOTICES.md` and the About screen's credits with them.
- `Downloader` is the only network code in the app. A file is renamed into place and marked verified only after the
  SHA-256 of its bytes on disk matches the catalog.
- A take or a retranscription holds a lease on its model; deleting a model needs it free (`ModelLeases`).
- Cleanup runs custom words, then filler removal, then `normalize`, and fails open: if a step throws, the raw text comes
  back. `CustomWords.correct` never throws, and correcting its result again changes nothing.
- Custom words' guards against near misses know English only, so other languages, and the multilingual model's takes,
  get exact matches only (`CustomWords.exactOnlyFor`).
- `CommonWords.kt` is CC BY-SA 4.0 data from wordfreq. Regenerate it as its header says; don't edit it by hand.
- `Fillers.kt`, `Normalize.kt`, the match score in `CustomWords.kt` and some text test rows are adapted from MIT-licensed
  code. Keep their `THIRD_PARTY_NOTICES.md` section in step when you move, split or rewrite them, and say so in
  comments only as "adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md".

## Testing

Host tests mirror these packages under `android/app/src/test/.../core/`. `SessionPropertyTest` and `SessionReducerTest`
cover the state machine, `CustomWordsTest` holds the trap table and a speed budget, `GateReplayTest` replays WAV lists
for `android/tools/vad-parity.py` (it skips without them).

## Pitfalls

- Text helpers follow Rust's Unicode rules (`UnicodeText.kt`). Don't use `java.util.regex` `\s` or `\b`: the JVM and
  Android's ICU engine disagree on them.
- `Char.isWhitespace` is not Unicode White_Space; use `isRustWhitespace` and `splitWhitespace`.
- `CustomWordsTest.fast` times the matcher with generous budgets; a very busy machine can still slow one run down.
- `AsrPolicy`'s environment switches are for phone A/B runs and are read only in debug builds.
