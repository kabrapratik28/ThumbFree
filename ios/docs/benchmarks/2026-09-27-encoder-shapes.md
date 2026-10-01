# Encoder shapes: aufklarer/Parakeet-TDT-v3-CoreML-INT8 against our 15 s Encoder

Date: 2026-09-27. Mac: M4 Pro, macOS 26.7, Xcode 27. Harness: a temporary Swift Testing file, not kept.

## What the repo holds

- Revision `96f9a621fd81a2016bd3524928c8db343117458a`, license CC BY 4.0.
- The card says the base model is parakeet-tdt-0.6b-v2. The files are v3: `config.json` has 8,192 tokens and blank 8192, `vocab.json` has 8,192 entries, and the decoder and joint weights have the sizes of FluidInference's v3 (23,604,992 and 12,642,764 bytes).
- The card says the encoder takes enumerated shapes from 100 to 3,000 mel frames. Only `encoder.mlmodelc/metadata.json` says so. Its compiled `model.mil` takes a fixed `[1, 128, 3000]` (30 s). The repo also has `encoder_5s.mlmodelc` (500 frames) and `encoder_15s.mlmodelc` (1,500 frames), both without `metadata.json`. So only 5, 15 and 30 s exist. 30 s was not measured: our chunk planner never sends more than 14.5 s.
- The encoders are 8-bit palettized (about 590 MB each; ours is 6-bit, 445 MB). They output Float16 `[1, T, 1024]`. Here they got our v3 Preprocessor's mel, cut to their frame count, and were decoded by our v3 Decoder, JointDecision and greedy TDT loop. Their Float16 output is not `TdtDecoder`'s expected Float32 `[1, 1024, T]`, so the harness never fed it straight in: it located the 1024-wide hidden axis by its size (not by assuming a position, since the two encoders' output could have laid it out differently from ours), then copied each value into a freshly allocated Float32 `[1, 1024, T]` array before decoding. The WER numbers below (both encoders read sensibly, encoder_15s slightly under ours) confirm that conversion landed correctly rather than silently misreading the layout.

## Speed and placement

Both INT8 encoders were also loaded `.cpuOnly` and timed the same way as the Neural Engine path, because on iOS 27 without the Background Inference entitlement the Encoder runs on the CPU (about 75 to 105 ms per 15 s window elsewhere in this repo), and a shorter window could matter most on that path. Ours was measured `.cpuOnly` too, as the same-Mac baseline for that column.

| Encoder | Window | Share on the Neural Engine | First load | First call | Warm median, Neural Engine | Warm median, CPU only |
|---|---|---|---|---|---|---|
| Ours (FluidInference v3) | 15 s | 0.9935 | | | 27.3 ms | 74.4 ms |
| encoder_5s | 5 s | 0.9935 | 7846 ms | 14.9 ms | 8.4 ms | 30.5 ms |
| encoder_15s | 15 s | 0.9935 | 10147 ms | 32.8 ms | 26.4 ms | 73.1 ms |

encoder_5s is 69.2% faster warm than ours on the Neural Engine and 59.0% faster on the CPU (the bigger absolute gap: 43.9 ms against 18.9 ms). encoder_15s is not meaningfully faster than ours on either path: 3.3% on the Neural Engine, 1.7% on the CPU. The CPU path does not change which rule below passes.

## Accuracy

| Encoder | Clips | WER | Ours on the same clips | Same text as ours |
|---|---|---|---|---|
| encoder_5s | 1272-141231-0017, 1272-128104-0001 | 0.00% | 0.00% | 2 of 2 |
| encoder_15s | 11 LibriSpeech clips and JFK | 3.64% | 3.94% | 10 of 12 |
| encoder_15s | fleurs-de | 0.00% | 0.00% | 1 of 1 |

## Decision

Rules, fixed before the run. A shorter window for short takes is adopted only if encoder_5s is at least 40% faster warm than ours, makes no more errors than ours on the two short clips, and encoder_15s English WER is within 0.3 points of ours (so the conversion itself is sound). Replacing ours with encoder_15s is adopted only if it is at least 20% faster warm, its English WER is within 0.3 points of ours and its German WER is no worse. Either way the cost counts against it: each encoder is about 590 MB more to download, a second encoder is a second model in memory next to ours on the phone, and it is a second model source to pin and verify.

Adopt: the shorter-window rule passed. encoder_5s is 69.2% faster warm than ours on the Neural Engine (8.4 ms against 27.3 ms) and 59.0% faster on the CPU (30.5 ms against 74.4 ms); it made no more errors than ours on the two clips that fit it (0.00% against 0.00%, matching our text on both); and encoder_15s's English WER (3.64%) sits within 0.3 points of ours (3.94%, a 0.30-point gap in encoder_15s's favor), so the conversion itself is sound. The full-replacement rule did not pass: encoder_15s is only 3.3% faster warm than ours on the Neural Engine (26.4 ms against 27.3 ms) and 1.7% faster on the CPU (73.1 ms against 74.4 ms), short of the 20% bar, so ours stays the Encoder there. Adopting it waits for a measurement on an iPhone. Nothing changes in the engine today.
