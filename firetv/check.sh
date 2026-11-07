#!/usr/bin/env bash
# The fast check on the stick (~1 min): opens Movies & Shows screens through the app's launch hook
# (`--es open <target>`, system/Check.kt), reads its IptvCheck timings from logcat, the worst frame from
# gfxinfo and the Java heap from meminfo, prints a table against the budgets and exits 1 on a fail.
# Ends on HOME (playback stops). Usage: ./check.sh [--install]  (STICK=ip:port to override the device)
set -euo pipefail
cd "$(dirname "$0")"
adb=~/Library/Android/sdk/platform-tools/adb
stick=${STICK:-192.168.178.25:5555}
pkg=com.mohamed3on.iptv
# Inception (a debrid copy plays) and Hobal, a Saudi film IPTV has in 4K and no scraper lists a torrent for,
# so only IPTV can play it (with TorBox, the old pick movie:26280 played a debrid copy).
FILM=${FILM:-movie:27205} IPTV_FILM=${IPTV_FILM:-movie:1173720}
PLAYS_MS=1000 FRAME_MS=4000 WORST_MS=32

# The nightly quality probe needs the account's one connection.
now=$((10#$(date -u +%H%M)))
if ((now >= 150 && now < 250)); then echo "01:50-02:50 UTC is the nightly probe's: no streaming now" >&2; exit 2; fi
[[ ${1:-} == --install ]] && ./dev.sh

# `adb` calls can hang on the stick; cap each one.
a() { perl -e "alarm($1); exec @ARGV" -- "${@:2}"; }
sh() { a 20 "$adb" -s "$stick" shell "$@"; }
a 10 "$adb" connect "$stick" >/dev/null 2>&1 || true

open() { sh am start -n $pkg/.MainActivity --activity-single-top --es open "$1" >/dev/null 2>&1; }
# The first IptvCheck line matching $2 since the app took open target $1, waiting up to $3 s; empty if none.
event() {
  local end=$((SECONDS + $3)) line
  while ((SECONDS < end)); do
    line=$(sh logcat -d -s IptvCheck:I | tr -d '\r' |
      awk -v t="target=$1" '/ open ms=/ { on = ($NF == t); b = ""; next } on { b = b $0 "\n" } END { printf "%s", b }' | grep -m1 -E "$2" || true)
    [[ -n $line ]] && { echo "$line"; return; }
    sleep 0.4
  done
}
val() { sed -nE "s/.* $1=([^ ]*).*/\1/p" <<<"$2"; }
gfx_reset() { sh dumpsys gfxinfo $pkg reset >/dev/null; }
# The slowest frame since the reset: the histogram's last non-empty bucket (ms).
worst() { sh dumpsys gfxinfo $pkg | grep -m1 HISTOGRAM | tr ' ' '\n' | awk -F'ms=' 'NF == 2 && $2 > 0 { w = $1 } END { print w + 0 }'; }
heap() { sh dumpsys meminfo $pkg | awk '/Java Heap:/ { print int($3 / 1024) " MB"; exit }'; }

table="" fails=0
# row <name> <value> [max] [note]: no value is a fail; over max is a fail.
row() {
  local v=${2:-} max=${3:-} st=ok
  if [[ -z $v ]]; then v="none" st=FAIL; elif [[ -n $max ]] && ((v > max)); then st=FAIL; fi
  [[ $st == FAIL ]] && fails=$((fails + 1))
  table+=$(printf '%-26s %8s %8s  %-4s  %s' "$1" "$v" "${max:+≤$max}" "$st" "${4:-}")$'\n'
}
started=$SECONDS

# Movies & Shows: cold (a fresh process), then warm (closed and opened again).
sh logcat -c || true # sometimes refused; the open target still marks where this run starts
sh am force-stop $pkg
open movies
l=$(event movies movies_shown 25); row "Movies open cold (ms)" "$(val ms "$l")" "" "from $(val from "$l")"
open live; sleep 1
open movies
l=$(event movies movies_shown 10); row "Movies open warm (ms)" "$(val ms "$l")" "" "from $(val from "$l")"

# Title page: shown, then Plays: filled, and its worst frame.
sleep 1; gfx_reset
open "title:$FILM"
l=$(event "title:$FILM" ' plays ' 10); t=$(event "title:$FILM" title_shown 1)
row "Title Plays (ms)" "$(val ms "$l")" $PLAYS_MS "$FILM shown $(val ms "$t") ms · $(val src "$l")"
sleep 1; row "Title open worst frame" "$(worst)" $WORST_MS

# Play to first frame: an IPTV pick, then a debrid pick, and the heap while it plays.
for p in "$IPTV_FILM Iptv" "$FILM Debrid"; do
  set -- $p
  open "play:$1"
  l=$(event "play:$1" first_frame 20); src=$(val src "$l")
  [[ -n $l && $src != "$2" ]] && l="" # the other source played: the title no longer tests this one
  # Where the time went: the player opened (copy and link known), then ready (connected, start buffer full).
  o=$(event "play:$1" film_open 1); r=$(event "play:$1" film_ready 1)
  row "First frame $2 (ms)" "$(val ms "$l")" $FRAME_MS "$1 · ${src:-?} $(val copy "$l" | cut -c1-16) · open $(val ms "$o") ready $(val ms "$r")"
done
sleep 5; table+=$(printf '%-26s %8s' "Java heap playing" "$(heap)")$'\n'

sh input keyevent 3 # HOME: the film and the live channel stop
extra=$(sh logcat -d -s IptvCheck:I | tr -d '\r' | grep -E ' (notice|debrid) ' | sed -E 's/.*IptvCheck: //; s/ ms=[0-9]+//' | sort -u | tr '\n' ';' || true)
printf '%s' "$table"
[[ -n $extra ]] && echo "events: $extra"
echo "$((SECONDS - started)) s, $fails failed"
((fails == 0))
