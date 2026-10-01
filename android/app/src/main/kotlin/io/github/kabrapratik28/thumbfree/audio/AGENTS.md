# audio: capture, the microphone service and audio focus

`Recorder` runs a take: it opens the WAV, then the microphone (`AudioRecordSource`), reads 20 ms blocks at 16 kHz mono
16-bit, feeds the speech gate and the chunk planner (`core/audio`), and writes every sample to
`filesDir/recordings/<session>.wav`. `RecordingService` is the microphone foreground service each take needs,
`AudioFocus` pauses other media while the user speaks, and `PcmRing` hands samples between threads.

## Invariants

- The WAV keeps every sample read. The gate and the planner only judge frames, and the engine's padding for short clips
  (`core/audio/Padding.kt`) never reaches the file. The only samples added are the zeros that fill an early stop tail.
- The microphone opens only after the foreground service has started (`ForegroundListener.onForegroundStarted`). A
  background app without that service records silence, and Android 14 and newer may refuse to start it.
- A take refuses to start with less than 64 MiB free (`WavWriter.MIN_FREE_BYTES`). The WAV is synced at chunk
  boundaries and at the end, not on every write.
- Reads never block: a read with nothing ready waits 10 ms and returns 0, so the capture loop can check its stall rule,
  the stop tail and cancel between reads. Every microphone failure becomes a `CaptureException` with its `Code`.
- The stop tail ends when the user had already paused, when sound has stopped for `HANGOVER_MS`, or at the 350 ms cap.
  A tail that ends early is filled with zeros to the length the cap would have recorded, so the engine hears the
  same length as before; the microphone still closes early.
- Audio focus is transient and exclusive for the take. It never changes a volume or mutes the microphone; a loss to
  another app (a call) stops the take, as a stop tap would; a request to duck does not.
- `PcmRing` has exactly one writer thread and one reader thread. It needs no lock; adding a second writer breaks it.

## Testing

- Host: `RecorderTest` (with `FakeAudioSource`), `RecordingServiceTest`, `AudioFocusTest`, `PcmRingTest`,
  `AudioRecordSourcePermissionTest`, and the gate and planner tests in `core/audio`.
- Device: `AudioRecordSourceTest` for the real microphone. `android/tools/fgs-gate.sh <serial>` checks that a tap on the
  bubble over another app starts the service and captures real audio while none of our activities is visible.

## Pitfalls

- A blocking `AudioRecord.read` on a stalled device may never return; keep reads non-blocking.
- After `DEVICE_LOST`, `start()` builds a new `AudioRecord`; don't reuse the old one.
- The phone's own screen recorder with audio competes for the microphone, and the take then stops with its
  microphone-taken code. Record demos with the computer's microphone instead.
