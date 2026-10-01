#!/usr/bin/env bash
# Archives ThumbFree the App Store way (Release, generic iPhone), signs it for the App Store and uploads it to App Store
# Connect, which then processes the build for a few minutes; `tools/asc.py submit` attaches it to the version. Signing
# and upload use the Apple account in Xcode's settings (the account holder's, which may create the Apple Distribution
# certificate on the first run). Each upload needs a new build number (CURRENT_PROJECT_VERSION in project.yml).
# --export-only stops at build/store/export/ThumbFree.ipa, with nothing uploaded.
# Usage: tools/store-upload.sh [--export-only]
set -euo pipefail
cd "$(dirname "$0")/.."
destination=upload
[ "${1:-}" = --export-only ] && destination=export
xcodegen generate --quiet
rm -rf build/store
mkdir -p build/store
# Each step's full output goes to a log; its end shows here when the step fails.
run() { log=$1; shift; "$@" > "build/store/$log" 2>&1 || { tail -40 "build/store/$log"; exit 1; }; tail -1 "build/store/$log"; }
run archive.log xcodebuild archive -project ThumbFree.xcodeproj -scheme ThumbFree -configuration Release \
  -destination "generic/platform=iOS" -derivedDataPath build/StoreRelease -archivePath build/store/ThumbFree.xcarchive \
  -allowProvisioningUpdates
cat > build/store/options.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>$destination</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
EOF
run export.log xcodebuild -exportArchive -archivePath build/store/ThumbFree.xcarchive \
  -exportOptionsPlist build/store/options.plist -exportPath build/store/export -allowProvisioningUpdates
