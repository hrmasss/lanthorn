#!/usr/bin/env bash
# Test streams for perf/stream-server.py: a 20 s 360p H.264+AAC clip as progressive MP4, HLS and DASH,
# plus a text file named .mp4 for the "unsupported" case. Usage: perf/gen-streams.sh <dir>
set -euo pipefail
D=${1:?dir}
mkdir -p "$D/hls" "$D/dash"
ffmpeg -y -loglevel error -f lavfi -i testsrc2=size=640x360:rate=25:duration=20 \
  -f lavfi -i sine=frequency=440:duration=20 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 \
  -c:a aac -shortest "$D/clip.mp4"
ffmpeg -y -loglevel error -i "$D/clip.mp4" -c copy -f hls -hls_time 4 -hls_playlist_type vod "$D/hls/index.m3u8"
ffmpeg -y -loglevel error -i "$D/clip.mp4" -c copy -f dash "$D/dash/manifest.mpd"
echo "this is not a video" >"$D/fake.mp4"
echo "streams in $D"
