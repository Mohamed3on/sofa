#!/usr/bin/env bash
# Copies the dex metadata (.dm) AGP built for Android [sdk] from the last assembleRelease: installed
# alongside the APK (as base.dm), the platform compiles the app's hot paths at install instead of at
# the next idle maintenance. Each platform reads only its own profile format (Android 12+ rejects the
# 9-11 one the APK's assets carry, and installs uncompiled), so AGP builds one per range; the map
# names, for each sdk, its .dm (the first sdk at or above the asked one).
# Usage: dex-metadata.sh <sdk> <out.dm>
set -euo pipefail
dir=$(cd "$(dirname "$0")/../app/build/intermediates/dex_metadata_directory/release/compileReleaseArtProfile" && pwd)
dm=$(awk -F= -v sdk="$1" '$1 >= sdk && (best == "" || $1 < best) { best = $1; file = $2 } END { print file }' "$dir/dex-metadata-map.properties")
[ -n "$dm" ] && cp "$dir/$dm" "$2"
