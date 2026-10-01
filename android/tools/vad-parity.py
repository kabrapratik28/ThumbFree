#!/usr/bin/env python3
"""The app's Silero VAD against official Silero v6.2, and the speech check replayed through the app's planner.

  android/tools/vad-parity.py parity            every clip's probabilities against official Silero: mean and max error,
                                                keep/drop
  android/tools/vad-parity.py replay [out.tsv]  the app's chunks (GateReplayTest) and each chunk's check: the sweep,
                                                acceptance

The app's VAD (android/app/src/main/cpp/vad on transcribe.cpp's ggml) is built as a macOS dylib
(android/tools/vad-parity/CMakeLists.txt) and loaded with ctypes: this Mac runs no newly built executable. Building it
applies third_party/patches to the transcribe.cpp checkout, as the app's build does. The oracle is silero_vad.onnx from
the silero-vad 6.2.0 wheel (both pinned by SHA-256, cached in VAD_CACHE) on ONNX Runtime, fed as silero-vad's
OnnxWrapper feeds it: 512-sample windows with the previous 64 samples in front, the last window zero-padded, state reset
per clip. Needs numpy and onnxruntime (a venv), cmake, ninja, git and Xcode's clang; replay also needs Gradle's
JAVA_HOME.

Clips: jfk.wav and PUBLIC's LibriSpeech clips (speech); jfk after silence and noise, quiet, faded in and cut mid-word
(transitions and quiet starts); QUALITY's quiet speech and 113 non-speech clips; tones and taps made here; MUSIC's WAVs
if set; CORPUS's owner clips, the long take and edge clips (numbers only).

replay runs GateReplayTest over the owner clips, the long take, the edge, quiet and non-speech clips, and the owner
clips mixed under steady noise as the energy gate's noise matrix made them (-40 and -35 dBFS, seeds 7 to 9, written to
a temporary directory and deleted). Every chunk the planner sends gets Silero's probabilities and, but for the mixes,
the words the app's Parakeet (the same dylib: transcribe.cpp with third_party/patches, PARAKEET's GGUF, default the
Hugging Face cache's) types for it, padded as Padding.forEngine pads it. Private text is only counted. It prints the
threshold sweep and exits non-zero when the chosen setting breaks the approved contract (CONTRACT below): no owner,
long-take, quiet-clip or phrase word lost, no mix take the energy gate kept lost, no word on the known 19, no loop, no
non-speech word beyond one named clip, and the reviewed evidence counts no worse. Noise can still type a word there.
"""
import csv, ctypes, glob, hashlib, json, os, re, shutil, subprocess, sys, tempfile, wave, zipfile

import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
CORPUS = os.environ.get("CORPUS") or os.path.join(ROOT, "testdata", "private")
QUALITY = os.environ.get("QUALITY") or os.path.join(ROOT, "research", "spikes", "quality")
PUBLIC = os.environ.get("PUBLIC") or os.path.join(ROOT, "testdata", "public-bench")
MUSIC = os.environ.get("MUSIC")
CACHE = os.environ.get("VAD_CACHE") or os.path.join(tempfile.gettempdir(), "thumbfree-vad-parity")
MODEL = os.path.join(ROOT, "android", "app", "src", "main", "assets", "vad", "ggml-silero-v6.2.0.bin")
JFK = os.path.join(ROOT, "android", "app", "src", "androidTest", "assets", "audio", "jfk.wav")
WHEEL = ("https://files.pythonhosted.org/packages/9e/76/12cbd574562511a8914731436b6c584112186bb6bf149e9f952af4940146/"
         "silero_vad-6.2.0-py3-none-any.whl", "8a31f1e58e73cdc20820d842f757e678e0ae1b0ee1cb7b8aa50f20f8fd7f7638")
ONNX_SHA = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
PARAKEET = os.environ.get("PARAKEET") or next(iter(sorted(glob.glob(os.path.expanduser(
    "~/.cache/huggingface/hub/models--handy-computer--*/snapshots/*/parakeet-unified-en-0.6b-Q8_0.gguf")))), "")
PARAKEET_SHA = "4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795"  # Catalog.PARAKEET_UNIFIED_Q8
THRESHOLD, MIN_RUN = 0.15, 2  # SpeechCheck.THRESHOLD and MIN_RUN
THRESHOLDS = (0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50)
RUNS = (1, 2, 3, 4)
SR, WINDOW, CONTEXT = 16_000, 512, 64

# CONTRACT: what replay requires at the chosen setting, as approved, with two waivers:
# the long take keeps every word though only 13 of its 18 chunks are heard (the other 5 carry none), and one public
# noise clip types one word (Silero rates it 0.43 for 21 windows; a threshold that refuses it loses the long take's
# quiet words). So noise can still type a word; the gate fails on any regression from the reviewed result.
# The known 19: non-speech clips the gate still passed before its cold-start change (when the first 3 s needed 12 dB)
# and for which Parakeet typed words when run on the engine directly.
KNOWN_19 = {
    "noise-free-sound-0000-cut1s.wav", "noise-free-sound-0120-cut0.25s.wav", "noise-free-sound-0150.wav",
    "noise-free-sound-0160-cut1s.wav", "noise-free-sound-0180-cut1s.wav", "noise-free-sound-0260-cut0.25s.wav",
    "noise-free-sound-0340-cut0.25s.wav", "noise-free-sound-0340-cut1s.wav", "noise-free-sound-0400-cut1s.wav",
    "noise-free-sound-0420.wav", "noise-free-sound-0450-cut0.25s.wav", "noise-free-sound-0450-cut1s.wav",
    "noise-free-sound-0450.wav", "noise-free-sound-0460-cut1s.wav", "noise-free-sound-0500-cut0.25s.wav",
    "noise-free-sound-0500-cut1s.wav", "noise-free-sound-0600-cut1s.wav", "noise-free-sound-0700-cut0.25s.wav",
    "noise-sound-bible-0060-cut1s.wav",
}
KNOWN_FALSE_POSITIVE, KNOWN_FALSE_WORDS = "noise-free-sound-0260-cut1s.wav", 1  # the one clip allowed a word
LONG0_HEARD, KNOWN_19_REFUSED = 13, 17  # the reviewed evidence counts; fewer fails


def contract(n):
    """The contract's lines for the counts [n] at one setting: (name, value, passes)."""
    return [
        ("owner: takes speech of 150, words lost", f"{n['owner_speech']}, {n['owner_lost']}",
         n["owner_speech"] == 150 and n["owner_lost"] == 0),
        ("long0: words lost", f"{n['long0_lost']} of {n['long0_words']}", n["long0_lost"] == 0),
        (f"long0: chunks heard (reviewed {LONG0_HEARD})", f"{n['long0_heard']} of {n['long0_sent']}", n["long0_heard"] >= LONG0_HEARD),
        ("quiet clips: speech of 4, words lost", f"{n['quiet_speech']}, {n['quiet_lost']}",
         n["quiet_speech"] == 4 and n["quiet_lost"] == 0),
        ("mixes: takes NOISE1a kept that are lost", str(n["mix_lost"]), n["mix_lost"] == 0),
        ("known 19: typed words", str(n["k19_words"]), n["k19_words"] == 0),
        (f"known 19: refused by Silero (reviewed {KNOWN_19_REFUSED})", f"{n['k19_refused']} of 19",
         n["k19_refused"] >= KNOWN_19_REFUSED),
        ("non-speech: loops", str(n["ns_loops"]), n["ns_loops"] == 0),
        ("non-speech: words on the other 112 clips", str(n["ns_other_words"]), n["ns_other_words"] == 0),
        (f"{KNOWN_FALSE_POSITIVE}: words (reviewed {KNOWN_FALSE_WORDS})", str(n["ns_known_words"]),
         n["ns_known_words"] <= KNOWN_FALSE_WORDS),
        ("edge silence/noise/tap: typed words", str(n["edge_words"]), n["edge_words"] == 0),
        ("edge phrase clips: speech of 2, words lost", f"{n['phrase_speech']}, {n['phrase_lost']}",
         n["phrase_speech"] == 2 and n["phrase_lost"] == 0),
    ]


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def speech(probs, threshold=THRESHOLD, run=MIN_RUN):
    """SpeechCheck.isSpeech: at least [run] windows in a row at or over [threshold]."""
    n = 0
    for p in probs:
        n = n + 1 if p >= threshold else 0
        if n >= run:
            return True
    return False


def longest(probs, threshold=THRESHOLD):
    best = n = 0
    for p in probs:
        n = n + 1 if p >= threshold else 0
        best = max(best, n)
    return best


def read(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), "<i2")


def write(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1), w.setsampwidth(2), w.setframerate(SR)
        w.writeframes(pcm.astype("<i2").tobytes())


def floats(pcm):
    return pcm.astype(np.float32) / 32768  # Wav.readFloat


class App:
    """The app's VAD and its Parakeet engine as one dylib (android/tools/vad-parity/CMakeLists.txt), built from this
    checkout."""

    def __init__(self, asr=False):
        build = os.path.join(CACHE, "build")
        subprocess.run(["cmake", "-S", os.path.join(ROOT, "android", "tools", "vad-parity"), "-B", build, "-G", "Ninja",
                        "-DCMAKE_BUILD_TYPE=Release"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["nice", "-n", "10", "cmake", "--build", build, "--target", "vad_parity"], check=True,
                       stdout=subprocess.DEVNULL)
        lib = ctypes.CDLL(os.path.join(build, "libvad_parity.dylib"))
        lib.silero_vad_init.restype, lib.silero_vad_init.argtypes = ctypes.c_void_p, [ctypes.c_char_p]
        lib.silero_vad_detect.restype = ctypes.c_bool
        lib.silero_vad_detect.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
        lib.silero_vad_n_probs.restype, lib.silero_vad_n_probs.argtypes = ctypes.c_int, [ctypes.c_void_p]
        lib.silero_vad_probs.restype, lib.silero_vad_probs.argtypes = ctypes.POINTER(ctypes.c_float), [ctypes.c_void_p]
        lib.asr_load.restype, lib.asr_load.argtypes = ctypes.c_int, [ctypes.c_char_p, ctypes.c_int]
        lib.asr_run.restype = ctypes.c_int
        lib.asr_run.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        self.lib, self.vad = lib, lib.silero_vad_init(MODEL.encode())
        assert self.vad, "silero_vad_init failed"
        if asr:
            assert sha256(PARAKEET) == PARAKEET_SHA, f"{PARAKEET}: not Catalog.PARAKEET_UNIFIED_Q8"
            assert lib.asr_load(PARAKEET.encode(), 4) == 0, "asr_load failed"

    def probs(self, x):
        x = np.ascontiguousarray(x, dtype=np.float32)
        assert self.lib.silero_vad_detect(self.vad, x.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), len(x))
        n = self.lib.silero_vad_n_probs(self.vad)
        return np.ctypeslib.as_array(self.lib.silero_vad_probs(self.vad), (n,)).copy() if n else np.zeros(0, np.float32)

    def text(self, x):
        """Parakeet's text for a chunk, padded as Padding.forEngine pads it."""
        if 0 < len(x) < SR:
            x = np.concatenate([np.zeros(SR // 2, np.float32), x, np.zeros(max(SR // 2, 20_000 - SR // 2 - len(x)),
                                                                          np.float32)])
        x = np.ascontiguousarray(x, dtype=np.float32)
        out = ctypes.create_string_buffer(1 << 16)
        assert self.lib.asr_run(x.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), len(x), out, len(out)) == 0
        return out.value.decode("utf-8", "replace")


class Onnx:
    """Official Silero v6.2 on ONNX Runtime, one thread, fed like silero-vad's OnnxWrapper.audio_forward."""

    def __init__(self):
        import onnxruntime
        os.makedirs(CACHE, exist_ok=True)
        wheel = os.path.join(CACHE, os.path.basename(WHEEL[0]))
        if not os.path.exists(wheel):
            subprocess.run(["curl", "-fsSL", "-o", wheel, WHEEL[0]], check=True)  # the system's certificates
        assert sha256(wheel) == WHEEL[1], "silero-vad 6.2.0 wheel hash"
        onnx = os.path.join(CACHE, "silero_vad.onnx")
        with zipfile.ZipFile(wheel) as z, open(onnx, "wb") as f:
            f.write(z.read("silero_vad/data/silero_vad.onnx"))
        assert sha256(onnx) == ONNX_SHA, "silero_vad.onnx hash"
        opts = onnxruntime.SessionOptions()
        opts.inter_op_num_threads = opts.intra_op_num_threads = 1
        self.session = onnxruntime.InferenceSession(onnx, sess_options=opts, providers=["CPUExecutionProvider"])

    def probs(self, x):
        x = np.asarray(x, dtype=np.float32)
        if len(x) % WINDOW:
            x = np.concatenate([x, np.zeros(WINDOW - len(x) % WINDOW, np.float32)])
        state, context, out = np.zeros((2, 1, 128), np.float32), np.zeros((1, CONTEXT), np.float32), []
        for i in range(0, len(x), WINDOW):
            inp = np.concatenate([context, x[None, i:i + WINDOW]], axis=1)
            p, state = self.session.run(None, {"input": inp, "state": state, "sr": np.array(SR, np.int64)})
            context = inp[:, -CONTEXT:]
            out.append(p[0, 0])
        return np.array(out, np.float32)


def made_clips():
    """Transitions, quiet starts, tones and taps, from jfk.wav and fixed seeds."""
    rng = np.random.default_rng(1)
    jfk = read(JFK).astype(np.float64)
    noise = lambda s, dbfs: rng.standard_normal(int(s * SR)) * 32768 * 10 ** (dbfs / 20)
    clip = lambda x: np.clip(np.round(x), -32768, 32767).astype(np.int16)
    t = np.arange(2 * SR) / SR
    fade = np.concatenate([np.logspace(-3, 0, SR // 2), np.ones(len(jfk) - SR // 2)])
    tap = np.zeros(SR // 4)
    tap[1000:1040] = rng.standard_normal(40) * 12_000 * np.exp(-np.arange(40) / 8)
    taps = np.zeros(3 * SR)
    for at in (4_000, 21_000, 33_000):
        taps[at:at + 40] = rng.standard_normal(40) * 16_000 * np.exp(-np.arange(40) / 6)
    return {
        "transition": {
            "silence-1s-then-jfk": clip(np.concatenate([np.zeros(SR), jfk])),
            "silence-2s-then-jfk-quiet": clip(np.concatenate([np.zeros(2 * SR), jfk * 10 ** (-30 / 20)])),
            "noise-50dBFS-1s-then-jfk": clip(np.concatenate([noise(1, -50), jfk])),
            "noise-40dBFS-2s-then-jfk-under-it": clip(np.concatenate([noise(2, -40), jfk + noise(len(jfk) / SR, -40)])),
            "jfk-then-silence-2s": clip(np.concatenate([jfk, np.zeros(2 * SR)])),
        },
        "quiet-start": {
            "jfk-40dB": clip(jfk * 10 ** (-40 / 20)),
            "jfk-faded-in": clip(jfk * fade),
            "jfk-cut-mid-word": clip(jfk[12_345:]),
            "jfk-from-first-sample-40dB-then-noise": clip(np.concatenate([jfk * 10 ** (-40 / 20), noise(1, -55)])),
        },
        "tone": {
            "tone-440Hz-20dBFS": clip(np.sin(2 * np.pi * 440 * t) * 32768 * 0.1),
            "tone-1kHz-40dBFS": clip(np.sin(2 * np.pi * 1000 * t) * 32768 * 0.01),
            "sweep-100Hz-4kHz": clip(np.sin(2 * np.pi * (100 * t + (3900 / 4) * t ** 2)) * 32768 * 0.1),
            "chord-and-beeps": clip((np.sin(2 * np.pi * 261.6 * t) + np.sin(2 * np.pi * 329.6 * t) +
                                     np.sin(2 * np.pi * 392 * t)) * 3000 * (np.sin(2 * np.pi * 2 * t) > 0)),
        },
        "tap": {"tap-0.25s": clip(tap), "taps-3s": clip(taps), "taps-3s-in-noise": clip(taps + noise(3, -55))},
    }


def parity_clips():
    """category -> [(name, pcm)]."""
    out = {"speech": [("jfk.wav", read(JFK))] + [(os.path.basename(p), read(p))
                                               for p in sorted(glob.glob(os.path.join(PUBLIC, "*.wav"))) if "tone" not in p]}
    for category, clips in made_clips().items():
        out[category] = list(clips.items())
    manifest = os.path.join(QUALITY, "data", "silence_noise", "manifest.tsv")
    if os.path.isfile(manifest):  # the public noise clips (QUALITY), when this checkout has them
        with open(manifest, newline="") as f:
            rows = list(csv.DictReader(f, delimiter="\t"))
        out["quiet-speech"] = [(os.path.basename(r["file"]), read(r["file"])) for r in rows if r["category"] == "quiet-speech"]
        out["noise"] = [(os.path.basename(r["file"]), read(r["file"])) for r in rows if r["category"] != "quiet-speech"]
    if MUSIC:
        out["music"] = [(os.path.basename(p), read(p)) for p in sorted(glob.glob(os.path.join(MUSIC, "*.wav")))]
    if os.path.isdir(CORPUS):  # private: names are not printed
        out["owner (private)"] = [(p, read(p)) for p in sorted(glob.glob(os.path.join(CORPUS, "desktop", "*.wav")))]
        out["long (private)"] = [(p, read(p)) for p in [os.path.join(CORPUS, "long", "low-snr-0.wav")]]
        out["edge (private)"] = [(p, read(p)) for p in sorted(glob.glob(os.path.join(CORPUS, "edge", "*.wav")))]
    return out


def self_checks(app):
    """The per-chunk reset and the last-window padding, on the dylib."""
    x = floats(read(JFK))
    a, b = x[:40_000], x[40_000:100_000]
    fresh = app.probs(b)
    app.probs(a)
    assert np.array_equal(app.probs(b), fresh), "a detect depends on the one before it"
    n = 5 * WINDOW + 100
    part = app.probs(x[:n])
    assert len(part) == 6, len(part)
    assert np.array_equal(part, app.probs(np.concatenate([x[:n], np.zeros(WINDOW - 100, np.float32)])))
    assert len(app.probs(x[:1])) == 1 and len(app.probs(x[:0])) == 0
    print("self-checks: each detect starts fresh; a partial last window is zero-padded (n=2660 -> 6 windows)")


def parity():
    ggml, onnx = App(), Onnx()
    self_checks(ggml)
    print(f"\nkeep/drop at threshold {THRESHOLD} with {MIN_RUN} windows in a row; errors are |ggml - onnx| per window")
    print(f"{'set':18} clips  windows  mean_err  worst_clip_mean  max_err  keep_ggml keep_onnx disagree")
    total, worst, disagree = [], 0.0, 0
    for category, clips in parity_clips().items():
        errs, clip_means, keeps = [], [], [0, 0, 0]
        for _, pcm in clips:
            a, b = ggml.probs(floats(pcm)), onnx.probs(floats(pcm))
            assert len(a) == len(b)
            d = np.abs(a - b)
            errs.append(d)
            clip_means.append(d.mean() if len(d) else 0.0)
            ka, kb = speech(a), speech(b)
            keeps[0] += ka
            keeps[1] += kb
            keeps[2] += ka != kb
        d = np.concatenate(errs)
        total.append(d)
        worst = max(worst, max(clip_means))
        disagree += keeps[2]
        print(f"{category:18} {len(clips):5} {len(d):8} {d.mean():9.5f} {max(clip_means):16.5f} {d.max():8.5f} "
              f"{keeps[0]:9} {keeps[1]:9} {keeps[2]:8}")
    d = np.concatenate(total)
    print(f"{'all':18} {'':5} {len(d):8} {d.mean():9.5f} {worst:16.5f} {d.max():8.5f} {'':9} {'':9} {disagree:8}")
    ok = d.mean() <= 0.005 and worst <= 0.005 and d.max() <= 0.02 and disagree == 0
    sys.exit(0 if ok else "FAIL: over 0.005 mean, over 0.02 max, or a keep/drop disagreement")


# --- replay ---

def mixes(tmp):
    """The private clips mixed under steady noise at -40 and -35 dBFS (seeds 7 to 9), as the energy gate's noise matrix
    made them: (set, path) rows."""
    def levels(pcm):
        n = len(pcm) // 480
        x = pcm[: n * 480].astype(np.int64).reshape(n, 480)
        with np.errstate(divide="ignore"):
            return (20 * np.log10(np.sqrt((x * x).sum(axis=1) / 480) / 32768)).astype(np.float32)

    rows = []
    owners = sorted(glob.glob(os.path.join(CORPUS, "desktop", "*.wav")))
    for seed in (7, 8, 9):
        rng = np.random.default_rng(seed)
        gauss = lambda ms, dbfs: np.clip(np.round(rng.standard_normal(ms * 16) * 32768 * 10 ** (dbfs / 20)), -32768,
                                         32767).astype(np.int16)
        gauss(30, -74), gauss(30, -72)  # the Pixel ramp matrix.py draws first, so every seed's noise matches it
        z = lambda ms: np.zeros(ms * 16, np.int16)
        for i, path in enumerate(owners):
            pcm = read(path)
            lv = levels(pcm)
            onset = next((j for j, x in enumerate(lv) if x >= -45), 0)
            last = max(j for j, x in enumerate(lv) if x >= -45)
            core = pcm[onset * 480:(last + 1) * 480]
            if seed == 7:  # matrix.py draws noise for the -60 dBFS tails of seed 7's quiet variants first
                gauss(350, -60), gauss(350, -60)
            for nd in (-55, -50, -45, -40, -35):
                for variant, parts in (("frame0", [core, z(350)]), ("lead300", [z(300), core, z(350)]),
                                       ("lead3s", [z(3000), core, z(350)])):
                    body = np.concatenate(parts).astype(np.int32)
                    body += gauss(len(body) // 16 + 1, nd)[: len(body)]
                    if nd in (-40, -35):
                        out = os.path.join(tmp, f"mix{nd}-{variant}-s{seed}-{i:03d}.wav")
                        write(out, np.clip(body, -32768, 32767))
                        rows.append((f"mix{nd}-{variant}-s{seed}", out))
    return rows


def gate_replay(rows, out):
    listing = out + ".list"
    with open(listing, "w") as f:
        f.writelines(f"{s}\t{p}\n" for s, p in rows)
    try:
        subprocess.run(["./gradlew", ":app:testDebugUnitTest", "--tests", "io.github.kabrapratik28.thumbfree.core.audio.GateReplayTest",
                        "--rerun"], cwd=os.path.join(ROOT, "android"), check=True, stdout=subprocess.DEVNULL,
                       env={**os.environ, "GATE_REPLAY": listing, "GATE_REPLAY_OUT": out})
    finally:
        os.remove(listing)
    with open(out, newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def chunks(row, n):
    """The planner's chunks of a take of n samples: (from, to, sent)."""
    cuts = [] if row["cuts"] == "-" else [int(c) for c in row["cuts"].split(",")]
    bounds = [0] + cuts + [n]
    sent = [s == "1" for s in row["chunk_sent"].split(",")]
    assert len(sent) == len(bounds) - 1
    return [(a, b, s) for a, b, s in zip(bounds, bounds[1:], sent)]


def replay(out):
    app = App(asr=True)
    base = [("owner", p) for p in sorted(glob.glob(os.path.join(CORPUS, "desktop", "*.wav")))]
    base.append(("long0", os.path.join(CORPUS, "long", "low-snr-0.wav")))
    base += [("edge", p) for p in sorted(glob.glob(os.path.join(CORPUS, "edge", "*.wav")))]
    with open(os.path.join(QUALITY, "data", "silence_noise", "manifest.tsv"), newline="") as f:
        base += [("quiet" if r["category"] == "quiet-speech" else "nonspeech", r["file"])
                 for r in csv.DictReader(f, delimiter="\t")]
    tmp = tempfile.mkdtemp(prefix="vad-mixes-")
    try:
        rows = base + mixes(tmp)
        replayed = gate_replay(rows, out)
        # Every chunk the planner sends: Silero's probabilities, of the chunk's audio as EngineService reads it, and
        # (not for the mixes) Parakeet's words and whether they loop. Private text is only counted.
        results = []  # (set, name, gate take decision, [Chunk])
        for (s, path), row in zip(rows, replayed):
            pcm = read(path)
            # GateReplayTest and WavChunks read a 44-byte header
            assert row["file"] == os.path.basename(path) and os.path.getsize(path) - 44 == 2 * len(pcm), path
            cs = []
            for a, b, sent in chunks(row, len(pcm)):
                x = floats(pcm[a:b])
                words = norm(app.text(x)) if sent and not s.startswith("mix") else []
                cs.append(Chunk(a, b, sent, app.probs(x) if sent else None, len(words), loops(words),
                                " ".join(words) if s == "nonspeech" else None))
            results.append((s, os.path.basename(path), row["take_speech"] == "1", cs))
    finally:
        shutil.rmtree(tmp)
    report(results)


class Chunk:
    def __init__(self, a, b, sent, probs, words, loop, text):
        self.a, self.b, self.sent, self.probs, self.words, self.loop, self.text = a, b, sent, probs, words, loop, text

    def heard(self, t=THRESHOLD, k=MIN_RUN):
        return self.probs is not None and speech(self.probs, t, k)


def norm(text):
    """normalizeTranscript's words: lowercase, anything but a-z, 0-9 and space becomes a space."""
    return re.sub(r"[^a-z0-9 ]", " ", text.lower()).split()


def loops(words):
    """A word said 3 times or more in a row, as in the looped output on the public noise."""
    return any(words[i] == words[i + 1] == words[i + 2] for i in range(len(words) - 2))


def report(results):
    by = {}
    for r in results:
        by.setdefault(r[0], []).append(r)
    with open(os.path.join(QUALITY, "reports", "silence_noise.jsonl")) as f:
        filled = {j["file"].split("__", 1)[1]: j["hyp"].strip() not in ("", "(empty)")
                  for j in map(json.loads, f) if j["model"] == "unified"}
    # The direct-engine report's false outputs whose take the gate passes: the known 19 and this gate's.
    false19 = [r for r in by["nonspeech"] if r[1] in KNOWN_19]
    assert len(false19) == 19, "the known 19 are not all in QUALITY's manifest"
    false_now = [r for r in by["nonspeech"] if r[2] and filled[r[1]]]
    mixsets = sorted(k for k in by if k.startswith("mix"))
    edge_ns = [r for r in by["edge"] if r[1] in ("silence-3s.wav", "noise-3s.wav", "tap-0.25s.wav")]
    edge_phrase = [r for r in by["edge"] if r not in edge_ns]
    take = lambda r, t, k: any(c.heard(t, k) for c in r[3])
    lost = lambda rs, t, k: sum(c.words for r in rs for c in r[3] if c.sent and not c.heard(t, k))
    typed = lambda rs, t, k: sum(c.words for r in rs for c in r[3] if c.heard(t, k))
    looped = lambda rs, t, k: sum(c.loop for r in rs for c in r[3] if c.heard(t, k))
    mix_lost = lambda t, k: sum(r[2] and not take(r, t, k) for s in mixsets for r in by[s])
    long0 = by["long0"][0][3]

    def lines(t, k):
        """The contract's lines at one setting."""
        known = [r for r in by["nonspeech"] if r[1] == KNOWN_FALSE_POSITIVE]
        others = [r for r in by["nonspeech"] if r[1] != KNOWN_FALSE_POSITIVE]
        return contract(dict(
            owner_speech=sum(take(r, t, k) for r in by["owner"]), owner_lost=lost(by["owner"], t, k),
            long0_lost=lost(by["long0"], t, k), long0_words=sum(c.words for c in long0),
            long0_heard=sum(c.sent and c.heard(t, k) for c in long0), long0_sent=sum(c.sent for c in long0),
            quiet_speech=sum(take(r, t, k) for r in by["quiet"]), quiet_lost=lost(by["quiet"], t, k),
            mix_lost=mix_lost(t, k), k19_words=typed(false19, t, k),
            k19_refused=sum(not take(r, t, k) for r in false19), ns_loops=looped(by["nonspeech"], t, k),
            ns_other_words=typed(others, t, k), ns_known_words=typed(known, t, k), edge_words=typed(edge_ns, t, k),
            phrase_speech=sum(take(r, t, k) for r in edge_phrase), phrase_lost=lost(edge_phrase, t, k),
        ))

    print(f"Sweep over {len(THRESHOLDS) * len(RUNS)} settings. owner: takes speech of 150, words lost; long0: chunks "
          f"refused of {sum(c.sent for c in long0)}, words lost; quiet: words lost; mixes: takes NOISE1a kept that are "
          f"lost; non-speech: clips heard of 113, typed words; the known 19 and this gate's {len(false_now)}: typed words;"
          f" edge silence/noise/tap: typed words")
    print("thr  run owner own_lost long0_ref long0_lost quiet_lost mix_lost ns_heard ns_words f19_words fnow_words "
          "edge_words  all")
    for t in THRESHOLDS:
        for k in RUNS:
            print(f"{t:4.2f} {k:3} {sum(take(r, t, k) for r in by['owner']):5} {lost(by['owner'], t, k):8} "
                  f"{sum(c.sent and not c.heard(t, k) for c in long0):9} {lost(by['long0'], t, k):10} "
                  f"{lost(by['quiet'], t, k):10} {mix_lost(t, k):8} {sum(take(r, t, k) for r in by['nonspeech']):8} "
                  f"{typed(by['nonspeech'], t, k):8} {typed(false19, t, k):9} {typed(false_now, t, k):10} "
                  f"{typed(edge_ns, t, k):10}  {'PASS' if all(p for *_, p in lines(t, k)) else '-'}")

    heard_speech = [c for s in ("owner", "long0", "quiet") for r in by[s] for c in r[3] if c.words and c.heard()]
    print(f"\nMargin at {THRESHOLD}: of the {len(heard_speech)} owner, long0 and quiet chunks with words, the shortest "
          f"longest run over the threshold is {min(longest(c.probs) for c in heard_speech)} windows and the lowest "
          f"peak {min(c.probs.max() for c in heard_speech):.3f}")
    print("The long take's chunks: Silero peak, longest run over the threshold, words")
    for i, c in enumerate(long0):
        print(f"  {i:2} {c.a / SR:6.1f}-{c.b / SR:6.1f} s  peak {c.probs.max():.3f}  run {longest(c.probs):3}  "
              f"words {c.words:3}  {'heard' if c.heard() else 'refused'}")

    print(f"\nMixes: takes speech of 150, NOISE1a's gate -> Silero at {THRESHOLD}/{MIN_RUN}, and takes NOISE1a kept "
          "that Silero loses")
    for s in mixsets:
        print(f"  {s:22} {sum(r[2] for r in by[s]):3} -> {sum(take(r, THRESHOLD, MIN_RUN) for r in by[s]):3}   "
              f"lost {sum(r[2] and not take(r, THRESHOLD, MIN_RUN) for r in by[s])}")

    print("\nNon-speech clips: gate take decision, in the known 19, then per sent chunk the Silero peak/longest run over "
          "the threshold and the words Parakeet types when Silero hears it:")
    for r in by["nonspeech"]:
        parts = "  ".join(f"{c.probs.max():.3f}/{longest(c.probs)}" + (f' "{c.text}"' if c.heard() and c.text else "")
                          if c.sent and len(c.probs) else "-" for c in r[3])
        print(f"  {r[1]:42} gate={int(r[2])} {'19' if r[1] in KNOWN_19 else '  '}  {parts}")

    # The energy gate's rule: the gate's take decision, then every sent chunk is transcribed.
    before = lambda rs: [c for r in rs if r[2] for c in r[3] if c.sent]
    after = lambda rs: [c for r in rs for c in r[3] if c.heard()]
    print(f"\nWhat NOISE1a's rule (the gate decides the take, every sent chunk is transcribed) types, against Silero at "
          f"{THRESHOLD}/{MIN_RUN}: words, clips with words, loops")
    for name, rs in (("non-speech", by["nonspeech"]), ("edge silence/noise/tap", edge_ns)):
        clips = lambda pick: sum(any(c.words for c in pick([r])) for r in rs)
        print(f"  {name:24} {sum(c.words for c in before(rs)):3} words on {clips(before):2} clips, "
              f"{sum(c.loop for c in before(rs))} loops -> {sum(c.words for c in after(rs)):3} words on {clips(after):2} "
              f"clips, {sum(c.loop for c in after(rs))} loops")

    print(f"\nThe reviewed contract at threshold {THRESHOLD}, {MIN_RUN} windows in a row:")
    results_ok = True
    for name, value, ok in lines(THRESHOLD, MIN_RUN):
        results_ok &= ok
        print(f"  {name:52} {value:12} {'PASS' if ok else 'FAIL'}")
    sys.exit(0 if results_ok else "FAIL: the chosen setting breaks the reviewed contract")


if __name__ == "__main__":
    assert speech([0.1, 0.2, 0.3], 0.15, 2) and not speech([0.2, 0.1, 0.2], 0.15, 2) and longest([0.2, 0.2, 0, 0.2]) == 2
    assert norm("It's 5.") == ["it", "s", "5"] and loops(["a", "b", "b", "b"]) and not loops(["a", "a", "b", "a"])
    reviewed = dict(owner_speech=150, owner_lost=0, long0_lost=0, long0_words=215, long0_heard=13, long0_sent=18,
                    quiet_speech=4, quiet_lost=0, mix_lost=0, k19_words=0, k19_refused=17, ns_loops=0, ns_other_words=0,
                    ns_known_words=1, edge_words=0, phrase_speech=2, phrase_lost=0)
    assert all(ok for *_, ok in contract(reviewed))
    for worse in (dict(ns_known_words=2), dict(ns_other_words=1), dict(ns_loops=1), dict(long0_heard=12),
                  dict(k19_refused=16), dict(k19_words=1), dict(long0_lost=1), dict(owner_lost=1), dict(mix_lost=1)):
        assert not all(ok for *_, ok in contract({**reviewed, **worse})), worse
    if sys.argv[1:2] == ["parity"]:
        parity()
    elif sys.argv[1:2] == ["replay"]:
        replay(os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.join(CORPUS, "results", "vad-replay.tsv"))
    else:
        sys.exit(__doc__)
