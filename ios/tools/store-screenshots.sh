#!/usr/bin/env bash
# The App Store screens about voice to text (UITests/StoreScreenshotsUITests.swift), 1-talk.png to 8-history.png. On the
# default Simulator, "iPhone 17 Pro Max (store)" (6.9 inch, 1320 x 2868), the raw screens go to the repository's
# docs/brand/ios/raw/, which tools/store-frames.py builds the store images from; another Simulator's go to
# build/store-screenshots/<Simulator>/raw/. build/store-screenshots/<Simulator>/ also gets each screen under a short
# caption on ThumbFree's sunflower (tools/store-compose.swift), at the screen's own size, without alpha, as a preview.
# Apple's screenshot specification, checked 2026-09-29 (App Store Connect Help > Screenshot specifications): 6.9-inch
# portrait 1260 x 2736, 1290 x 2796 or 1320 x 2868; .png, .jpg or .jpeg; no alpha channel or transparency; one to ten per
# set; App Store Connect scales them down for the smaller iPhones.
# The build is Debug, for the test hooks, without TF_AUTO_RETURN: none of these screens shows automatic return.
# Create the Simulator once:
#   xcrun simctl create "iPhone 17 Pro Max (store)" com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max com.apple.CoreSimulator.SimRuntime.iOS-26-5
# Usage: tools/store-screenshots.sh ["<Simulator name>"]
set -euo pipefail
cd "$(dirname "$0")/.."
sim="${1:-iPhone 17 Pro Max (store)}"
out="$PWD/build/store-screenshots/$sim"
raw="$out/raw"
if [ "$sim" = "iPhone 17 Pro Max (store)" ]; then raw="$(cd .. && pwd)/docs/brand/ios/raw"; fi
xcodegen generate --quiet
udid=$(tools/store-sim.sh "$sim")
rm -rf "${out:?}" && mkdir -p "$out" "$raw"
# JFK over and over as the microphone, so a take minutes into a session still hears speech (silence types nothing).
ffmpeg -hide_banner -loglevel error -y -stream_loop 15 -i testdata/public/jfk.wav -c:a pcm_s16le "$out/speech.wav"
TEST_RUNNER_TF_SCREENSHOTS="$raw" TEST_RUNNER_TF_SCREENSHOTS_AUDIO="$out/speech.wav" xcodebuild -project ThumbFree.xcodeproj -scheme ThumbFree \
  -destination "platform=iOS Simulator,id=$udid" -derivedDataPath build/StoreShots -collect-test-diagnostics never \
  SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG GCC_PREPROCESSOR_DEFINITIONS=DEBUG=1 \
  -only-testing:ThumbFreeUITests/StoreScreenshotsUITests test 2>&1 | tail -5
# The captions ("|" starts a new line), the same words as FRAMES in tools/store-frames.py; tools/store-review.sh reads them.
while IFS='|' read -r name caption; do
  swift tools/store-compose.swift frame "$raw/$name.png" "$out/$name.png" "$caption"
done <<'EOF'
1-talk|Talk. It types.
2-typed|Your words,|right where you type
3-note|Talk as long|as you like
4-private|Works offline.|Your voice is never uploaded.
5-languages|Speak in 25 languages
6-keyboard|A full keyboard, too
7-dictionary|Names spelled your way
8-history|Every take, saved|on your iPhone
EOF
for shot in "$out"/*.png; do
  echo "$(basename "$shot"): $(sips -g pixelWidth -g pixelHeight -g hasAlpha "$shot" | awk '/pixel|hasAlpha/ {print $2}' | paste -sd ' ' -)"
done
