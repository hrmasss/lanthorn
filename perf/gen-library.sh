#!/usr/bin/env bash
# Fake video library for NF-1 and NF-9 runs: N small videos over F folders, pushed to a device or
# emulator and indexed by MediaStore. Ten distinct clips, so thumbnails differ.
#
#   perf/gen-library.sh [count=1000] [folders=20]   push and index
#   perf/gen-library.sh --clean                     remove it again
#
# Files land in /sdcard/Movies/LanthornBench/. Needs ffmpeg and adb (ADB or PATH).
set -euo pipefail

ADB=${ADB:-adb}
REMOTE=/sdcard/Movies/LanthornBench

if [[ ${1:-} == --clean ]]; then
  "$ADB" shell rm -rf "$REMOTE"
  "$ADB" shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null
  echo "removed $REMOTE"
  exit 0
fi

COUNT=${1:-1000}
FOLDERS=${2:-20}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Ten 2 to 11 s clips, 160x90 H.264, about 10 kB each.
for i in $(seq 0 9); do
  ffmpeg -loglevel error -f lavfi -i "testsrc2=size=160x90:rate=25:duration=$((i + 2))" \
    -vf "hue=h=$((i * 36))" -c:v libx264 -preset ultrafast -crf 35 -pix_fmt yuv420p "$WORK/clip$i.mp4"
done

TREE="$WORK/LanthornBench"
for n in $(seq 1 "$COUNT"); do
  folder=$(printf "Folder %02d" $(((n - 1) % FOLDERS + 1)))
  mkdir -p "$TREE/$folder"
  cp "$WORK/clip$((n % 10)).mp4" "$TREE/$folder/Episode $n.mp4"
done

"$ADB" shell rm -rf "$REMOTE"
"$ADB" push -q "$TREE" /sdcard/Movies/ >/dev/null
"$ADB" shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null
indexed=$("$ADB" shell "content query --uri content://media/external/video/media --projection _id \
  --where \"relative_path LIKE 'Movies/LanthornBench/%'\"" | grep -c '^Row' || true)
echo "pushed $COUNT videos in $FOLDERS folders to $REMOTE, MediaStore indexed $indexed"
