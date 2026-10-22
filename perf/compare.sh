#!/usr/bin/env bash
# The other player on the same phone and files, measured from outside, the same way for both apps
# (perf/README.md, "Measuring MX on the same phone"). Appends to the newest bench results file.
#
#   perf/compare.sh --package <other app package> [--label "MX 3.3.1"] [--seconds 60]
#
# Automatic: NF-1 cold start (am start -W, 10 runs), NF-3/NF-4 drops from the compositor while each
# app plays the same file from /sdcard/Movies/PlayerBench (perf/sf-drops.sh), NF-9 installed size.
# By hand, with a screen recording: NF-2 first frame and NF-6 seek (README).
set -euo pipefail
cd "$(dirname "$0")"

OTHER=""; LABEL=""; SECS=60
while [ $# -gt 0 ]; do
  case "$1" in
    --package) OTHER=$2; shift 2 ;;
    --label) LABEL=$2; shift 2 ;;
    --seconds) SECS=$2; shift 2 ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
[ -n "$OTHER" ] || { echo "--package <other app package> is required"; exit 2; }
adb shell pm path "$OTHER" > /dev/null 2>&1 || { echo "$OTHER is not installed"; exit 2; }
LABEL=${LABEL:-$OTHER}
OURS=io.github.hrmasss.lanthorn.bench
OUT=$(ls -t results/*.md 2>/dev/null | head -1)
[ -n "$OUT" ] || { echo "run perf/bench.sh first: this adds to its results file"; exit 2; }
MEDIA=/sdcard/Movies/PlayerBench

launcher() { adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tr -d '\r' | tail -1; }
p() { sort -n | awk -v p="$1" '{a[NR]=$1} END {if (NR==0) {print "-"; exit} i=int((p/100)*(NR-1))+1; print a[i]}'; }

cold() { # package -> "p50 p90" over 10 cold starts
  local act; act=$(launcher "$1"); : > /tmp/compare-cold
  for _ in $(seq 1 10); do
    adb shell am force-stop "$1"; sleep 1
    adb shell am start -W -n "$act" | tr -d '\r' | awk '/TotalTime/ {print $2}' >> /tmp/compare-cold || true
  done
  adb shell am force-stop "$1"
  echo "$(p 50 < /tmp/compare-cold) $(p 90 < /tmp/compare-cold)"
}

drops() { # package file fps -> sf-drops line, the app playing the file through a VIEW intent
  adb shell am force-stop "$1"
  adb shell "am start -a android.intent.action.VIEW -d 'file://$MEDIA/$2' -t 'video/*' -p $1" > /dev/null
  sleep 5
  # The video's own layer: that package's SurfaceView, the BLAST buffer layer first, the first one
  # that reports frames. Android 16 lists names as RequestedLayerState{<name> parentId=...}.
  local layer="" l n
  while read -r l; do
    n=$(adb shell "dumpsys SurfaceFlinger --latency '$l'" < /dev/null | tail -n +2 | awk '$2 > 0 && $2 < 9e18' | wc -l)
    [ "$n" -gt 0 ] && { layer=$l; break; }
  done < <(adb shell dumpsys SurfaceFlinger --list | tr -d '\r' \
             | sed -E 's/^RequestedLayerState\{//; s/ (parentId|relativeParentId|z)=.*$//; s/\}$//' \
             | grep "SurfaceView\[$1" | awk '/BLAST/ {print; next} {rest[NR] = $0} END {for (i in rest) print rest[i]}')
  [ -n "$layer" ] || { echo "no video layer found"; return; }
  ./sf-drops.sh "$layer" "$SECS" "$3"
  adb shell am force-stop "$1"
}

size() { # package -> installed APK bytes (all splits)
  adb shell pm path "$1" | tr -d '\r' | sed 's/^package://' | while read -r f; do adb shell stat -c %s "$f" | tr -d '\r'; done | awk '{s += $1} END {print s}'
}

echo "cold start"
read -r ours50 ours90 < <(cold "$OURS"); read -r other50 other90 < <(cold "$OTHER")
{
  echo "## $LABEL on the same phone and files (from outside, same method for both apps)"
  echo
  echo "| Measure | Lanthorn | $LABEL |"
  echo "|---|---|---|"
  echo "| NF-1 cold start p50 / p90 ms | $ours50 / $ours90 | $other50 / $other90 |"
} >> "$OUT"
for spec in h264-1080p60.mp4:60 hevc8-1080p60.mp4:60 hevc10-2160p60.mp4:60 av1-2160p60.mp4:60 ass-karaoke-1080p60.mkv:60; do
  f=${spec%%:*}; fps=${spec##*:}
  echo "drops $f"
  echo "| Drops, $f, ${SECS} s (compositor) | $(drops "$OURS" "$f" "$fps") | $(drops "$OTHER" "$f" "$fps") |" >> "$OUT"
done
echo "| NF-9 installed size MB (ours: bench build) | $(size "$OURS" | awk '{printf "%.1f", $1 / 1e6}') | $(size "$OTHER" | awk '{printf "%.1f", $1 / 1e6}') |" >> "$OUT"
{
  echo
  echo "By hand (README): NF-2 first frame and NF-6 seek from a 60 fps screen recording, NF-7 styling from screenshots at the same timecodes."
  echo
} >> "$OUT"
echo "added to $OUT"
