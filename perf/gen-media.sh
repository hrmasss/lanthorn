#!/usr/bin/env bash
# Generates the perf test media into perf/media/ (gitignored). Media is never committed.
#
#   perf/gen-media.sh            full set, durations the NF rules name (slow: tens of minutes)
#   perf/gen-media.sh --quick    10-second versions of everything, for the emulator and CI smoke
#   THREADS=8 perf/gen-media.sh  more encoder threads on a machine you own (default 2)
#
# Every frame carries a burnt-in timecode and frame number, so screen recordings can be read
# frame-accurately (perf/README.md, MX comparison). Film-grain noise makes the encoders spend
# realistic bitrates; flat test patterns would decode unrealistically fast.
set -euo pipefail
cd "$(dirname "$0")"
OUT=media
mkdir -p "$OUT"
THREADS=${THREADS:-2}
QUICK=0; [ "${1:-}" = "--quick" ] && QUICK=1
FONT=${FONT:-/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf}
[ -f "$FONT" ] || { echo "set FONT to a .ttf file (DejaVuSans.ttf expected)"; exit 1; }

dur() { [ $QUICK = 1 ] && echo 10 || echo "$1"; }

# Source: moving test pattern, grain, timecode and frame number. $1 size, $2 fps.
src() {
  echo "testsrc2=size=$1:rate=$2,noise=alls=10:allf=t,drawtext=fontfile=$FONT:fontsize=h/14:fontcolor=white:box=1:boxcolor=black@0.6:x=w*0.04:y=h*0.80:text='%{pts\\:hms} f%{frame_num}'"
}
audio() { echo "sine=frequency=440:sample_rate=48000"; }

enc() { # name size fps seconds pix_fmt -- encoder args
  local name=$1 size=$2 fps=$3 secs=$4 pix=$5; shift 5
  local f="$OUT/$name"
  if [ -s "$f" ]; then echo "have $f"; return; fi
  echo "make $f ($size@$fps, ${secs}s)"
  nice -n 10 ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "$(src "$size" "$fps")" -f lavfi -i "$(audio)" -t "$secs" \
    -pix_fmt "$pix" -c:a aac -b:a 160k "$@" -threads "$THREADS" "$f.tmp.${f##*.}"
  mv "$f.tmp.${f##*.}" "$f"
}

# NF-2, NF-3: 1080p60 H.264 and HEVC 8-bit, 5 minutes.
enc h264-1080p60.mp4 1920x1080 60 "$(dur 300)" yuv420p \
  -c:v libx264 -preset fast -profile:v high -b:v 12M -maxrate 16M -bufsize 24M -g 120
enc hevc8-1080p60.mp4 1920x1080 60 "$(dur 300)" yuv420p \
  -c:v libx265 -preset fast -b:v 8M -x265-params log-level=error:keyint=120 -tag:v hvc1

# NF-4: 4K HEVC 10-bit and AV1, 2 minutes. AV1 1080p as well, for phones without 4K AV1 decode.
enc hevc10-2160p60.mp4 3840x2160 60 "$(dur 120)" yuv420p10le \
  -c:v libx265 -preset faster -profile:v main10 -b:v 30M -x265-params log-level=error:keyint=120 -tag:v hvc1
enc av1-2160p60.mp4 3840x2160 60 "$(dur 120)" yuv420p10le \
  -c:v libsvtav1 -preset 10 -b:v 18M -g 120
enc av1-1080p60.mp4 1920x1080 60 "$(dur 120)" yuv420p \
  -c:v libsvtav1 -preset 10 -b:v 6M -g 120

# Codec coverage: VP9.
enc vp9-1080p60.webm 1920x1080 60 "$(dur 120)" yuv420p \
  -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -b:v 8M -g 120 -c:a libopus

# NF-5: H.264 4:4:4, which phone hardware decoders refuse. Must fall back to software.
enc h264-444-1080p30.mp4 1920x1080 30 "$(dur 30)" yuv444p \
  -c:v libx264 -preset fast -profile:v high444 -b:v 8M -g 60

# NF-6: long file for seeking, typical long GOP (about 8 s at 30 fps).
enc h264-long-1080p30.mp4 1920x1080 30 "$(dur 3600)" yuv420p \
  -c:v libx264 -preset veryfast -profile:v high -b:v 3M -g 250

# NF-7: ASS with styling, positioning, fades, rotation and karaoke, muxed with the font attached.
ass="$OUT/karaoke.ass"
python3 - "$ass" "$(dur 300)" <<'PY'
import sys
path, secs = sys.argv[1], int(sys.argv[2])
head = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Karaoke,DejaVu Sans,64,&H00FFFFFF,&H000080FF,&H00200020,&H80000000,-1,0,0,0,100,100,2,0,1,4,3,8,40,40,60,1
Style: Dialogue,DejaVu Sans,56,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,2,2,40,40,50,1
Style: Sign,DejaVu Sans,48,&H0000E0FF,&H000000FF,&H00101010,&H00000000,-1,-1,0,0,100,100,0,0,1,2,0,5,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
def t(s):
    return f"{int(s//3600)}:{int(s//60%60):02d}:{s%60:05.2f}"
words = ["Ka", "ra", "o", "ke ", "tim", "ing ", "on ", "ev", "ery ", "syl", "la", "ble"]
lines = []
for i in range(0, secs, 4):
    k = "".join(f"{{\\kf{25 + (j * 7) % 20}}}{w}" for j, w in enumerate(words))
    lines.append(f"Dialogue: 0,{t(i)},{t(i+4)},Karaoke,,0,0,0,,{{\\fad(150,150)}}{k}")
    lines.append(f"Dialogue: 0,{t(i)},{t(i+4)},Dialogue,,0,0,0,,Line {i//4 + 1}: plain dialogue with an \\Noutline, a shadow and {{\\i1}}italics{{\\i0}}.")
    lines.append(f"Dialogue: 1,{t(i)},{t(i+4)},Sign,,0,0,0,,{{\\pos(1500,300)\\frz{(i * 9) % 30 - 15}\\blur2\\t(0,4000,\\fscx130\\fscy130)}}SIGN {i//4 + 1}")
    lines.append(f"Dialogue: 1,{t(i)},{t(i+4)},Sign,,0,0,0,,{{\\move(200,200,800,400)\\alpha&H40&\\bord6}}Moving")
open(path, "w").write(head + "\n".join(lines) + "\n")
PY
if [ ! -s "$OUT/ass-karaoke-1080p60.mkv" ]; then
  echo "make $OUT/ass-karaoke-1080p60.mkv"
  nice -n 10 ffmpeg -hide_banner -loglevel error -y -i "$OUT/h264-1080p60.mp4" -i "$ass" \
    -map 0 -map 1 -c copy -c:s ass -attach "$FONT" -metadata:s:t:0 mimetype=font/ttf \
    "$OUT/ass-karaoke-1080p60.mkv"
fi

ls -la "$OUT"
