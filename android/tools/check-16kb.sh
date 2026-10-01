#!/bin/sh
# Usage: android/tools/check-16kb.sh <apk>
# Exits 1 unless every .so in the APK has 16 KB aligned LOAD segments and the APK passes zipalign -P 16.
set -eu
apk=$1
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
readelf=$(ls "$sdk"/ndk/30.0.16248370/toolchains/llvm/prebuilt/*/bin/llvm-readelf)
zipalign=$(ls -d "$sdk"/build-tools/*/zipalign | sort -V | tail -1)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

unzip -q -o "$apk" 'lib/*' -d "$tmp" || { echo "no native libs to check in $apk"; exit 1; }
bad=0
for so in "$tmp"/lib/*/*.so; do
    if ! segments=$("$readelf" -lW "$so"); then
        echo "llvm-readelf failed: ${so#"$tmp"/}"
        bad=1
    elif echo "$segments" | awk '$1 == "LOAD" && $NF != "0x4000" { found = 1 } END { exit !found }'; then
        echo "not 16 KB aligned: ${so#"$tmp"/}"
        bad=1
    fi
done
[ "$bad" -eq 0 ] && echo "all LOAD segments Align 0x4000"

if "$zipalign" -c -P 16 4 "$apk"; then
    echo "zipalign -P 16: OK"
else
    echo "zipalign -P 16: FAILED"
    bad=1
fi
exit "$bad"
