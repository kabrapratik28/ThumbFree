#!/usr/bin/env python3
"""Engine speed, memory and accuracy on a device, for comparing engine builds.

Usage, under the emulator lock (the script installs its APKs, so another checkout's install cannot slip in between):
  lockf -k /tmp/thumbfree-emulator.lock android/tools/engine-bench.py <serial> <tag> <config>...
A config is name[@apkdir]=[ENV=1,ENV2=1]: the APKs in apkdir (app-debug.apk and app-debug-androidTest.apk; default
this checkout's build outputs) with those transcribe.cpp environment switches, each run in its own process
(EngineBenchTest) with THREADS threads (default 4, as NativeEngineTest); each timed run starts after GAP_S seconds of
rest (default 0) and, with THERMAL_WAIT=1, once the phone's thermal status is 0. RESUME=1 keeps what an interrupted
run with the same tag already saved.
Configs alternate over 3 rounds of 10 warm runs on a 3.7 s clip, JFK and a 29.4 s clip; then each transcribes the 11
public clips, 23 private clips over 10 s and 5 private edge clips (testdata/private, not in the repository; the tool
needs them), plus DITHER (default 4) copies of the first two sets
with +-1 LSB of dither, whose mean WER is the accuracy gate. Prints encoder and decoder ms (median, p90),
PSS, peak RSS, WER, the words that changed against the first config, and the Mac's load average. Private texts stay in
$CORPUS/results/engine-bench/ (default testdata/private in the main checkout); only numbers are printed.
"""
import csv
import os
import pathlib
import re
import statistics
import subprocess
import sys
import time
import wave

ROOT = pathlib.Path(__file__).resolve().parents[2]
PUBLIC = ROOT / "testdata/public-bench"
# The private corpus is in the main checkout, the folder holding the shared .git, also when this runs in a worktree.
GIT_DIR = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                         capture_output=True, text=True, check=True).stdout.strip()
CORPUS = pathlib.Path(os.environ.get("CORPUS", pathlib.Path(GIT_DIR).parent / "testdata/private"))
BUILD = ROOT / "android/app/build/outputs/apk"
PKG = "io.github.kabrapratik28.thumbfree"
SPEED_CLIPS = ["1272-141231-0017.wav", "jfk.wav", "1272-128104-0004.wav"]  # 3.7 s, 11 s, 29.4 s
EMPTY_EDGE = ["silence-3s.wav", "noise-3s.wav", "tap-0.25s.wav"]  # must type nothing; the other two carry the phrase


def adb(serial, *args):
    for attempt in range(3):  # a phone's adb link and package manager fail now and then; the calls are repeatable
        try:
            return subprocess.run(["adb", "-s", serial, *args], check=True, capture_output=True, text=True,
                                  stdin=subprocess.DEVNULL).stdout
        except subprocess.CalledProcessError as e:
            if attempt == 2:
                sys.exit(f"adb {' '.join(args)[:200]} failed:\n{e.stdout}{e.stderr}")
            time.sleep(10)


def clip_sets():
    long = sorted(p for p in (CORPUS / "desktop").glob("*.wav") if wave.open(str(p)).getnframes() > 10 * 16000)
    return {"public": sorted(PUBLIC.glob("[0-9j]*.wav")), "long": long, "edge": sorted((CORPUS / "edge").glob("*.wav"))}


def push(serial):
    """Copies the clips into filesDir/bench/<set>/ unless they are there."""
    for name, clips in clip_sets().items():
        have = adb(serial, "shell", f"run-as {PKG} sh -c 'ls files/bench/{name} 2>/dev/null | wc -l'").strip()
        if have == str(len(clips)):
            continue
        adb(serial, "shell", "rm -rf /data/local/tmp/bench && mkdir -p /data/local/tmp/bench")
        adb(serial, "push", *map(str, clips), "/data/local/tmp/bench/")
        adb(serial, "shell", f"run-as {PKG} sh -c 'rm -rf files/bench/{name} && mkdir -p files/bench/{name} && "
                             f"cp /data/local/tmp/bench/*.wav files/bench/{name}/' && rm -rf /data/local/tmp/bench")


class Device:
    def __init__(self, serial, tag):
        self.serial, self.tag, self.installed = serial, tag, None

    def cool(self):
        """Before a timed run: GAP_S seconds of rest, then with THERMAL_WAIT=1 thermal status 0 (polled every 5 s)."""
        time.sleep(float(os.environ.get("GAP_S", "0")))
        if os.environ.get("THERMAL_WAIT"):
            for _ in range(180):
                if "Thermal Status: 0" in adb(self.serial, "shell", "dumpsys thermalservice"):
                    break
                time.sleep(5)

    def run(self, method, config, suffix, **args):
        name, apks, env = config
        tag = f"{self.tag}-{name}-{suffix}"
        dest = CORPUS / f"results/engine-bench/{method}-{tag}.tsv"
        if os.environ.get("RESUME") and dest.exists():  # RESUME=1 keeps the results an interrupted run already saved
            return dest
        if apks != self.installed:
            for apk in apks:
                adb(self.serial, "install", "-r", "-t", str(apk))
            self.installed = apks
        if method == "speed":
            self.cool()
        args = {"engine_bench": "1", "tag": tag, "env": env, "threads": os.environ.get("THREADS", "4"), **args}
        extra = sum((["-e", k, v] for k, v in args.items() if v), [])
        out = adb(self.serial, "shell", "am", "instrument", "-w", "-e", "class",
                  f"io.github.kabrapratik28.thumbfree.bench.EngineBenchTest#{method}", *extra, f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in out:
            sys.exit(f"{method} {tag} failed:\n{out[-3000:]}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(adb(self.serial, "shell", f"run-as {PKG} cat files/bench/{method}-{tag}.tsv"))
        return dest


def words(text):
    return re.sub(" +", " ", re.sub("[^a-z0-9 ]", " ", text.lower())).strip().split()


def edits(ref, hyp):
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (r != h))
    return row[-1]


def refs():
    out = {}
    for path, col in [(PUBLIC / "refs.tsv", "text"), (CORPUS / "desktop/refs.tsv", "desktop_text")]:
        with open(path, newline="") as f:
            out.update({r["file"]: r[col] for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE)})
    phrase = (CORPUS / "edge/phrase.txt").read_text()
    out.update({f: phrase for f in ["lead-silence-2s.wav", "trail-silence-3s.wav"]} | {f: "" for f in EMPTY_EDGE})
    return out


def texts(path):
    return {f: t for f, _, t in (line.rstrip("\n").partition("\t") for line in open(path)) if f}


def p90(values):
    return sorted(values)[max(0, round(0.9 * len(values)) - 1)]


def main(serial, tag, specs):
    configs = []
    for spec in specs:
        name, _, env = spec.partition("=")
        name, _, apkdir = name.partition("@")
        apks = ((pathlib.Path(apkdir) / "app-debug.apk", pathlib.Path(apkdir) / "app-debug-androidTest.apk") if apkdir
                else (BUILD / "debug/app-debug.apk", BUILD / "androidTest/debug/app-debug-androidTest.apk"))
        configs.append((name, apks, env))
    device = Device(serial, tag)
    adb(serial, "install", "-r", "-t", str(configs[0][1][0]))  # run-as needs the app
    push(serial)

    load = [f"{os.getloadavg()[0]:.0f}"]  # the Mac's 1-minute load average; the emulator shares its cores
    speed = {name: [] for name, _, _ in configs}
    for rnd in range(3):
        for config in (configs if rnd % 2 == 0 else configs[::-1]):
            path = device.run("speed", config, f"r{rnd}", clips=",".join(SPEED_CLIPS), runs="10")
            speed[config[0]] += list(csv.DictReader(open(path), delimiter="\t"))
        load.append(f"{os.getloadavg()[0]:.0f}")
    # Seed 0 is the clips as they are; seeds 1 to DITHER (default 4) add +-1 LSB of dither, and the gate reads their mean,
    # because one pass can flip a knife-edge clip either way whatever the engine.
    seeds = range(int(os.environ.get("DITHER", "4")) + 1)
    accuracy = {c[0]: {(d, s): texts(device.run("accuracy", c, f"{d}-s{s}", dir=d, dither=str(s)))
                       for d in ["public", "long", "edge"] for s in (seeds if d != "edge" else [0])} for c in configs}

    print(f"Mac load average (1 min) at the start and after each speed round: {' / '.join(load)}")
    print("| config | clip | runs | encoder ms p50 | p90 | decoder ms p50 | p90 | PSS MB | peak RSS MB |")
    print("|---|---|---|---|---|---|---|---|---|")
    for name, rows in speed.items():
        for clip in SPEED_CLIPS:
            c = [r for r in rows if r["clip"] == clip]
            enc, dec = [float(r["encode_ms"]) for r in c], [float(r["decode_ms"]) for r in c]
            print(f"| {name} | {clip} | {len(c)} | {statistics.median(enc):.0f} | {p90(enc):.0f} | {statistics.median(dec):.0f} | "
                  f"{p90(dec):.0f} | {statistics.median(int(r['pss_kb']) for r in c) / 1024:.0f} | "
                  f"{max(int(r['vm_hwm_kb']) for r in c) / 1024:.0f} |")
    for name, rows in speed.items():
        print(f"{name}: thermal status max {max(int(r['thermal']) for r in rows)}, allowed CPUs "
              f"{' '.join(sorted({r['cpus'] for r in rows}))}")
    print()
    ref, first = refs(), configs[0][0]
    for name in accuracy:
        parts = []
        for d in ["public", "long"]:
            errors, moved = [], []
            for s in seeds:
                hyp, base = accuracy[name][(d, s)], accuracy[first][(d, s)]
                errors.append(sum(edits(words(ref[f]), words(t)) for f, t in hyp.items()))
                moved.append(sum(edits(words(base[f]), words(hyp[f])) for f in hyp))
            hyp = accuracy[name][(d, 0)]
            n = sum(len(words(ref[f])) for f in hyp)
            jfk = f", JFK exact {words(hyp['jfk.wav']) == words(ref['jfk.wav'])}" if d == "public" else ""
            dithered = (f"; dithered mean WER {100 * sum(errors[1:]) / (len(errors) - 1) / n:.2f}% (errors by seed "
                        f"{errors[1:]}, words changed vs {first} {moved[1:]})") if len(errors) > 1 else ""
            parts.append(f"{d} {len(hyp)} clips/{n} words: WER {100 * errors[0] / n:.2f}% ({errors[0]} errors), "
                         f"{moved[0]} words changed vs {first}{dithered}{jfk}")
        edge = accuracy[name][("edge", 0)]
        parts.append(f"edge: {sum(bool(words(edge[f])) for f in EMPTY_EDGE)} of {len(EMPTY_EDGE)} silence/noise/tap typed "
                     f"text; lead/trail errors vs phrase "
                     f"{sum(edits(words(ref[f]), words(t)) for f, t in edge.items() if f not in EMPTY_EDGE)}")
        print(f"{name}: " + "; ".join(parts))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
