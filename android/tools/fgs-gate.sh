#!/bin/sh
# Usage: android/tools/fgs-gate.sh <serial>
# With none of our activities visible, a tap on the bubble over another app's field must start the microphone
# service and capture unsilenced audio. Runs without instrumentation. Prints "fgs-gate: ok" or a FAIL reason.
set -eu
serial=${1:?usage: android/tools/fgs-gate.sh <serial>}
a() { adb -s "$serial" "$@"; }
a shell input keyevent KEYCODE_HOME
sleep 8   # past the 5 s grace after our activity went invisible, which alone would allow a background service start
a logcat -b main -b system -b events -c
trap 'a shell input keyevent KEYCODE_BACK' EXIT   # every exit, a FAIL too, leaves the Settings search
a shell am start -a android.settings.APP_SEARCH_SETTINGS >/dev/null   # Settings search: a focused field in another app
sleep 3
line=$(a logcat -d -s ThumbFree | grep 'bubble_shown' | tail -1)
[ -n "$line" ] || { echo "fgs-gate: FAIL no bubble over the other app's field"; exit 1; }
x=$(echo "$line" | sed 's/.*x=\([0-9]*\).*/\1/'); y=$(echo "$line" | sed 's/.* y=\([0-9]*\).*/\1/')
w=$(echo "$line" | sed 's/.* w=\([0-9]*\).*/\1/'); h=$(echo "$line" | sed 's/.* h=\([0-9]*\).*/\1/')
cx=$((x + w / 2)); cy=$((y + h / 2))
a shell input tap "$cx" "$cy"    # tap: a locked take starts
sleep 3
a shell input tap "$cx" "$cy"    # tap again: stop
sleep 2
log=$(a logcat -d -s ThumbFree)
if echo "$log" | grep -q 'take_denied'; then echo "fgs-gate: FAIL microphone service refused"; exit 1; fi
echo "$log" | grep -q 'take_capture fgs=true silenced=false' || { echo "fgs-gate: FAIL no unsilenced capture"; exit 1; }
# The system logs the end of the microphone service, which comes when the take's tail ends.
a logcat -d -b events | grep -q 'am_foreground_service_stop.*RecordingService' ||
    { echo "fgs-gate: FAIL the stop tap did not end the take; it may still be recording"; exit 1; }
echo "fgs-gate: ok"
