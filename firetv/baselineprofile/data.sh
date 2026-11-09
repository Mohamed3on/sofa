#!/usr/bin/env bash
# break-ui's data toggle for the emulator: switches the running fixture server to <mode> (demo, worst, empty, one,
# huge; see worst-case.ts), clears the app's cached feed and guide, and relaunches it on the new data.
# Usage: baselineprofile/data.sh worst   (fixture server and emulator running). Never the stick: clearing would
# wipe the real app's favourites and marks, so it only ever talks to the emulator.
set -euo pipefail
adb() { "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb" -s "${EMULATOR:-emulator-5554}" "$@"; }
curl -sf "localhost:8791/data/${1:?mode: demo, worst, empty, one or huge}"
adb shell pm clear com.mohamed3on.iptv >/dev/null
adb shell am start -n com.mohamed3on.iptv/.MainActivity >/dev/null
