#!/usr/bin/env python3
"""Parakeet TDT 0.6B v3 (the multilingual model) through the app's engine on a Mac, against Parakeet Unified EN.

  tools/v3-check.py build    the engine dylib (tools/v3-check/CMakeLists.txt) with third_party/patches, as the app builds
                             it, and without them, from a clean clone of the pinned transcribe.cpp commit; clears the
                             cached results (V3_CACHE/runs), which the other commands reuse by name
  tools/v3-check.py fleurs   the first N (default 100) test clips of each FLEURS language in FLEURS, streamed from
                             google/fleurs at a pinned revision (CC BY 4.0), as 16 kHz mono PCM16 WAVs and refs.tsv
  tools/v3-check.py parity   v3's texts with the patch series against the unpatched build, on the 11 public clips and
                             the first 20 FLEURS clips of each language: the series as the app runs it (persistent
                             pool), and with each patch's switch back (SWITCHES); then the language hint "en" and each
                             clip's own language against none
  tools/v3-check.py wer      v3 and Unified (both patched, persistent pool) and v3 unpatched on the LibriSpeech sample
                             (1,113 utterances; WER per split, paired speaker bootstraps of v3 minus Unified and of the
                             series minus unpatched) and on the FLEURS clips (WER and CER per language)
  tools/v3-check.py speed    engine time of both models (patched, persistent pool) on the 3.7 s, 11 s and 29.4 s
                             public clips, ROUNDS (3) alternating rounds of RUNS (10) warm runs each, and a Pixel
                             estimate from the numbers measured on the phone
  tools/v3-check.py abort    patch 0007 on v3: an abort asked 150 ms into a JFK run, patched and unpatched, 5 times each:
                             the status and how long the run went on after the ask

The dylib is loaded with ctypes (this Mac runs no newly built executable), one process per (build, model, switches), so
each load reads its environment afresh. Runs use the app's run parameters (tools/v3-check/asr.cpp) on THREADS (4) threads,
each clip padded as Padding.forEngine pads it. Texts and numbers go to V3_CACHE (/tmp/thumbfree-v3-check); all audio is
public. Needs numpy, cmake, ninja, git and Xcode's clang.
"""
import csv, ctypes, glob, hashlib, io, json, os, re, resource, shutil, statistics, struct, subprocess, sys, tarfile
import unicodedata, urllib.request, wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.environ.get("V3_CACHE") or "/tmp/thumbfree-v3-check"
# The public clips are in this checkout (testdata/public-bench). The LibriSpeech test sample is not in the repository:
# BAKEOFF names it, else the main checkout's research/spikes/bakeoff/data (the folder holding the shared .git).
MAIN = os.path.dirname(subprocess.run(["git", "-C", ROOT, "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                      capture_output=True, text=True, check=True).stdout.strip())
PUBLIC = os.environ.get("PUBLIC") or os.path.join(ROOT, "testdata", "public-bench")
LIBRISPEECH = os.environ.get("BAKEOFF") or os.path.join(MAIN, "research", "spikes", "bakeoff", "data")  # refs.tsv, WAVs
THREADS = int(os.environ.get("THREADS", "4"))
HUB = os.path.expanduser("~/.cache/huggingface/hub")
MODELS = {  # file name in the Hugging Face cache, SHA-256 (Catalog.kt)
    "v3": ("parakeet-tdt-0.6b-v3-Q8_0.gguf", "5859f77944efcd8eafa23a6350731960b2b55b2203df51f319665c807d802cc7"),
    "unified": ("parakeet-unified-en-0.6b-Q8_0.gguf", "4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795"),
}
FLEURS_REV = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
FLEURS = {"en_us": "en", "de_de": "de", "fr_fr": "fr", "es_419": "es", "it_it": "it", "pt_br": "pt", "nl_nl": "nl",
          "pl_pl": "pl", "ru_ru": "ru"}  # FLEURS config: v3's language code
# Each patch's switch back to the unpatched path (docs/decisions/native-build.md); 0006's is its decoder thread count.
# 0007 acts only on an abort, 0008 has none (bit-identical), 0010 adds functions, 0011 is the pool itself.
SWITCHES = {
    "0001": "TRANSCRIBE_CONV_PW_F32=1", "0002": "TRANSCRIBE_NO_REPACK=1", "0003": "TRANSCRIBE_DECODER_F32=1",
    "0004": "TRANSCRIBE_MEL_DENSE=1", "0005": "TRANSCRIBE_SPLIT_FLASH_MASK=1", "0006": "TRANSCRIBE_DECODER_THREADS=1",
    "0009": "TRANSCRIBE_CONV2D_GENERIC=1", "0012": "TRANSCRIBE_KEEP_SCRATCH=1",
}
# 3.7 s, 11 s, 29.4 s: the speed clips of android/tools/engine-bench.py
SPEED_CLIPS = ["1272-141231-0017.wav", "jfk.wav", "1272-128104-0004.wav"]
# Unified on the Pixel 10 with the app's default thread profile (fast cores and ADPF hints): engine p50 and the
# decoder's share, in ms, for the three speed clips.
PIXEL_UNIFIED = {"1272-141231-0017.wav": (201, 10), "jfk.wav": (480, 15), "1272-128104-0004.wav": (1378, 47)}


def model_path(name):
    file, sha = MODELS[name]
    found = sorted(glob.glob(os.path.join(HUB, "models--handy-computer--*", "snapshots", "*", file)))
    if not found:
        sys.exit(f"{file} is not in the Hugging Face cache")
    with open(found[0], "rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != sha:
            sys.exit(f"{found[0]}: SHA-256 is not Catalog.kt's")
    return found[0]


def lib_path(build):
    return os.path.join(CACHE, f"build-{build}", "libv3_check.dylib")


def build():
    pinned = subprocess.run(["git", "-C", ROOT, "ls-files", "--stage", "third_party/transcribe.cpp"], capture_output=True,
                            text=True, check=True).stdout.split()[1]
    clean = os.path.join(CACHE, "transcribe-unpatched")
    if not os.path.isdir(clean):
        subprocess.run(["git", "clone", "--quiet", "--no-checkout", os.path.join(ROOT, "third_party", "transcribe.cpp"),
                        clean], check=True)
        subprocess.run(["git", "-C", clean, "checkout", "--quiet", pinned], check=True)
    head = subprocess.run(["git", "-C", clean, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", clean, "status", "--porcelain"], capture_output=True, text=True).stdout.strip()
    if head != pinned or dirty:
        sys.exit(f"{clean} is not a clean checkout of {pinned}: delete it and build again")
    for name, extra in (("patched", []), ("unpatched", ["-DPATCHED=OFF", f"-DTRANSCRIBE_DIR={clean}"])):
        out = os.path.join(CACHE, f"build-{name}")
        subprocess.run(["cmake", "-S", os.path.join(ROOT, "tools", "v3-check"), "-B", out, "-G", "Ninja",
                        "-DCMAKE_BUILD_TYPE=Release", *extra], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["nice", "-n", "10", "cmake", "--build", out, "--target", "v3_check"], check=True,
                       stdout=subprocess.DEVNULL)
        print(f"{name}: {lib_path(name)}")
    shutil.rmtree(os.path.join(CACHE, "runs"), ignore_errors=True)  # results of earlier builds would be reused by tag


def read_wav(data):
    """16 kHz mono samples of a PCM16 or float32 WAV, as int16."""
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE"
    i, fmt, body = 12, None, None
    while i + 8 <= len(data):
        cid, size = data[i:i + 4], struct.unpack("<I", data[i + 4:i + 8])[0]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", data[i + 8:i + 24])
        elif cid == b"data":
            body = data[i + 8:i + 8 + size]
        i += 8 + size + (size & 1)
    tag, channels, rate, _, _, bits = fmt
    assert channels == 1 and rate == 16_000, fmt
    if tag == 3 and bits == 32:
        return np.clip(np.round(np.frombuffer(body, "<f4") * 32768), -32768, 32767).astype("<i2")
    assert tag == 1 and bits == 16, fmt
    return np.frombuffer(body, "<i2")


def write_wav(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16_000)
        w.writeframes(pcm.tobytes())


def fleurs(n):
    for config, lang in FLEURS.items():
        out = os.path.join(CACHE, "fleurs", lang)
        refs_path = os.path.join(out, "refs.tsv")
        if os.path.exists(refs_path) and len(open(refs_path).readlines()) == n + 1:
            continue
        os.makedirs(out, exist_ok=True)
        base = f"https://huggingface.co/datasets/google/fleurs/resolve/{FLEURS_REV}/data/{config}"
        with urllib.request.urlopen(base + "/test.tsv") as r:
            # id, file name, raw transcription, normalized transcription, phonemes, samples, gender
            refs = {row[1]: row for row in csv.reader(io.StringIO(r.read().decode()), delimiter="\t", quoting=csv.QUOTE_NONE)}
        rows = []
        with urllib.request.urlopen(base + "/audio/test.tar.gz") as r, tarfile.open(fileobj=r, mode="r|gz") as tar:
            for member in tar:  # streamed: only the first n clips are downloaded
                name = os.path.basename(member.name)
                if name not in refs:
                    continue
                write_wav(os.path.join(out, name), read_wav(tar.extractfile(member).read()))
                rows.append((name, refs[name][0], refs[name][6], refs[name][2]))
                if len(rows) == n:
                    break
        with open(refs_path, "w", newline="") as f:
            csv.writer(f, delimiter="\t", lineterminator="\n").writerows([("file", "sentence", "gender", "text"), *rows])
        print(f"fleurs {lang}: {len(rows)} clips")


def clip_set(name):
    """[(path, language, reference text, group)] for "public", "librispeech", "fleurs" or "fleurs20"."""
    if name == "public":
        refs = {r["file"]: r["text"] for r in csv.DictReader(open(os.path.join(PUBLIC, "refs.tsv")), delimiter="\t")}
        return [(os.path.join(PUBLIC, f), "en", t, "public") for f, t in sorted(refs.items())]
    if name == "librispeech":
        out = []
        for split in ("test-clean", "test-other"):
            for r in csv.DictReader(open(os.path.join(LIBRISPEECH, split, "refs.tsv")), delimiter="\t"):
                out.append((os.path.join(LIBRISPEECH, split, r["file"]), "en", r["text"], f"{split}/{r['speaker_id']}"))
        return out
    limit = 20 if name == "fleurs20" else None
    out = []
    for lang in FLEURS.values():
        rows = list(csv.DictReader(open(os.path.join(CACHE, "fleurs", lang, "refs.tsv")), delimiter="\t"))[:limit]
        out += [(os.path.join(CACHE, "fleurs", lang, r["file"]), lang, r["text"], f"fleurs-{lang}") for r in rows]
    return out


def worker():
    """The child: argv = run <build> <model> <pool> <lang: none|clip|code> <runs> <clips.json>; JSON lines out."""
    _, build_name, model, pool, lang, runs, clips = sys.argv[1:]
    lib = ctypes.CDLL(lib_path(build_name))
    lib.asr_load.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int, ctypes.c_int]
    lib.asr_run.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int,
                            ctypes.POINTER(ctypes.c_float)]
    lib.asr_info.argtypes = [ctypes.c_char_p, ctypes.c_int]
    status = lib.asr_load(model_path(model).encode(), THREADS, int(pool), int(os.environ.get("V3_VERBOSE", "0")))
    assert status == 0, f"asr_load: {status}"
    info = ctypes.create_string_buffer(4096)
    lib.asr_info(info, len(info))
    print(json.dumps({"info": info.value.decode()}), flush=True)
    out, ms = ctypes.create_string_buffer(1 << 16), (ctypes.c_float * 3)()
    for path, clip_lang in json.load(open(clips)):
        with open(path, "rb") as f:
            x = read_wav(f.read()).astype(np.float32) / 32768
        if 0 < len(x) < 16_000:  # Padding.forEngine
            x = np.concatenate([np.zeros(8_000, np.float32), x, np.zeros(max(8_000, 12_000 - len(x)), np.float32)])
        hint = None if lang == "none" else (clip_lang if lang == "clip" else lang)
        for run in range(int(runs) + 1 if int(runs) > 0 else 1):  # with runs, a warm-up run first
            status = lib.asr_run(x.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), len(x),
                                 hint.encode() if hint else None, out, len(out), ms)
            if int(runs) == 0 or run > 0:
                print(json.dumps({"clip": path, "run": run, "status": status, "text": out.value.decode(errors="replace"),
                                  "ms": list(ms)}), flush=True)
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss  # bytes on macOS
    print(json.dumps({"peak_rss_mb": rss / 1e6}), flush=True)


def abort_worker():
    """The child for abort: argv = abortrun <build> <model> <pool>; one warm run of JFK, then 5 aborted ones."""
    _, build_name, model, pool = sys.argv[1:]
    lib = ctypes.CDLL(lib_path(build_name))
    lib.asr_load.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int, ctypes.c_int]
    lib.asr_run.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int,
                            ctypes.POINTER(ctypes.c_float)]
    lib.asr_abort_after.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_float)]
    assert lib.asr_load(model_path(model).encode(), THREADS, int(pool), 0) == 0
    with open(os.path.join(PUBLIC, "jfk.wav"), "rb") as f:
        x = read_wav(f.read()).astype(np.float32) / 32768
    data, out, ms = x.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), ctypes.create_string_buffer(1 << 12), (ctypes.c_float * 3)()
    assert lib.asr_run(data, len(x), None, out, len(out), ms) == 0
    print(json.dumps({"warm_ms": list(ms)}), flush=True)
    for _ in range(5):
        status = lib.asr_abort_after(data, len(x), 150, ms)
        print(json.dumps({"status": status, "after_abort_ms": ms[0], "run_ms": ms[1]}), flush=True)


def abort():
    print("\n| build, model | status of 5 runs | ms the run went on after the abort (median, max) | warm run ms |\n|---|---|---|---|")
    for build_name, model in (("patched", "v3"), ("unpatched", "v3"), ("patched", "unified")):
        result = subprocess.run([sys.executable, __file__, "abortrun", build_name, model, str(int(build_name == "patched"))],
                                capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"abort {build_name} {model} failed:\n{result.stderr[-3000:]}")
        lines = [json.loads(line) for line in result.stdout.splitlines()]
        runs = lines[1:]
        after = sorted(r["after_abort_ms"] for r in runs)
        print(f"| {build_name}, {model} | {', '.join(str(r['status']) for r in runs)} | {statistics.median(after):.0f}, "
              f"{after[-1]:.0f} | {sum(lines[0]['warm_ms']):.0f} |")


def run(tag, build_name, model, clips, pool=1, lang="none", env="", runs=0):
    """Runs a config in its own process (cached in CACHE/runs/<tag>.jsonl); returns (rows, info, peak RSS MB)."""
    path = os.path.join(CACHE, "runs", f"{tag}.jsonl")
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        listing = path + ".clips.json"
        json.dump([(c[0], c[1]) for c in clips], open(listing, "w"))
        child = dict(os.environ, **dict(kv.split("=", 1) for kv in env.split(",") if kv))
        result = subprocess.run([sys.executable, __file__, "run", build_name, model, str(pool), lang, str(runs), listing],
                                env=child, capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"{tag} failed:\n{result.stderr[-3000:]}")
        open(path + ".tmp", "w").write(result.stdout)
        os.replace(path + ".tmp", path)
        os.remove(listing)
    lines = [json.loads(line) for line in open(path)]
    rows = [r for r in lines if "clip" in r]
    assert all(r["status"] == 0 for r in rows), f"{tag}: a run failed"
    return rows, lines[0]["info"], lines[-1]["peak_rss_mb"]


def words(text, lang):
    """Words for WER. English as the earlier WER gates scored it (lowercase ASCII letters and digits, Mr. as mister);
    other languages in NFKC, case-folded, anything but letters, marks and digits as a space."""
    if lang == "en":
        w = re.sub(" +", " ", re.sub("[^a-z0-9 ]", " ", text.lower())).strip().split()
        return ["mister" if x == "mr" else x for x in w]
    text = unicodedata.normalize("NFKC", text).casefold()
    return "".join(c if unicodedata.category(c)[0] in "LMN" else " " for c in text).split()


def edits(r, h):
    row = list(range(len(h) + 1))
    for i, a in enumerate(r, 1):
        prev, row[0] = row[0], i
        for j, b in enumerate(h, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (a != b))
    return row[-1]


def score(clips, rows):
    """{group: [(word edits, words, char edits, chars)]} per clip, in clip order."""
    text = {r["clip"]: r["text"] for r in rows}
    out = {}
    for path, lang, ref, group in clips:
        r, h = words(ref, lang), words(text[path], lang)
        chars = (edits(" ".join(r), " ".join(h)), len(" ".join(r))) if group.startswith("fleurs") else (0, 0)
        out.setdefault(group, []).append((edits(r, h), len(r), *chars))
    return out


def rate(items, i=0):
    return 100 * sum(x[i] for x in items) / max(1, sum(x[i + 1] for x in items))


def bootstrap(a, b, reps=10_000):
    """95% interval of WER(b) - WER(a) in points, resampling speakers ({speaker: [(edits, words, ...)]}, paired)."""
    speakers = sorted(a)
    n = np.array([sum(x[1] for x in a[s]) for s in speakers])
    d = np.array([sum(x[0] for x in b[s]) - sum(x[0] for x in a[s]) for s in speakers])
    pick = np.random.default_rng(7).integers(0, len(speakers), (reps, len(speakers)))
    diffs = np.sort(100 * d[pick].sum(axis=1) / n[pick].sum(axis=1))
    return diffs[int(0.025 * reps)], diffs[int(0.975 * reps)]


def changed(clips, base, other):
    """The clips whose text differs, with both texts."""
    a, b = {r["clip"]: r["text"] for r in base}, {r["clip"]: r["text"] for r in other}
    return [(os.path.basename(c[0]), a[c[0]], b[c[0]]) for c in clips if a[c[0]] != b[c[0]]]


def median_ms(rows):
    return [statistics.median(r["ms"][i] for r in rows) for i in range(3)]


def parity():
    clips = clip_set("public") + clip_set("fleurs20")
    base, info, _ = run("parity-unpatched", "unpatched", "v3", clips, pool=0)
    print(f"v3: {info}\n{len(clips)} clips: 11 public, 20 FLEURS clips each of {', '.join(FLEURS.values())}")
    print(f"\n| config | texts identical to unpatched | median mel / encoder / decoder ms |\n|---|---:|---|")
    print(f"| unpatched | (base) | {' / '.join(f'{x:.0f}' for x in median_ms(base))} |")
    configs = [("patched, no pool", "", 0), ("patched, S1 pool (the app)", "", 1)]
    configs += [(f"{p} switched back", env, 1) for p, env in SWITCHES.items()]
    diffs = {}
    for name, env, pool in configs:
        tag = "parity-" + re.sub("[^a-z0-9]+", "-", name.lower()).strip("-")
        rows, _, _ = run(tag, "patched", "v3", clips, pool=pool, env=env)
        diffs[name] = changed(clips, base, rows)
        print(f"| {name} | {len(clips) - len(diffs[name])} of {len(clips)} | "
              f"{' / '.join(f'{x:.0f}' for x in median_ms(rows))} |")
    for name, items in diffs.items():
        for clip, a, b in items:
            print(f"\n{name}, {clip}:\n  unpatched: {a}\n  patched:   {b}")
    app, _, _ = run("parity-patched-s1-pool-the-app", "patched", "v3", clips)
    for lang in ("en", "clip"):
        rows, _, _ = run(f"parity-hint-{lang}", "patched", "v3", clips, lang=lang)
        what = "the hint \"en\"" if lang == "en" else "each clip's own language as the hint"
        print(f"\n{what}: {len(clips) - len(changed(clips, app, rows))} of {len(clips)} texts identical to no hint")


def wer():
    ls, fl = clip_set("librispeech"), clip_set("fleurs")
    results = {}
    for tag, build_name, model in (("v3", "patched", "v3"), ("unified", "patched", "unified"),
                                   ("v3-unpatched", "unpatched", "v3")):
        rows, _, _ = run(f"wer-{tag}-librispeech", build_name, model, ls, pool=int(build_name == "patched"))
        results[tag] = score(ls, rows)
        if model == "v3":
            rows, _, _ = run(f"wer-{tag}-fleurs", build_name, model, fl, pool=int(build_name == "patched"))
            results[tag].update(score(fl, rows))
        if tag == "unified":  # English FLEURS only: Unified is an English model
            en = [c for c in fl if c[1] == "en"]
            rows, _, _ = run("wer-unified-fleurs-en", build_name, model, en)
            results[tag].update(score(en, rows))
    print("\nLibriSpeech (the bakeoff's sample), WER %, 95% intervals from a paired speaker bootstrap\n\n| split | clips | "
          "words | Unified | v3 | v3 - Unified | v3 unpatched | patched - unpatched |\n|---|---:|---:|---:|---:|---|---:|---|")
    for split in ("test-clean", "test-other"):
        per = {tag: {g: v for g, v in res.items() if g.startswith(split)} for tag, res in results.items()}
        flat = {tag: [x for v in per[tag].values() for x in v] for tag in per}
        lo, hi = bootstrap(per["unified"], per["v3"])
        plo, phi = bootstrap(per["v3-unpatched"], per["v3"])
        u, v, un = rate(flat["unified"]), rate(flat["v3"]), rate(flat["v3-unpatched"])
        print(f"| {split} | {len(flat['v3'])} | {sum(x[1] for x in flat['v3']):,} | {u:.2f} | {v:.2f} | "
              f"{v - u:+.2f} ({lo:+.2f} to {hi:+.2f}) | {un:.2f} | {v - un:+.2f} ({plo:+.2f} to {phi:+.2f}) |")
    print("\nFLEURS test clips, WER % and CER %\n\n| language | clips | words | v3 WER | v3 CER | v3 unpatched WER |"
          " Unified WER |\n|---|---:|---:|---:|---:|---:|---:|")
    for lang in FLEURS.values():
        g = f"fleurs-{lang}"
        v, un = results["v3"][g], results["v3-unpatched"][g]
        uni = f"{rate(results['unified'][g]):.2f}" if g in results["unified"] else ""
        print(f"| {lang} | {len(v)} | {sum(x[1] for x in v):,} | {rate(v):.2f} | {rate(v, 2):.2f} | {rate(un):.2f} | {uni} |")


def speed():
    rounds, runs = int(os.environ.get("ROUNDS", "3")), int(os.environ.get("RUNS", "10"))
    clips = [(os.path.join(PUBLIC, c), "en", "", "speed") for c in SPEED_CLIPS]
    times = {m: {c: [] for c in SPEED_CLIPS} for m in MODELS}
    rss = {m: [] for m in MODELS}
    for i in range(rounds):
        for model in (("unified", "v3") if i % 2 == 0 else ("v3", "unified")):
            rows, _, peak = run(f"speed-{model}-r{i}", "patched", model, clips, runs=runs)
            rss[model].append(peak)
            for r in rows:
                times[model][os.path.basename(r["clip"])].append(r["ms"])
    load = os.getloadavg()[0]
    print(f"\nMac (M4 Pro, {THREADS} threads, S1 pool), medians of {rounds * runs} warm runs, ms; load average {load:.0f}"
          f"\n\n| clip | Unified mel / enc / dec | v3 mel / enc / dec | v3 / Unified engine | Pixel Unified | Pixel v3 "
          f"(estimate) |\n|---|---|---|---:|---:|---:|")
    for c in SPEED_CLIPS:
        u = [statistics.median(t[i] for t in times["unified"][c]) for i in range(3)]
        v = [statistics.median(t[i] for t in times["v3"][c]) for i in range(3)]
        total_u, dec_u = PIXEL_UNIFIED[c]
        # The encoder side (mel and encoder) and the decoder scale by their own Mac ratios.
        est = (total_u - dec_u) * (v[0] + v[1]) / (u[0] + u[1]) + dec_u * v[2] / u[2]
        print(f"| {c} | {u[0]:.0f} / {u[1]:.0f} / {u[2]:.0f} | {v[0]:.0f} / {v[1]:.0f} / {v[2]:.0f} | "
              f"{sum(v) / sum(u):.2f} | {total_u} | {est:.0f} |")
    print(f"\npeak RSS of the process (MB): Unified {max(rss['unified']):.0f}, v3 {max(rss['v3']):.0f}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "run":
        worker()
    elif command == "abortrun":
        abort_worker()
    elif command == "build":
        build()
    elif command == "fleurs":
        fleurs(int(sys.argv[2]) if len(sys.argv) > 2 else 100)
    elif command in ("parity", "wer", "speed", "abort"):
        globals()[command]()
    else:
        sys.exit(__doc__)
