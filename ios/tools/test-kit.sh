#!/usr/bin/env bash
# Runs the ThumbFreeKit tests on this Mac. Extra args go to `swift test` (for example --filter BrandTests).
set -euo pipefail
cd "$(dirname "$0")/../ThumbFreeKit"
swift test "$@"
