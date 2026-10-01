#!/usr/bin/env bash
# Generates the Xcode project and runs the app's tests on a Simulator.
# TF_SIM picks the Simulator (default "iPhone 17 Pro"); parallel runs use different ones.
# The real-model tier (ThumbFreeTests/RealModelTests, the Mac's cached Parakeet) runs with the whole suite; a focused
# run (-only-testing) leaves it out unless its filter names the tier.
set -euo pipefail
cd "$(dirname "$0")/.."
xcodegen generate --quiet
sim="${TF_SIM:-iPhone 17 Pro}"
udid=$(tools/sim-enable-keyboard.sh "$sim" 26.5)
# Core ML compiles a fresh 1 to 2 GB copy of the encoder for every Simulator build and never reuses the old ones (the
# phone keeps one), so ThumbFree's copies on this Simulator go at the end of a run that may load the real model, or the
# disk fills up: the whole suite, the tier, or EndToEndUITests' real-model test (with TEST_RUNNER_TF_MODELS_DIR).
if [[ "$*" != *-only-testing* || "$*" == *RealModelTests* || "$*" == *EndToEndUITests* || -n "${TEST_RUNNER_TF_MODELS_DIR:-}" ]]; then
  trap 'rm -rf "$HOME/Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Data/Application/"*/Library/Caches/io.github.kabrapratik28.thumbfree/com.apple.e5rt.e5bundlecache' EXIT
fi
if [[ "$*" == *-only-testing* && "$*" != *RealModelTests* ]]; then
  set -- "$@" -skip-testing:ThumbFreeTests/RealModelTests
fi
xcodebuild -project ThumbFree.xcodeproj -scheme ThumbFree \
  -destination "platform=iOS Simulator,name=$sim,OS=26.5" \
  -derivedDataPath build/DerivedData -collect-test-diagnostics never "$@" test 2>&1 | tail -30
# A filter that matches nothing still says TEST SUCCEEDED (Swift Testing ids need their parentheses): fail instead.
if [[ "$*" == *-only-testing* ]]; then
  result=$(ls -td build/DerivedData/Logs/Test/*.xcresult | head -1)
  count=$(xcrun xcresulttool get test-results summary --path "$result" --format json |
    python3 -c 'import json, sys; print(json.load(sys.stdin).get("totalTestCount", 0))')
  [ "$count" -gt 0 ] || { echo "No test matched the -only-testing filter"; exit 1; }
fi
