#!/usr/bin/env bash
# Gets a Simulator ready for keyboard UI tests: boots it, adds the ThumbFree keyboard, and turns off keyboard
# minimization, because the Simulator reports a hardware keyboard and iOS then hides every on-screen keyboard.
# Allow Full Access is a privacy grant that simctl cannot set; UITests/KeyboardSetup.swift turns it on in Settings.
# Usage: tools/sim-enable-keyboard.sh "iPhone 17" [26.5]   (prints the Simulator's UDID)
set -euo pipefail
name="${1:-iPhone 17}"
os="${2:-26.5}"
udid=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
name, runtime = sys.argv[1], "iOS-" + sys.argv[2].replace(".", "-")
for key, devices in json.load(sys.stdin)["devices"].items():
    if key.endswith(runtime):
        for device in devices:
            if device["name"] == name:
                print(device["udid"])
                sys.exit(0)
sys.exit("no Simulator named " + name + " on iOS " + sys.argv[2])
' "$name" "$os")
xcrun simctl boot "$udid" 2>/dev/null || true # already booted is fine
xcrun simctl bootstatus "$udid" -b >/dev/null
keyboard=io.github.kabrapratik28.thumbfree.keyboard
# A new Simulator has no keyboard list until Settings writes one: start it with iOS's own two, as the others have.
if ! xcrun simctl spawn "$udid" defaults read -g AppleKeyboards >/dev/null 2>&1; then
  xcrun simctl spawn "$udid" defaults write -g AppleKeyboards -array "en_US@sw=QWERTY;hw=Automatic" "emoji@sw=Emoji"
fi
if ! xcrun simctl spawn "$udid" defaults read -g AppleKeyboards | grep -q "$keyboard"; then
  xcrun simctl spawn "$udid" defaults write -g AppleKeyboards -array-add "$keyboard"
fi
# iOS uses the list as written only once it is marked expanded, as Settings marks it on a first visit: without the mark a
# new Simulator offers only its own keyboard and emoji, even after a restart (seen on iOS 26.5).
xcrun simctl spawn "$udid" defaults write -g AppleKeyboardsExpanded -bool true
xcrun simctl spawn "$udid" defaults write com.apple.keyboard.preferences AutomaticMinimizationEnabled -bool false
# UI tests tap keys all day: keep the keyboard clicks off so the Mac stays quiet.
xcrun simctl spawn "$udid" defaults write com.apple.preferences.sounds keyboard -bool false
xcrun simctl spawn "$udid" defaults write com.apple.preferences.sounds keyboard-audio -bool false
echo "$udid"
