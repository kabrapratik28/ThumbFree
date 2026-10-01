#!/bin/sh
# Live preview: the phone check that decides the default of Settings > Bubble > Show words while I speak,
# engine only, on a disposable bench copy of the app. The phone owner's own ThumbFree ("the owner's app" below) is
# never installed over, instrumented, stopped or changed.
#
#   android/tools/live-phone-check.sh SERIAL
#
# For a maintainer to run on a phone they own. Every adb call names SERIAL; Gradle only assembles (no connected task).
#   0. A trap, armed as soon as the adb wrapper exists, uninstalls both bench APKs, removes this run's temp files and
#      sends the phone Home, however the script ends, once the run has changed anything on the phone; a run stopped
#      before that (a failed build, a refused APK) has only read it, and leaves it alone. Read-only checks at the start
#      and the end: the owner's app (io.github.kabrapratik28.thumbfree) keeps its versionCode and lastUpdateTime, and
#      enabled_accessibility_services keeps its exact value. A loop keeps the screen on meanwhile (a phone may lock
#      after 2 minutes).
#   1. Builds the bench app (-Pthumbfree.benchApp: <applicationId>.bench) and its test APK (SKIP_BUILD=1 skips the
#      build; LIVE_APK and LIVE_TEST_APK name other APKs), copies both to this run's folder, read-only, and checks the
#      copies (both name the bench packages; the test APK's one instrumentation is the AndroidX runner targeting the
#      bench app), all before the first change on the device, and installs exactly those copies fresh next to the
#      owner's app (no engine-env.txt there). Pushes the Parakeet Unified GGUF from the Hugging Face cache into the
#      bench app's files (adb push to /data/local/tmp, run-as cp, the temp copy removed) and checks its SHA-256 against
#      the catalog.
#   2. LivePreviewPhoneTest#take in the bench package (RemoteEngine and :engine; no bubble, no accessibility service, no
#      UiAutomator): for ROUNDS rounds (3), the offline path alone against the offline path with the preview's stream,
#      30 s and 60 s of JFK at real time, in a shuffled order, each once the phone is back at thermal status 0 (at most
#      COOL_S s, 300) and under a Perfetto trace of the power rails. LONG_S=900 adds one take that long with the preview
#      on. QUICK=1: one round of 30 s takes, no cooling.
#   3. android/tools/live-phone-summary.py writes $OUT/summary.md: the table and the rules for turning the preview on by
#      default (it keeps up at a real-time factor of 0.35 or better, the same final text with and without it, stop to
#      text at most 100 ms slower, energy per minute at most 2 times, thermal status at most 1, :engine's peak RSS
#      readable and under 1.2 GB), held to the exact list of takes the run made ($OUT/expected.txt). Anything but a
#      pass, a rule failed (1) or not decided (2: no power rails, a take missing), fails the run.
# A failed check is counted and the run goes on; the cleanup still runs, and the script exits nonzero if any failed, if
# a step stopped the run, or if a signal interrupted it (130 for INT, 143 for TERM, 129 for HUP). Results in OUT
# (/tmp/live-phone/<time>).
set -u
S=${1:?usage: android/tools/live-phone-check.sh SERIAL}
a() { adb -s "$S" "$@" < /dev/null; } # never the script's stdin

ROOT=$(cd "$(dirname "$0")/.." && pwd) # android/, the Gradle project
OUT=${OUT:-/tmp/live-phone/$(date +%Y%m%d-%H%M%S)}
RUN=live-check-$(date +%Y%m%d%H%M%S) # this run's directory in the bench app's files/bench, and its temp files' prefix
OWNER=io.github.kabrapratik28.thumbfree
BENCH=$OWNER.bench
TBENCH=$BENCH.test
RUNNER=$TBENCH/androidx.test.runner.AndroidJUnitRunner
BUILT_APK=${LIVE_APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}
BUILT_TAPK=${LIVE_TEST_APK:-$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}
APK=$OUT/apk/bench.apk # the run's own copies: what is checked is what is installed
TAPK=$OUT/apk/bench-test.apk
PERFETTO_CONFIG=/data/misc/perfetto-configs/$RUN.pbtxt # the power-rail trace's config, pushed once
MODEL=parakeet-unified-en-0.6b-Q8_0.gguf
MODEL_SHA256=4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795 # Catalog.PARAKEET_UNIFIED_Q8
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"; export JAVA_HOME
if [ "${QUICK:-0}" = 1 ]; then ROUNDS=${ROUNDS:-1}; LENGTHS=30; COOL_S=${COOL_S:-0}; else ROUNDS=${ROUNDS:-3}; LENGTHS="30 60"; fi

FAILS=0
KPID=
TPID=
STARTED= # set just before the run's first change on the phone; until then it has only read it
mkdir -p "$OUT/log" "$OUT/apk"
say() { echo "[live-check $(date +%H:%M:%S)] $*"; }
fail() { say "FAILED: $*"; FAILS=$((FAILS + 1)); echo "$*" >> "$OUT/failures.txt"; }
# The owner's app and the accessibility services, read-only; any difference between the start and the end fails the run.
owner_state() {
    printf '%s\n' "$(a shell dumpsys package "$OWNER" | tr -d '\r' | grep -E '^ *(versionCode|lastUpdateTime)=' | sed 's/^ *//')"
    printf 'enabled_accessibility_services=%s\n' "$(a shell settings get secure enabled_accessibility_services | tr -d '\r')"
}
installed() { a shell pm path "$1" 2> /dev/null | tr -d '\r' | grep -q '^package:'; }
cleanup() {
    rc=$? # the status the run ends with: a signal's 130, or the exit a failed step asked for
    exec 1>&3 2>&4 # to the script's own output, not the redirect of the call a signal interrupted (a take's log)
    trap '' INT TERM HUP
    trap - EXIT
    [ -z "$KPID" ] || kill "$KPID" 2> /dev/null
    [ -z "$TPID" ] || a shell kill "$TPID" 2> /dev/null
    if [ -n "$STARTED" ]; then # nothing to undo before it
        for p in "$TBENCH" "$BENCH"; do
            if installed "$p"; then a uninstall "$p" > /dev/null || fail "uninstalling $p"; fi
        done
        a shell "rm -f /data/local/tmp/$RUN-* /data/misc/perfetto-traces/$RUN-* $PERFETTO_CONFIG" || fail "removing the temp files"
    fi
    if [ -f "$OUT/owner-start.txt" ]; then
        owner_state > "$OUT/owner-end.txt"
        if cmp -s "$OUT/owner-start.txt" "$OUT/owner-end.txt"; then say "owner check: $OWNER and the accessibility services are as they were"
        else fail "the owner's app or the accessibility services changed:"; diff "$OUT/owner-start.txt" "$OUT/owner-end.txt"; fi
    fi
    [ -z "$STARTED" ] || a shell input keyevent KEYCODE_HOME || fail "Home"
    if [ "$rc" -ne 0 ] || [ "$FAILS" -gt 0 ]; then
        say "done with exit status $rc and $FAILS failed checks, see $OUT/failures.txt"
        if [ "$rc" -ne 0 ]; then exit "$rc"; fi
        exit 1
    fi
    say "done, summary in $OUT/summary.md"
    exit 0
}
exec 3>&1 4>&2 # the script's own output, for cleanup
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

[ "$(a get-state 2> /dev/null)" = device ] || { fail "$S is not attached"; exit 1; }
owner_state > "$OUT/owner-start.txt" || { fail "cannot read the owner's app state"; exit 1; }
say "output in $OUT, run directory files/bench/$RUN"
cat "$OUT/owner-start.txt"
echo "serial $S model $(a shell getprop ro.product.model | tr -d '\r') rounds $ROUNDS lengths $LENGTHS long ${LONG_S:-0}" \
    "commit $(git -C "$ROOT" rev-parse --short HEAD)" > "$OUT/device.txt"
(while :; do a shell input trackball roll 0 0 > /dev/null 2>&1; sleep 25; done) &
KPID=$!

thermal() { a shell dumpsys thermalservice 2> /dev/null | sed -n 's/^Thermal Status: //p' | head -1 | tr -d '\r'; }
# Waits (at most COOL_S s) for thermal status 0; fails if the phone is still warmer or its status cannot be read.
cool() {
    i=0
    while [ "$(thermal)" != 0 ] && [ $i -lt $((${COOL_S:-300} / 5)) ]; do sleep 5; i=$((i + 1)); done
    [ "$(thermal)" = 0 ]
}

# 1. The bench app and its model.
if [ "${SKIP_BUILD:-0}" != 1 ]; then
    if ! (cd "$ROOT" && /bin/sh -c './gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pthumbfree.benchApp -q') \
        > "$OUT/log/build.txt" 2>&1; then
        fail "the build, see $OUT/log/build.txt"
        exit 1
    fi
fi
cp "$BUILT_APK" "$APK" && cp "$BUILT_TAPK" "$TAPK" && chmod a-w "$APK" "$TAPK" || { fail "copying the APKs"; exit 1; }
AAPT2=$(ls -d "$HOME"/Library/Android/sdk/build-tools/* | tail -1)/aapt2
[ "$("$AAPT2" dump packagename "$APK")" = "$BENCH" ] || { fail "$APK is not $BENCH (build with -Pthumbfree.benchApp)"; exit 1; }
[ "$("$AAPT2" dump packagename "$TAPK")" = "$TBENCH" ] || { fail "$TAPK is not $TBENCH"; exit 1; }
# A test package's name says nothing of what it instruments: its manifest's instrumentation must be the AndroidX runner
# targeting the bench app, and it must declare no other.
"$AAPT2" dump xmltree --file AndroidManifest.xml "$TAPK" > "$OUT/test-manifest.txt" || { fail "reading $TAPK's manifest"; exit 1; }
TARGETS=$(python3 - "$OUT/test-manifest.txt" << 'EOF'
import re, sys
found, current = [], None
for line in open(sys.argv[1]):
    if re.match(r"\s*E: ", line):  # an element starts; its attributes follow until the next one
        current = {} if re.match(r"\s*E: instrumentation\b", line) else None
        if current is not None:
            found.append(current)
        continue
    attribute = re.match(r'\s*A: http://schemas.android.com/apk/res/android:(\w+)\([^)]*\)="([^"]*)"', line)
    if attribute and current is not None:
        current[attribute.group(1)] = attribute.group(2)
print(" ".join(f"{i.get('name')}>{i.get('targetPackage')}" for i in found))
EOF
)
[ "$TARGETS" = "androidx.test.runner.AndroidJUnitRunner>$BENCH" ] ||
    { fail "$TAPK instruments \"$TARGETS\", not androidx.test.runner.AndroidJUnitRunner>$BENCH"; exit 1; }
STARTED=1 # the first change on the phone comes next
for p in "$TBENCH" "$BENCH"; do # a copy left by an interrupted run goes first, so this one starts clean
    if installed "$p"; then a uninstall "$p" > /dev/null || { fail "removing an old $p"; exit 1; }; fi
done
a install -t "$APK" > /dev/null || { fail "installing $BENCH"; exit 1; }
a install -t "$TAPK" > /dev/null || { fail "installing $TBENCH"; exit 1; }
ENV=$(a shell "run-as $BENCH sh -c 'if [ -f files/engine-env.txt ]; then cat files/engine-env.txt; else echo absent; fi'" | tr -d '\r')
say "bench engine env (files/engine-env.txt): $ENV"
[ "$ENV" = absent ] || fail "the bench app has an engine-env.txt"
src=$(ls "$HOME"/.cache/huggingface/hub/models--handy-computer--*/snapshots/*/"$MODEL" 2> /dev/null | head -1)
[ -n "$src" ] || { fail "$MODEL is not in the Hugging Face cache"; exit 1; }
a push "$(realpath "$src")" "/data/local/tmp/$RUN-$MODEL" > /dev/null 2>&1 || { fail "pushing $MODEL"; exit 1; }
a shell "run-as $BENCH sh -c 'mkdir -p files/models && cp /data/local/tmp/$RUN-$MODEL files/models/$MODEL'" ||
    { fail "copying $MODEL into $BENCH"; exit 1; }
a shell "rm /data/local/tmp/$RUN-$MODEL" || fail "removing the temp copy of $MODEL"
sum=$(a shell "run-as $BENCH sha256sum files/models/$MODEL" | tr -d '\r' | cut -d' ' -f1)
[ "$sum" = "$MODEL_SHA256" ] || { fail "$MODEL on the phone has SHA-256 $sum, not the catalog's"; exit 1; }

# The power rails every 250 ms, from a config file (so perfetto's stdin is /dev/null, like every adb call's).
cat > "$OUT/perfetto.pbtxt" << 'PBTXT'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "android.power" android_power_config { battery_poll_ms: 250 collect_power_rails: true } } }
write_into_file: true
file_write_period_ms: 5000
flush_period_ms: 20000
duration_ms: 3600000
PBTXT
a push "$OUT/perfetto.pbtxt" "$PERFETTO_CONFIG" > /dev/null 2>&1 || fail "pushing the trace config (no power rails)"

# 2. The takes: each round's shuffled, the long take after the last round; expected.txt lists them all first.
takes() {
    if [ "$1" -le "$ROUNDS" ]; then
        for l in $LENGTHS; do printf 'off %s\non %s\n' "$l" "$l"; done |
            awk -v r="$1" 'BEGIN { srand(r) } { print rand() "\t" $1 "-" $2 }' | sort -n | cut -f2
    elif [ "${LONG_S:-0}" -gt 0 ]; then
        echo "on-$LONG_S"
    fi
}
r=1
while [ $r -le $((ROUNDS + 1)) ]; do
    for t in $(takes $r); do echo "${t%-*} ${t#*-} $r" >> "$OUT/expected.txt"; done
    r=$((r + 1))
done
r=1
while [ $r -le $((ROUNDS + 1)) ]; do
    for t in $(takes $r); do
        mode=${t%-*}
        seconds=${t#*-}
        tag=$t-r$r
        cool || fail "$tag started above thermal status 0"
        say "$tag (thermal $(thermal))"
        trace=/data/misc/perfetto-traces/$RUN-$tag.pftrace
        TPID=$(a shell perfetto --background --txt -c "$PERFETTO_CONFIG" -o "$trace" 2> /dev/null | tr -d '\r' | tail -1)
        a shell am instrument -w -e live_check 1 -e class io.github.kabrapratik28.thumbfree.bench.LivePreviewPhoneTest#take \
            -e mode "$mode" -e seconds "$seconds" -e out "$RUN" -e tag "$tag" "$RUNNER" > "$OUT/log/$tag.txt" 2>&1
        grep -q "^OK (1 test)" "$OUT/log/$tag.txt" || fail "$tag, see $OUT/log/$tag.txt"
        sleep 3 # rail samples past the take's end
        if [ -n "$TPID" ]; then
            a shell kill "$TPID" 2> /dev/null
            i=0
            while a shell kill -0 "$TPID" 2> /dev/null && [ $i -lt 20 ]; do sleep 1; i=$((i + 1)); done # it writes on exit
            a pull "$trace" "$OUT/$tag.pftrace" > /dev/null 2>&1 || say "no power-rail trace for $tag"
            a shell rm -f "$trace"
            TPID=
        fi
        for f in "take-$tag.tsv:$tag.tsv" "take-$tag.txt:$tag.txt" "env-$tag.txt:env-$tag.txt"; do
            a exec-out run-as "$BENCH" cat "files/bench/$RUN/${f%%:*}" > "$OUT/${f#*:}" 2> /dev/null || fail "no ${f%%:*}"
        done
    done
    r=$((r + 1))
done

# 3. The table and the rules: written first, its exit status kept, then shown.
python3 "$ROOT/tools/live-phone-summary.py" "$OUT" > "$OUT/summary.md" 2>&1
status=$?
cat "$OUT/summary.md"
case $status in
    0) ;;
    2) fail "the summary could not decide (a take, a pair or the power rails missing; see summary.md)" ;;
    *) fail "the summary failed (a rule, bad data or an error; see summary.md)" ;;
esac
