#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
BACKEND_HEALTH_URL="http://127.0.0.1:5084/swagger/index.html"
PACKAGE_NAME="com.example.tv_caller_app"
LAUNCH_ACTIVITY="$PACKAGE_NAME/.ui.activities.AuthActivity"

if ! curl --silent --fail --output /dev/null "$BACKEND_HEALTH_URL"; then
    echo "ViKom backend is not reachable at http://127.0.0.1:5084."
    echo "Start it first: cd /Users/sam/Developer/vikom-web-main && dotnet run --project backend --launch-profile http"
    exit 1
fi

mapfile_compatible_devices() {
    adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

devices="$(mapfile_compatible_devices)"
if [ -z "$devices" ]; then
    echo "No authorized Android tablets are visible to ADB."
    echo "Connect and unlock each tablet, then accept the USB debugging prompt."
    exit 1
fi

cd "$PROJECT_DIR"
bash ./gradlew :app:assembleDebug

for serial in $devices; do
    echo "Updating ViKom on tablet $serial"
    adb -s "$serial" install -r "$APK_PATH"
    if grep -Eq '^backend\.base\.url=http://(127\.0\.0\.1|localhost):5084/?$' "$PROJECT_DIR/local.properties"; then
        adb -s "$serial" reverse tcp:5084 tcp:5084
        echo "USB backend tunnel active for $serial"
    else
        adb -s "$serial" reverse --remove tcp:5084 >/dev/null 2>&1 || true
        echo "Direct network backend mode active for $serial"
    fi
    adb -s "$serial" shell am force-stop "$PACKAGE_NAME"
    # Do not use `am start -W`: some older Samsung Android builds wait
    # indefinitely even after MainActivity is visibly resumed.
    adb -s "$serial" shell am start -n "$LAUNCH_ACTIVITY"
done

echo "All connected tablets are updated."
