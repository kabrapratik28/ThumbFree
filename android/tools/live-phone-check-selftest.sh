#!/bin/sh
# Live preview: proves android/tools/live-phone-check.sh's safety on an emulator (never the owner's phone):
#   1. android/tools/live-phone-summary.py --self-test: unreadable memory, a missing pair and a malformed row are not a
#      pass.
#   2. A test APK named for the bench app but instrumenting the owner's (-Pthumbfree.wrongTargetFixture) makes the check
#      exit nonzero before any change on the device: its packages, the owner's state and the accessibility services are
#      the same before and after.
#   3. SIGINT in the middle of a QUICK pass, sent to the run's process group as Ctrl-C in its terminal would be (so the
#      adb call in flight dies too), exits 130, uninstalls both bench APKs, and leaves the owner's state as it was.
#
#   android/tools/live-phone-check-selftest.sh SERIAL      (an emulator, under its lock)
#
# It builds three times (the fixture, then the bench app for runs 2 and 3, then the regular app back) and removes what
# it made: its temp folder, and on the device whatever the check's own cleanup handles.
set -u
S=${1:?usage: android/tools/live-phone-check-selftest.sh SERIAL}
a() { adb -s "$S" "$@" < /dev/null; }
ROOT=$(cd "$(dirname "$0")/.." && pwd) # android/, the Gradle project
TMP=$(mktemp -d /tmp/live-selftest.XXXXXX)
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"; export JAVA_HOME
FAILS=0
fail() { echo "[selftest] FAILED: $*"; FAILS=$((FAILS + 1)); }
say() { echo "[selftest] $*"; }
state() { # the device's packages, the owner's version and update time, the accessibility services: read only
    a shell pm list packages | tr -d '\r' | sort
    a shell dumpsys package io.github.kabrapratik28.thumbfree | tr -d '\r' | grep -E '^ *(versionCode|lastUpdateTime)='
    a shell settings get secure enabled_accessibility_services | tr -d '\r'
}
build() { (cd "$ROOT" && /bin/sh -c "./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q $*") > "$TMP/build.txt" 2>&1; }
trap 'rm -rf "$TMP"' EXIT

# 1. The summary's fail-closed exits.
python3 -B "$ROOT/tools/live-phone-summary.py" --self-test > "$TMP/summary.txt" 2>&1 || fail "summary self-test: $(tail -1 "$TMP/summary.txt")"

# 2. The wrong-target fixture: the bench app, and a test APK named .bench.test that instruments the owner's app.
build -Pthumbfree.wrongTargetFixture || { fail "building the fixture"; exit 1; }
cp "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$TMP/wrong-target-test.apk"
build -Pthumbfree.benchApp || { fail "building the bench app"; exit 1; }
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$TMP/bench.apk"
state > "$TMP/before.txt"
LIVE_APK="$TMP/bench.apk" LIVE_TEST_APK="$TMP/wrong-target-test.apk" SKIP_BUILD=1 OUT="$TMP/wrong" QUICK=1 \
    "$ROOT/tools/live-phone-check.sh" "$S" > "$TMP/wrong.txt" 2>&1
rc=$?
state > "$TMP/after.txt"
[ "$rc" -ne 0 ] || fail "the wrong-target run exited 0"
grep -q "instruments" "$TMP/wrong.txt" || fail "the wrong-target run did not stop at the instrumentation check"
cmp -s "$TMP/before.txt" "$TMP/after.txt" || { fail "the wrong-target run changed the device"; diff "$TMP/before.txt" "$TMP/after.txt"; }
say "wrong target: exit $rc; the check said:"
sed 's/^/    /' "$TMP/wrong.txt"

# 3. SIGINT in the middle of a QUICK pass (the bench build from step 2, and its own test APK).
cp "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$TMP/bench-test.apk"
state > "$TMP/before.txt"
set -m # the run gets its own process group, and does not start with SIGINT ignored as a script's background job does
LIVE_APK="$TMP/bench.apk" LIVE_TEST_APK="$TMP/bench-test.apk" SKIP_BUILD=1 OUT="$TMP/int" QUICK=1 \
    "$ROOT/tools/live-phone-check.sh" "$S" > "$TMP/int.txt" 2>&1 &
pid=$!
i=0
while ! grep -q -- "-30-r1 (thermal" "$TMP/int.txt" 2> /dev/null && [ $i -lt 120 ]; do sleep 1; i=$((i + 1)); done
sleep 10 # into the pass
kill -INT "-$pid"
wait "$pid"
rc=$?
set +m
state > "$TMP/after.txt"
[ "$rc" = 130 ] || fail "the interrupted run exited $rc, not 130"
grep -q "owner check: .* as they were" "$TMP/int.txt" || fail "the interrupted run's owner check did not pass"
grep -q "thumbfree.bench" "$TMP/after.txt" && fail "a bench package is still installed"
cmp -s "$TMP/before.txt" "$TMP/after.txt" || { fail "the interrupted run changed the device"; diff "$TMP/before.txt" "$TMP/after.txt"; }
say "interrupted: exit $rc; the check said:"
sed 's/^/    /' "$TMP/int.txt"

build || fail "building the regular app back"
say "$FAILS failure(s)"
[ "$FAILS" = 0 ]
