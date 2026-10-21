#!/usr/bin/env python3
"""Writes a minimal PGS (.sup) file: one text line shown from 0 to 10 s. Usage: pgs.py out.sup "text" width height"""
import struct, sys
from PIL import Image, ImageDraw, ImageFont

def seg(kind, payload, pts):
    return b"PG" + struct.pack(">IIBH", pts, 0, kind, len(payload)) + payload

def rle(img):
    w, h = img.size
    px = img.load()
    out = bytearray()
    for y in range(h):
        x = 0
        while x < w:
            c = px[x, y]
            n = 1
            while x + n < w and px[x + n, y] == c and n < 16383:
                n += 1
            if c != 0 and n < 3:
                out += bytes([c]) * n
            elif c == 0:
                out += bytes([0, n]) if n < 64 else bytes([0, 0x40 | (n >> 8), n & 0xFF])
            else:
                out += bytes([0, 0x80 | n, c]) if n < 64 else bytes([0, 0xC0 | (n >> 8), n & 0xFF, c])
            x += n
        out += b"\x00\x00"
    return bytes(out)

def main(path, text, W, H):
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 40)
    tw, th = font.getbbox(text)[2] + 8, font.getbbox(text)[3] + 8
    img = Image.new("P", (tw, th), 0)
    d = ImageDraw.Draw(img)
    for dx in (-2, 0, 2):
        for dy in (-2, 0, 2):
            d.text((4 + dx, 2 + dy), text, font=font, fill=2)
    d.text((4, 2), text, font=font, fill=1)
    x, y = (W - tw) // 2, H - th - 40
    start, end = 0, 10 * 90000
    pcs = struct.pack(">HHBHBBBB", W, H, 0x10, 0, 0x80, 0, 0, 1) + struct.pack(">HBBHH", 0, 0, 0, x, y)
    wds = struct.pack(">BBHHHH", 1, 0, x, y, tw, th)
    # palette: 0 transparent, 1 white, 2 black (Y, Cr, Cb, A)
    pds = struct.pack(">BB", 0, 0) + bytes([0, 16, 128, 128, 0, 1, 235, 128, 128, 255, 2, 16, 128, 128, 255])
    data = rle(img)
    ods = struct.pack(">HBB", 0, 0, 0xC0) + (len(data) + 4).to_bytes(3, "big") + struct.pack(">HH", tw, th) + data
    out = seg(0x16, pcs, start) + seg(0x17, wds, start) + seg(0x14, pds, start) + seg(0x15, ods, start) + seg(0x80, b"", start)
    clear = struct.pack(">HHBHBBBB", W, H, 0x10, 1, 0x00, 0, 0, 0)
    out += seg(0x16, clear, end) + seg(0x17, wds, end) + seg(0x80, b"", end)
    open(path, "wb").write(out)

main(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
