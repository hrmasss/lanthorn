#!/usr/bin/env bash
# Test media for feature/subtitles and feature/audio instrumented tests. Small, synthetic, no
# third-party content. Rerun to regenerate; outputs are committed (all under 2 MB).
set -euo pipefail
cd "$(dirname "$0")/.."
SUB=feature/subtitles/src/androidTest/assets
AUD=feature/audio/src/androidTest/assets
mkdir -p "$SUB" "$AUD"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

# ASS with styling and karaoke (NF-7, US-42.4).
cat > "$T/karaoke.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Kara,sans-serif,36,&H0000FFFF,&H00FF0000,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,3,2,2,20,20,30,1
Style: Top,serif,28,&H00FF80FF,&H000000FF,&H00400040,&H00000000,0,1,0,0,100,100,0,0,1,2,0,8,20,20,20,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:10.00,Kara,,0,0,0,,{\k50}Sing {\k50}a{\k60}long {\k80}with {\kf120}karaoke
Dialogue: 0,0:00:00.00,0:00:10.00,Top,,0,0,0,,{\frz-5}Styled top line
ASS
printf '1\n00:00:00,000 --> 00:00:10,000\nEmbedded English line\n' > "$T/embedded.srt"

ffmpeg -loglevel error -y \
  -f lavfi -i testsrc2=size=640x360:rate=25:duration=10 \
  -f lavfi -i sine=frequency=440:duration=10:sample_rate=48000 \
  -f lavfi -i "sine=frequency=880:duration=10:sample_rate=48000,pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0" \
  -i "$T/karaoke.ass" -i "$T/embedded.srt" \
  -map 0:v -map 1:a -map 2:a -map 3:s -map 4:s \
  -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p -c:a:0 aac -b:a:0 64k -c:a:1 ac3 -b:a:1 192k \
  -c:s:0 ass -c:s:1 srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=ita -metadata:s:a:1 title="Italiano 5.1" \
  -metadata:s:s:0 language=eng -metadata:s:s:0 title=Karaoke -metadata:s:s:1 language=eng -metadata:s:s:1 title=English \
  -disposition:s:0 default "$T/tracks.mkv"
cp "$T/tracks.mkv" "$SUB/tracks.mkv"
cp "$T/tracks.mkv" "$AUD/tracks.mkv"

# External subtitles: UTF-8, and Czech in Windows-1250 for the encoding reload (US-44.2).
printf '1\n00:00:00,000 --> 00:00:10,000\nExternal UTF-8 line\n' > "$SUB/external.srt"
printf '1\n00:00:00,000 --> 00:00:10,000\nP\xf8\xedli\x9a \x9elu\x9dou\xe8k\xfd k\xf9\xf2\n' > "$SUB/cp1250.srt"

# External audio (US-47.3): 5 minutes, with a 1 kHz click every second on both video and audio
# tracks, so A/V offset is visible.
ffmpeg -loglevel error -y \
  -f lavfi -i "testsrc2=size=160x90:rate=25:duration=300" \
  -f lavfi -i "sine=frequency=1000:duration=300:sample_rate=48000:beep_factor=4" \
  -map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 45 -g 50 -pix_fmt yuv420p -c:a aac -b:a 32k "$AUD/long.mp4"
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=600:duration=300:sample_rate=48000" -c:a aac -b:a 32k "$AUD/dub.m4a"
du -h "$SUB"/* "$AUD"/*

# Wide 2.39:1 clip with a text subtitle: on a phone screen it gets black bars top and bottom,
# so "keep inside the picture" (US-43.2) is visible.
printf '1\n00:00:00,000 --> 00:00:10,000\nInside or outside the picture\n' > "$T/wide.srt"
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x268:rate=25:duration=10 -i "$T/wide.srt" \
  -map 0:v -map 1:s -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p -c:s srt -metadata:s:s:0 language=eng "$SUB/wide.mkv"
# ASS that names a font only the file carries (DejaVu Sans Mono, Bitstream Vera licence, free to
# redistribute), for "ignore embedded fonts" (US-42.4).
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf
cat > "$T/fonts.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Mono,DejaVu Sans Mono,40,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,2,0,2,20,20,40,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:10.00,Mono,,0,0,0,,iiii WWWW embedded font
ASS
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=25:duration=10 -i "$T/fonts.ass" \
  -attach "$FONT" -metadata:s:t:0 mimetype=application/x-truetype-font -metadata:s:t:0 filename=DejaVuSansMono-Bold.ttf \
  -map 0:v -map 1:s -c:v libx264 -preset veryfast -crf 32 -pix_fmt yuv420p -c:s ass "$SUB/fonts.mkv"

# Loud tone for the limiter check (US-49.2): 1 kHz at -3 dBFS, lossless so the peak is exact.
ffmpeg -loglevel error -y -f lavfi -i "aevalsrc=0.7079*sin(2*PI*1000*t):s=48000:d=10" -c:a flac "$AUD/loud.flac"

# Two audio tracks where the default-flagged one is second (US-47.2, "else the default-flagged track").
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=320x180:rate=25:duration=10 \
  -f lavfi -i sine=frequency=440:duration=10:sample_rate=48000 -f lavfi -i sine=frequency=880:duration=10:sample_rate=48000 \
  -map 0:v -map 1:a -map 2:a -c:v libx264 -preset veryfast -crf 35 -pix_fmt yuv420p -c:a aac -b:a 48k \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=ita -disposition:a:0 0 -disposition:a:1 default "$AUD/default-second.mkv"
