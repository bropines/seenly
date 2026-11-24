#!/usr/bin/env python3
"""Draw the demo pictures the README screenshots translate.

    python3 scripts/demo_images.py [--out DIR] [--fonts DIR]

All three are drawn here from nothing, so they can be published with the
repository: a manga-style page with vertical Japanese speech bubbles, a
chalkboard menu photographed in a ramen shop, and a notice taped to a cafe
door in German. The dialogue, the shop and the cafe are invented.

The Lens results that go with them are saved by the CLI, once per language:

    lens manga.png -t en --json > manga.en.json
    lens manga.png -t ru --json > manga.ru.json

Needs Pillow and a CJK font. By default the Windows fonts are used (Yu Gothic
for Japanese, Segoe UI and Georgia for Latin); --fonts points at a folder with
the same files elsewhere. Only the rendered pictures are published, never the
fonts.
"""
import argparse
import math
import os
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

OUT = "app/shared/src/androidHostTest/resources/readme"
INK = (20, 20, 22)

# Characters that turn a quarter in vertical writing.
ROTATE = set("ー〜…―")


def font(dirs, name, size, index=0):
    for d in dirs:
        path = os.path.join(d, name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size, index=index)
    raise SystemExit(f"font {name} not found in {dirs}; pass --fonts")


# ---------------------------------------------------------------- manga page

def vertical_text(d, img, columns, right, top, f, pitch, step, fill=INK):
    """Columns of Japanese, right to left, each top to bottom."""
    x = right
    for col in columns:
        y = top
        for ch in col:
            w = f.getlength(ch)
            if ch in ROTATE:
                tile = Image.new("L", (step, step), 0)
                ImageDraw.Draw(tile).text(((step - w) / 2, 0), ch, font=f, fill=255)
                tile = tile.rotate(-90)
                img.paste(Image.new("RGB", tile.size, fill), (int(x - step / 2), int(y)), tile)
            elif ch in "、。":
                d.text((x - w / 2 + step * 0.35, y - step * 0.35), ch, font=f, fill=fill)
            else:
                d.text((x - w / 2, y), ch, font=f, fill=fill)
            y += step
        x -= pitch


def bubble(d, img, box, columns, f, tail=None, pitch=52, step=45):
    """A white speech balloon with a tail, holding vertical text centred."""
    x0, y0, x1, y1 = box
    d.ellipse(box, fill="white", outline=INK, width=4)
    if tail:  # the base points lie inside the balloon: fill over its outline, then draw the sides
        d.polygon(tail, fill="white")
        d.line(tail[:2], fill=INK, width=4)
        d.line(tail[1:], fill=INK, width=4)
    longest = max(len(c) for c in columns)
    width = pitch * (len(columns) - 1)
    cx = (x0 + x1) / 2
    right = cx + width / 2
    top = (y0 + y1) / 2 - longest * step / 2
    vertical_text(d, img, columns, right, top, f, pitch, step)


def tone(size, spacing=9, radius=1.6, angle=45):
    """A screentone: a rotated dot grid, as an L mask."""
    w, h = size
    big = int(math.hypot(w, h)) + spacing * 2
    t = Image.new("L", (big, big), 0)
    td = ImageDraw.Draw(t)
    for yy in range(0, big, spacing):
        for xx in range(0, big, spacing):
            td.ellipse([xx - radius, yy - radius, xx + radius, yy + radius], fill=255)
    t = t.rotate(angle)
    left = (big - w) // 2
    top = (big - h) // 2
    return t.crop((left, top, left + w, top + h))


def apply_tone(img, box, spacing=9, radius=1.6):
    x0, y0, x1, y1 = box
    mask = tone((x1 - x0, y1 - y0), spacing, radius)
    img.paste(Image.new("RGB", mask.size, (90, 90, 96)), (x0, y0), mask)


def panel(d, box):
    d.rectangle(box, outline=INK, width=6)


def girl(d, cx, cy, s=1.0):
    """A simple line-art girl: ponytail, big eyes, a sailor collar."""
    def p(x, y):
        return (cx + x * s, cy + y * s)
    d.polygon([p(-150, 230), p(-110, 120), p(110, 120), p(150, 230)], fill="white", outline=INK)
    d.line([p(-150, 230), p(-110, 120), p(110, 120), p(150, 230)], fill=INK, width=5)
    d.polygon([p(-70, 120), p(0, 190), p(70, 120)], fill=(40, 40, 60))
    d.ellipse([*p(70, -120), *p(190, 20)], fill=(40, 40, 46), outline=INK, width=4)  # ponytail
    d.ellipse([*p(-100, -110), *p(100, 120)], fill="white", outline=INK, width=5)  # face
    d.chord([*p(-108, -130), *p(108, 60)], 180, 360, fill=(40, 40, 46), outline=INK, width=4)  # hair
    d.polygon([p(-104, -30), p(-60, -40), p(-80, 30)], fill=(40, 40, 46))
    d.polygon([p(104, -30), p(60, -40), p(80, 30)], fill=(40, 40, 46))
    for ex in (-45, 45):
        d.ellipse([*p(ex - 20, 0), *p(ex + 20, 48)], fill=INK)
        d.ellipse([*p(ex - 9, 8), *p(ex + 3, 20)], fill="white")
    d.arc([*p(-26, 52), *p(26, 88)], 20, 160, fill=INK, width=4)
    d.line([p(-70, 50), p(-55, 58)], fill=(200, 120, 120), width=3)
    d.line([p(55, 58), p(70, 50)], fill=(200, 120, 120), width=3)


def boy(d, cx, cy, s=1.0):
    """A simple line-art boy: spiky hair, a grin, a T-shirt."""
    def p(x, y):
        return (cx + x * s, cy + y * s)
    d.polygon([p(-160, 230), p(-115, 115), p(115, 115), p(160, 230)], fill="white")
    d.line([p(-160, 230), p(-115, 115), p(115, 115), p(160, 230)], fill=INK, width=5)
    d.arc([*p(-40, 95), *p(40, 140)], 0, 180, fill=INK, width=4)
    d.ellipse([*p(-100, -110), *p(100, 120)], fill="white", outline=INK, width=5)
    spikes = [p(-112, 0), p(-125, -70), p(-90, -60), p(-80, -135), p(-35, -90), p(0, -150),
              p(30, -95), p(80, -140), p(85, -70), p(125, -80), p(110, 0), p(70, -50), p(-60, -45)]
    d.polygon(spikes, fill="white", outline=INK)
    d.line(spikes + [spikes[0]], fill=INK, width=4)
    for ex in (-42, 42):
        d.line([p(ex - 18, 22), p(ex + 18, 22)], fill=INK, width=6)
        d.ellipse([*p(ex - 8, 28), *p(ex + 8, 44)], fill=INK)
    d.chord([*p(-36, 55), *p(36, 95)], 0, 180, fill="white", outline=INK, width=4)


def framed(img, box, draw_fn):
    """Draws a panel's picture on its own layer, cut to the panel, then its border."""
    layer = Image.new("RGB", img.size, "white")
    draw_fn(layer, ImageDraw.Draw(layer))
    img.paste(layer.crop(box), box[:2])
    panel(ImageDraw.Draw(img), box)


def cloud(d, cx, cy):
    puffs = ((0, 0, 45), (50, -22, 55), (110, 0, 45), (55, 18, 48))
    for dx, dy, r in puffs:
        d.ellipse([cx + dx - r - 4, cy + dy - r - 4, cx + dx + r + 4, cy + dy + r + 4], fill=INK)
    for dx, dy, r in puffs:
        d.ellipse([cx + dx - r, cy + dy - r, cx + dx + r, cy + dy + r], fill="white")


def manga(fonts):
    w, h = 1000, 1460
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    talk = font(fonts, "YuGothB.ttc", 40)
    narr = font(fonts, "YuGothM.ttc", 34)
    m, g = 36, 22

    # Panel 1: the sea on a summer morning.
    a = (m, m, w - m, 470)
    horizon = 330

    def sea(layer, ld):
        apply_tone(layer, (a[0], a[1], a[2], horizon), spacing=10, radius=1.4)
        ld.ellipse([150, 90, 290, 230], fill="white", outline=INK, width=5)  # sun
        for k in range(12):
            ang = k * math.pi / 6
            ld.line([(220 + 88 * math.cos(ang), 160 + 88 * math.sin(ang)),
                     (220 + 118 * math.cos(ang), 160 + 118 * math.sin(ang))], fill=INK, width=5)
        cloud(ld, 400, 120)
        cloud(ld, 560, 200)
        ld.line([(a[0], horizon), (a[2], horizon)], fill=INK, width=4)
        for row, y in enumerate(range(horizon + 25, a[3] - 10, 32)):
            for x in range(a[0] + 20 + (row % 2) * 40, a[2] - 40, 90):
                ld.arc([x, y - 10, x + 50, y + 12], 200, 340, fill=INK, width=3)

    framed(img, a, sea)
    # A narration box, vertical, in the corner.
    box = (w - m - 130, m + 22, w - m - 22, m + 290)
    d.rectangle(box, fill="white", outline=INK, width=4)
    vertical_text(d, img, ["夏休み", "最初の日"], box[2] - 32, box[1] + 22, narr, 40, 40)
    bubble(d, img, (580, 70, 820, 456), ["今日は本当に", "いい天気だね！"], talk,
           tail=[(620, 330), (545, 440), (640, 365)])

    # Panels 2 and 3: the two of them.
    b = (m, 470 + g, w // 2 - g // 2, 960)
    c = (w // 2 + g // 2, 470 + g, w - m, 960)

    def her(layer, ld):
        apply_tone(layer, b, spacing=7, radius=1.2)
        girl(ld, b[2] - 120, 820, 0.8)

    def him(layer, ld):
        cx0, cy0 = c[0] + 130, 800
        for k in range(24):  # sparkle lines behind the boy
            ang = k * math.pi / 12
            ld.line([(cx0 + 200 * math.cos(ang), cy0 + 200 * math.sin(ang)),
                     (cx0 + 420 * math.cos(ang), cy0 + 420 * math.sin(ang))], fill=INK, width=2)
        boy(ld, c[0] + 130, cy0 + 20, 0.8)

    framed(img, b, her)
    framed(img, c, him)
    bubble(d, img, (b[0] + 12, b[1] + 14, b[0] + 252, b[1] + 400), ["ねえ、一緒に", "海へ行こうか？"], talk,
           tail=[(b[0] + 205, b[1] + 300), (b[0] + 290, b[1] + 385), (b[0] + 222, b[1] + 265)])
    bubble(d, img, (c[2] - 216, c[1] + 14, c[2] - 16, c[1] + 400), ["お弁当なら、", "もう作ったよ！"], talk,
           tail=[(c[2] - 170, c[1] + 330), (c[2] - 250, c[1] + 380), (c[2] - 190, c[1] + 290)])

    # Panel 4: the train is coming.
    e = (m, 960 + g, w - m, h - m)

    def train(layer, ld):
        for k in range(40):
            y = random.Random(k).randint(e[1] + 10, e[3] - 10)
            x = random.Random(k + 99).randint(e[0] + 10, e[0] + 300)
            ld.line([(x, y), (e[2] - 10, y)], fill=INK, width=random.Random(k + 7).choice((2, 3, 4)))
        tx0, ty0 = 300, e[1] + 120
        ld.rounded_rectangle([tx0, ty0, e[2] + 60, ty0 + 230], 50, fill="white", outline=INK, width=6)
        for k in range(4):
            ld.rounded_rectangle([tx0 + 60 + k * 140, ty0 + 40, tx0 + 160 + k * 140, ty0 + 120], 12,
                                 fill="white", outline=INK, width=5)
        ld.ellipse([tx0 + 15, ty0 + 150, tx0 + 65, ty0 + 200], fill="white", outline=INK, width=5)
        ld.rectangle([e[0], ty0 + 240, e[2], ty0 + 290], fill="white")
        ld.line([(e[0], ty0 + 245), (e[2], ty0 + 245)], fill=INK, width=6)
        ld.line([(e[0], ty0 + 268), (e[2], ty0 + 268)], fill=INK, width=3)
        for x in range(e[0] + 10, e[2], 60):
            ld.line([(x, ty0 + 245), (x - 20, ty0 + 268)], fill=INK, width=3)

    framed(img, e, train)
    bubble(d, img, (e[2] - 236, e[1] + 20, e[2] - 36, e[1] + 406), ["ほら、急いで！", "電車が来るよ！"], talk,
           tail=[(e[2] - 190, e[1] + 330), (e[2] - 270, e[1] + 380), (e[2] - 210, e[1] + 290)])
    return img


# --------------------------------------------------------- photo-like images

def noise(size, amount, seed):
    rnd = random.Random(seed)
    n = Image.effect_noise(size, amount)
    return n.point(lambda v: v + rnd.randint(-2, 2))


def grainy(img, sigma, seed):
    """Adds film grain: Gaussian noise centred on zero."""
    random.seed(seed)
    n = Image.effect_noise(img.size, sigma).convert("RGB")
    return ImageChops.add(img, n, scale=1.0, offset=-128)


def wall(size, base, seed):
    """A painted wall in soft light: a gradient, grain and a vignette."""
    w, h = size
    img = Image.new("RGB", size, base)
    grad = Image.linear_gradient("L").resize(size)
    img = Image.composite(img, Image.new("RGB", size, tuple(int(c * 0.72) for c in base)), grad)
    return grainy(img, 22, seed)


def vignette(img, strength=0.55):
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse([-w * 0.25, -h * 0.2, w * 1.25, h * 1.2], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(min(w, h) // 5))
    dark = Image.new("RGB", (w, h), (0, 0, 0))
    return Image.composite(img, Image.blend(img, dark, strength), mask)


def chalk(layer_size, draw_fn, seed):
    """Text as chalk: drawn, then worn by noise and softened."""
    mask = Image.new("L", layer_size, 0)
    draw_fn(ImageDraw.Draw(mask))
    wear = noise(layer_size, 80, seed).point(lambda v: 255 if v > 70 else 120)
    mask = ImageChops.multiply(mask, wear)
    return mask.filter(ImageFilter.GaussianBlur(0.8))


def menu(fonts):
    """A ramen shop's chalkboard menu, photographed slightly askew."""
    w, h = 1080, 1520
    img = wall((w, h), (186, 160, 128), 1)
    bw, bh = 880, 1180
    board = Image.new("RGB", (bw, bh), (30, 40, 36))
    board = grainy(board, 14, 2)
    # Old chalk smudges.
    smudge = Image.new("L", (bw, bh), 0)
    sd = ImageDraw.Draw(smudge)
    rnd = random.Random(5)
    for _ in range(14):
        x, y = rnd.randint(0, bw), rnd.randint(0, bh)
        sd.ellipse([x - 120, y - 40, x + 120, y + 40], fill=rnd.randint(10, 28))
    smudge = smudge.filter(ImageFilter.GaussianBlur(30))
    board.paste(Image.new("RGB", (bw, bh), (200, 210, 205)), (0, 0), smudge)

    title = font(fonts, "YuGothB.ttc", 82)
    item = font(fonts, "YuGothB.ttc", 56)
    price = font(fonts, "YuGothB.ttc", 56)
    small = font(fonts, "YuGothM.ttc", 38)
    rows = [("醤油ラーメン", "850円"), ("味噌ラーメン", "900円"), ("つけ麺", "950円"),
            ("餃子（6個）", "400円"), ("生ビール", "500円")]

    def write_white(d):
        y = 300
        for name, cost in rows:
            d.text((80, y), name, font=item, fill=255)
            d.text((bw - 80 - price.getlength(cost), y), cost, font=price, fill=255)
            y += 132
        d.text((80, 1000), "営業時間 11:00〜22:00", font=small, fill=230)
        d.text((80, 1058), "水曜日は定休日です", font=small, fill=230)

    def write_yellow(d):
        t = "本日のおすすめ"
        d.text(((bw - title.getlength(t)) / 2, 90), t, font=title, fill=255)
        d.line([(120, 222), (bw - 120, 222)], fill=200, width=5)

    board.paste(Image.new("RGB", (bw, bh), (246, 244, 236)), (0, 0), chalk((bw, bh), write_white, 3))
    board.paste(Image.new("RGB", (bw, bh), (250, 214, 92)), (0, 0), chalk((bw, bh), write_yellow, 4))
    # Doodled steam bowl in the corner.
    bowl = chalk((bw, bh), lambda d: (
        d.chord([bw - 250, 1000, bw - 90, 1100], 0, 180, outline=255, width=6),
        # Steam as long waves: short arcs would read as brackets.
        [d.line([(bw - 205 + k * 45 + 9 * math.sin(t / 3), 990 - t * 4) for t in range(20)], fill=255, width=4)
         for k in range(3)],
    ), 6)
    board.paste(Image.new("RGB", (bw, bh), (240, 240, 232)), (0, 0), bowl)

    frame = Image.new("RGB", (bw + 60, bh + 60), (120, 78, 44))
    frame = grainy(frame, 18, 7)
    fd = ImageDraw.Draw(frame)
    for k in range(0, frame.height, 7):
        fd.line([(0, k), (frame.width, k + random.Random(k).randint(-2, 2))], fill=(104, 66, 36), width=1)
    frame.paste(board, (30, 30))
    frame = frame.rotate(-1.6, resample=Image.BICUBIC, expand=True, fillcolor=(0, 0, 0))
    mask = Image.new("L", (bw + 60, bh + 60), 255).rotate(-1.6, resample=Image.BICUBIC, expand=True)
    shadow = Image.new("L", img.size, 0)
    ox, oy = (w - frame.width) // 2, 150
    shadow.paste(mask, (ox + 18, oy + 24))
    shadow = shadow.filter(ImageFilter.GaussianBlur(22))
    img.paste(Image.new("RGB", img.size, (40, 30, 20)), (0, 0), shadow.point(lambda v: v * 0.6))
    img.paste(frame, (ox, oy), mask)
    img = vignette(img, 0.45)
    return img.filter(ImageFilter.GaussianBlur(0.6))


def notice(fonts):
    """A printed notice taped to a cafe's glass door, in German."""
    w, h = 1080, 1520
    img = wall((w, h), (92, 104, 110), 11)
    # Reflections on the glass.
    glare = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glare).polygon([(620, 0), (820, 0), (360, h), (160, h)], fill=60)
    glare = glare.filter(ImageFilter.GaussianBlur(60))
    img.paste(Image.new("RGB", (w, h), (230, 236, 240)), (0, 0), glare)

    pw, ph = 820, 1160
    paper = Image.new("RGB", (pw, ph), (247, 246, 240))
    paper = grainy(paper, 8, 12)
    d = ImageDraw.Draw(paper)
    head = font(fonts, "seguisb.ttf", 92)
    body = font(fonts, "georgia.ttf", 44)
    sign = font(fonts, "georgiai.ttf", 44)
    ink = (34, 34, 40)
    d.text(((pw - head.getlength("Hinweis")) / 2, 90), "Hinweis", font=head, fill=(150, 30, 34))
    d.line([(90, 220), (pw - 90, 220)], fill=(150, 30, 34), width=4)
    lines = [
        "Liebe Gäste,",
        "",
        "unser Café bleibt am",
        "Montag wegen Renovierung",
        "geschlossen.",
        "",
        "Ab Dienstag sind wir",
        "wieder für Sie da.",
        "",
        "Vielen Dank für Ihr",
        "Verständnis!",
    ]
    y = 280
    for line in lines:
        if line:
            d.text((90, y), line, font=body, fill=ink)
        y += 62 if line else 34
    d.text((90, y + 40), "Ihr Team vom Café Linde", font=sign, fill=ink)

    tape = Image.new("RGBA", (220, 64), (236, 230, 200, 170))
    paper = paper.convert("RGBA")
    for x, ang in ((40, -12), (pw - 260, 10)):
        t = tape.rotate(ang, expand=True)
        paper.alpha_composite(t, (x, -10))
    paper = paper.rotate(1.2, resample=Image.BICUBIC, expand=True)
    shadow = Image.new("L", img.size, 0)
    ox, oy = (w - paper.width) // 2, 170
    shadow.paste(paper.getchannel("A"), (ox + 14, oy + 18))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    img.paste(Image.new("RGB", img.size, (10, 14, 16)), (0, 0), shadow.point(lambda v: v * 0.5))
    img.paste(paper.convert("RGB"), (ox, oy), paper.getchannel("A"))
    img = vignette(img, 0.4)
    return img.filter(ImageFilter.GaussianBlur(0.5))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--fonts", action="append", default=[],
                    help="a folder with YuGothB.ttc, YuGothM.ttc, seguisb.ttf, georgia.ttf, georgiai.ttf")
    args = ap.parse_args()
    fonts = args.fonts + [r"C:\Windows\Fonts", "/mnt/c/Windows/Fonts"]
    os.makedirs(args.out, exist_ok=True)
    manga(fonts).save(os.path.join(args.out, "manga.png"), optimize=True)
    menu(fonts).save(os.path.join(args.out, "menu.jpg"), quality=86, optimize=True)
    notice(fonts).save(os.path.join(args.out, "notice.jpg"), quality=86, optimize=True)
    print("demo images written to", args.out)


if __name__ == "__main__":
    main()
