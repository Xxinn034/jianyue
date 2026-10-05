"""Measure ink geometry of a reader screenshot: line pitch, margins, clipping.

Usage: python analyze-shot.py <png>

WHY: the emulator's framebuffer is the only view of the reading page (API 1 has no
screencap), and "the last line is clipped" / "the right margin is wider than the left"
are pixel facts. Eyeballing a 480x320 PNG is not measurement, so this prints:
  - the ink rows grouped into text lines (top, bottom, pitch),
  - each line's ink left/right columns (so both margins can be compared),
  - how much ink touches the very last row (clipped glyphs).
"""
import sys

from PIL import Image

path = sys.argv[1]
img = Image.open(path).convert("L")
w, h = img.size
px = img.load()

# ink = clearly darker than the paper background (#F6F1E7 -> luma ~241)
INK = 150

rows = []
for y in range(h):
    n = 0
    for x in range(w):
        if px[x, y] < INK:
            n += 1
    rows.append(n)

lines = []
y = 0
while y < h:
    if rows[y] > 0:
        y0 = y
        while y < h and rows[y] > 0:
            y += 1
        lines.append((y0, y - 1))
    else:
        y += 1

print("image %dx%d" % (w, h))
print("ink lines: %d" % len(lines))
prev = None
for (a, b) in lines:
    xs = []
    for x in range(w):
        for yy in range(a, b + 1):
            if px[x, yy] < INK:
                xs.append(x)
                break
    left = min(xs) if xs else -1
    right = max(xs) if xs else -1
    pitch = "" if prev is None else ("  pitch=%d" % (a - prev))
    print("  y=%3d..%3d h=%2d  x=%3d..%3d  leftGap=%3d  rightGap=%3d%s"
          % (a, b, b - a + 1, left, right, left, w - 1 - right, pitch))
    prev = a

# anything painted in the last two rows is a clipped line
print("ink in last row: %d px ; second-to-last row: %d px" % (rows[h - 1], rows[h - 2]))
