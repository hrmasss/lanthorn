#!/usr/bin/env python3
"""US-44.3 test set: Arabic, Hebrew, Devanagari and Thai lines in one ASS file, muxed into a black
clip with their fonts embedded under unique names (so the device cannot swap in its own Noto
fonts), plus libass reference frames rendered on the host at the size the device draws the video.
Needs fontTools (pip install fonttools) and an FFmpeg with libass.
Usage: gen-complex-scripts.py <asset dir> <reference dir>"""
import os, subprocess, sys, tempfile
from fontTools.ttLib import TTFont

FONTS = {  # script: (source font, unique family)
    "arabic": ("/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf", "LTest Arabic"),
    "hebrew": ("/usr/share/fonts/truetype/noto/NotoSansHebrew-Regular.ttf", "LTest Hebrew"),
    "devanagari": ("/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf", "LTest Devanagari"),
    "thai": ("/usr/share/fonts/truetype/noto/NotoSansThai-Regular.ttf", "LTest Thai"),
}
LINES = {  # one line each, shown 3 s apart
    "arabic": "مرحبا بكم في المشغل الجديد",
    "hebrew": "שלום וברוכים הבאים לנגן",
    "devanagari": "नए प्लेयर में आपका स्वागत है",
    "thai": "ยินดีต้อนรับสู่เครื่องเล่นใหม่",
}
W, H = 1920, 1080  # the device draws a 16:9 video at 1920x1080 on its 2400x1080 screen

def rename(src, family, dst):
    f = TTFont(src)
    for rec in f["name"].names:
        if rec.nameID in (1, 4, 16):
            rec.string = family
        elif rec.nameID == 6:
            rec.string = family.replace(" ", "")
    f.save(dst)

def main(assets, refs):
    os.makedirs(assets, exist_ok=True); os.makedirs(refs, exist_ok=True)
    t = tempfile.mkdtemp()
    styles, events, attach = [], [], []
    for i, (script, (src, family)) in enumerate(FONTS.items()):
        dst = os.path.join(t, family.replace(" ", "") + ".ttf")
        rename(src, family, dst)
        attach += ["-attach", dst, f"-metadata:s:t:{i}", "mimetype=application/x-truetype-font"]
        styles.append(f"Style: {script},{family},64,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,40,40,80,1")
        events.append(f"Dialogue: 0,0:00:{i*3:02d}.00,0:00:{i*3+3:02d}.00,{script},,0,0,0,,{LINES[script]}")
    ass = os.path.join(t, "complex.ass")
    open(ass, "w", encoding="utf-8").write("\n".join([
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}", "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        *styles, "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text", *events, ""]))
    mkv = os.path.join(assets, "complex.mkv")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=black:s=1280x720:r=25:d=12",
                    "-i", ass, *attach, "-map", "0:v", "-map", "1:s", "-c:v", "libx264", "-preset", "veryfast",
                    "-crf", "35", "-pix_fmt", "yuv420p", "-c:s", "ass", mkv], check=True)
    # Host libass reference: the same ASS and fonts, rendered at the device's video size.
    for i, script in enumerate(FONTS):
        out = os.path.join(refs, f"reference-{script}.png")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"color=c=black:s={W}x{H}:r=25:d=12",
                        "-vf", f"subtitles={ass}:fontsdir={t}", "-ss", f"{i*3+1.5}", "-frames:v", "1", out], check=True)

main(sys.argv[1], sys.argv[2])
