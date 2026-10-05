"""
Generate the 简阅 (jianyue) launcher icon.

Design: a dark neutral background (following 开源阅读's restrained palette) with an OPEN
book - landscape, two pages, folded corner - and NO text baked into the bitmap.

WHY THERE IS NO TEXT
--------------------
The 简阅 characters were requested and were tried. Measured while tuning:

    two CJK glyphs at 20 px each -> illegible
    two CJK glyphs at 24 px each -> the minimum that reads
    one CJK glyph  at 24-28 px   -> clean

Two glyphs at 24 px need about 48 px of width, which is the ENTIRE icon. There is no room
left for the book, and any smaller text turns to grey mush. API 1's launcher renders the
icon at 48x48, so this is a hard size limit rather than an implementation problem.

Keeping the book alone lets it stay crisp, and the launcher's own label under the icon
already shows 简阅. Set DRAW_TEXT = True to put the characters back anyway.

Usage: python make_icon.py <project-root>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

DRAW_TEXT = False

# Dark neutral background, mirroring the palette used by 开源阅读's icon.
BG_TOP = (88, 90, 94, 255)       # #585A5E
BG_BOTTOM = (56, 58, 61, 255)    # #383A3D
PAGE = (250, 249, 247, 255)
PAGE_SHADE = (226, 224, 220, 255)
FOLD = (196, 193, 188, 255)
SPINE = (120, 118, 114, 255)
TEXT_COLOR = (238, 236, 232, 255)

# Candidate CJK fonts, in order of preference: a bold/heavy face reads better at 10 px.
FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/Dengb.ttf",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simsun.ttc",
]


def load_font(px):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, px)
            except Exception:
                continue
    return None


def rounded_rect_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def make(size):
    """Draw one icon whose OUTPUT is exactly `size` pixels square."""
    # Supersampling factor.
    #
    # 16 destroyed the sharpness: drawing a 48 px icon at 768 px and LANCZOS-ing back down
    # to 48 leaves the thin page edges as grey haze. 6 is the sweet spot - enough samples to
    # keep the diagonal fold and the spine crisp, not so many that the downscale softens
    # every edge.
    ss = 6
    W = size * ss
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    # ---- background: vertical gradient inside a rounded square ----
    grad = Image.new("RGBA", (W, W))
    gd = ImageDraw.Draw(grad)
    for y in range(W):
        t = y / float(W - 1)
        c = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(4))
        gd.line([(0, y), (W, y)], fill=c)
    grad.putalpha(rounded_rect_mask((W, W), int(W * 0.22)))
    img.alpha_composite(grad)

    d = ImageDraw.Draw(img)

    # ---- an OPEN book: two page planes meeting at a central spine ----
    # Kept landscape (wider than tall) as requested. The outer edges sit HIGHER than the
    # spine, which is what gives the open-book silhouette; the page block below adds
    # thickness so it reads as a book rather than a sheet of paper.
    #
    # Sizing was tuned by eye on the device: this is the proportion the user picked
    # ("现在的尺寸很合适"), so it is deliberately left alone.
    cx = W * 0.5
    if DRAW_TEXT:
        bodyW = W * 0.62
        bodyH = W * 0.34
        text_h = W * 0.22
        gap = W * 0.22                      # one character height, as requested
    else:
        bodyW = W * 0.62
        bodyH = W * 0.46
        text_h = 0.0
        gap = 0.0
    block_h = bodyH + gap + text_h
    top = (W - block_h) / 2.0

    x0 = cx - bodyW / 2.0
    x1 = cx + bodyW / 2.0
    ytop = top + W * 0.026
    ybot = top + bodyH - W * 0.010
    dip = bodyH * 0.115                    # the spine sits this much lower
    fold = bodyH * 0.20                    # folded corner on the right page

    # ---- page block underneath: thickness + a soft drop shadow ----
    d.polygon([
        (x0 + W * 0.010, ytop + W * 0.046),
        (x1 + W * 0.010, ytop + W * 0.046),
        (x1 - W * 0.002, ybot + W * 0.052),
        (x0 - W * 0.002, ybot + W * 0.052),
    ], fill=(26, 27, 29, 150))
    for i in range(3, 0, -1):
        off = W * 0.009 * i
        d.polygon([
            (x0 + off, ytop + off),
            (cx, ytop + dip + off),
            (x1 + off, ytop + off),
            (x1 + off, ybot + off),
            (cx, ybot + dip + off),
            (x0 + off, ybot + off),
        ], fill=(234, 231, 226, 255))

    # ---- left page ----
    d.polygon([
        (x0, ytop),                        # outer top
        (cx, ytop + dip),                  # spine top
        (cx, ybot + dip),                  # spine bottom
        (x0, ybot),                        # outer bottom
    ], fill=PAGE_SHADE)

    # ---- right page, with the top-right corner folded ----
    d.polygon([
        (cx, ytop + dip),                  # spine top
        (x1 - fold, ytop),                 # along the top edge
        (x1, ytop + fold),                 # down the diagonal
        (x1, ybot),                        # outer bottom
        (cx, ybot + dip),                  # spine bottom
    ], fill=PAGE)

    # the folded flap
    d.polygon([
        (x1 - fold, ytop),
        (x1, ytop + fold),
        (x1 - fold, ytop + fold),
    ], fill=FOLD)
    d.line([(x1 - fold, ytop), (x1, ytop + fold)],
           fill=(172, 168, 162, 255), width=max(1, int(W * 0.007)))

    # ---- spine seam down the middle ----
    d.line([(cx, ytop + dip), (cx, ybot + dip)],
           fill=SPINE, width=max(1, int(W * 0.016)))

    # ---- text lines on both pages, kept clear of the fold ----
    line_w = max(1, int(W * 0.014))
    for i in range(3):
        y = ytop + dip + (ybot - ytop) * (0.28 + i * 0.26)
        # left page
        lx0 = x0 + W * 0.055
        lx1 = cx - W * 0.045
        if i == 2:
            lx1 = cx - W * 0.075           # last line shorter, like real text
        d.line([(lx0, y), (lx1, y)], fill=(200, 196, 190, 255), width=line_w)
        # right page
        rx0 = cx + W * 0.045
        rx1 = x1 - W * 0.055
        if i == 0:
            rx1 = x1 - fold - W * 0.020
        d.line([(rx0, y), (rx1, y)], fill=(200, 196, 190, 255), width=line_w)

    # ---- the app's Chinese name under the book ----
    if DRAW_TEXT:
        # One size up from the previous version, matching the enlarged text_h above.
        font = load_font(int(W * 0.215))
        if font is not None:
            title = "\u7B80\u9605"           # 简阅
            ty = ybot + dip + gap
            try:
                bbox = d.textbbox((0, 0), title, font=font)
                tw = bbox[2] - bbox[0]
                tx = (W - tw) / 2.0 - bbox[0] - bbox[0]
                tx = (W - tw) / 2.0 - bbox[0]
                ty = ty - bbox[1]
            except Exception:
                tx = None
            if tx is not None:
                d.text((tx, ty), title, font=font, fill=TEXT_COLOR)

    return img.resize((size, size), Image.LANCZOS)


def premultiplied_resize(img, size):
    """
    Downscale RGBA with alpha premultiplied, then un-premultiply.

    Resizing straight RGBA blends the colour channels with the fully-transparent pixels in
    the corner cut-outs, which is what pulls a grey halo around every edge and makes a small
    icon look soft. Weighting by alpha first, then dividing it back out, keeps the edges
    clean.
    """
    w, h = img.size
    px = img.load()
    pre = Image.new("RGBA", (w, h))
    pp = pre.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                pp[x, y] = (0, 0, 0, 0)
            else:
                pp[x, y] = (r * a // 255, g * a // 255, b * a // 255, a)
    small = pre.resize(size, Image.LANCZOS)
    sw, sh = small.size
    sp = small.load()
    out = Image.new("RGBA", (sw, sh))
    op = out.load()
    for y in range(sh):
        for x in range(sw):
            r, g, b, a = sp[x, y]
            if a == 0:
                op[x, y] = (0, 0, 0, 0)
            else:
                op[x, y] = (min(255, r * 255 // a), min(255, g * 255 // a),
                            min(255, b * 255 // a), a)
    return out


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."

    # A single density-independent drawable, NOT drawable-ldpi/mdpi/hdpi: Android 1.0
    # predates density-qualified resources, and the archived aapt v0.2 rejects
    # "drawable-hdpi" with "invalid resource directory name".
    #
    # 48x48 is the classic mdpi launcher-icon size. The bitmap is rendered at that exact
    # size (6x supersampled) rather than downscaled from something larger, which is what
    # keeps it crisp.
    out_dir = os.path.join(root, "res", "drawable")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "ic_launcher.png")
    premultiplied_resize(make(48 * 6), (48, 48)).save(path, "PNG", optimize=True)
    print("wrote %s (48x48, %d bytes)" % (path, os.path.getsize(path)))

    # Previews outside res/, so they are never packaged. 192 shows the design; 96 is close
    # to what a hdpi phone actually shows, which is the honest way to judge sharpness.
    preview_dir = os.path.join(root, "tools", "preview")
    os.makedirs(preview_dir, exist_ok=True)
    for px in (192, 96, 72):
        p = os.path.join(preview_dir, "icon-%d.png" % px)
        premultiplied_resize(make(px * 6), (px, px)).save(p, "PNG", optimize=True)
        print("wrote %s (%dx%d preview, not packaged)" % (p, px, px))


if __name__ == "__main__":
    main()
