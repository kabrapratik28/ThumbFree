# Stop to text on this Mac

Date: 2026-09-27. Made by `tfreplay`, in simulated time.
Machine: Apple M4 Pro, macOS Version 26.7 (Build 25G229).
Model: parakeet-tdt-0.6b-v2, Encoder on the CPU. Speech check: Silero, beside each run.

**Provisional.** These are this Mac's numbers, Encoder on the CPU. The targets are for an iPhone with an A17 Pro or newer, from the stop tap in the keyboard to the text in the field (p50: 60 ms for a stop after a pause, 250 ms for a stop mid-speech in a take up to 15 s, 300 ms in a longer take); an iPhone run replaces this table.

## Method

- Clips: the 12 English public clips in `testdata/public` (LibriSpeech and JFK), each followed by digital silence.
- Each take goes through `ChunkedTranscriber` in 20 ms blocks. That size is an assumption here (Android's read size); the iPhone's real buffer size is only measured on a device. The stop comes 150 ms before, at, 300 ms after or 1 s after the end of speech: the end of the last 30 ms frame with sound (speech, or above -55 dBFS), the stop tail's own rule.
- Before the stop, blocks go in as fast as the engine keeps up (the harness waits for the engine after each block). From the stop on, the tail's blocks arrive every 20 ms on the real clock.
- Stop to result: from the stop call to the texts being ready, on the real clock. It includes the stop tail (up to 350 ms) and every engine run the stop still waits for. It leaves out the text pipeline and the keyboard's insertion.
- Modes: **plain** runs the final window after the tail ends; **optimistic** runs it at the stop with zeros for the tail and runs it again only if the tail holds sound; **full** adds the speculative finish 300 ms into each pause. Full is what the app runs.
- WER: every stop of a mode pooled, against `refs.tsv`, next to the engine's one-shot text of each clip (clips over 15 s cut with `Bench.windows`). The rule: at most 0.3 points worse than one-shot.

## Stop to result, ms

| Mode | Stop | p50 | p90 | Paths |
|---|---|---|---|---|
| plain | 150 ms early | 382 | 388 | afterTail 12 |
| plain | at the end | 218 | 224 | afterTail 12 |
| plain | 300 ms after | 218 | 227 | afterTail 11, silent 1 |
| plain | 1 s after | 88 | 98 | afterTail 7, silent 5 |
| plain | all | 218 | 383 | afterTail 42, silent 6 |
| optimistic | 150 ms early | 377 | 387 | afterTail 12 |
| optimistic | at the end | 124 | 129 | optimistic 12 |
| optimistic | 300 ms after | 124 | 127 | optimistic 11, silent 1 |
| optimistic | 1 s after | 84 | 94 | optimistic 7, silent 5 |
| optimistic | all | 124 | 383 | optimistic 30, afterTail 12, silent 6 |
| full | 150 ms early | 379 | 385 | afterTail 12 |
| full | at the end | 121 | 126 | optimistic 12 |
| full | 300 ms after | 125 | 128 | speculation 11, silent 1 |
| full | 1 s after | 0 | 0 | speculation 7, silent 5 |
| full | all | 123 | 381 | speculation 18, optimistic 12, afterTail 12, silent 6 |

## Each clip, full mode (ms and path at each stop)

| Clip | Length | 150 ms early | at the end | 300 ms after | 1 s after | Errors at each stop | One-shot errors |
|---|---|---|---|---|---|---|---|
| 1272-128104-0000.wav | 5.9 s | 387 afterTail | 124 optimistic | 120 speculation | 0 speculation | 0, 0, 0, 0 | 0 |
| 1272-128104-0001.wav | 4.8 s | 385 afterTail | 121 optimistic | 127 speculation | 0 speculation | 0, 0, 0, 0 | 0 |
| 1272-128104-0002.wav | 12.5 s | 381 afterTail | 126 optimistic | 122 speculation | 0 speculation | 0, 0, 0, 0 | 0 |
| 1272-128104-0003.wav | 9.9 s | 378 afterTail | 121 optimistic | 125 speculation | 0 silent | 0, 0, 0, 0 | 0 |
| 1272-128104-0004.wav | 29.4 s | 380 afterTail | 123 optimistic | 128 speculation | 0 speculation | 4, 4, 4, 4 | 3 |
| 1272-128104-0005.wav | 9.0 s | 383 afterTail | 127 optimistic | 123 speculation | 0 silent | 0, 0, 0, 0 | 0 |
| 1272-128104-0009.wav | 18.3 s | 369 afterTail | 126 optimistic | 127 speculation | 0 speculation | 3, 3, 3, 3 | 3 |
| 1272-128104-0011.wav | 15.1 s | 368 afterTail | 121 optimistic | 127 speculation | 0 speculation | 1, 1, 1, 1 | 1 |
| 1272-135031-0010.wav | 9.0 s | 382 afterTail | 122 optimistic | 127 speculation | 0 silent | 1, 1, 1, 1 | 1 |
| 1272-135031-0023.wav | 7.5 s | 377 afterTail | 120 optimistic | 123 speculation | 0 silent | 2, 2, 2, 2 | 2 |
| 1272-141231-0017.wav | 3.7 s | 369 afterTail | 121 optimistic | 128 speculation | 0 speculation | 0, 0, 0, 0 | 0 |
| jfk.wav | 11.0 s | 379 afterTail | 121 optimistic | 120 silent | 0 silent | 0, 0, 0, 0 | 0 |

## Accuracy

| Transcription | Errors | Words | WER | Against one-shot |
|---|---|---|---|---|
| One-shot | 10 | 330 | 3.03% | |
| plain (48 stops, 1.4 engine runs a take) | 44 | 1320 | 3.33% | +0.30 points, +1.0 words a stop (over the 0.3 limit) |
| optimistic (48 stops, 1.6 engine runs a take) | 44 | 1320 | 3.33% | +0.30 points, +1.0 words a stop (over the 0.3 limit) |
| full (48 stops, 2.5 engine runs a take) | 44 | 1320 | 3.33% | +0.30 points, +1.0 words a stop (over the 0.3 limit) |

The rule is checked on the Neural Engine run, the app's path; this CPU run (the app's fallback) only reports it: a mode is more than 0.3 points worse than one-shot.

## Long takes (full mode)

| Take | Length | Stop | ms | Path | Engine runs | WER | Check |
|---|---|---|---|---|---|---|---|
| JFK 6 times, 0.6 s gaps | 69 s | 150 ms early | 374 | afterTail | 12 (2 after the stop) | 0.00% | 6 of 6 copies |
| JFK 6 times, 0.6 s gaps | 69 s | at the end | 120 | optimistic | 11 (1 after the stop) | 0.00% | 6 of 6 copies |
| JFK 6 times, 0.6 s gaps | 69 s | 300 ms after | 120 | speculation | 11 (0 after the stop) | 0.00% | 6 of 6 copies |
| JFK 6 times, 0.6 s gaps | 69 s | 1 s after | 0 | speculation | 11 (0 after the stop) | 0.00% | 6 of 6 copies |
| 12 clips in a row, 0.6 s gaps | 143 s | 150 ms early | 376 | afterTail | 33 (2 after the stop) | 3.03% | one-shot 3.03% |
| 12 clips in a row, 0.6 s gaps | 143 s | at the end | 129 | optimistic | 32 (1 after the stop) | 3.03% | one-shot 3.03% |
| 12 clips in a row, 0.6 s gaps | 143 s | 300 ms after | 126 | speculation | 32 (0 after the stop) | 3.03% | one-shot 3.03% |
| 12 clips in a row, 0.6 s gaps | 143 s | 1 s after | 0 | speculation | 32 (0 after the stop) | 3.03% | one-shot 3.03% |

## Notes

- A room above -55 dBFS counts every frame as sound, so there the tail runs to its 350 ms cap and the final window runs again after it. Three clips here already sit above -55 dBFS on their own (`jfk.wav`, `1272-135031-0010.wav`, `1272-135031-0023.wav`); this harness follows each with digital silence where the clip itself ends, so their tails behave like a quiet room's. A real noisy room needs the device run.
- Nothing here measures the app switch, the keyboard, or an iPhone's Neural Engine.
