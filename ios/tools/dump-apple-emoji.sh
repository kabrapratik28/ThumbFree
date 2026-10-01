#!/usr/bin/env bash
# Reads the layout of Apple's Emoji keyboard (sections, order, and the Frequently Used a new iPhone starts with) from a
# Simulator into tools/emoji/apple-order.txt, for tools/gen-emoji.py. Run it on a Simulator whose Emoji keyboard was
# never used (a new or erased one), with the newest iOS runtime, once a year after iOS adds that year's emoji.
# Usage: TF_SIM="iPhone Air" tools/dump-apple-emoji.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export TEST_RUNNER_TF_APPLE_EMOJI_OUT="$PWD/tools/emoji/apple-order.txt"
tools/test-app.sh "-only-testing:ThumbFreeUITests/AppleEmojiOrder/testDumpAppleEmojiOrder"
echo "wrote tools/emoji/apple-order.txt ($(awk -F'\t' 'NR > 1 { n += split($2, e, " ") } END { print n }' tools/emoji/apple-order.txt) emoji)"
