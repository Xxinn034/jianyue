"""
Generate the three bottom-tab icons for 简阅 (jianyue).

WHY PNG AND NOT TEXT GLYPHS
---------------------------
API 1 has no vector drawables (VectorDrawable arrives at API 21), no Material icon font and
no useful symbol coverage in the built-in font, so the tab bar's "icons" have to be small
bitmaps.  The bar is 52 dip tall and the icon box is 22 dip, i.e. 22 px on the API-1
emulator (mdpi, 1 dip = 1 px).

Everything is drawn at SUPERSAMPLE (x8) and downsampled with LANCZOS, because PIL has no
antialiased polygon/ellipse fill: drawing 1-2 px strokes directly produces hard jaggies that
make a 22 px glyph unreadable.

Two colour variants per icon - the inactive grey and the accent red - are shipped as
separate files on purpose: `ImageView.setColorFilter` exists at API 1 but tinting goes
through Skia's colour-filter path, which is one more piece of native code to trust on a
2008 emulator image.  Two bitmaps are boringly reliable.

Usage:
    python make_tab_icons.py <project-root>        # writes res/drawable + a preview sheet
    python make_tab_icons.py <project-root> --sheet-only
"""
import os
import sys

from PIL import Image, ImageDraw

SS = 8                       # supersample factor
SIZE = 22                    # final icon edge, px == dip on mdpi
EDGE = SIZE * SS

GREY = (150, 150, 150, 255)       # inactive tab
ACCENT = (191, 59, 46, 255)       # active tab (colour/accent)
SHEET_BG = (247, 247, 247, 255)


def new_canvas():
    img = Image.new("RGBA", (EDGE, EDGE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def s(v):
    """dip -> supersampled px"""
    return v * SS


# --------------------------------------------------------------- glyph shapes
#
# Each draw function fills a 22x22 dip box.  Coordinates below are therefore written in dip
# and scaled through s(); the supersample only exists to get smooth edges.

def draw_shelf(d, color):
    """书架: an open book - two page panels meeting at the centre gutter.

    The pages rise towards the gutter at both the top and the bottom edge, which is what
    makes the silhouette read as an open book rather than as two rectangles; the gutter is
    1.6 dip wide so it survives the downsample (at 0.8 dip it blurs into a faint seam).
    """
    d.polygon([(s(3.4), s(6.8)), (s(10.2), s(4.6)), (s(10.2), s(17.6)), (s(3.4), s(19.4))],
              fill=color)
    d.polygon([(s(18.6), s(6.8)), (s(11.8), s(4.6)), (s(11.8), s(17.6)), (s(18.6), s(19.4))],
              fill=color)


def draw_source(d, color):
    """书源: a globe - ring, equator and one meridian."""
    w = s(1.7)
    cx, cy, r = s(11), s(11), s(7.4)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=int(w))
    d.line([s(3.6), cy, s(18.4), cy], fill=color, width=int(w))
    mx, my = s(3.5), s(7.4)
    d.ellipse([cx - mx, cy - my, cx + mx, cy + my], outline=color, width=int(w))


def draw_local(d, color):
    """本地导入: a folder with a plus punched out of it."""
    # folder tab + body, drawn as one polygon so the silhouette stays solid
    d.polygon([
        (s(2.8), s(6.6)), (s(8.6), s(6.6)), (s(10.1), s(8.6)),
        (s(19.2), s(8.6)), (s(19.2), s(17.4)),
        (s(2.8), s(17.4)),
    ], fill=color)
    d.rectangle([s(2.8), s(17.4 - 1.2), s(19.2), s(18.6)], fill=color)
    # plus, knocked out in the background colour by the caller (see punch())
    d.line([s(11), s(10.4), s(11), s(16.4)], fill=(0, 0, 0, 0), width=int(s(2.0)))
    d.line([s(8.0), s(13.4), s(14.0), s(13.4)], fill=(0, 0, 0, 0), width=int(s(2.0)))


def punch(img):
    """Erase the alpha that the plus cut out, so the plus is a real hole."""
    px = img.load()
    for y in range(EDGE):
        for x in range(EDGE):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if r == 0 and g == 0 and b == 0:
                px[x, y] = (0, 0, 0, 0)
    return img


SHAPES = [
    ("shelf", draw_shelf),
    ("source", draw_source),
    ("local", draw_local),
]


def render(shape_fn, color):
    img, d = new_canvas()
    shape_fn(d, color)
    if shape_fn is draw_local:
        punch(img)
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def make_sheet(out_path):
    """A contact sheet: every icon at 1:1 and at 5x, in both colours, so a human (or the
    agent) can actually judge legibility at the real size."""
    pad, zoom = 10, 5
    group_w = SIZE + pad + SIZE * zoom
    cell_w = pad + 2 * group_w + pad
    cell_h = pad + SIZE * zoom + pad
    sheet = Image.new("RGBA", (cell_w + pad, cell_h * len(SHAPES) + pad), SHEET_BG)
    for row, (name, fn) in enumerate(SHAPES):
        y = pad + row * cell_h
        for col, color in enumerate([GREY, ACCENT]):
            icon = render(fn, color)
            x = pad + col * (group_w + pad)
            sheet.alpha_composite(icon, (x, y + (SIZE * zoom - SIZE) // 2))
            big = icon.resize((SIZE * zoom, SIZE * zoom), Image.NEAREST)
            sheet.alpha_composite(big, (x + SIZE + pad, y))
    sheet.convert("RGB").save(out_path)
    return out_path


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else r"G:\jianyue-src"
    sheet_only = "--sheet-only" in sys.argv
    drawable = os.path.join(root, "res", "drawable")
    if not sheet_only:
        if not os.path.isdir(drawable):
            print("!! no such dir: " + drawable)
            return 1
        for name, fn in SHAPES:
            for suffix, color in [("off", GREY), ("on", ACCENT)]:
                p = os.path.join(drawable, "tab_%s_%s.png" % (name, suffix))
                render(fn, color).save(p)
                print("%-34s %d bytes" % (os.path.basename(p), os.path.getsize(p)))
    sheet = make_sheet(os.path.join(root, "tools", "tab-icons-preview.png"))
    print("preview: " + sheet)
    return 0


if __name__ == "__main__":
    sys.exit(main())
