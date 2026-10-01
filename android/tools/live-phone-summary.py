#!/usr/bin/env python3
"""Live preview: android/tools/live-phone-check.sh's table and rules, from its OUT folder, as markdown on stdout.

  live-phone-summary.py OUT

Reads OUT/<mode>-<seconds>-r<round>.tsv (LivePreviewPhoneTest's row), .txt (the take's final text), env-*.txt (the
engine's effective environment) and .pftrace (the power rails). The rules for turning Show words while I speak on by
default, each for every take length run both ways:
  - the stream keeps up (never behind, never failed) with a median real-time factor of 0.35 or better;
  - each preview-on take types exactly the text of its round's preview-off take;
  - the median stop to text with the preview is at most 100 ms over the median without it;
  - energy per minute of dictation with the preview at most 2 times without it;
  - no thermal status above 1;
  - :engine's peak RSS under 1.2 GB (1,200 MB) in every take, and readable (above 0).
It fails closed: OUT/expected.txt, which the check writes, lists every take it ran as "mode seconds round", and a
missing or extra take, an unreadable peak RSS, a malformed row or any error is not a pass. Exit status 0
pass, 1 fail (a rule, bad data or an error), 2 not decided (no power rails, a missing take or pair, no expected list).
--self-test checks those exits on fixtures it makes.
"""
import bisect
import csv
import glob
import os
import statistics
import sys
from collections import defaultdict

sys.dont_write_bytecode = True

RTF_MAX, ENERGY_MAX, THERMAL_MAX, HWM_MAX_MB, STOP_MAX_MS = 0.35, 2.0, 1, 1200, 100


# Perfetto power rails, read without trace_processor: TracePacket timestamp 8, power_rails 40; PowerRails
# rail_descriptor 1 (index 1), energy_data 2 (index 1, timestamp_ms 2, energy 3 in uWs).
def varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, i
        shift += 7


def fields(buf, start=0, end=None):
    i, end = start, len(buf) if end is None else end
    while i < end:
        key, i = varint(buf, i)
        wt = key & 7
        if wt == 0:
            v, i = varint(buf, i)
        elif wt == 1:
            v, i = int.from_bytes(buf[i:i + 8], "little"), i + 8
        elif wt == 2:
            n, i = varint(buf, i)
            v, i = (i, i + n), i + n
        elif wt == 5:
            v, i = int.from_bytes(buf[i:i + 4], "little"), i + 4
        else:
            raise ValueError("wire type %d at %d" % (wt, i))
        yield key >> 3, wt, v


def rails(path):
    """{rail index: (boot-clock ms list, cumulative uWs list)}, empty without a trace or rails."""
    if not os.path.exists(path):
        return {}
    buf = open(path, "rb").read()
    data = defaultdict(list)
    for fn, wt, span in fields(buf):
        if fn != 1 or wt != 2:
            continue
        ts, pr = 0, None
        for pf, _, pv in fields(buf, *span):
            if pf == 8:
                ts = pv
            elif pf == 40:
                pr = pv
        if pr:
            for rf, _, rv in fields(buf, *pr):
                if rf == 2:
                    d = {k: v for k, _, v in fields(buf, *rv)}
                    data[d.get(1, 0)].append((d[2] if d.get(2) else ts / 1e6, d.get(3, 0)))
    return {k: tuple(map(list, zip(*sorted(v)))) for k, v in data.items() if len(v) > 1}


def rail_energy(tr, t0, t1):
    """mJ of all rails between boot-clock ms t0 and t1 (interpolated), None if a rail has no sample within 1 s."""
    total = 0.0
    for ts, vs in tr.values():
        e = []
        for t in (t0, t1):
            i = bisect.bisect_left(ts, t)
            if i == 0 or i == len(ts) or t - ts[i - 1] > 1000 or ts[i] - t > 1000:
                return None
            e.append(vs[i - 1] + (vs[i] - vs[i - 1]) * (t - ts[i - 1]) / (ts[i] - ts[i - 1]))
        total += e[1] - e[0]
    return total / 1000 if tr else None


def main(out):
    expected_path = os.path.join(out, "expected.txt")
    if not os.path.exists(expected_path):
        print("No expected.txt in %s: nothing to hold the takes to." % out)
        return 2
    expected = {tuple(line.split()) for line in open(expected_path) if line.strip()}  # (mode, seconds, round)
    rows = []
    for path in sorted(glob.glob(os.path.join(out, "*-r*.tsv"))):
        tag = os.path.basename(path)[:-4]
        with open(path, newline="") as f:
            for row in csv.DictReader(f, delimiter="\t"):
                start, end = int(row["start_ms"]), int(row["end_ms"])
                mj = rail_energy(rails(path[:-4] + ".pftrace"), start, end)
                row["mj_per_min"] = mj / ((end - start) / 60_000) if mj is not None else None
                row["round"] = tag.rsplit("-r", 1)[1]
                text = path[:-4] + ".txt"
                row["text"] = open(text).read() if os.path.exists(text) else None
                rows.append(row)
    found = {(r["mode"], r["seconds"], r["round"]) for r in rows}
    if found != expected:
        print("The takes are not the ones expected: missing %s, extra %s." % (
            sorted(expected - found) or "none", sorted(found - expected) or "none"))
        return 2
    unreadable = [r for r in rows if int(r["engine_hwm_kb"]) <= 0 or int(r["seconds"]) >= 60 and int(r["engine_hwm_1min_kb"]) <= 0]
    if unreadable:
        print("Unreadable peak RSS in %d take(s): no verdict on memory." % len(unreadable))
        return 1
    groups = {}
    for row in rows:
        groups.setdefault((row["mode"], int(row["seconds"])), []).append(row)
    med = lambda v: statistics.median(v) if v else float("nan")
    print("| take | runs | stream RTF (median, max) | most audio behind, ms | behind / failed | stop to text, ms (median) | "
          ":engine PSS, MB (max) | peak RSS growth after 1 min, MB (max) | :engine peak RSS, MB (max) | thermal (max) | "
          "energy, mJ per minute (median) |\n|---|---|---|---|---|---|---|---|---|---|---|")
    for (mode, seconds), g in sorted(groups.items()):
        rtf = [float(r["stream_rtf"]) for r in g]
        energy = [r["mj_per_min"] for r in g if r["mj_per_min"] is not None]
        growth = [int(r["engine_hwm_kb"]) - int(r["engine_hwm_1min_kb"]) for r in g if int(r["engine_hwm_1min_kb"]) > 0]
        print("| preview %s, %d s | %d | %s | %d | %d / %d | %.0f | %.0f | %s | %.0f | %d | %s |" % (
            mode, seconds, len(g), "%.3f, %.3f" % (med(rtf), max(rtf)) if mode == "on" else "-",
            max(int(r["behind_max_ms"]) for r in g), sum(int(r["behind"]) for r in g), sum(int(r["failed"]) for r in g),
            med([int(r["stop_to_text_ms"]) for r in g]), max(int(r["engine_pss_kb"]) for r in g) / 1024,
            "%.0f" % (max(growth) / 1024) if growth else "-", max(int(r["engine_hwm_kb"]) for r in g) / 1024,
            max(int(r["thermal_max"]) for r in g), "%.0f" % med(energy) if energy else "no rails"))

    verdict = lambda ok: "unknown" if ok is None else ("pass" if ok else "FAIL")
    on = [r for r in rows if r["mode"] == "on"]
    keeps_up = bool(on) and all(r["behind"] == "0" and r["failed"] == "0" for r in on) and \
        med([float(r["stream_rtf"]) for r in on]) <= RTF_MAX
    paired = sorted({s for (m, s) in groups if m == "off"} & {s for (m, s) in groups if m == "on"})
    # The same text: each preview-on take against the preview-off take of its round and length.
    by_round = {(r["mode"], int(r["seconds"]), r["round"]): r for r in rows}
    pairs = [(by_round[("off", s, r["round"])], r) for r in on for s in [int(r["seconds"])]
             if ("off", s, r["round"]) in by_round]
    same_text = None if not pairs or any(p[0]["text"] is None or p[1]["text"] is None for p in pairs) else \
        all(off["text"] == on_["text"] for off, on_ in pairs)
    stop_delta = [med([int(r["stop_to_text_ms"]) for r in groups[("on", s)]]) -
                  med([int(r["stop_to_text_ms"]) for r in groups[("off", s)]]) for s in paired]
    stop_ok = None if not stop_delta else all(d <= STOP_MAX_MS for d in stop_delta)
    ratios = []
    for seconds in paired:
        e = {m: [r["mj_per_min"] for r in groups[(m, seconds)] if r["mj_per_min"] is not None] for m in ("on", "off")}
        ratios.append(med(e["on"]) / med(e["off"]) if e["on"] and e["off"] else None)
    energy_ok = None if None in ratios or not ratios else all(x <= ENERGY_MAX for x in ratios)
    thermal_ok = max(int(r["thermal_max"]) for r in rows) <= THERMAL_MAX
    hwm = max(int(r["engine_hwm_kb"]) for r in rows) / 1024
    hwm_ok = hwm < HWM_MAX_MB
    print("\n- Keeps up (never behind or failed, median real-time factor at most %.2f): %s" % (RTF_MAX, verdict(keeps_up)))
    print("- The same final text with and without the preview (%d pairs): %s" % (len(pairs), verdict(same_text)))
    print("- Stop to text with the preview against without, per take length: %s (at most +%d ms): %s" % (
        ", ".join("%+.0f ms" % d for d in stop_delta) or "no length run both ways", STOP_MAX_MS, verdict(stop_ok)))
    print("- Energy per minute with the preview against without, per take length: %s (at most %.1f): %s" % (
        ", ".join("%.2f" % x if x is not None else "no rails" for x in ratios) or "no length run both ways", ENERGY_MAX,
        verdict(energy_ok)))
    print("- Thermal status at most %d: %s" % (THERMAL_MAX, verdict(thermal_ok)))
    print("- :engine's peak RSS %.0f MB, under %d MB: %s" % (hwm, HWM_MAX_MB, verdict(hwm_ok)))
    envs = sorted({open(p).read().strip() for p in glob.glob(os.path.join(out, "env-*.txt"))})
    env_lines = [line for e in envs for line in e.splitlines() if line.startswith("env=")]
    print("- The engine's effective environment: %s" % (", ".join(sorted(set(env_lines))) or "not recorded"))
    rules = [keeps_up, same_text, stop_ok, energy_ok, thermal_ok, hwm_ok]
    result = 1 if False in rules else (2 if None in rules else 0)
    print("\nDefault on: %s" % {0: "yes, every rule passes", 1: "no", 2: "not decided (a rule has no data)"}[result])
    return result


def self_test():
    """The fail-closed exits on made-up takes, through the command line as the check runs it: unreadable memory 1, a
    missing pair 2, a malformed row 1 (an exception, caught at the top)."""
    import subprocess
    import tempfile

    header = ("mode\tseconds\taudio_ms\tcalls\tcompute_ms\tstream_rtf\tchunk_max_ms\tbehind_max_ms\tbehind\tfailed\ttexts\t"
              "stop_to_text_ms\tengine_pss_kb\tengine_hwm_1min_kb\tengine_hwm_kb\tthermal_max\tstart_ms\tend_ms")

    def run(takes, expected):
        with tempfile.TemporaryDirectory() as out:
            for tag, row in takes.items():
                open(os.path.join(out, tag + ".tsv"), "w").write(header + "\n" + row + "\n")
                open(os.path.join(out, tag + ".txt"), "w").write("And so, my fellow Americans.")
            open(os.path.join(out, "expected.txt"), "w").write("".join(e + "\n" for e in expected))
            return subprocess.run([sys.executable, "-B", os.path.abspath(__file__), out], capture_output=True).returncode

    good = "30\t30000\t10\t100\t0.15\t200\t300\t0\t0\t5\t220\t700000\t0\t950000\t0\t1000\t31000"
    both = ["off 30 1", "on 30 1"]
    assert run({"off-30-r1": "off\t" + good, "on-30-r1": "on\t" + good}, both) == 2  # no power rails: not decided
    zero = good.replace("\t950000\t", "\t0\t")
    assert run({"off-30-r1": "off\t" + good, "on-30-r1": "on\t" + zero}, both) == 1  # unreadable peak RSS
    assert run({"on-30-r1": "on\t" + good}, both) == 2  # the off take of the pair is missing
    assert run({"off-30-r1": "off\t" + good, "on-30-r1": "on\tthirty\t30000"}, both) == 1  # a malformed row
    assert run({"off-30-r1": "off\t" + good, "on-30-r1": "on\t" + good}, []) == 2  # takes nobody expected
    print("self-test: pass")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    try:
        sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
    except Exception as e:  # bad data is never a pass
        print("The summary failed: %r" % e)
        sys.exit(1)
