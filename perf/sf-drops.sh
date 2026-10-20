#!/usr/bin/env bash
# Counts dropped frames of any app's video layer from the compositor, the same way for us and MX.
#   perf/sf-drops.sh '<layer name>' <seconds> <video fps>
# Layer names: adb shell dumpsys SurfaceFlinger --list (pick the SurfaceView layer).
# Samples `dumpsys SurfaceFlinger --latency` every second (it keeps the last 128 frames), merges
# the presentation times, and counts gaps longer than 1.5 frame intervals as drops.
set -euo pipefail
layer=${1:?layer name}; secs=${2:?seconds}; fps=${3:?video fps}
tmp=$(mktemp)
for _ in $(seq 1 "$secs"); do
  adb shell dumpsys SurfaceFlinger --latency "$layer" | tail -n +2 | awk '$2 > 0 && $2 < 9e18 {print $2}' >> "$tmp"
  sleep 1
done
sort -n -u "$tmp" | awk -v fps="$fps" '
  NR > 1 { gap = ($1 - prev) / 1e9; frames++
           if (gap > 1.5 / fps) dropped += int(gap * fps + 0.5) - 1 }
  { prev = $1 }
  END { total = frames + dropped
        printf "presented %d, dropped %d, drop %.3f%%\n", frames, dropped, total ? 100 * dropped / total : 0 }'
rm -f "$tmp"
