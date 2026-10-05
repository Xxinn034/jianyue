"""Scratch: compare book-glyph candidates for the bottom tab bar (delete after picking)."""
import sys
from PIL import Image, ImageDraw

SS = 8
SIZE = 22
EDGE = SIZE * SS
GREY = (150, 150, 150, 255)
ACCENT = (191, 59, 46, 255)
BG = (247, 247, 247, 255)


def s(v):
    return v * SS


def canvas():
    img = Image.new("RGBA", (EDGE, EDGE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def book_a(d, c):
    d.polygon([(s(3.6), s(6.8)), (s(10.6), s(4.6)), (s(10.6), s(17.6)), (s(3.6), s(19.4))], fill=c)
    d.polygon([(s(18.4), s(6.8)), (s(11.4), s(4.6)), (s(11.4), s(17.6)), (s(18.4), s(19.4))], fill=c)


def book_b(d, c):
    # wider gutter, pages fan up towards the gutter
    d.polygon([(s(3.2), s(7.4)), (s(10.0), s(4.6)), (s(10.0), s(17.0)), (s(3.2), s(19.8))], fill=c)
    d.polygon([(s(18.8), s(7.4)), (s(12.0), s(4.6)), (s(12.0), s(17.0)), (s(18.8), s(19.8))], fill=c)


def book_c(d, c):
    # closed book: body + spine stripe on the left + a title line
    d.rounded_rectangle([s(3.4), s(2.8), s(18.6), s(19.2)], radius=s(1.6), fill=c)
    d.rectangle([s(6.2), s(2.8), s(7.6), s(19.2)], fill=(0, 0, 0, 0))
    d.rectangle([s(9.6), s(6.4), s(15.4), s(7.8)], fill=(0, 0, 0, 0))


def book_d(d, c):
    # open book, squared: two solid pages with a clear gutter
    d.polygon([(s(3.0), s(6.4)), (s(10.0), s(5.0)), (s(10.0), s(17.6)), (s(3.0), s(19.0))], fill=c)
    d.polygon([(s(19.0), s(6.4)), (s(12.0), s(5.0)), (s(12.0), s(17.6)), (s(19.0), s(19.0))], fill=c)
    d.rectangle([s(10.0), s(5.0), s(12.0), s(19.0)], fill=(0, 0, 0, 0))


def globe(d, c):
    w = int(s(1.7))
    cx, cy, r = s(11), s(11), s(7.4)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=w)
    d.line([s(3.6), cy, s(18.4), cy], fill=c, width=w)
    mx, my = s(3.5), s(7.4)
    d.ellipse([cx - mx, cy - my, cx + mx, cy + my], outline=c, width=w)


VARIANTS = [("A", book_a), ("B", book_b), ("C", book_c), ("D", book_d), ("G", globe)]


def render(fn, color, punch_black=False):
    img, d = canvas()
    fn(d, color)
    if punch_black:
        px = img.load()
        for y in range(EDGE):
            for x in range(EDGE):
                r, g, b, a = px[x, y]
                if a and r == 0 and g == 0 and b == 0:
                    px[x, y] = (0, 0, 0, 0)
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    zoom = 6
    pad = 10
    cols = len(VARIANTS)
    row_h = pad + SIZE * zoom + pad
    sheet = Image.new("RGB", (pad + cols * (SIZE + pad + SIZE * zoom + pad), row_h * 2 + pad), BG)
    for row, color in enumerate([GREY, ACCENT]):
        for col, (name, fn) in enumerate(VARIANTS):
            icon = render(fn, color, punch_black=(fn in (book_c, book_d)))
            x = pad + col * (SIZE + pad + SIZE * zoom + pad)
            y = pad + row * row_h
            sheet.paste(icon, (x, y + (SIZE * zoom - SIZE) // 2), icon)
            big = icon.resize((SIZE * zoom, SIZE * zoom), Image.NEAREST)
            sheet.paste(big, (x + SIZE + pad, y), big)
    out = r"G:\jianyue-src\tools\_variants.png"
    sheet.save(out)
    print(out + "  %dx%d" % sheet.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
