#!/usr/bin/env bash
# The local loop: build the release APK, install it on the stick with its dex metadata (never a bare
# `adb install`: without the .dm the app runs interpreted), and reopen the app. ~1-2 min, no CI.
# Usage: ./dev.sh  (STICK=ip:port to override the device)
set -euo pipefail
cd "$(dirname "$0")"
adb=~/Library/Android/sdk/platform-tools/adb
stick=${STICK:-192.168.178.25:5555}
# `adb` calls can hang on the stick; cap each one.
a() { perl -e "alarm($1); exec @ARGV" -- "${@:2}"; }

# Usually connected already; a connect that hangs isn't fatal, the install below is the real check.
a 10 "$adb" connect "$stick" >/dev/null 2>&1 || true
# A build older than the one on the stick (another branch, a session ahead of this one) installs as the next
# version up, never as a downgrade: Android refuses those without wiping the app's data. The drift is one, so
# the next CI build still updates over it.
code=$(( $(git rev-list --count HEAD) + $(sed -n 's/^iptv.versionOffset=//p' gradle.properties) ))
installed=$(a 15 "$adb" -s "$stick" shell dumpsys package com.mohamed3on.iptv | sed -nE '/versionCode=/{s/.*versionCode=([0-9]+).*/\1/p;q;}' || true)
((code > ${installed:-0})) || code=$((installed + 1))
./gradlew -q assembleRelease -Piptv.versionCode=$code
out=app/build/outputs/apk/release
baselineprofile/dex-metadata.sh "$out/app-release.apk" "$out/app-release.dm" # the .dm name must match the APK's
a 120 "$adb" -s "$stick" install-multiple -r "$out/app-release.apk" "$out/app-release.dm"
a 15 "$adb" -s "$stick" shell am start -n com.mohamed3on.iptv/.MainActivity >/dev/null
a 15 "$adb" -s "$stick" shell dumpsys package com.mohamed3on.iptv | grep -m1 versionName
