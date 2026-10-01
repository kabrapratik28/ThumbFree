#!/bin/sh
# Usage: android/tools/push-test-model.sh <serial> [file]
# Copies a GGUF into the debug app's filesDir/models/, where the instrumented tests look (TestModels).
# [file] is a path, or a file name found in the Hugging Face cache or else in ~/.cache/thumbfree/models
# (default: Parakeet Unified EN Q8_0).
# gradle.properties keeps the app installed after connectedAndroidTest, so one push lasts until the app is uninstalled.
set -eu
serial=$1
file=${2:-parakeet-unified-en-0.6b-Q8_0.gguf}
pkg=io.github.kabrapratik28.thumbfree
apk=$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk

if [ -f "$file" ]; then
    src=$file
else
    src=
    for f in "$HOME"/.cache/huggingface/hub/models--handy-computer--*/snapshots/*/"$file" "$HOME/.cache/thumbfree/models/$file"; do
        if [ -f "$f" ]; then src=$f; break; fi
    done
    if [ -z "$src" ]; then echo "$file: not in the Hugging Face cache or ~/.cache/thumbfree/models" >&2; exit 1; fi
fi
src=$(realpath "$src") # hub snapshots are symlinks into blobs/
name=$(basename "$file")

a() { adb -s "$serial" "$@" < /dev/null; }
# A temp copy of this run's own, removed however the script ends.
tmp=/data/local/tmp/push-test-model-$$-$name
trap 'a shell rm -f "$tmp"' EXIT

# run-as needs the app installed.
a shell pm path "$pkg" >/dev/null || a install -r -t "$apk"
a push "$src" "$tmp"
a shell "run-as $pkg sh -c 'mkdir -p files/models && cp $tmp files/models/$name'"
a shell "run-as $pkg ls -l files/models/$name"
