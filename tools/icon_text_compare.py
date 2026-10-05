"""
Compare text rendering strategies for a 48x48 launcher icon.

Two characters have to fit in roughly 44 px of width, so each glyph gets ~21 px. The
question is which pipeline keeps them readable at that size:

  A) draw straight at 48 px with no resampling (glyphs hinted at their final size)
  B) draw at 4x and LANCZOS down (smooth but grey)
  C) draw at 6x and BOX down      (slightly softer than LANCZOS but holds weight)
  D) draw straight at 48 px with a slightly bolder face

Writes each variant plus a 4x nearest-neighbour zoom, because judging a 48 px icon by
looking at a 48 px image is unreliable.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

BG = (72, 74, 77, 255)
FG = (245, 243, 240, 255)
TITLE = "\u7B80\u9605"          # 简阅

FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"
FONT_HEI = "C:/Windows/Fonts/simhei.ttf"
FONT_DENG = "C:/Windows/Fonts/Dengb.ttf"


def load(path, px):
    try:
        return ImageFont.truetype(path, px)
    except Exception:
        return None


def draw_bg(img):
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                        radius=int(img.size[0] * 0.20), fill=BG)
    return d


def text_plain(size, font_path, px, ss=1, resample=Image.LANCZOS):
    """Render just the characters, to judge legibility without the book competing."""
    W = size * ss
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = draw_bg(img)
    f = load(font_path, px)
    if f is None:
        return None
    bbox = d.textbbox((0, 0), TITLE, font=f)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = (W - tw) / 2.0 - bbox[0]
    ty = (W - th) / 2.0 - bbox[1]
    d.text((tx, ty), TITLE, font=f, fill=FG)
    if ss != 1:
        img = img.resize((size, size), resample)
    return img


def zoom4(img):
    return img.resize((img.size[0] * 4, img.size[1] * 4), Image.NEAREST)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)

    variants = [
        # name,        font,       px at 48, ss, resample
        ("A-hei-14-plain", FONT_HEI, 14, 1, None),
        ("B-hei-14-4x", FONT_HEI, 14 * 4, 4, Image.LANCZOS),
        ("C-hei-14-6xbox", FONT_HEI, 14 * 6, 6, Image.BOX),
        ("D-msyhbd-14", FONT_BOLD, 14, 1, None),
        ("E-deng-14-plain", FONT_DENG, 14, 1, None),
        ("F-hei-16-plain", FONT_HEI, 16, 1, None),
        ("G-hei-16-4x", FONT_HEI, 16 * 4, 4, Image.BOX),
    ]

    sheet = Image.new("RGBA", (48 * len(variants), 48 * 2), (24, 24, 26, 255))
    x = 0
    for name, font, px, ss, resample in variants:
        if resample is None:
            img = text_plain(48, font, px, 1, None)
        else:
            img = text_plain(48, font, px, ss, resample)
        if img is None:
            print("skip %s (no font)" % name)
            continue
        img.save(os.path.join(out, name + ".png"))
        zoom4(img).save(os.path.join(out, name + "-zoom4.png"))
        # 1x row on top, 4x zoom row below
        sheet.paste(img, (x, 0))
        sheet.paste(zoom4(img).resize((48, 48), Image.LANCZOS), (x, 48))
        x += 48
        print("wrote %s (48x48 and 4x zoom)" % name)

    sheet.save(os.path.join(out, "compare.png"))
    print("wrote compare.png (top: actual 48px, bottom: 4x zoom scaled back for viewing)")


if __name__ == "__main__":
    main()
