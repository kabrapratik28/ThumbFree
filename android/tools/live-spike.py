#!/usr/bin/env python3
"""Live preview prototype: can ThumbFree type words while you speak? Parakeet Unified's buffered stream against today's
offline run.

  android/tools/live-spike.py run JOB...    JOB is "offline" or a stream config L-C-R in 80 ms encoder frames (CONFIGS);
                                            each clip's result goes to LIVE_CACHE/results/JOB.jsonl, and a rerun resumes
  android/tools/live-spike.py bench         Mac compute, THREADS threads: offline and every config, interleaved, REPS
                                            times on the public clips (the Pixel's three reference clips among them)
  android/tools/live-spike.py score         accuracy, lag, compute and stability from the finished runs and the bench
  android/tools/live-spike.py memory        long takes (1, 5, 15 min) offline, streamed, and both on two sessions: CPU
                                            seconds, peak RSS, chunk cost over time (each run in its own process)
  android/tools/live-spike.py verify        patches 0014/0015: 70-13-4 streams of the first VERIFY_CLIPS clips, without
                                            and with a persistent pool, against a finished run's rows (LIVE_REF): text,
                                            commits, tentatives
  android/tools/live-spike.py abort         an abort in the encoder and in each decode of a chunk: status 13, how soon
                                            the feed returns, and a new stream after it
  android/tools/live-spike.py trim          patch 0015 over TRIM_MIN minutes of public speech, trimmed and untrimmed
                                            (TRANSCRIBE_STREAM_KEEP_PCM=1), each in its own process: identical commits
                                            and tentatives after every chunk, most audio held and allocated, peak RSS

More jobs: gate-L-C-R streams the audio a stateful VAD gate passes (GATE); pauses-(offline|L-C-R|
gate-L-C-R) runs PAUSE_TAKES takes of three test-clean utterances with GAP_S between them, in silence and under steady
noise; noise-(offline|L-C-R|gate-L-C-R) runs 113 public non-speech clips (QUALITY).

Clips, public only: PUBLIC's 11 clips and a fixed LibriSpeech sample (BAKEOFF: 150 test-clean and 150 test-other
utterances up to 19 s, drawn with seed 2). The engine is the app's: android/tools/live-spike's dylib
(transcribe.cpp with third_party/patches, CPU, loaded with ctypes: this Mac runs no newly built executable) and
PARAKEET's GGUF. Offline runs use engine_jni.cpp's parameters on the whole clip, as the earlier WER gates did. Streams
use the same parameters with parakeet-unified's (left, chunk, right) extension and the AUTO commit policy, fed 20 ms
reads (AudioRecordSource's) and finalized at the clip's end.

Streams run with TRANSCRIBE_STREAM_TENTATIVE=1 (third_party/patches/0013): each chunk's right-context frames give a
tentative tail; the committed text is the same as without it.

WER uses the earlier gates' normalization (normalizeTranscript, as in the device tests) on the final committed text. A word
lands when the committed text holds it in its final (normalized) form; it can be typed with h words held back once the
committed text reaches word i + h (or at finalize): h = 1 holds back only a word that may still grow, h = 3 the two
words a custom-word or repeat match could still join. It shows in a preview when the committed text plus the tentative
tail first agree with the final text up to it. Its end in the audio is the end of its last alphanumeric token in the
offline run's token timestamps. Chunk compute comes from a linear fit (per config, ms against the window the encoder
sees) to the bench; for the Pixel 10, that fit times the ratio of the Pixel's offline engine p50 (PIXEL_MS) to the
Mac's bench median on the same clip (ANCHOR, JFK by default), and "Pixel-hi" uses pixel_local instead. Chunks run when
their audio is in and the previous chunk is done; finalize runs when the audio ends.
"""
import csv, ctypes, fcntl, glob, json, os, random, re, shutil, statistics, subprocess, sys, tempfile, time
import wave

import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
PUBLIC = os.environ.get("PUBLIC") or os.path.join(ROOT, "testdata", "public-bench")
BAKEOFF = os.environ.get("BAKEOFF") or os.path.join(ROOT, "research", "spikes", "bakeoff", "data")
CACHE = os.environ.get("LIVE_CACHE") or os.path.join(tempfile.gettempdir(), "thumbfree-live-spike")
PARAKEET = os.environ.get("PARAKEET") or next(iter(sorted(glob.glob(os.path.expanduser(
    "~/.cache/huggingface/hub/models--handy-computer--*/snapshots/*/parakeet-unified-en-0.6b-Q8_0.gguf")))), "")
THREADS = int(os.environ.get("THREADS", 4))
RESULTS = os.environ.get("LIVE_RESULTS") or os.path.join(CACHE, "results")  # verify, abort and trim write here
REPS = int(os.environ.get("REPS", 5))
CONFIGS = ("70-13-13", "70-7-7", "70-2-4", "70-13-4")  # (left, chunk, right) frames: the model's menu is L 70,
# C 1/2/7/13, R 0/1/2/3/4/7/13; the first three are published rows, 70-13-4 keeps 13's chunk with 2-4's lookahead
PIXEL_MS = {"1272-141231-0017.wav": 201, "jfk.wav": 480, "1272-128104-0004.wav": 1378}  # engine p50, current defaults
PIXEL_S = (3.73, 11.0, 29.4)  # those clips' lengths
ANCHOR = os.environ.get("ANCHOR", "jfk.wav")
QUALITY = os.environ.get("QUALITY") or os.path.join(ROOT, "research", "spikes", "quality")
VAD_MODEL = os.path.join(ROOT, "android", "app", "src", "main", "assets", "vad", "ggml-silero-v6.2.0.bin")
VAD_WINDOW = 512  # samples per Silero probability here (32 ms)
GATE = dict(threshold=0.3, prefill_ms=450, onset_ms=60, hangover_ms=1650)  # a streaming VAD profile
GAP_S, NOISE_DBFS = 3.0, -50  # pause takes: three utterances with 3 s between them, and the same under steady noise
SR, READ, FRAME = 16_000, 320, 1280  # 20 ms reads; one 80 ms encoder frame
MAX_S, PER_SPLIT = 19, 150  # the LibriSpeech sample


def pixel_local(seconds):
    """A pessimistic Pixel model: its offline p50 for a clip that long, joining its three points (and extending the
    3.7 to 11 s line below 3.7 s), so the Pixel's larger cost per run (201 ms for 3.7 s, 2.5 times the Mac's) recurs
    every chunk."""
    ms = [PIXEL_MS[f] for f in ("1272-141231-0017.wav", "jfk.wav", "1272-128104-0004.wav")]
    at0 = ms[0] - (ms[1] - ms[0]) / (PIXEL_S[1] - PIXEL_S[0]) * PIXEL_S[0]
    return float(np.interp(seconds, (0,) + PIXEL_S, [at0] + ms))


sys.dont_write_bytecode = True
os.environ["TRANSCRIBE_STREAM_TENTATIVE"] = "1"  # patch 0013, read by the dylib at each chunk


def norm(text):
    """normalizeTranscript's words: lowercase, anything but a-z, 0-9 and space becomes a space."""
    return re.sub(r"[^a-z0-9 ]", " ", text.lower()).split()


def edits(ref, hyp):
    """Levenshtein distance between two sequences (words, or characters)."""
    d = list(range(len(hyp) + 1))
    for i in range(1, len(ref) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(hyp) + 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
    return d[len(hyp)]


def clips():
    """(set, file, path, reference): PUBLIC's clips, then the LibriSpeech sample (seed 2, in file order)."""
    with open(os.path.join(PUBLIC, "refs.tsv"), newline="") as f:
        out = [("public", r["file"], os.path.join(PUBLIC, r["file"]), r["text"]) for r in csv.DictReader(f, delimiter="\t")]
    rng = random.Random(2)
    for split in ("test-clean", "test-other"):
        with open(os.path.join(BAKEOFF, split, "refs.tsv"), newline="") as f:
            rows = [r for r in csv.DictReader(f, delimiter="\t") if float(r["seconds"]) <= MAX_S]
        out += [(split, r["file"], os.path.join(BAKEOFF, split, r["file"]), r["text"])
                for r in sorted(rng.sample(rows, PER_SPLIT), key=lambda r: r["file"])]
    return out


def read(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float32) / 32768  # Wav.readFloat


class Engine:
    """android/tools/live-spike's dylib, built from this checkout (the build applies third_party/patches, as the app's
    does)."""

    def __init__(self, threads):
        build = os.path.join(CACHE, "build")
        os.makedirs(CACHE, exist_ok=True)
        # One build at a time, and each process loads its own copy: a relink under a running process kills it.
        with open(os.path.join(CACHE, "build.lock"), "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            # LIVE_CMAKE: more -D flags, e.g. a scratch checkout and series (android/tools/live-spike/CMakeLists.txt).
            subprocess.run(["cmake", "-S", os.path.join(ROOT, "android", "tools", "live-spike"), "-B", build,
                            "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release"] + os.environ.get("LIVE_CMAKE", "").split(),
                           check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["nice", "-n", "10", "cmake", "--build", build, "--target", "live_spike"], check=True,
                           stdout=subprocess.DEVNULL)
            path = os.path.join(CACHE, f"live_spike-{os.getpid()}.dylib")
            shutil.copy2(os.path.join(build, "liblive_spike.dylib"), path)
        self.lib = lib = ctypes.CDLL(path)
        os.remove(path)  # the loaded copy stays mapped
        f32, buf = ctypes.POINTER(ctypes.c_float), ctypes.c_char_p
        lib.live_run.argtypes = [f32, ctypes.c_int, ctypes.c_int, buf, ctypes.c_int]
        lib.live_feed.argtypes = [f32, ctypes.c_int, ctypes.POINTER(ctypes.c_int64)]
        lib.live_text.argtypes = [buf, buf, buf, ctypes.c_int]
        assert lib.live_load(PARAKEET.encode(), threads) == 0, "live_load failed"
        self.out = [ctypes.create_string_buffer(1 << 16) for _ in range(3)]
        self.progress = (ctypes.c_int64 * 3)()

    def offline(self, x, tokens=False):
        """(text or token rows, wall ms)."""
        t = time.perf_counter()
        assert self.lib.live_run(x.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), len(x), int(tokens), self.out[0],
                                 len(self.out[0])) == 0
        ms = 1000 * (time.perf_counter() - t)
        text = self.out[0].value.decode()
        if not tokens:
            return text, ms
        rows = [line.split(" ", 3) for line in text.split("\n") if line]
        return [[int(a), int(b), int(w), s] for a, b, w, s in rows], ms

    def pcm(self):
        """The stream's audio buffer: (samples held, samples allocated)."""
        out = (ctypes.c_int64 * 2)()
        self.lib.live_pcm(out)
        return out[0], out[1]

    def stream(self, x, config, arrivals=None, stop_ms=None, pcm=None):
        """Feeds x in 20 ms reads, then finalizes. chunks: [audio ms, wall ms] for each read that ran a chunk, and
        finalize last; commits: [committed bytes, chunk index] each time the committed text grows; snaps: [chunk index,
        full text] each time the full text (committed text and tentative tail) changes. A gated feed passes the audio
        clock time each fed sample arrives at (arrivals, ms) and the stop's (stop_ms); by default sample i arrives at
        (i + 1) / 16 ms and the stop comes when x ends."""
        at_end = lambda end: arrivals[end - 1] if arrivals is not None else end / 16
        stop_ms = len(x) / 16 if stop_ms is None else stop_ms
        assert self.lib.live_begin(*(80 * int(f) for f in config.split("-")), 0) == 0
        committed, chunks, commits, snaps, shown = "", [], [], [], ""
        stats = dict(reads=0, not_prefix=0, full_not_prefix=0, tentative_nonempty=0, tentative_changes=0)
        tentative, audio_committed, uncommitted = "", 0, 0.0
        for i in range(0, len(x) + READ, READ):
            last = i >= len(x)
            block = np.ascontiguousarray(x[i:i + READ]) if not last else None
            t = time.perf_counter()
            ptr = block.ctypes.data_as(ctypes.POINTER(ctypes.c_float)) if block is not None else None
            assert self.lib.live_feed(ptr, 0 if last else len(block), self.progress) == 0
            ms = 1000 * (time.perf_counter() - t)
            at = stop_ms if last else at_end(i + len(block))
            assert self.lib.live_text(*self.out, len(self.out[0])) == 0
            c, tent, full = (b.value.decode() for b in self.out)
            stats["reads"] += 1
            stats["not_prefix"] += not c.startswith(committed)
            stats["full_not_prefix"] += not full.startswith(c)
            stats["tentative_nonempty"] += bool(tent)
            stats["tentative_changes"] += tent != tentative
            ran = last or self.progress[1] != audio_committed
            if pcm is not None:  # the most audio the stream held and allocated
                held, allocated = self.pcm()
                pcm["held"], pcm["allocated"] = max(pcm.get("held", 0), held), max(pcm.get("allocated", 0), allocated)
            if last:
                uncommitted = len(x) / 16 - audio_committed  # audio fed but not decoded when the audio ends
            if ran:
                chunks.append([at, ms])
                audio_committed = self.progress[1]
            if c != committed:
                assert ran, "committed text grew on a read that ran no chunk"
                commits.append([len(c.encode()), len(chunks) - 1])
            if c + tent != shown:
                assert ran, "the text changed on a read that ran no chunk"
                snaps.append([len(chunks) - 1, c + tent])
                shown = c + tent
            committed, tentative = c, tent
            if last:
                break
        timings = (ctypes.c_float * 3)()
        self.lib.live_timings(timings)
        return dict(text=committed, full=full, commits=commits, snaps=snaps, chunks=chunks, stats=stats,
                    uncommitted_ms=uncommitted, fed_ms=len(x) / 16, timings=[round(v, 1) for v in timings])

    def select(self, i):
        """Session i (0 or 1) for the calls that follow: a stream beside offline runs needs its own."""
        assert self.lib.live_select(i) == 0

    def vad_probs(self, x):
        """The app's Silero (v6.2, android/app/src/main/cpp/vad) over x from a fresh state: one probability per 512
        samples. Silero runs forward in time, so these are what a stateful streaming Silero would give."""
        lib, f32 = self.lib, ctypes.POINTER(ctypes.c_float)
        if not hasattr(self, "vad"):
            lib.silero_vad_init.restype, lib.silero_vad_init.argtypes = ctypes.c_void_p, [ctypes.c_char_p]
            lib.silero_vad_detect.restype = ctypes.c_bool
            lib.silero_vad_detect.argtypes = [ctypes.c_void_p, f32, ctypes.c_int]
            lib.silero_vad_n_probs.restype, lib.silero_vad_n_probs.argtypes = ctypes.c_int, [ctypes.c_void_p]
            lib.silero_vad_probs.restype, lib.silero_vad_probs.argtypes = f32, [ctypes.c_void_p]
            self.vad = lib.silero_vad_init(VAD_MODEL.encode())
            assert self.vad, "silero_vad_init failed"
        x = np.ascontiguousarray(x, dtype=np.float32)
        assert lib.silero_vad_detect(self.vad, x.ctypes.data_as(f32), len(x))
        n = lib.silero_vad_n_probs(self.vad)
        return np.ctypeslib.as_array(lib.silero_vad_probs(self.vad), (n,)).copy()


def gate(probs, n):
    """A smoothed VAD gate on a streaming policy: GATE's threshold, prefill, onset and hangover, here in Silero's
    512-sample windows. Returns (sample index ranges it passes, in feed order, each with the audio ms it is passed at).
    At onset it passes its whole prefill buffer, frames already passed included; frames still held at the stop are
    dropped."""
    frames = lambda ms: -(-ms * 16 // VAD_WINDOW)
    prefill, onset, hangover = frames(GATE["prefill_ms"]), frames(GATE["onset_ms"]), frames(GATE["hangover_ms"])
    buf, out, in_speech, hang, count = [], [], False, 0, 0
    for t in range(min(len(probs), -(-n // VAD_WINDOW))):
        buf = (buf + [t])[-(prefill + 1):]
        voice = probs[t] > GATE["threshold"]
        at = min((t + 1) * VAD_WINDOW, n) / 16
        if not in_speech and voice:
            count += 1
            if count >= onset:
                in_speech, hang, count = True, hangover, 0
                out += [(w, at) for w in buf]
        elif in_speech and voice:
            hang = hangover
            out.append((t, at))
        elif in_speech:
            if hang > 0:
                hang -= 1
                out.append((t, at))
            else:
                in_speech = False
        else:
            count = 0
    return [((w * VAD_WINDOW, min((w + 1) * VAD_WINDOW, n)), at) for w, at in out]


def gated(x, ranges):
    """The audio the gate passes and the audio ms each sample of it arrives at."""
    if not ranges:
        return np.zeros(0, np.float32), np.zeros(0)
    audio = np.concatenate([x[a:b] for (a, b), _ in ranges])
    arrivals = np.concatenate([np.full(b - a, at) for (a, b), at in ranges])
    return audio, arrivals


def geometry(config, n):
    """The samples each chunk's encoder sees for a stream of n samples, as emit_buffered_chunk builds its window
    (buf_ctx_add_frames); the last is finalize's, with R of silence after the audio."""
    L, C, R = (int(f) * FRAME for f in config.split("-"))
    ctx = [0, 0, 0]  # left, chunk, right
    read, out = 0, []

    def add(num_new, last):
        ctx[0] += ctx[1]
        ctx[1], ctx[2] = 0, ctx[2] + num_new
        ctx[1], ctx[2] = (ctx[2], 0) if last else (C, ctx[2] - C)
        ctx[0] -= max(sum(ctx) - (L + C + R), 0)

    while read + (C if out else C + R) <= n:
        num_new = C if out else C + R
        add(num_new, False)
        read += num_new
        out.append(sum(ctx))
    add(n - read, True)
    return out + [sum(ctx) + R]


def results(job):
    return os.path.join(CACHE, "results", f"{job}.jsonl")


PAUSE_TAKES = 20  # per gap kind


def pause_takes():
    """(set, name, audio, reference, gaps in ms): the fixed sample's first test-clean utterances three at a time with
    GAP_S of digital silence between them ("pauses-silence"), and the same takes under steady MUSAN noise at NOISE_DBFS
    RMS ("pauses-noise"), where a noise gap is what a VAD gate would drop."""
    utts = [c for c in clips() if c[0] == "test-clean"][:3 * PAUSE_TAKES]
    noise = read(os.path.join(QUALITY, "data", "musan_noise", "noise-free-sound-0160.wav"))
    out = []
    for i in range(0, len(utts), 3):
        group, parts, gaps, at = utts[i:i + 3], [], [], 0
        for j, (_, _, path, _) in enumerate(group):
            if j:
                gaps.append([at / 16, (at + GAP_S * SR) / 16])
                parts.append(np.zeros(int(GAP_S * SR), np.float32))
                at += int(GAP_S * SR)
            parts.append(read(path))
            at += len(parts[-1])
        x = np.concatenate(parts)
        name = "+".join(g[1].removesuffix(".wav") for g in group)
        ref = " ".join(g[3] for g in group)
        bed = np.resize(noise, len(x))
        bed = bed * (10 ** (NOISE_DBFS / 20) / np.sqrt(np.mean(bed.astype(np.float64) ** 2)))
        out += [("pauses-silence", name, x, ref, gaps), ("pauses-noise", name, (x + bed).astype(np.float32), ref, gaps)]
    return out


def noise_clips():
    """(category, file, path): the 113 public non-speech clips (digital silence, MUSAN noise cut and at
    natural length, a tone), from its manifest."""
    with open(os.path.join(QUALITY, "data", "silence_noise", "manifest.tsv"), newline="") as f:
        rows = [r for r in csv.DictReader(f, delimiter="\t") if r["category"] != "quiet-speech"]
    where = lambda p: (os.path.join(QUALITY, p.split("research/spikes/quality/", 1)[1]) if "research/spikes/quality/" in p
                       else os.path.join(PUBLIC, os.path.basename(p)))
    return [(r["category"], os.path.basename(r["file"]), where(r["file"])) for r in rows]


def corpus(kind):
    """(set, name, audio loader, reference, gaps) for a job's corpus: "" (clips()), "pauses" or "noise"."""
    if kind == "pauses":
        return [(s, n, (lambda x=x: x), r, g) for s, n, x, r, g in pause_takes()]
    if kind == "noise":
        return [(s, n, (lambda p=p: read(p)), "", []) for s, n, p in noise_clips()]
    return [(s, n, (lambda p=p: read(p)), r, []) for s, n, p, r in clips()]


def parse(job):
    """job -> (corpus kind, gated, "offline" or a config): [pauses-|noise-][gate-](offline|L-C-R)."""
    kind = next((k for k in ("pauses", "noise") if job.startswith(k + "-")), "")
    rest = job[len(kind) + 1:] if kind else job
    gated_ = rest.startswith("gate-")
    what = rest[5:] if gated_ else rest
    assert what == "offline" or what in CONFIGS, job
    return kind, gated_, what


def run(jobs):
    engine = Engine(THREADS)
    for job in jobs:
        kind, gated_, what = parse(job)
        path = results(job)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        done = set()
        if os.path.exists(path):
            with open(path) as f:
                done = {(r["set"], r["file"]) for r in map(json.loads, f)}
        for s, name, load, _, gaps in corpus(kind):
            if (s, name) in done:
                continue
            x = load()
            if what == "offline":
                text, ms = engine.offline(x)
                tokens, _ = engine.offline(x, tokens=True)
                row = dict(text=text, ms=round(ms, 1), tokens=tokens)
            elif gated_:
                audio, arrivals = gated(x, gate(engine.vad_probs(x), len(x)))
                row = engine.stream(audio, what, arrivals=arrivals, stop_ms=len(x) / 16)
            else:
                row = engine.stream(x, what)
            with open(path, "a") as f:
                f.write(json.dumps(dict(set=s, file=name, audio_ms=len(x) / 16, gaps=gaps, **row)) + "\n")
        print(job, "done", flush=True)


def bench():
    """Interleaved on one engine: each rep runs every public clip offline, then as a stream at every config."""
    engine = Engine(THREADS)
    pub = [(name, read(wav)) for s, name, wav, _ in clips() if s == "public"]
    engine.offline(pub[0][1])  # warm up
    rows = []
    for rep in range(REPS):
        for name, x in pub:
            rows.append(dict(rep=rep, file=name, audio_ms=len(x) / 16, config="offline", ms=engine.offline(x)[1]))
            for config in CONFIGS:
                r = engine.stream(x, config)
                wins = geometry(config, len(x))
                assert len(wins) == len(r["chunks"]), (name, config, len(wins), len(r["chunks"]))
                rows.append(dict(rep=rep, file=name, audio_ms=len(x) / 16, config=config,
                                 chunks=[[w, ms] for w, (_, ms) in zip(wins, r["chunks"])]))
        print("rep", rep, "done", flush=True)
    os.makedirs(os.path.join(CACHE, "results"), exist_ok=True)
    with open(os.path.join(CACHE, "results", "bench.jsonl"), "w") as f:
        f.writelines(json.dumps(r) + "\n" for r in rows)


LONG_MIN = (1, 5, 15)


def long_take(minutes):
    """The LibriSpeech sample's test-clean then test-other utterances in file order, end to end, as whole utterances up
    to [minutes]; and the app planner's chunks of it, approximated: utterances packed until 20 s (at most 30 s)."""
    paths = []
    for split in ("test-clean", "test-other"):
        with open(os.path.join(BAKEOFF, split, "refs.tsv"), newline="") as f:
            paths += [os.path.join(BAKEOFF, split, r["file"]) for r in sorted(csv.DictReader(f, delimiter="\t"),
                                                                            key=lambda r: r["file"])]
    parts, n = [], 0
    for path in paths:
        x = read(path)
        if n + len(x) > minutes * 60 * SR:
            break
        parts.append(x)
        n += len(x)
    chunks, start, at = [], 0, 0
    for x in parts:
        if at - start >= 20 * SR or at + len(x) - start > 30 * SR:
            chunks.append((start, at))
            start = at
        at += len(x)
    return np.concatenate(parts), chunks + [(start, at)]


def memory_one(scenario, minutes):
    """One long-take measurement in a fresh process, printed as JSON: CPU seconds, wall seconds, peak RSS, and for a
    stream each chunk's wall ms. offline: the planner's chunks one after another (today). stream-C: one stream of the
    take. both-C: the stream on a second session, with each planner chunk run offline on the first session as soon as
    its audio is in (the preview beside today's path)."""
    import resource
    engine = Engine(THREADS)
    x, chunks = long_take(minutes)
    rss = lambda: int(subprocess.run(["ps", "-o", "rss=", "-p", str(os.getpid())], capture_output=True,
                                     text=True).stdout) * 1024
    cpu = lambda: sum(resource.getrusage(resource.RUSAGE_SELF)[:2])
    loaded, c0, t0, walls = rss(), cpu(), time.perf_counter(), []
    if scenario == "offline":
        for a, b in chunks:
            engine.offline(np.ascontiguousarray(x[a:b]))
    else:
        config = scenario.split("-", 1)[1]
        both = scenario.startswith("both")
        engine.select(1 if both else 0)
        assert engine.lib.live_begin(*(80 * int(f) for f in config.split("-")), 0) == 0
        pending, committed_ms = list(chunks), 0
        for i in range(0, len(x) + READ, READ):
            last = i >= len(x)
            block = None if last else np.ascontiguousarray(x[i:i + READ])
            t = time.perf_counter()
            ptr = None if last else block.ctypes.data_as(ctypes.POINTER(ctypes.c_float))
            assert engine.lib.live_feed(ptr, 0 if last else len(block), engine.progress) == 0
            if last or engine.progress[1] != committed_ms:
                walls.append(round(1000 * (time.perf_counter() - t), 2))
                committed_ms = engine.progress[1]
            while both and pending and (last or pending[0][1] <= i + READ):
                a, b = pending.pop(0)
                engine.select(0)
                engine.offline(np.ascontiguousarray(x[a:b]))
                engine.select(1)
            if last:
                break
    print(json.dumps(dict(scenario=scenario, minutes=minutes, audio_s=len(x) / SR, chunks=len(chunks),
                          cpu_s=round(cpu() - c0, 2), wall_s=round(time.perf_counter() - t0, 2),
                          rss_loaded_mb=round(loaded / 2 ** 20), peak_rss_mb=round(resource.getrusage(
                              resource.RUSAGE_SELF).ru_maxrss / 2 ** 20), chunk_walls=walls)))


def memory():
    """Every long-take scenario, each in its own process so each peak RSS is its own; to LIVE_CACHE/results."""
    runs = [("offline", m) for m in LONG_MIN] + [("stream-70-13-4", m) for m in LONG_MIN] + \
           [("stream-70-13-13", 15), ("both-70-13-4", 15)]
    os.makedirs(os.path.join(CACHE, "results"), exist_ok=True)
    with open(os.path.join(CACHE, "results", "memory.jsonl"), "w") as f:
        for scenario, minutes in runs:
            out = subprocess.run([sys.executable, os.path.abspath(__file__), "memory-one", scenario, str(minutes)],
                                 check=True, capture_output=True, text=True).stdout
            f.write(out.strip().splitlines()[-1] + "\n")
            f.flush()
            print(scenario, minutes, "done", flush=True)


def pct(values, p):
    return float(np.percentile(values, p)) if len(values) else float("nan")


def load(job):
    with open(results(job)) as f:  # a line still being written by a run is not read
        return {(r["set"], r["file"]): r for r in (json.loads(line) for line in f if line.endswith("\n"))}


def fits(rows):
    """config -> (a, b): chunk ms = a + b * window seconds on the Mac, a least-squares fit over the bench's chunks."""
    out = {}
    for config in CONFIGS:
        pts = np.array([(w / SR, ms) for r in rows if r["config"] == config for w, ms in r["chunks"]])
        b, a = np.polyfit(pts[:, 0], pts[:, 1], 1)
        resid = pts[:, 1] - (a + b * pts[:, 0])
        out[config] = (a, b, float(np.median(np.abs(resid))), len(pts))
    return out


def word_spans(text):
    return [(m.start(), m.end()) for m in re.finditer(r"\S+", text)]


def key(word):
    return "".join(norm(word))


def align(ref, hyp):
    """Levenshtein alignment of two key lists: hyp index -> ref index for matched and substituted words."""
    n, m = len(ref), len(hyp)
    d = [[i + j if i == 0 or j == 0 else 0 for j in range(m + 1)] for i in range(n + 1)]
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]))
    out, i, j = {}, n, m
    while i > 0 and j > 0:
        if d[i][j] == d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            out[j - 1] = i - 1
            i, j = i - 1, j - 1
        elif d[i][j] == d[i - 1][j] + 1:
            i -= 1
        else:
            j -= 1
    return out


def word_ends(tokens):
    """The offline words (key, end ms): the end of each word's last alphanumeric token (or last token)."""
    words = {}
    for t0, t1, w, text in tokens:
        k, end, alnum = words.get(w, ("", 0, False))
        has = bool(re.search(r"[A-Za-z0-9]", text))
        words[w] = (k + text.strip(), t1 if has or not alnum else end, alnum or has)
    return [(key(k), end) for k, end, _ in (words[w] for w in sorted(words))]


HOLDS = (1, 3)


def lags(stream, offline, finish):
    """Per word of the final committed text: dict of chunk finish times (lands, held h for each of HOLDS, preview),
    the word's audio end in ms (None for a word the offline run does not have) and whether it lands at finalize."""
    text = stream["text"].encode()
    spans = word_spans(stream["text"])
    bspans = [(len(stream["text"][:a].encode()), len(stream["text"][:b].encode())) for a, b in spans]  # commits count bytes
    keys = [key(stream["text"][a:b]) for a, b in spans]
    last = len(finish) - 1
    ref = word_ends(offline["tokens"])
    matched = align([k for k, _ in ref], keys)
    shown = [(k, [key(w) for w in full.split()]) for k, full in stream["snaps"]]
    out = []
    for i, (a, b) in enumerate(bspans):
        lands = next(k for n, k in stream["commits"] if n > a and key(text[a:min(b, n)].decode("utf-8", "ignore")) == keys[i])
        times = dict(lands=finish[lands])
        for h in HOLDS:
            start = bspans[i + h][0] if i + h < len(bspans) else None
            times[f"held{h}"] = finish[next((k for n, k in stream["commits"] if start is not None and n > start), last)]
        times["preview"] = finish[next(k for k, words in shown if words[:i + 1] == keys[:i + 1])]
        out.append((times, ref[matched[i]][1] if i in matched else None, lands == last))
    return out


def live_disagreements(stream, offline):
    """For the words committed before the audio ended (what live typing would already have typed): (words, words that
    differ from the offline text normalized, as typed, offline words missing between them). Alignment on normalized
    words; a stream word the offline text lacks counts as differing both ways."""
    text = stream["text"]
    spans = word_spans(text)
    last = len(stream["chunks"]) - 1
    nbytes = [len(text[:b].encode()) for _, b in spans]
    live = sum(1 for n in nbytes if any(c >= n and k < last for c, k in stream["commits"]))
    words = [text[a:b] for a, b in spans]
    off = offline["text"].split()
    skeys, okeys = [key(w) for w in words], [key(w) for w in off]
    matched = align(okeys, skeys)  # stream index -> offline index
    normed = sum(1 for i in range(live) if i not in matched or okeys[matched[i]] != skeys[i])
    typed = sum(1 for i in range(live) if i not in matched or off[matched[i]] != words[i])
    reach = max((matched[i] for i in range(live) if i in matched), default=-1)
    missing = sum(1 for j in range(reach + 1) if j not in set(matched.values()))
    return live, normed, typed, missing


def rewrites(stream):
    """(chunk updates of the shown text, updates that change or drop a shown word rather than add words or letters,
    words changed or dropped). A word that only grows ("Americ" to "Americans") is no rewrite."""
    updates = changed = words = 0
    prev = []
    for _, full in stream["snaps"]:
        cur = [key(w) for w in full.split()]
        bad = sum(1 for j, w in enumerate(prev) if j >= len(cur) or (cur[j] != w and not cur[j].startswith(w)))
        updates += 1
        changed += bad > 0
        words += bad
        prev = cur
    return updates, changed, words


def fed(r):
    """Samples a stream run fed: the whole clip, or what a gate passed."""
    return round((r["fed_ms"] if "fed_ms" in r else r["audio_ms"]) * 16)


def simulate(stream, config, n, cost):
    """Each chunk's finish time (ms on the audio clock) when chunks cost cost(window samples) ms."""
    free, finish, wins = 0.0, [], geometry(config, n)
    assert len(wins) == len(stream["chunks"]), (config, n, len(wins), len(stream["chunks"]))
    for (at, _), w in zip(stream["chunks"], wins):
        free = max(free, at) + cost(w)
        finish.append(free)
    return finish


def score():
    offline = load("offline")
    present = [c for c in CONFIGS if os.path.exists(results(c))]  # a partial run scores the clips every run has
    runs = {c: load(c) for c in present}
    bench_rows = [json.loads(line) for line in open(os.path.join(CACHE, "results", "bench.jsonl"))]
    fit = fits(bench_rows)
    mac_off = {f: statistics.median(r["ms"] for r in bench_rows if r["config"] == "offline" and r["file"] == f)
               for f in {r["file"] for r in bench_rows}}
    ratios = {f: PIXEL_MS[f] / mac_off[f] for f in PIXEL_MS}
    ratio = ratios[ANCHOR]
    audio = {f: next(r["audio_ms"] for r in bench_rows if r["file"] == f) for f in mac_off}
    off_b, off_a = np.polyfit([audio[f] for f in mac_off], [mac_off[f] for f in mac_off], 1)  # Mac offline ms by audio ms
    print(f"Mac offline (bench median, {THREADS} threads): " + ", ".join(
        f"{f} {audio[f] / 1000:.1f} s {mac_off[f]:.0f} ms (RTF {mac_off[f] / audio[f]:.3f}), Pixel/Mac {ratios[f]:.2f}"
        for f in PIXEL_MS))
    print(f"Pixel estimate: Mac ms x {ratio:.2f} ({ANCHOR}); the other reference clips give "
          + ", ".join(f"{v:.2f}" for f, v in ratios.items() if f != ANCHOR))

    sets = {"public": ["public"], "LibriSpeech": ["test-clean", "test-other"], "test-clean": ["test-clean"],
            "test-other": ["test-other"]}
    keys = sorted(set(offline).intersection(*runs.values()))
    print(f"{len(keys)} clips in every run ({sum(k[0] == 'public' for k in keys)} public, "
          f"{sum(k[0] != 'public' for k in keys)} LibriSpeech)")
    refs = {(s, f): norm(r) for s, f, _, r in clips()}
    wer = lambda texts, sel: (sum(edits(refs[k], norm(texts[k])) for k in sel) / sum(len(refs[k]) for k in sel)
                              if sel else float("nan"))
    off_text = {k: offline[k]["text"] for k in keys}
    print("\nAccuracy (final committed text; WER on normalized words; changed = word edits against offline):")
    print("config    " + "".join(f"{n:>24}" for n in sets) + "   LibriSpeech 95% CI of the difference")
    print("offline   " + "".join(f"{100 * wer(off_text, [k for k in keys if k[0] in s]):23.2f}%" for s in sets.values()))
    rng = random.Random(0)
    for config in present:
        st = runs[config]
        text = {k: st[k]["text"] for k in keys}
        cells = []
        for s in sets.values():
            sel = [k for k in keys if k[0] in s]
            d = 100 * (wer(text, sel) - wer(off_text, sel))
            chg = sum(edits(norm(off_text[k]), norm(text[k])) for k in sel)
            cells.append(f"{100 * wer(text, sel):6.2f}% {d:+5.2f} {chg:4d} chg")
        libri = [k for k in keys if k[0] != "public"]
        diffs = sorted(100 * (wer(text, b) - wer(off_text, b)) for b in
                       ([libri[rng.randrange(len(libri))] for _ in libri] for _ in range(2000))) if libri else [0] * 2000
        print(f"{config:10}" + "".join(f"{c:>24}" for c in cells) + f"   [{diffs[50]:+.2f}, {diffs[1949]:+.2f}]")

    print("\nPunctuation and case, which the WER ignores (words as typed; changed = edits against offline's words):")
    print("config    ends . ? !   marks per 100 words   capitalized per 100   changed words, as typed / normalized")
    for config in ["offline"] + present:
        text = off_text if config == "offline" else {k: v["text"] for k, v in runs[config].items()}
        n = sum(len(text[k].split()) for k in keys)
        ends = sum(text[k].rstrip()[-1:] in ".?!" for k in keys)
        marks = sum(len(re.findall(r"[.,?!;:]", text[k])) for k in keys)
        caps = sum(w[:1].isupper() for k in keys for w in text[k].split())
        raw = sum(edits(off_text[k].split(), text[k].split()) for k in keys)
        normed = sum(edits(norm(off_text[k]), norm(text[k])) for k in keys)
        print(f"{config:10}{ends:4d} of {len(keys)} {100 * marks / n:14.1f} {100 * caps / n:20.1f} {raw:18d} / {normed}")

    print("\nCompute (bench; chunk ms = a + b x window s, fit per config; steady state = the full window every chunk):")
    print("config    a_ms  b_ms/s  mad_ms  chunks  Mac: steady RTF  per-clip RTF (public)   Pixel: steady RTF  "
          "per-clip RTF  steady chunk ms / chunk ms   Pixel steady RTF, local model")
    for config in present:
        a, b, mad, npts = fit[config]
        L, C, R = (int(v) * FRAME for v in config.split("-"))
        steady = a + b * (L + C + R) / SR
        per_clip = [sum(ms for _, ms in r["chunks"]) / r["audio_ms"] for r in bench_rows if r["config"] == config]
        print(f"{config:10}{a:4.0f} {b:7.1f} {mad:7.1f} {npts:7d}   {steady / (C / 16):13.3f} {statistics.median(per_clip):14.3f}"
              f" {'':8} {ratio * steady / (C / 16):13.3f} {ratio * statistics.median(per_clip):13.3f}   "
              f"{ratio * steady:6.0f} / {C / 16:.0f} {pixel_local((L + C + R) / SR) / (C / 16):20.3f}")
    off_rtf = [r["ms"] / r["audio_ms"] for r in bench_rows if r["config"] == "offline"]
    print(f"offline   per-clip RTF, Mac {statistics.median(off_rtf):.3f}, Pixel {ratio * statistics.median(off_rtf):.3f}")

    print(f"\nLag, ms from a word's end in the audio to when it can be typed or shown, p50/p90 over the words of the "
          f"{len(keys)} clips (lands: committed in final form; held h: h words held back; preview: committed text plus "
          f"tentative tail right up to it); words at finalize: committed only after the audio ended:")
    cols = ["lands", *(f"held{h}" for h in HOLDS), "preview"]
    print("config    device  " + "".join(f"{c:>12}" for c in cols) + "   words at finalize   uncommitted audio p50"
          "   live stop-to-text p50/p90   offline stop-to-text p50/p90")
    for config in present:
        st = runs[config]
        a, b, _, _ = fit[config]
        models = (("Mac", lambda w: a + b * w / SR), ("Pixel", lambda w: ratio * (a + b * w / SR)),
                  ("Pixel-hi", lambda w: pixel_local(w / SR)))
        for device, cost in models:
            scale = ratio if device.startswith("Pixel") else 1.0
            lag, at_final, n_words, unc, stop, off_stop = {c: [] for c in cols}, 0, 0, [], [], []
            for k in keys:
                finish = simulate(st[k], config, fed(st[k]), cost)
                for times, end, final in lags(st[k], offline[k], finish):
                    n_words += 1
                    at_final += final
                    if end is not None:
                        for c in cols:
                            lag[c].append(times[c] - end)
                unc.append(st[k]["uncommitted_ms"])
                stop.append(finish[-1] - st[k]["audio_ms"])
                off_stop.append(pixel_local(st[k]["audio_ms"] / 1000) if device == "Pixel-hi"
                                else scale * (off_a + off_b * st[k]["audio_ms"]))
            print(f"{config:10}{device:8}" + "".join(f"{pct(lag[c], 50):6.0f}/{pct(lag[c], 90):<5.0f}" for c in cols)
                  + f"   {at_final:5d} of {n_words} ({100 * at_final / n_words:2.0f}%) {pct(unc, 50):12.0f}"
                  f" {pct(stop, 50):19.0f}/{pct(stop, 90):<5.0f} {pct(off_stop, 50):22.0f}/{pct(off_stop, 90):<5.0f}")

    print("\nStability, every 20 ms read of every clip; shown text = committed text + tentative tail:")
    for config in present:
        st = runs[config]
        tot = {f: sum(st[k]["stats"][f] for k in keys) for f in st[keys[0]]["stats"]}
        final_eq = sum(st[k]["text"] == st[k]["full"] for k in keys)
        rw = [sum(v) for v in zip(*(rewrites(st[k]) for k in keys))]
        minutes = sum(st[k]["audio_ms"] for k in keys) / 60_000
        print(f"{config:10} reads {tot['reads']}; committed text not extending the one before: {tot['not_prefix']}; "
              f"full text not starting with it: {tot['full_not_prefix']}; final committed text == final full text: "
              f"{final_eq} of {len(keys)}; reads with a tentative tail: {tot['tentative_nonempty']}, tail changes "
              f"{tot['tentative_changes']}; shown-text updates "
              f"{rw[0]}, of which rewrite a shown word: {rw[1]} ({100 * rw[1] / rw[0]:.1f}%), words rewritten {rw[2]} "
              f"({rw[2] / minutes:.1f} per minute of audio)")

    extra(runs, offline, keys, fit, ratio, off_a, off_b)
    gate_report(fit, ratio, offline)
    memory_report()


def char_edits(r, h):
    return edits(list(r), list(h))


def extra(runs, offline, keys, fit, ratio, off_a, off_b):
    """The measurements the lead added: finalized stream against offline, irreversible commitment error, lag and
    smoothness percentiles, stop to the final offline text."""
    refs = {(s, f): r for s, f, _, r in clips()}
    print("\nFinalized stream against today's offline text (all 311 clips):")
    print("config    identical as typed   identical normalized   CER offline -> stream (normalized, LibriSpeech)")
    libri = [k for k in keys if k[0] != "public"]
    cer = lambda texts: 100 * sum(char_edits(" ".join(norm(refs[k])), " ".join(norm(texts[k]))) for k in libri) / \
        sum(len(" ".join(norm(refs[k]))) for k in libri)
    off_text = {k: offline[k]["text"] for k in keys}
    for config, st in runs.items():
        text = {k: st[k]["text"] for k in keys}
        same_typed = sum(text[k] == off_text[k] for k in keys)
        same_norm = sum(norm(text[k]) == norm(off_text[k]) for k in keys)
        print(f"{config:10}{same_typed:5d} ({100 * same_typed / len(keys):4.1f}%) {same_norm:11d} ({100 * same_norm / len(keys):4.1f}%)"
              f" {cer(off_text):17.2f}% -> {cer(text):.2f}%")

    print("\nIrreversible commitment error: committed text against the finalized stream never differs (stability); "
          "against today's offline text, over the words committed before the audio ended:")
    print("config    live words   differ normalized (takes)   differ as typed (takes)   offline words skipped")
    for config, st in runs.items():
        tot = [0, 0, 0, 0]
        takes_norm = takes_typed = 0
        for k in keys:
            live, normed, typed, missing = live_disagreements(st[k], offline[k])
            tot = [t + v for t, v in zip(tot, (live, normed, typed, missing))]
            takes_norm += normed + missing > 0
            takes_typed += typed + missing > 0
        print(f"{config:10}{tot[0]:10d} {tot[1]:9d} ({100 * tot[1] / tot[0]:.1f}%) ({takes_norm} of {len(keys)}) "
              f"{tot[2]:9d} ({100 * tot[2] / tot[0]:.1f}%) ({takes_typed}) {tot[3]:14d}")

    print("\nLag and smoothness on the Pixel model (ms): p50 / p95 / max")
    print("config    onset->first text   word end->preview   word end->committed   preview update gap   longest wait "
          "while speaking (per take)   stop->finalized stream   stop->offline text   finalize then offline")
    fmt = lambda v: f"{pct(v, 50):5.0f} /{pct(v, 95):5.0f} /{max(v) if v else float('nan'):6.0f}"
    for config, st in runs.items():
        a, b, _, _ = fit[config]
        cost = lambda w: ratio * (a + b * w / SR)
        onset, prev, comm, gaps, frozen, stop, off_stop, both = [], [], [], [], [], [], [], []
        for k in keys:
            r = st[k]
            finish = simulate(r, config, fed(r), cost)
            toks = [t for t in offline[k]["tokens"] if re.search(r"[A-Za-z0-9]", t[3])]
            if not toks:
                continue
            start, end = toks[0][0], toks[-1][1]
            for times, wend, _ in lags(r, offline[k], finish):
                if wend is not None:
                    prev.append(times["preview"] - wend)
                    comm.append(times["lands"] - wend)
            shown = sorted({finish[i] for i, full in r["snaps"] if full.strip()})
            if shown:
                onset.append(shown[0] - start)
            live = [t for t in shown if t <= r["audio_ms"]]
            gaps += [b2 - b1 for b1, b2 in zip(live, live[1:])]
            marks = [start] + [t for t in live if start < t < end] + [end]
            frozen.append(max(b2 - b1 for b1, b2 in zip(marks, marks[1:])))
            stop.append(finish[-1] - r["audio_ms"])
            off_ms = ratio * (off_a + off_b * r["audio_ms"])
            off_stop.append(off_ms)
            both.append(finish[-1] - r["audio_ms"] + off_ms)
        print(f"{config:10}{fmt(onset)}   {fmt(prev)}   {fmt(comm)}   {fmt(gaps)}   {fmt(frozen)}          "
              f"{fmt(stop)}   {fmt(off_stop)}   {fmt(both)}")


def gate_report(fit, ratio, offline):
    """The stateful VAD gate (GATE) in front of the stream: on the 311 clips, on the pause takes and on the
    non-speech clips, against the ungated stream."""
    have = lambda job: os.path.exists(results(job))
    cost = lambda config: (lambda w: ratio * (fit[config][0] + fit[config][1] * w / SR))
    refs = {(s, f): norm(r) for s, f, _, r in clips()}
    wer = lambda texts, ks, rf: 100 * sum(edits(rf[k], norm(texts[k])) for k in ks) / sum(len(rf[k]) for k in ks)
    configs = [c for c in ("70-13-4", "70-13-13") if have(f"gate-{c}") and have(c)]
    if configs:
        print("\nStateful VAD gate (Silero > 0.3, 450 ms prefill, 60 ms onset, 1,650 ms hangover), "
              "311 clips; lag on the Pixel model, p50 / p95 ms:")
        print("config    feed      audio fed   WER LibriSpeech   words changed vs offline / vs ungated   preview lag   "
              "committed lag")
    for config in configs:
        plain, gated_ = load(config), load(f"gate-{config}")
        keys = sorted(set(plain) & set(gated_) & set(offline))
        libri = [k for k in keys if k[0] != "public"]
        for name, st in (("ungated", plain), ("gated", gated_)):
            text = {k: st[k]["text"] for k in keys}
            fed_share = sum(fed(st[k]) for k in keys) / sum(st[k]["audio_ms"] * 16 for k in keys)
            vs_off = sum(edits(norm(offline[k]["text"]), norm(text[k])) for k in keys)
            vs_plain = sum(edits(norm(plain[k]["text"]), norm(text[k])) for k in keys)
            pv, cm = [], []
            for k in keys:
                for times, end, _ in lags(st[k], offline[k], simulate(st[k], config, fed(st[k]), cost(config))):
                    if end is not None:
                        pv.append(times["preview"] - end)
                        cm.append(times["lands"] - end)
            print(f"{config:10}{name:8}{100 * fed_share:8.1f}% {wer(text, libri, refs):12.2f}% {vs_off:17d} / {vs_plain:<4d}"
                  f" {pct(pv, 50):12.0f} / {pct(pv, 95):<5.0f} {pct(cm, 50):6.0f} / {pct(cm, 95):<5.0f}")

    if have("pauses-offline"):
        off = load("pauses-offline")
        prefs = {(s, n): norm(r) for s, n, _, r, _ in pause_takes()}
        print(f"\nPause takes: {PAUSE_TAKES} takes of three test-clean utterances with {GAP_S:g} s between them, in digital "
              f"silence and under steady noise at {NOISE_DBFS} dBFS. Phrase-final words are the last word before each "
              "gap; 'waits for the next phrase' = committed only after the gap ends. Pixel model, ms, p50 / p95 / max:")
        print("set             run                 audio fed   WER    phrase-final committed lag   waits for next phrase"
              "   phrase-final preview lag")
        for gap_set in ("pauses-silence", "pauses-noise"):
            keys = sorted(k for k in off if k[0] == gap_set)
            print(f"{gap_set:16}{'offline':20}{100.0:8.1f}% {wer({k: off[k]['text'] for k in keys}, keys, prefs):6.2f}%")
            for config in ("70-13-4", "70-13-13"):
                for job in (f"pauses-{config}", f"pauses-gate-{config}"):
                    if not have(job):
                        continue
                    st = load(job)
                    ks = [k for k in keys if k in st]
                    fed_share = sum(fed(st[k]) for k in ks) / sum(st[k]["audio_ms"] * 16 for k in ks)
                    final_c, final_p, waits, n_final = [], [], 0, 0
                    for k in ks:
                        words = lags(st[k], off[k], simulate(st[k], config, fed(st[k]), cost(config)))
                        for g0, g1 in st[k]["gaps"]:
                            before = [(end, t) for t, end, _ in words if end is not None and end <= g0 + 100]
                            if not before:
                                continue
                            end, t = max(before, key=lambda v: v[0])
                            n_final += 1
                            final_c.append(t["lands"] - end)
                            final_p.append(t["preview"] - end)
                            waits += t["lands"] >= g1
                    fmt = lambda v: f"{pct(v, 50):5.0f} /{pct(v, 95):5.0f} /{max(v):6.0f}"
                    print(f"{gap_set:16}{job.replace(gap_set[:7], ''):20}{100 * fed_share:8.1f}% "
                          f"{wer({k: st[k]['text'] for k in ks}, ks, prefs):6.2f}%   {fmt(final_c)}   {waits:6d} of {n_final:<6d}"
                          f"     {fmt(final_p)}")

    if have("noise-70-13-4"):
        print("\nNon-speech clips (113: digital silence, a tone, MUSAN noise cut and whole): words the stream committed and "
              "words a preview would have shown")
        print("run                     clips with a committed word   committed words   clips with a shown word   "
              "most words shown at once   audio fed")
        for config in ("70-13-4", "70-13-13"):
            for job in (f"noise-{config}", f"noise-gate-{config}"):
                if not have(job):
                    continue
                st = load(job)
                committed = [len(r["text"].split()) for r in st.values()]
                shown = [max((len(full.split()) for _, full in r["snaps"]), default=0) for r in st.values()]
                fed_share = sum(fed(r) for r in st.values()) / sum(r["audio_ms"] * 16 for r in st.values())
                print(f"{job:24}{sum(c > 0 for c in committed):12d} of {len(st):<14d}{sum(committed):10d}"
                      f"{sum(v > 0 for v in shown):20d} {max(shown):22d} {100 * fed_share:14.1f}%")


def memory_report():
    """The long-take runs: CPU seconds per audio minute, peak RSS, and whether a stream's chunk cost stays flat."""
    path = os.path.join(CACHE, "results", "memory.jsonl")
    if not os.path.exists(path):
        return
    print("\nLong takes (Mac, THREADS threads, each run in its own process): CPU s per audio minute, wall RTF, peak RSS "
          "(model loaded), chunk ms in the first and the last minute")
    for r in map(json.loads, open(path)):
        w, per_min = r["chunk_walls"], r["audio_s"] / 60
        per_chunk_s = r["audio_s"] / max(len(w), 1)
        head = statistics.median(w[:max(1, int(60 / per_chunk_s))]) if w else float("nan")
        tail = statistics.median(w[-max(1, int(60 / per_chunk_s)):]) if w else float("nan")
        print(f"{r['scenario']:16}{r['minutes']:3d} min: CPU {r['cpu_s'] / per_min:6.1f} s/min, RTF "
              f"{r['wall_s'] / r['audio_s']:.3f}, peak RSS {r['peak_rss_mb']} MB ({r['rss_loaded_mb']} loaded)"
              + (f", chunk {head:.0f} ms first minute, {tail:.0f} ms last minute" if w else ""))


VERIFY_CLIPS = int(os.environ.get("VERIFY_CLIPS", 51))  # PUBLIC's 11 clips and the first 40 of the sample
LIVE_REF = os.environ.get("LIVE_REF") or os.path.join(CACHE, "results", "70-13-4.jsonl")


def verify():
    """70-13-4 streams (tentative tail on) of the first VERIFY_CLIPS clips, with no pool and with a persistent pool,
    against LIVE_REF's rows (a run made before 0014 and 0015): the same text, commits and tentative snapshots."""
    engine = Engine(THREADS)
    ref = {(r["set"], r["file"]): r for r in map(json.loads, open(LIVE_REF))}
    out = {}
    for pool in (0, 1):
        assert engine.lib.live_pool(pool) == 0
        same = dict(text=0, commits=0, snaps=0, clips=0)
        for s, name, path, _ in clips()[:VERIFY_CLIPS]:
            r, want = engine.stream(read(path), "70-13-4"), ref[(s, name)]
            same["clips"] += 1
            for k in ("text", "commits", "snaps"):
                same[k] += r[k] == want[k]
        out["pool" if pool else "no_pool"] = same
        print("pool" if pool else "no pool", same, flush=True)
    os.makedirs(RESULTS, exist_ok=True)
    json.dump(out, open(os.path.join(RESULTS, "verify.json"), "w"), indent=1)


def abort_test(reps=5):
    """Chunk 4 of JFK at 70-13-4 on its own feed (one sample completes it). Counts the abort polls of that feed with and
    without the tentative tail, then aborts at a poll in the encoder, in the committed decode and in the tentative
    decode. Polls in a chunk: the feed's (2), the chunk's (1), one per encoder graph node, one before the committed
    decode and one before its encoder projection and at each of its steps, the same for the tentative decode, then the
    feed's last. So the tentative decode's steps are the last polls but one, and the committed decode's steps end just
    before the T = (with tail) - (without) polls of the tentative part."""
    engine = Engine(THREADS)
    lib = engine.lib
    lib.live_polls.restype = lib.live_aborted_ns.restype = lib.live_now_ns.restype = ctypes.c_int64
    lib.live_polls_reset.argtypes = [ctypes.c_int64]
    x = read(os.path.join(PUBLIC, "jfk.wav"))
    edge = (1040 + 320) * 16 + 3 * 1040 * 16  # chunk 4 completes at this sample
    before = engine.stream(x, "70-13-4")["text"]

    def chunk(tentative, at):
        os.environ["TRANSCRIBE_STREAM_TENTATIVE"] = "1" if tentative else "0"
        lib.live_polls_reset(-1)
        lib.live_reset()  # the last chunk() left its stream open, aborted or not
        assert lib.live_begin(5600, 1040, 320, 0) == 0
        head = np.ascontiguousarray(x[:edge - 1])
        assert lib.live_feed(head.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), len(head), engine.progress) == 0
        lib.live_polls_reset(at)
        last = np.ascontiguousarray(x[edge - 1:edge])
        status = lib.live_feed(last.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 1, engine.progress)
        done, fired = lib.live_now_ns(), lib.live_aborted_ns()
        return status, lib.live_polls(), (done - fired) / 1e6 if fired else None

    status, polls, _ = chunk(True, -1)
    assert status == 0
    status_off, polls_off, _ = chunk(False, -1)
    assert status_off == 0
    tentative = polls - polls_off
    assert tentative >= 4, tentative  # its checks and at least two steps
    at = dict(encoder=10, committed_decode=polls - tentative - 3, tentative_decode=polls - 2)
    out = dict(polls=polls, polls_without_tentative=polls_off, tentative_polls=tentative, at=at, phases={})
    for phase, k in at.items():
        rows = [chunk(True, k) for _ in range(reps)]
        assert all(r[0] == 13 for r in rows), (phase, rows)  # TRANSCRIBE_ERR_ABORTED
        out["phases"][phase] = dict(status=13, polls_to_return=[r[1] - k for r in rows],
                                    ms_to_return=[round(r[2], 3) for r in rows])
        print(phase, "poll", k, out["phases"][phase], flush=True)
    # A new stream after the aborts gives the same text as one before them.
    os.environ["TRANSCRIBE_STREAM_TENTATIVE"] = "1"
    lib.live_polls_reset(-1)
    lib.live_reset()
    again = engine.stream(x, "70-13-4")["text"]
    out["after"] = dict(text=again, same_as_before=again == before)
    print("after:", out["after"], flush=True)
    os.makedirs(RESULTS, exist_ok=True)
    json.dump(out, open(os.path.join(RESULTS, "abort.json"), "w"), indent=1)


TRIM_MIN = float(os.environ.get("TRIM_MIN", 10.5))


def trim_audio():
    """PUBLIC's clips (jfk among them) end to end with 0.5 s of silence between them, over and over, for TRIM_MIN min."""
    with open(os.path.join(PUBLIC, "refs.tsv"), newline="") as f:
        clips_ = [read(os.path.join(PUBLIC, r["file"])) for r in csv.DictReader(f, delimiter="\t")]
    gap, parts, n = np.zeros(SR // 2, np.float32), [], 0
    while n < TRIM_MIN * 60 * SR:
        for c in clips_:
            parts += [c, gap]
            n += len(c) + len(gap)
    return np.concatenate(parts)[:int(TRIM_MIN * 60 * SR)]


def trim_one(mode):
    """One trim run in this process, printed as JSON: keep (TRANSCRIBE_STREAM_KEEP_PCM=1) or trim."""
    import resource
    if mode == "keep":
        os.environ["TRANSCRIBE_STREAM_KEEP_PCM"] = "1"
    engine = Engine(THREADS)
    x = trim_audio()
    rss = int(subprocess.run(["ps", "-o", "rss=", "-p", str(os.getpid())], capture_output=True, text=True).stdout)
    pcm, t = {}, time.perf_counter()
    r = engine.stream(x, "70-13-4", pcm=pcm)
    print(json.dumps(dict(mode=mode, audio_s=len(x) / SR, chunks=len(r["chunks"]), wall_s=round(time.perf_counter() - t, 1),
                          text=r["text"], commits=r["commits"], snaps=r["snaps"], pcm_held_max=pcm["held"],
                          pcm_allocated_max=pcm["allocated"], rss_loaded_mb=round(rss / 1024),
                          peak_rss_mb=round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 2 ** 20))))


def trim():
    """trim-one keep and trim, each in its own process; compares every commit and tentative snapshot."""
    runs = {}
    for mode in ("keep", "trim"):
        out = subprocess.run([sys.executable, os.path.abspath(__file__), "trim-one", mode], check=True,
                             capture_output=True, text=True).stdout
        runs[mode] = json.loads(out.strip().splitlines()[-1])
        print(mode, {k: v for k, v in runs[mode].items() if k not in ("text", "commits", "snaps")}, flush=True)
    keep, cut = runs["keep"], runs["trim"]
    window = (5600 + 1040 + 320) * 16
    summary = dict(audio_s=cut["audio_s"], chunks=cut["chunks"], text_same=keep["text"] == cut["text"],
                   commits_same=keep["commits"] == cut["commits"], snaps_same=keep["snaps"] == cut["snaps"],
                   snaps=len(cut["snaps"]), window_samples=window,
                   **{f"{m}_{k}": runs[m][k] for m in runs for k in ("pcm_held_max", "pcm_allocated_max",
                                                                      "rss_loaded_mb", "peak_rss_mb", "wall_s")})
    print(json.dumps(summary, indent=1))
    os.makedirs(RESULTS, exist_ok=True)
    json.dump(summary, open(os.path.join(RESULTS, "trim.json"), "w"), indent=1)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run" and len(sys.argv) > 2:
        run(sys.argv[2:])
    elif cmd == "bench":
        bench()
    elif cmd == "score":
        score()
    elif cmd == "memory":
        memory()
    elif cmd == "memory-one":
        memory_one(sys.argv[2], int(sys.argv[3]))
    elif cmd == "verify":
        verify()
    elif cmd == "abort":
        abort_test()
    elif cmd == "trim":
        trim()
    elif cmd == "trim-one":
        trim_one(sys.argv[2])
    else:
        sys.exit(__doc__)
