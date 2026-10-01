#!/usr/bin/env python3
"""Build testdata/public from public sources.

Run: uv run --with pyarrow tools/fetch-public-audio.py
LibriSpeech clips come from the cached Hugging Face dataset hf-internal-testing/librispeech_asr_dummy;
jfk.wav and fleurs-de.wav are copied (read only) from the Android app's test assets in this repository,
android/app/src/androidTest/assets/audio; TF_ANDROID_AUDIO names another folder.
"""
import glob, os, subprocess, tempfile
import pyarrow.parquet as pq

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "testdata", "public")
ANDROID = os.environ.get("TF_ANDROID_AUDIO") or os.path.join(ROOT, "..", "android", "app", "src", "androidTest", "assets", "audio")
WANT = ["1272-141231-0017", "1272-128104-0001", "1272-128104-0000", "1272-135031-0023",
        "1272-135031-0010", "1272-128104-0003", "1272-128104-0002", "1272-128104-0011",
        "1272-128104-0009", "1272-128104-0004"]
NEED = 11  # LibriSpeech clips


def to_wav16k(src, dst):
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", src, dst], check=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    refs = {}
    parquet = glob.glob(os.path.expanduser(
        "~/.cache/huggingface/hub/datasets--hf-internal-testing--librispeech_asr_dummy/snapshots/*/clean/validation-00000-of-00001.parquet"))[0]
    rows = pq.read_table(parquet).to_pylist()
    chosen = [r for r in rows if r["id"] in WANT]
    chosen += [r for r in rows if r["id"] not in WANT][: max(0, NEED - len(chosen))]
    with tempfile.TemporaryDirectory() as tmp:
        for row in chosen:
            flac = os.path.join(tmp, row["id"] + ".flac")
            with open(flac, "wb") as f:
                f.write(row["audio"]["bytes"])
            to_wav16k(flac, os.path.join(OUT, row["id"] + ".wav"))
            refs[row["id"] + ".wav"] = row["text"]
    for name in ["jfk.wav", "fleurs-de.wav"]:
        to_wav16k(os.path.join(ANDROID, name), os.path.join(OUT, name))
    refs["jfk.wav"] = "and so my fellow americans ask not what your country can do for you ask what you can do for your country"
    refs["fleurs-de.wav"] = "dieses sediment war nötig um sandbänke und strände zu bilden die als lebensräume für wildtiere dienten"
    with open(os.path.join(OUT, "refs.tsv"), "w") as f:
        for k in sorted(refs):
            f.write(f"{k}\t{refs[k]}\n")
    print(f"wrote {len(refs)} clips to {OUT}")


if __name__ == "__main__":
    main()
