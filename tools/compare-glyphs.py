#!/usr/bin/env python3
"""US-44.3: compares device screenshots of complex-script lines with host libass references.
Glyph pixels are those brighter than mid grey (white text on black). Only the reference text's box
(plus a margin) is compared, so the system gesture handle and the rest of the screen stay out. The
device frame is shifted by up to 20 px each way to its best alignment; the shift is reported, since
a placement difference is a difference too. Reports, per script, differing pixels as a share of the
reference's glyph pixels.
Usage: compare-glyphs.py <device shot dir> <reference dir> [video x offset, default 240]"""
import sys
from PIL import Image

def glyphs(img):
    w, h = img.size
    px = img.convert("L").load()
    return {(x, y) for y in range(h) for x in range(w) if px[x, y] > 128}

def main(shots, refs, x0=240):
    worst = 0.0
    for script in ["arabic", "hebrew", "devanagari", "thai"]:
        ref = Image.open(f"{refs}/reference-{script}.png")
        dev = Image.open(f"{shots}/us44-complex-{script}.png").crop((x0, 0, x0 + ref.width, ref.height))
        a = glyphs(ref)
        xs = [p[0] for p in a]; ys = [p[1] for p in a]
        m = 24
        box = (min(xs) - m, min(ys) - m, max(xs) + m, max(ys) + m)
        inbox = lambda p: box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]
        b = {p for p in glyphs(dev) if box[0] - 20 <= p[0] <= box[2] + 20 and box[1] - 20 <= p[1] <= box[3] + 20}
        best = None
        for dy in range(-20, 21):
            for dx in range(-20, 21):
                moved = {(x - dx, y - dy) for (x, y) in b}
                moved = {p for p in moved if inbox(p)}
                diff = len(a ^ moved)
                if best is None or diff < best[0]:
                    best = (diff, dx, dy)
        diff, dx, dy = best
        share = 100.0 * diff / max(len(a), 1)
        worst = max(worst, share)
        print(f"{script}: {len(a)} reference glyph pixels, {diff} differ ({share:.2f}%) at shift dx={dx} dy={dy} px")
    print(f"worst {worst:.2f}% (limit 1%)")

main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 240)
