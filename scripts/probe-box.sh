#!/usr/bin/env bash
# Probes feed quality on the box itself (sofa-probe.timer nightly, sofa-probe-live.timer during the day): on a
# copy of the store, then merges what changed, as deploy/probe-quality.sh does from another machine. The
# provider's normal fronts refuse the box (HTTP 511), so the probe goes through its VPN front.
# Args go to scripts/probe-quality.ts after the concurrency: "refresh mins=30" or "live mins=3".
set -euo pipefail
cd /opt/sofa
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if [ -f .cache/quality.json ]; then cp .cache/quality.json "$work/quality.json"; else echo '{}' > "$work/quality.json"; fi
# Stopped (the app started playing, or by a signal) the probe saves what it measured and exits 0 or 130: merged too.
status=0
QUALITY_FILE="$work/quality.json" PROBED_FILE="$work/probed.json" /usr/local/bin/bun scripts/probe-quality.ts 8 "$@" || status=$?
[ "$status" = 0 ] || [ "$status" = 130 ] || exit "$status"
if [ -s "$work/probed.json" ] && [ "$(cat "$work/probed.json")" != "{}" ]; then
  /usr/local/bin/bun scripts/merge-quality.ts < "$work/probed.json"
else
  echo "No changed readings."
fi
