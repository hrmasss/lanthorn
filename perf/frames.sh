#!/usr/bin/env bash
# Lists the moments a screen recording changes a lot, to find tap and first-frame times fast.
#   perf/frames.sh recording.mp4 [threshold 0.08]
# Prints "seconds  frame  score". Read the candidates in a player that steps frames to confirm.
set -euo pipefail
in=${1:?usage: frames.sh <recording.mp4> [threshold]}
th=${2:-0.08}
ffmpeg -hide_banner -nostats -i "$in" \
  -vf "select='gt(scene,$th)',metadata=print:file=-" -an -f null - 2>/dev/null |
  awk '/pts_time/ { split($0, a, "pts_time:"); t=a[2]+0 }
       /lavfi.scene_score/ { split($0, b, "="); printf "%8.3f  %6d  %.3f\n", t, int(t*60+0.5), b[2] }'
