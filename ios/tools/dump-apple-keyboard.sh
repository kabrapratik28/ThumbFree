#!/usr/bin/env bash
# Reads Apple's English (US) keyboard from a Simulator into tools/keyboard/: the keys of each layout with their widths
# (apple-layouts.txt) and the long-press alternatives in Apple's order (apple-alternates.txt). The reader turns off word
# predictions, auto-correction, slide to type and smart punctuation in that Simulator's Settings while it reads, and back
# after. Run it after each iOS release, on the newest runtime; it takes about half an hour.
# Usage: TF_SIM="iPhone 17e" tools/dump-apple-keyboard.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export TEST_RUNNER_TF_APPLE_LAYOUTS_OUT="$PWD/tools/keyboard/apple-layouts.txt"
export TEST_RUNNER_TF_APPLE_ALTERNATES_OUT="$PWD/tools/keyboard/apple-alternates.txt"
tools/test-app.sh "-only-testing:ThumbFreeUITests/AppleKeyboardReader"
echo "wrote tools/keyboard/apple-layouts.txt ($(grep -vc '^#' tools/keyboard/apple-layouts.txt) layouts) and apple-alternates.txt ($(grep -vc '^#' tools/keyboard/apple-alternates.txt) keys)"
