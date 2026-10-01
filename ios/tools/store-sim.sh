#!/usr/bin/env bash
# Gets a Simulator ready for tools/store-screenshots.sh and tools/store-video.sh and prints its UDID: the ThumbFree
# keyboard (tools/sim-enable-keyboard.sh), a 9:41 status bar with full bars and battery, the microphone permission the
# Try tab reads (the tests play a file instead), no unsent draft in Messages, and Maya, a made-up contact. The Simulator's
# Messages shows two sample conversations; the one with +1 (888) 555-1212 (the sample contact John Appleseed's number)
# becomes hers when the number moves to her card, so it shows her picture. ThumbFree's old Core ML copies go too, as in
# tools/test-app.sh.
# Usage: tools/store-sim.sh ["<Simulator name>"]   (default "iPhone 17 Pro Max (store)", iOS 26.5)
set -euo pipefail
cd "$(dirname "$0")/.."
udid=$(tools/sim-enable-keyboard.sh "${1:-iPhone 17 Pro Max (store)}" 26.5)
data="$HOME/Library/Developer/CoreSimulator/Devices/$udid/data"
xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --operatorName "" --batteryState charged --batteryLevel 100
xcrun simctl privacy "$udid" grant microphone io.github.kabrapratik28.thumbfree
xcrun simctl terminate "$udid" com.apple.MobileSMS 2>/dev/null || true
messages=$(xcrun simctl get_app_container "$udid" com.apple.MobileSMS data)
[ -d "$messages/Library/SMS" ] && rm -rf "$messages/Library/SMS/Drafts"
contacts="$data/Library/AddressBook/AddressBook.sqlitedb"
if [ "$(sqlite3 "$contacts" "SELECT count(*) FROM ABPerson WHERE First = 'Maya'")" = 0 ]; then
  card="$(mktemp -d)/maya.vcf"
  printf 'BEGIN:VCARD\r\nVERSION:3.0\r\nN:;Maya;;;\r\nFN:Maya\r\nEND:VCARD\r\n' > "$card"
  xcrun simctl addmedia "$udid" "$card"
fi
sqlite3 "$contacts" "UPDATE ABMultiValue SET record_id = (SELECT ROWID FROM ABPerson WHERE First = 'Maya' LIMIT 1)
  WHERE property = 3 AND replace(replace(replace(replace(value, ' ', ''), '-', ''), '(', ''), ')', '') LIKE '%8885551212'"
rm -rf "$data/Containers/Data/Application/"*/Library/Caches/io.github.kabrapratik28.thumbfree/com.apple.e5rt.e5bundlecache
echo "$udid"
