#!/bin/bash
# One command on a Mac: makes the Xcode project and runs Ludo Lux in the iPhone simulator.
#   ./iosApp/run.sh          build + run in the simulator
#   ./iosApp/run.sh open     just make the project and open it in Xcode (to run on your own iPhone)
# Needs: Xcode (from the App Store), a JDK 17+ (brew install openjdk@17), and Homebrew for xcodegen.
set -euo pipefail
cd "$(dirname "$0")"

command -v xcodegen >/dev/null || { echo "Installing xcodegen..."; brew install xcodegen; }
if [ -z "${JAVA_HOME:-}" ]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"; fi
xcodegen generate --quiet

if [ "${1:-}" = "open" ]; then open LudoLux.xcodeproj; exit 0; fi

# The first available iPhone simulator.
DEV=$(xcrun simctl list devices available -j | python3 -c "import json,sys
d=json.load(sys.stdin)['devices']
print(next(x['udid'] for k,v in d.items() if 'iOS' in k for x in v if x['name'].startswith('iPhone')))")
echo "Building (the first build downloads a lot and takes a while)..."
xcodebuild -project LudoLux.xcodeproj -scheme LudoLux -configuration Debug -sdk iphonesimulator \
  -destination "id=$DEV" -derivedDataPath ../build/ios CODE_SIGNING_ALLOWED=NO build | tail -n 20
open -a Simulator --args -CurrentDeviceUDID "$DEV"
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl install "$DEV" ../build/ios/Build/Products/Debug-iphonesimulator/LudoLux.app
xcrun simctl launch "$DEV" com.ludolux.app
echo "Ludo Lux is running in the simulator."
