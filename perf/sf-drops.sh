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
  # One quoted string for the phone's shell: layer names carry brackets and parentheses.
  adb shell "dumpsys SurfaceFlinger --latency '$layer'" | tail -n +2 | awk '$2 > 0 && $2 < 9e18 {print $2}' >> "$tmp"
  sleep 1
done
sort -n -u "$tmp" | awk -v fps="$fps" '
  NR > 1 { gap = ($1 - prev) / 1e9; frames++
           if (gap > 1.5 / fps) dropped += int(gap * fps + 0.5) - 1 }
  { prev = $1 }
  END { total = frames + dropped
        # No frames means a wrong layer or no playback, never "0 % dropped".
        if (frames == 0) { print "no frames seen on that layer"; exit 1 }
        printf "presented %d, dropped %d, drop %.3f%%\n", frames, dropped, 100 * dropped / total }'
rm -f "$tmp"
