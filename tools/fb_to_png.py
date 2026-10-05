"""
Turn a raw goldfish framebuffer dump into PNGs, so an API-1 screen can actually be LOOKED at.

WHY THIS EXISTS
---------------
The API-1 emulator has no `adb shell screencap` (it arrives at API 4) and its console has no
`screenrecord` command (that arrived with much newer emulator builds), so the usual ways to
grab a screen are both unavailable. What IS available is the kernel framebuffer:
/dev/graphics/fb0 is world-readable for the emulator's root shell, and the emulator keeps
rendering into it even under -no-window.

MEASURED GEOMETRY (this AVD)
----------------------------
  bits_per_pixel = 16 (RGB565, little endian)
  virtual_size   = "320,960"
  WindowManager  = 480x320 landscape

So the panel is 480x320 but the framebuffer holds the image TRANSPOSED - 320 wide, 480 tall,
two pages deep. Reading it as 480x320 produces garbage (the rows are interleaved wrong);
reading it as 320x480 produces the right pixels standing on their side, which is why the
default is a 90 degree turn. --rotate is therefore about the storage layout, not a taste.

    python fb_to_png.py <fb.raw> <out-prefix> [--size 480x320] [--rotate ccw|cw|none]

Writes <out-prefix>-page0.png and <out-prefix>-page1.png: the driver pans between the two
pages, so both are emitted and the caller uses the one that is not stale (page 1 has been the
live one in every capture so far).
"""
import os
import sys

from PIL import Image

BPP = 2


def parse_size(text):
    w, h = text.lower().split("x")
    return int(w), int(h)


def convert(data, w, h, offset):
    img = Image.new("RGB", (w, h))
    px = img.load()
    base = offset
    for y in range(h):
        row = base + y * w * BPP
        for x in range(w):
            i = row + x * BPP
            lo = data[i]
            hi = data[i + 1]
            v = lo | (hi << 8)
            r = (v >> 11) & 0x1F
            g = (v >> 5) & 0x3F
            b = v & 0x1F
            px[x, y] = (r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2)
    return img


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    raw_path, out_prefix = sys.argv[1], sys.argv[2]
    w, h = 480, 320
    rotate = "ccw"
    for i, a in enumerate(sys.argv):
        if a == "--size" and i + 1 < len(sys.argv):
            w, h = parse_size(sys.argv[i + 1])
        if a == "--rotate" and i + 1 < len(sys.argv):
            rotate = sys.argv[i + 1].lower()
    data = open(raw_path, "rb").read()

    # The stored page is transposed relative to the panel when --rotate is used.
    rw, rh = (h, w) if rotate in ("ccw", "cw") else (w, h)
    page_bytes = rw * rh * BPP
    pages = len(data) // page_bytes
    print("raw=%d bytes  panel=%dx%d  stored=%dx%d  pages=%d  rotate=%s"
          % (len(data), w, h, rw, rh, pages, rotate))
    for p in range(max(pages, 1)):
        img = convert(data, rw, rh, p * page_bytes)
        if rotate == "ccw":
            img = img.transpose(Image.ROTATE_90)
        elif rotate == "cw":
            img = img.transpose(Image.ROTATE_270)
        out = "%s-page%d.png" % (out_prefix, p)
        img.save(out)
        print(out + "  %dx%d" % img.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
