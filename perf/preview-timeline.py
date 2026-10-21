#!/usr/bin/env python3
"""Seek-preview numbers from a SeekPreviewUiTest logcat (US-30.1).

Usage: preview-timeline.py <logcat.txt>

Pairs every "preview frame at X ms, target Y ms" line from the engine with the finger's time
("finger at F ms" from the test) at that moment, and prints:
  - the drag's real length and the preview frames shown during it, per second
  - how far each frame is from the target it was sought for (the seek's accuracy)
  - how far each frame is from where the finger was when it showed (accuracy plus lag)
  - the frame and finger at the mid-drag screenshot
"""
import re
import sys
from datetime import datetime

STAMP = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})")
FRAME = re.compile(r"preview frame at (-?\d+|null) ms, target (-?\d+|null) ms")
# Logged when the view has the frame on screen; when present these are the frames that count.
SHOWN = re.compile(r"preview frame shown at (-?\d+|null) ms, (\d+) ms after it landed")
FINGER = re.compile(r"P1\.6\s*:\s*finger at (\d+) ms")


def when(line):
    m = STAMP.match(line)
    return datetime.strptime("2000-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f").timestamp() if m else None


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    return v[int(p / 100 * (len(v) - 1))]


def main(path):
    start = end = shot = None
    finger = None
    frames = []  # (time, shown, target, finger), from mpv landing the seek
    drawn = []  # the same, from the view showing the frame
    draw_delay = []
    target = None
    shot_state = None
    rest_state = None
    for line in open(path, errors="replace"):
        t = when(line)
        if t is None:
            continue
        if "P1.6" in line and "drag start" in line:
            start = t
        elif "P1.6" in line and "drag end" in line:
            end = t
        elif "P1.6" in line and "shot mid-drag" in line:
            shot = t
            shot_state = (t, finger)
        elif "P1.6" in line and "shot finger resting" in line:
            rest_state = (t, finger)
        m = FINGER.search(line)
        if m:
            finger = int(m.group(1))
            continue
        m = FRAME.search(line)
        if m and start is not None and m.group(1) != "null":
            shown = int(m.group(1))
            target = None if m.group(2) == "null" else int(m.group(2))
            frames.append((t, shown, target, finger))
            continue
        m = SHOWN.search(line)
        if m and start is not None and m.group(1) != "null":
            drawn.append((t, int(m.group(1)), target, finger))
            draw_delay.append(int(m.group(2)))
    if start is None or end is None:
        sys.exit("no drag start/end in the log")
    if drawn:
        print(f"frames counted when the view showed them; landing to shown p50 {pct(draw_delay, 50)} ms, max {max(draw_delay)} ms")
        frames = drawn
    during = [f for f in frames if start <= f[0] <= end]
    length = end - start
    acc = [abs(s - g) for _, s, g, _ in during if g is not None]
    lag = [abs(s - f) for _, s, _, f in during if f is not None]
    print(f"drag: {length:.1f} s, preview frames during it: {len(during)}, {len(during) / length:.1f} a second")
    print(f"frame vs its seek target: p50 {pct(acc, 50)} ms, p90 {pct(acc, 90)} ms, max {max(acc) if acc else None} ms ({len(acc)} frames)")
    print(f"frame vs finger when shown: p50 {pct(lag, 50)} ms, p90 {pct(lag, 90)} ms, max {max(lag) if lag else None} ms")
    for name, at in (("mid-drag shot", shot_state), ("finger-resting shot", rest_state)):
        if at is None:
            continue
        at, fing = at
        before = [f for f in frames if f[0] <= at]
        f = before[-1] if before else None
        print(f"{name}: last frame {f[1] if f else None} ms (target {f[2] if f else None} ms), finger at {fing} ms")


if __name__ == "__main__":
    main(sys.argv[1])
