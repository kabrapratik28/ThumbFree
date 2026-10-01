# Public test audio

- `1272-*.wav`: LibriSpeech dev-clean, speaker 1272 (via hf-internal-testing/librispeech_asr_dummy). CC BY 4.0, Panayotov et al. 2015, openslr.org/12.
- `jfk.wav`: an excerpt of John F. Kennedy's inaugural address, a US government work in the public domain.
- `fleurs-de.wav`: FLEURS (google/fleurs) German test clip 10058299886985225661, revision 70bb2e84b976b7e960aa89f1c648e09c59f894dd. CC BY 4.0.

All files are 16 kHz mono 16-bit PCM WAV, rebuilt by `tools/fetch-public-audio.py`. Private recordings never go in this repository.

## Reference transcripts

- `oracle-fluidaudio-v2.json`, `oracle-fluidaudio-v3.json`: the exact text FluidAudio (Apache 2.0, commit 20d4f0b) produces for each clip with the pinned Parakeet v2 and v3 Core ML models on `.cpuAndNeuralEngine`, a fresh decoder state per clip. Clips longer than 15 s (240,000 samples) went through FluidAudio's own chunking, so only clips of 15 s or less are exact-parity references for ThumbFree's runner.

## Speech check reference

- `jfk-silero-v6.2-onnx.txt`: official Silero VAD v6.2 speech probabilities for `jfk.wav` (`silero_vad.onnx` of silero-vad 6.2.0, MIT, on ONNX Runtime), one per 512-sample window, copied from the Android app's test assets (`android/app/src/androidTest/assets/vad/`).
