#!/usr/bin/env python3
"""US-44.3: compares device screenshots of complex-script lines with host libass references.
Glyph pixels are those brighter than mid grey (white text on black). Reports, per script, the pixels
that differ as a share of the reference's glyph pixels.
Usage: compare-glyphs.py <device shot dir> <reference dir> [video x offset, default 240]"""
import sys
from PIL import Image

def mask(img):
    g = img.convert("L")
    return [p > 128 for p in g.getdata()]

def main(shots, refs, x0=240):
    worst = 0.0
    for script in ["arabic", "hebrew", "devanagari", "thai"]:
        ref = Image.open(f"{refs}/reference-{script}.png")
        dev = Image.open(f"{shots}/us44-complex-{script}.png").crop((x0, 0, x0 + ref.width, ref.height))
        a, b = mask(ref), mask(dev)
        glyph = sum(a)
        diff = sum(1 for p, q in zip(a, b) if p != q)
        share = 100.0 * diff / max(glyph, 1)
        worst = max(worst, share)
        print(f"{script}: {glyph} reference glyph pixels, {diff} differ, {share:.2f}%")
    print(f"worst {worst:.2f}% (limit 1%)")

main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 240)
