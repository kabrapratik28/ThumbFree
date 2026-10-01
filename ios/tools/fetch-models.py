#!/usr/bin/env python3
"""Downloads the pinned Core ML models for development and tests: the English (v2) and Multilingual (v3) Parakeet
models and the Silero speech check, about 950 MB in all, each file checked by size and SHA-256 against
docs/models/model-manifest.json. Writes to $TF_MODELS_DIR/<model>, else to
~/Library/Application Support/FluidAudio/Models/<model>, where the package tests, tfbench, tfreplay and the app's tests
look. A file already there with the right size and SHA-256 is kept.
Run: python3 tools/fetch-models.py [model ...]   (for example silero-vad-coreml; with none, every model)"""
import hashlib
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Only the Silero model SpeechCheck uses: the repository holds an older one too.
ONLY = {"silero-vad-coreml": "silero-vad-unified-256ms-v6.2.1.mlmodelc/"}


def blocks(stream):
    return iter(lambda: stream.read(1 << 20), b"")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in blocks(f):
            digest.update(block)
    return digest.hexdigest()


def fetch(key, entry, models):
    out = os.path.join(models, key)
    for f in entry["files"]:
        if not f["path"].startswith(ONLY.get(key, "")):
            continue
        dst = os.path.join(out, f["path"])
        if os.path.exists(dst) and os.path.getsize(dst) == f["bytes"] and sha256(dst) == f["sha256"]:
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        url = f"https://huggingface.co/FluidInference/{key}/resolve/{entry['revision']}/{f['path']}"
        digest, size = hashlib.sha256(), 0
        with urllib.request.urlopen(url) as response, open(dst + ".part", "wb") as part:
            for block in blocks(response):
                digest.update(block)
                size += len(block)
                part.write(block)
        if size != f["bytes"] or digest.hexdigest() != f["sha256"]:
            os.remove(dst + ".part")
            sys.exit(f"checksum mismatch for {key}/{f['path']}")
        os.replace(dst + ".part", dst)
        print("ok", key, f["path"])
    print(key, "in", out)


manifest = json.load(open(os.path.join(ROOT, "docs", "models", "model-manifest.json")))
models = os.environ.get("TF_MODELS_DIR") or os.path.expanduser("~/Library/Application Support/FluidAudio/Models")
for key in sys.argv[1:] or [k for k in manifest if not k.startswith("_")]:
    if key.startswith("_") or key not in manifest:
        sys.exit(f"no model {key!r} in the manifest")
    fetch(key, manifest[key], models)
