"""
Turn a raw framebuffer dump into PNGs, so a screen can actually be LOOKED at.

WHY THIS EXISTS
---------------
The API-1 emulator has no `adb shell screencap` (it arrives at API 4) and its console has no
`screenrecord` command (that arrived with much newer emulator builds), so the usual ways to
grab a screen are both unavailable. What IS available is the kernel framebuffer:
/dev/graphics/fb0 is world-readable for the emulator's root shell, and the emulator keeps
rendering into it even under -no-window. The same trick is the only way to see the screen of a
real device that predates screencap: the Coolpad 8056 (Android 2.3.5) has no screencap at all.

MEASURED GEOMETRY
-----------------
  API-1 emulator (goldfish)
    bits_per_pixel = 16 (RGB565, little endian)
    virtual_size   = "320,960"
    WindowManager  = 480x320 landscape
    -> the framebuffer holds the image TRANSPOSED: 320 wide, 480 tall, two pages deep.
       Reading it as 480x320 produces garbage (the rows come out interleaved wrong); reading
       it as 320x480 gives the right pixels standing on their side, which is why the default
       is a 90 degree turn. --rotate is about the storage layout, not a taste.

  Coolpad 8056 (sprdfb, Android 2.3.5)
    bits_per_pixel = 32 (BGRA in memory), stride 1280
    virtual_size   = "320,960" - two 480 line pages, NOT transposed, no rotation
    -> --bpp 4 --order bgra --rotate none

    python fb_to_png.py <fb.raw> <out-prefix> [--size 480x320] [--rotate ccw|cw|none]
                        [--bpp 2|4] [--order rgba|bgra]

Writes <out-prefix>-page0.png and <out-prefix>-page1.png: the driver pans between the two
pages, so both are emitted and the caller uses the one that is not stale (page 1 has been the
live one in every API-1 capture so far; on the Coolpad /sys/class/graphics/fb0/pan reports
which page is on screen).
"""
import os
import sys

from PIL import Image


def parse_size(text):
    w, h = text.lower().split("x")
    return int(w), int(h)


def convert565(data, w, h, offset):
    img = Image.new("RGB", (w, h))
    px = img.load()
    base = offset
    for y in range(h):
        row = base + y * w * 2
        for x in range(w):
            i = row + x * 2
            lo = data[i]
            hi = data[i + 1]
            v = lo | (hi << 8)
            r = (v >> 11) & 0x1F
            g = (v >> 5) & 0x3F
            b = v & 0x1F
            px[x, y] = (r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2)
    return img


def convert8888(data, w, h, offset, order):
    """32 bpp pixels; --order names the byte order the panel stores them in."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    base = offset
    for y in range(h):
        row = base + y * w * 4
        for x in range(w):
            i = row + x * 4
            if order == "rgba":
                px[x, y] = (data[i], data[i + 1], data[i + 2])
            else:
                px[x, y] = (data[i + 2], data[i + 1], data[i])
    return img


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    raw_path, out_prefix = sys.argv[1], sys.argv[2]
    w, h = 480, 320
    rotate = "ccw"
    bpp = 2
    order = "bgra"
    for i, a in enumerate(sys.argv):
        if a == "--size" and i + 1 < len(sys.argv):
            w, h = parse_size(sys.argv[i + 1])
        if a == "--rotate" and i + 1 < len(sys.argv):
            rotate = sys.argv[i + 1].lower()
        if a == "--bpp" and i + 1 < len(sys.argv):
            bpp = int(sys.argv[i + 1])
        if a == "--order" and i + 1 < len(sys.argv):
            order = sys.argv[i + 1].lower()
    data = open(raw_path, "rb").read()

    # The stored page is transposed relative to the panel when --rotate is used.
    rw, rh = (h, w) if rotate in ("ccw", "cw") else (w, h)
    page_bytes = rw * rh * bpp
    pages = len(data) // page_bytes
    print("raw=%d bytes  panel=%dx%d  stored=%dx%d  bpp=%d  pages=%d  rotate=%s  order=%s"
          % (len(data), w, h, rw, rh, bpp, pages, rotate, order))
    for p in range(max(pages, 1)):
        offset = p * page_bytes
        if bpp == 4:
            img = convert8888(data, rw, rh, offset, order)
        else:
            img = convert565(data, rw, rh, offset)
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
