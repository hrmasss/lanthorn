#!/usr/bin/env bash
# Runs the perf bench on one adb device and writes a results table.
#
#   perf/bench.sh --apk app-arm64-v8a-bench.apk            full run (about 25 minutes)
#   perf/bench.sh --apk ... --quick                        short runs, for the emulator
#   perf/bench.sh --apk ... --only startup,first-frame     a subset
#   perf/bench.sh --apk ... --force                        record even if the phone is not ready
#   ANDROID_SERIAL=<serial> perf/bench.sh ...              pick a device
#
# Needs: adb, perf/media/ from perf/gen-media.sh, the bench build (./gradlew assembleBench).
# Writes perf/results/<date>-<model>.md and raw JSON in perf/results/raw/.
# Emulator numbers are for regressions only, never for claims (docs: perf/README.md).
set -euo pipefail
cd "$(dirname "$0")"

APK=""; QUICK=0; FORCE=0; ONLY="startup,first-frame,playback,seek,scrub,preview"
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK=$2; shift 2 ;;
    --quick) QUICK=1; shift ;;
    --only) ONLY=$2; shift 2 ;;
    --force) FORCE=1; shift ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
[ -n "$APK" ] && [ -f "$APK" ] || { echo "--apk <bench apk> is required"; exit 2; }
want() { [[ ",$ONLY," == *",$1,"* ]]; }

# Preflight: one device, the media, and a phone in a state whose numbers mean something.
# Problems stop the run; --force runs anyway and writes them at the top of the results.
devices=$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$(echo "$devices" | grep -c .)" != 1 ]; then
  echo "need exactly one adb device (or ANDROID_SERIAL); have: ${devices:-none}"; exit 2
fi
missing=""
for f in h264-1080p60.mp4 hevc8-1080p60.mp4 hevc10-2160p60.mp4 av1-2160p60.mp4 av1-1080p60.mp4 \
         vp9-1080p60.webm ass-karaoke-1080p60.mkv h264-444-1080p30.mp4 h264-long-1080p30.mp4; do
  [ -f "media/$f" ] || missing="$missing $f"
done
[ -z "$missing" ] || { echo "missing media:$missing (run perf/gen-media.sh)"; exit 2; }
warnings=""
battery=$(adb shell dumpsys battery | tr -d '\r' | awk '$1 == "level:" {print $2; exit}')
temp=$(adb shell dumpsys battery | tr -d '\r' | awk '$1 == "temperature:" {print $2 / 10; exit}')
plane=$(adb shell settings get global airplane_mode_on | tr -d '\r')
[ "${battery:-0}" -ge 50 ] || warnings="$warnings battery ${battery:-?}% (want 50% or more);"
[ "$plane" = 1 ] || warnings="$warnings airplane mode off;"
awk -v t="${temp:-0}" 'BEGIN {exit !(t > 38)}' && warnings="$warnings battery at ${temp} C (let the phone cool);"
if [ -n "$warnings" ]; then
  echo "phone not ready:$warnings"
  [ $FORCE = 1 ] || { echo "fix it, or rerun with --force to record anyway"; exit 2; }
fi

PKG=io.github.hrmasss.lanthorn.bench
MAIN=$PKG/app.player.MainActivity
PLAYER=$PKG/app.player.feature.player.PlayerActivity
BENCH=$PKG/app.player.bench.BenchActivity
RUNS=10; PLAY_SECS=300; PLAY4K_SECS=120; SEEKS=20; SCRUB_SECS=5
[ $QUICK = 1 ] && { RUNS=3; PLAY_SECS=10; PLAY4K_SECS=10; SEEKS=5; SCRUB_SECS=3; }

model=$(adb shell getprop ro.product.model | tr -d '\r')
release=$(adb shell getprop ro.build.version.release | tr -d '\r')
soc=$(adb shell getprop ro.soc.model | tr -d '\r')
emu=$(adb shell getprop ro.kernel.qemu | tr -d '\r')
stamp=$(date -u +%Y-%m-%dT%H%MZ)
slug=$(echo "$model" | tr -c 'A-Za-z0-9\n' '-')
RAW=results/raw/$stamp-$slug; mkdir -p "$RAW"
OUT=results/$stamp-$slug.md
DEV=/sdcard/Android/data/$PKG/files
# Shared storage, read through READ_MEDIA_VIDEO: the same files MX can open.
MEDIA=/sdcard/Movies/PlayerBench

echo "device: $model, Android $release, SoC ${soc:-unknown}${emu:+ (emulator)}"
adb uninstall "$PKG" >/dev/null 2>&1 || true
# A results folder left from an earlier install can belong to another user id: the bench then
# cannot write its results (EACCES) and every scenario times out. Start from none.
adb shell "ls -lnd /sdcard/Android/data/$PKG /sdcard/Android/data/$PKG/files/bench" 2>/dev/null || true
adb shell rm -rf "/sdcard/Android/data/$PKG" 2>/dev/null || true
adb install -r -g "$APK" >/dev/null
adb shell mkdir -p "$MEDIA"
for f in media/*.mp4 media/*.mkv media/*.webm; do
  [ -f "$f" ] || continue
  remote_size=$(adb shell stat -c %s "$MEDIA/$(basename "$f")" 2>/dev/null | tr -d '\r' || true)
  [ "$remote_size" = "$(stat -c %s "$f")" ] || adb push "$f" "$MEDIA/" >/dev/null
  echo "media $(basename "$f")"
done
# Compile like a Play install: AOT from the baseline profile.
sleep 3
adb shell cmd package compile -f -m speed-profile "$PKG" >/dev/null || true

p() { # percentile of the numbers on stdin
  sort -n | awk -v p="$1" '{a[NR]=$1} END {if (NR==0) {print "-"; exit} i=int((p/100)*(NR-1))+1; print a[i]}'
}
wait_log() { # pattern timeout_s : poll logcat until the pattern shows up
  local i
  for i in $(seq 1 "$2"); do adb logcat -d -s Bench:I | grep -q "$1" && return 0; sleep 1; done
  return 1
}
bench() { # scenario file seconds_or_runs timeout
  local name; name=$(basename "$2")
  adb logcat -c
  adb shell am force-stop "$PKG"
  adb shell am start -n "$BENCH" --es scenario "$1" --es file "$MEDIA/$name" \
    --ei seconds "$3" --ei runs "$3" >/dev/null
  if wait_log DONE "$4"; then
    adb pull "$DEV/bench/$1-$name.json" "$RAW/" >/dev/null
  else
    echo "{\"scenario\":\"$1\",\"file\":\"$name\",\"error\":\"timed out\"}" > "$RAW/$1-$name.json"
  fi
}
j() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); v=d.get(sys.argv[2]); print('-' if v is None else (round(v,3) if isinstance(v,float) else v))" "$@"; }

{
  echo "# Bench: $model"
  echo
  echo "Android $release, SoC ${soc:-unknown}${emu:+, **emulator: regressions only, not claims**}. $stamp. APK \`$(basename "$APK")\`."
  echo "Battery ${battery:-?}%, ${temp:-?} C, airplane mode $([ "$plane" = 1 ] && echo on || echo off)."
  [ -n "$warnings" ] && echo "**Recorded with --force:$warnings**"
  [ $QUICK = 1 ] && echo "Quick mode: short runs, not comparable with full runs."
  echo
} > "$OUT"

if want startup; then
  echo "startup (NF-1)"
  : > "$RAW/startup.txt"
  for _ in $(seq 1 $RUNS); do
    adb shell am force-stop "$PKG"; sleep 1
    adb shell am start -W -n "$MAIN" | tr -d '\r' | awk '/TotalTime/ {print $2}' >> "$RAW/startup.txt" || true
  done
  {
    echo "## Cold start to first library frame (NF-1: p50 <= 500 ms)"
    echo
    echo "| Runs | p50 ms | p90 ms | max ms |"
    echo "|---|---|---|---|"
    echo "| $(wc -l < "$RAW/startup.txt") | $(p 50 < "$RAW/startup.txt") | $(p 90 < "$RAW/startup.txt") | $(sort -n "$RAW/startup.txt" | tail -1) |"
    echo
  } >> "$OUT"
fi

if want first-frame; then
  echo "first frame (NF-2)"
  {
    echo "## Open to first video frame (NF-2: p50 <= 300 ms, p90 <= 500 ms; NF-5: 4:4:4 on SW)"
    echo
    echo "Measured from the VIEW intent reaching the system to the engine's first-frame event, cold process."
    echo
    echo "| File | Runs | p50 ms | p90 ms | Decoder |"
    echo "|---|---|---|---|---|"
  } >> "$OUT"
  # The 4:4:4 file is NF-5: hardware refuses it, so the decoder column must read SW and it still plays.
  for f in h264-1080p60.mp4 hevc8-1080p60.mp4 h264-444-1080p30.mp4; do
    [ -f "media/$f" ] || continue
    : > "$RAW/first-frame-$f.txt"; dec=""
    for _ in $(seq 1 $RUNS); do
      adb shell am force-stop "$PKG"; sleep 1
      adb logcat -c
      adb shell am start -a android.intent.action.VIEW -t video/mp4 -d "file://$MEDIA/$f" -n "$PLAYER" >/dev/null
      for _ in $(seq 1 20); do adb logcat -d -s PlayerEngine:I | grep -q "first frame" && break; sleep 0.5; done
      log=$(adb logcat -d -v epoch -s ActivityTaskManager:I PlayerEngine:I | tr -d '\r')
      t0=$(echo "$log" | grep "START u0" | grep PlayerActivity | head -1 | awk '{print $1}' || true)
      t1=$(echo "$log" | grep "first frame" | head -1 | awk '{print $1}' || true)
      dec=$(echo "$log" | grep -o 'decoder [A-Z+]* ([a-z-]*)' | head -1 | cut -d' ' -f2 || true)
      if [ -n "$t0" ] && [ -n "$t1" ]; then python3 -c "print(round(($t1-$t0)*1000))" >> "$RAW/first-frame-$f.txt"; fi
    done
    echo "| $f | $(wc -l < "$RAW/first-frame-$f.txt") | $(p 50 < "$RAW/first-frame-$f.txt") | $(p 90 < "$RAW/first-frame-$f.txt") | ${dec:--} |" >> "$OUT"
  done
  echo >> "$OUT"
fi

if want playback; then
  echo "playback (NF-3, NF-4, NF-7)"
  {
    echo "## Frame drops with the overlay toggled every 10 s (NF-3 <= 0.1%, NF-4 and NF-7 per rule)"
    echo
    echo "| File | Seconds | Frames | Dropped | Drop % | Decoder | Video |"
    echo "|---|---|---|---|---|---|---|"
  } >> "$OUT"
  for spec in h264-1080p60.mp4:$PLAY_SECS hevc8-1080p60.mp4:$PLAY_SECS ass-karaoke-1080p60.mkv:$PLAY_SECS \
              hevc10-2160p60.mp4:$PLAY4K_SECS av1-2160p60.mp4:$PLAY4K_SECS av1-1080p60.mp4:$PLAY4K_SECS \
              vp9-1080p60.webm:$PLAY4K_SECS; do
    f=${spec%%:*}; secs=${spec##*:}
    [ -f "media/$f" ] || continue
    bench playback "$f" "$secs" $((secs + 60))
    r="$RAW/playback-$f.json"
    echo "| $f | $(j "$r" seconds) | $(j "$r" frames) | $(j "$r" dropped) | $(j "$r" dropPercent) | $(j "$r" activeDecoder) | $(j "$r" video) $(j "$r" error | sed 's/^-$//') |" >> "$OUT"
  done
  echo >> "$OUT"
fi

if want seek && [ -f media/h264-long-1080p30.mp4 ]; then
  echo "seek (NF-6)"
  bench seek h264-long-1080p30.mp4 $SEEKS 120
  r="$RAW/seek-h264-long-1080p30.mp4.json"
  {
    echo "## Seek release to target frame (NF-6: p50 <= 200 ms)"
    echo
    echo "| File | Seeks | p50 ms | p90 ms |"
    echo "|---|---|---|---|"
    echo "| h264-long-1080p30.mp4 | $SEEKS | $(j "$r" seekP50) | $(j "$r" seekP90) |"
    echo
  } >> "$OUT"
fi

for sc in scrub preview; do
  want $sc && [ -f media/h264-long-1080p30.mp4 ] || continue
  echo "$sc (NF-6, US-30.1)"
  bench $sc h264-long-1080p30.mp4 $SCRUB_SECS 60
done
if (want scrub || want preview) && [ -f media/h264-long-1080p30.mp4 ]; then
  {
    echo "## Frames while dragging (NF-6: >= 10 per second; US-30.1: preview within 2 s of the target)"
    echo
    echo "30 targets a second across 10 to 90% of a 1-hour file with an 8 s GOP. Distance is from each shown frame to the newest target."
    echo
    echo "| Path | Targets | Frames shown | Per second | Distance p50 ms | p90 ms |"
    echo "|---|---|---|---|---|---|"
    for sc in scrub preview; do
      r="$RAW/$sc-h264-long-1080p30.mp4.json"
      [ -f "$r" ] || continue
      echo "| $sc | $(j "$r" targetsSent) | $(j "$r" framesShown) | $(j "$r" framesPerSecond) | $(j "$r" distanceP50Ms) | $(j "$r" distanceP90Ms) |"
    done
    echo
  } >> "$OUT"
fi

adb shell am force-stop "$PKG"
echo "wrote $OUT"
