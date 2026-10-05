"""
Generate the shelf's search icon (magnifier) for 简阅 (jianyue).

WHY A PNG
---------
Same reason as the tab bar (see make_tab_icons.py): API 1 has no vector drawables and the
built-in font has no magnifier glyph, so the "搜索" pill button at the top of the shelf is
replaced by a small bitmap. The icon box is 24 dip, i.e. 24 px on the API-1 emulator (mdpi).

Drawn at SUPERSAMPLE (x8) and downsampled with LANCZOS: PIL cannot antialias a stroked
ellipse, and a 2px ring drawn directly turns into unreadable jaggies at this size.

The shape is deliberately the plain "ring + handle at 45 degrees" magnifier that 开源阅读
uses, in the app's accent red, so it reads as an action even on a light background.

Usage:
    python make_search_icon.py <project-root>          # writes res/drawable/ic_search.png
    python make_search_icon.py <project-root> --sheet-only
"""
import os
import sys

from PIL import Image, ImageDraw

SS = 8                       # supersample factor
SIZE = 24                    # final icon edge, px == dip on mdpi
EDGE = SIZE * SS

ACCENT = (191, 59, 46, 255)      # @color/accent
SHEET_BG = (247, 247, 247, 255)


def s(v):
    """dip -> supersampled px"""
    return v * SS


def draw_search(d, color):
    """Magnifier: a ring plus a handle running to the lower-right corner.

    The ring's outer radius and the stroke are both kept >= 2 dip so the downsample keeps a
    clean hole in the middle; the handle starts inside the ring so the two shapes join
    without a visible seam.
    """
    w = s(2.4)
    cx, cy, r = s(10.2), s(10.2), s(5.6)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=int(w))
    d.line([s(14.6), s(14.6), s(20.4), s(20.4)], fill=color, width=int(w))
    # square cap on the handle: PIL has no capstyle, so the end is rounded by hand
    d.ellipse([s(19.2), s(19.2), s(21.6), s(21.6)], fill=color)


def render():
    img = Image.new("RGBA", (EDGE, EDGE), (0, 0, 0, 0))
    draw_search(ImageDraw.Draw(img), ACCENT)
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def make_sheet(out_path):
    """Contact sheet: 1:1 and 6x, on the shelf's white surface."""
    icon = render()
    zoom, pad = 6, 10
    cell_w = pad + SIZE + pad + SIZE * zoom + pad
    cell_h = pad + SIZE * zoom + pad
    sheet = Image.new("RGBA", (cell_w, cell_h), SHEET_BG)
    sheet.alpha_composite(icon, (pad, (cell_h - SIZE) // 2))
    sheet.alpha_composite(icon.resize((SIZE * zoom, SIZE * zoom), Image.NEAREST),
                          (pad + SIZE + pad, pad))
    sheet.convert("RGB").save(out_path)
    return out_path


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else r"G:\jianyue-src"
    sheet_only = "--sheet-only" in sys.argv
    if not sheet_only:
        drawable = os.path.join(root, "res", "drawable")
        if not os.path.isdir(drawable):
            print("!! no such dir: " + drawable)
            return 1
        p = os.path.join(drawable, "ic_search.png")
        render().save(p)
        print("%-20s %d bytes" % (os.path.basename(p), os.path.getsize(p)))
    sheet = make_sheet(os.path.join(root, "tools", "search-icon-preview.png"))
    print("preview: " + sheet)
    return 0


if __name__ == "__main__":
    sys.exit(main())
