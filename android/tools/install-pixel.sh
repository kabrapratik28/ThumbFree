#!/bin/sh
# Usage: android/tools/install-pixel.sh <serial>
# Builds and installs the debug app, grants permissions, copies the model, and enables the accessibility
# service by appending it to the existing list (other services stay enabled).
set -eu
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"; export JAVA_HOME
serial=${1:?usage: android/tools/install-pixel.sh <serial>}
pkg=io.github.kabrapratik28.thumbfree
svc=$pkg/$pkg.a11y.DictationAccessibilityService
cd "$(dirname "$0")/.." # android/: the paths below start here
# THUMBFREE_ARMV9=1 builds with the Armv9 (SVE2) CPU module too, for comparison against the default Armv8.6 module.
# THUMBFREE_ONLY_CPU=<variant> (for example armv8.2_2) ships only that CPU module and the armv8.0_1 fallback.
/bin/sh -c "./gradlew :app:assembleDebug ${THUMBFREE_ARMV9:+-Pthumbfree.armv9} ${THUMBFREE_ONLY_CPU:+-Pthumbfree.onlyCpu=$THUMBFREE_ONLY_CPU}"
adb -s "$serial" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" shell pm grant "$pkg" android.permission.RECORD_AUDIO
./tools/push-test-model.sh "$serial"
# Canary 180M Flash too, when this Mac has it: the catalog's revision in the Hugging Face cache, else the local
# copy. Only its absence skips it; a failed push stops the script.
canary=
for f in "$HOME/.cache/huggingface/hub/models--handy-computer--canary-180m-flash-gguf/snapshots/456e6049062ecf06f1a0f4607f2ee3dc80ebbf8a/canary-180m-flash-Q8_0.gguf" \
    "$HOME/.cache/thumbfree/models/canary-180m-flash-Q8_0.gguf"; do
    if [ -f "$f" ]; then canary=$f; break; fi
done
if [ -n "$canary" ]; then ./tools/push-test-model.sh "$serial" "$canary"; else echo "no Canary on this Mac: not pushed"; fi
cur=$(adb -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')
case ":$cur:" in
    *":$svc:"*) new=$cur ;;
    ":null:" | "::") new=$svc ;;
    *) new="$cur:$svc" ;;
esac
adb -s "$serial" shell settings put secure enabled_accessibility_services "$new"
adb -s "$serial" shell settings put secure accessibility_enabled 1
adb -s "$serial" shell am start -n "$pkg/.ui.MainActivity"
echo ready
