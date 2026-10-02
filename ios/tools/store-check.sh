#!/usr/bin/env bash
# Builds ThumbFree the way the App Store gets it (Release, generic iPhone) and checks the app, keyboard and widget binaries:
# no private interface name beyond automatic return's (when project.yml ships it) and no Debug test hook ships, every required-reason API a binary references is declared in its
# bundle's privacy manifest, and the extensions' versions match the app's. Extra arguments go to xcodebuild (add
# CODE_SIGNING_ALLOWED=NO when signing blocks). Swift keeps a string of up to 15 bytes inside the code, as mov/movk
# immediates that `strings` cannot see, so the check decodes those too.
# Usage: tools/store-check.sh [xcodebuild settings]
set -euo pipefail
cd "$(dirname "$0")/.."
xcodegen generate --quiet
xcodebuild -project ThumbFree.xcodeproj -scheme ThumbFree -configuration Release -destination "generic/platform=iOS" \
  -derivedDataPath build/StoreRelease -allowProvisioningUpdates "$@" build 2>&1 | tail -1
app=build/StoreRelease/Build/Products/Release-iphoneos/ThumbFree.app
private='arbiter|LSApplicationWorkspace|_hostProcessIdentifier|sourceBundleIdentifier'
# Automatic return ships since 1.0.1 (project.yml's release block sets TF_AUTO_RETURN): then its own private names are
# expected, and any other private name still fails.
if sed -n '/^    release:/,/^[a-z]/p' project.yml | grep -q TF_AUTO_RETURN; then
  expected='arbiter|_hostProcessIdentifier|sourceBundleIdentifier'
else
  expected='^$'
fi
# The Debug test launch arguments (AppEnvironment, AppSettings, ThumbFreeApp, TryItView, GuideView, FloatingGuide, KeyboardStatus), by the 8-byte pieces Swift splits
# them into (`-TFReturnDelayMs` by its second piece: "TFReturn" also starts TFReturnedApps, a real setting), the fixture's
# id and the fixed engine's text.
hooks='TFResetS|TFAudioF|TFFakeEn|TFFakeTe|TFModelF|TFKeepSe|TFKeptSe|^DelayMs$|TFKeepAw|TFEndSes|TFGuideP|TFFieldT|TFAutoco|TFHoldDo|TFHoldSp|TFHoldEn|TFGuideB|TFSetupK|TFTrySta|TFOpenTr|test-fix|fellow americans'

# Every 64-bit immediate built by mov/movk, as the printable text in its bytes.
immediates() {
  xcrun otool -tv "$1" | python3 -c '
import re, sys
pattern = re.compile(r"\t(movk|movz|mov)\t([xw])(\d+), #(-?0x[0-9a-f]+|-?\d+)(?:, lsl #(\d+))?$")
values, seen = {}, set()
for line in sys.stdin:
    m = pattern.search(line)
    if not m: continue
    op, _, reg, imm, shift = m.groups()
    imm, shift = int(imm, 0), int(shift or 0)
    old = values.get(reg, 0)
    value = (old & ~(0xFFFF << shift)) | ((imm & 0xFFFF) << shift) if op == "movk" else (imm << shift) & (2**64 - 1)
    values[reg] = value
    text = "".join(chr(b) for b in value.to_bytes(8, "little") if 32 <= b < 127)
    if len(text) >= 3: seen.add(text)
print("\n".join(sorted(seen)))'
}

# The required-reason API categories a binary references (Apple's list: user defaults, file timestamps, disk space,
# system boot time, active keyboards).
used() {
  local symbols texts
  symbols=$(xcrun nm -u "$1")
  texts=$(strings -a "$1")
  grep -q 'NSUserDefaults' <<<"$symbols" && echo NSPrivacyAccessedAPICategoryUserDefaults
  grep -qE '^_NSFile(Modification|Creation)Date$|^_NSURL(ContentModification|Creation)DateKey$|^_(f|l)?stat(at)?(\$INODE64)?$|getattrlist' <<<"$symbols" &&
    echo NSPrivacyAccessedAPICategoryFileTimestamp
  grep -qE 'NSURLVolume(Available|Total)Capacity|^_NSFileSystem(Free)?Size$|^_f?statv?fs(64)?$' <<<"$symbols" && echo NSPrivacyAccessedAPICategoryDiskSpace
  { grep -q '^_mach_absolute_time$' <<<"$symbols" || grep -qx systemUptime <<<"$texts"; } && echo NSPrivacyAccessedAPICategorySystemBootTime
  grep -qx activeInputModes <<<"$texts" && echo NSPrivacyAccessedAPICategoryActiveKeyboards
  true
}

failed=0
version=$(plutil -extract CFBundleShortVersionString raw "$app/Info.plist") build=$(plutil -extract CFBundleVersion raw "$app/Info.plist")
echo "App version $version ($build)"
for bundle in "$app" "$app/PlugIns/ThumbFreeKeyboard.appex" "$app/PlugIns/ThumbFreeWidgets.appex"; do
  binary="$bundle/$(plutil -extract CFBundleExecutable raw "$bundle/Info.plist")"
  manifest="$bundle/PrivacyInfo.xcprivacy"
  echo "== $(basename "$bundle")"
  [ "$(plutil -extract CFBundleShortVersionString raw "$bundle/Info.plist") $(plutil -extract CFBundleVersion raw "$bundle/Info.plist")" = "$version $build" ] ||
    { echo "FAIL: version differs from the app's"; failed=1; }
  plutil -lint -s "$manifest" || { echo "FAIL: no valid PrivacyInfo.xcprivacy"; failed=1; continue; }
  declared=$(plutil -convert json -o - "$manifest" | { grep -o 'NSPrivacyAccessedAPICategory[A-Za-z]*' || true; } | sort -u)
  uses=$(used "$binary" | sort -u)
  echo "required-reason APIs referenced: ${uses:-none}" | tr '\n' ' '; echo
  echo "declared in the manifest: ${declared:-none}" | tr '\n' ' '; echo
  missing=$(comm -23 <(echo "$uses") <(echo "$declared") | grep . || true)
  [ -z "$missing" ] || { echo "FAIL: not declared: $missing"; failed=1; }
  texts=$( { strings -a "$binary"; immediates "$binary"; } )
  if leaks=$(grep -i -E "$private" <<<"$texts"); then
    if unexpected=$(grep -i -v -E "$expected" <<<"$leaks"); then echo "FAIL: private interface names:"; echo "$unexpected"; failed=1
    else echo "private interface names: automatic return's only ($(grep -i -o -E "$expected" <<<"$leaks" | sort -u | tr '\n' ' '))"; fi
  else echo "private interface names: none"; fi
  if leaks=$(grep -E "$hooks" <<<"$texts"); then echo "FAIL: test hooks:"; echo "$leaks"; failed=1; else echo "test hooks: none"; fi
done
[ "$failed" = 0 ] && echo "Store check passed" || { echo "Store check FAILED"; exit 1; }
