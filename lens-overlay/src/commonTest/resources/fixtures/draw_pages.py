"""Draws the made-up pages the overlay's quality corpus is captured from.

    python -I draw_pages.py <out dir>

Everything is drawn here from nothing (invented text, simple shapes), so the
pictures can live in the repository next to the Lens answers saved for them:
a printed article, the photograph of a monitor showing documentation, a phone's
settings screen, a comic page with awkward balloons and a page of vertical
Japanese prose. The answers are what the CLI printed, minified:

    lens page-document.png -t ru --tiled --json > page-document.ru.json

Fonts are read from C:\\Windows\\Fonts and never copied.
"""
import math
import os
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONTS = r"C:\Windows\Fonts"
INK = (20, 20, 22)


def font(name, size, index=0):
    path = os.path.join(FONTS, name)
    if not os.path.exists(path):
        raise SystemExit(f"font {name} not found")
    return ImageFont.truetype(path, size, index=index)


def wrap(text, f, width):
    """Greedy word wrap of text into lines no wider than width."""
    lines, line = [], ""
    for word in text.split():
        trial = word if not line else line + " " + word
        if f.getlength(trial) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def paragraph(d, x, y, text, f, width, pitch, fill, indent=0):
    """Draws a ragged-right paragraph; returns the y below it."""
    first = wrap(text, f, width - indent)[0] if indent else None
    if indent:
        rest = text[len(first):].strip()
        lines = [first] + (wrap(rest, f, width) if rest else [])
    else:
        lines = wrap(text, f, width)
    for i, line in enumerate(lines):
        d.text((x + (indent if i == 0 else 0), y), line, font=f, fill=fill)
        y += pitch
    return y


# ------------------------------------------------------------------- document

def document():
    w, h = 1200, 1640
    img = Image.new("RGB", (w, h), (252, 251, 247))
    d = ImageDraw.Draw(img)
    title = font("seguisb.ttf", 56)
    by = font("segoeui.ttf", 22)
    body = font("georgia.ttf", 27)
    sub = font("seguisb.ttf", 34)
    quote = font("georgiai.ttf", 31)
    small = font("segoeui.ttf", 18)
    ink = (28, 28, 32)
    grey = (110, 112, 120)
    x, width = 110, 980
    y = 90
    d.text((x, y), "Keeping a Lighthouse in Winter", font=title, fill=ink)
    y += 86
    d.text((x, y), "Notes from the northern coast  \u00b7  12 min read", font=by, fill=grey)
    y += 62
    paragraphs = [
        "The lamp has to be lit a little before dusk, which in December means the middle of the afternoon. "
        "The keeper climbs one hundred and forty steps with a can of oil in each hand, and by the time the "
        "wick is trimmed and the lens is turning, the first fishing boats are already looking for it.",
        "Most of the work is cleaning. Salt gets into everything: the brass, the hinges, the glass of the "
        "lantern room. A single greasy thumbprint on the lens scatters enough light to matter, so the last "
        "job every evening is to polish it with a soft cloth until it looks like nothing is there at all.",
        "Storms are the easy part. When the wind is too strong to stand in, nobody expects visitors or "
        "deliveries, and the keeper can read by the stove while the tower hums like a struck glass. It is "
        "the calm, foggy nights that are hard, because the horn must sound every two minutes until morning.",
    ]
    for i, text in enumerate(paragraphs):
        y = paragraph(d, x, y, text, body, width, 42, ink, indent=0 if i == 0 else 44)
        y += 22
    y += 14
    d.text((x, y), "What the logbook asks for", font=sub, fill=ink)
    y += 66
    bullets = [
        "The time the lamp was lit and put out",
        "Wind direction, visibility and the state of the sea",
        "Every ship that passed close enough to read its name, with the hour it was seen and the "
        "direction it was heading",
    ]
    for text in bullets:
        d.ellipse([x + 8, y + 14, x + 18, y + 24], fill=ink)
        y = paragraph(d, x + 40, y, text, body, width - 40, 42, ink)
        y += 10
    y += 30
    d.rectangle([x + 30, y, x + 36, y + 96], fill=(170, 60, 50))
    y = paragraph(d, x + 66, y + 4, "A light that is seen is never thanked; a light that fails is never forgiven.",
                  quote, width - 160, 46, (60, 60, 70))
    y += 50
    col = (width - 60) // 2
    left = ("Supplies arrive once a month by boat when the weather allows. Flour, lamp oil, coffee and "
            "letters come first; everything else can wait.")
    right = ("In spring the relief keeper takes over for two weeks. He brings newspapers, which are read "
             "in order, oldest first.")
    y1 = paragraph(d, x, y, left, body, col, 42, ink)
    y2 = paragraph(d, x + col + 60, y, right, body, col, 42, ink)
    y = max(y1, y2) + 60
    foot = "Printed for the Coastal Heritage Society. Not for sale."
    d.line([(x, y - 24), (x + width, y - 24)], fill=(200, 198, 190), width=2)
    d.text(((w - small.getlength(foot)) / 2, y), foot, font=small, fill=grey)
    return img.crop((0, 0, w, min(h, y + 70)))


# -------------------------------------------------------- photo of a monitor

def docs_page():
    """A dark documentation page, as a browser shows it."""
    w, h = 1500, 1000
    bg = (30, 31, 34)
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    h1 = font("seguisb.ttf", 44)
    h2 = font("seguisb.ttf", 30)
    body = font("segoeui.ttf", 23)
    mono = font("consola.ttf", 21)
    small = font("segoeui.ttf", 17)
    text = (214, 216, 222)
    dim = (140, 144, 152)
    # Browser bar and sidebar.
    d.rectangle([0, 0, w, 54], fill=(43, 45, 49))
    d.rounded_rectangle([190, 12, 1100, 42], 15, fill=(30, 31, 34))
    d.text((210, 16), "docs.example.org/sync/getting-started", font=small, fill=dim)
    d.rectangle([0, 54, 250, h], fill=(36, 37, 41))
    for i, item in enumerate(["Overview", "Getting started", "Configuration", "Command line", "Troubleshooting", "Release notes"]):
        d.text((28, 96 + i * 44), item, font=body, fill=(120, 170, 255) if i == 1 else dim)
    x, width = 300, 1110
    y = 92
    d.text((x, y), "Getting started with the sync client", font=h1, fill=(240, 241, 245))
    y += 78
    y = paragraph(d, x, y, "The sync client keeps a folder on your computer identical to a folder on the server. "
                  "It runs in the background, starts with the system and uses very little memory while nothing "
                  "is changing. This page explains how to install it, connect it to your account and choose "
                  "what gets copied.", body, width, 35, text)
    y += 30
    d.text((x, y), "Before you install", font=h2, fill=(240, 241, 245))
    y += 54
    y = paragraph(d, x, y, "Check that you have at least two gigabytes of free space and a stable connection. "
                  "The first run compares every file on both sides, which can take several minutes for large "
                  "folders. Later runs only look at what has changed since the previous one, so they usually "
                  "finish in a few seconds.", body, width, 35, text)
    y += 24
    d.rounded_rectangle([x, y, x + width, y + 96], 10, fill=(45, 52, 66))
    d.rectangle([x, y, x + 6, y + 96], fill=(96, 150, 255))
    paragraph(d, x + 26, y + 14, "Note: files that are open in another program are skipped and retried later. "
              "Nothing is lost, but the change may arrive a few minutes late.", body, width - 52, 35, (222, 228, 240))
    y += 126
    d.rounded_rectangle([x, y, x + width, y + 58], 8, fill=(20, 21, 24))
    d.text((x + 20, y + 17), "sync-client connect --server files.example.org --folder ~/Documents", font=mono, fill=(170, 220, 170))
    y += 88
    d.text((x, y), "Options", font=h2, fill=(240, 241, 245))
    y += 54
    rows = [("--interval", "How often to check for changes, in seconds"),
            ("--exclude", "Patterns for files that should never be copied"),
            ("--dry-run", "Show what would be done without changing anything")]
    for name, what in rows:
        d.line([(x, y - 8), (x + width, y - 8)], fill=(58, 60, 66), width=1)
        d.text((x + 8, y), name, font=mono, fill=(255, 190, 120))
        d.text((x + 230, y - 2), what, font=body, fill=text)
        y += 44
    d.line([(x, y - 8), (x + width, y - 8)], fill=(58, 60, 66), width=1)
    d.text((x, h - 44), "Last updated on 3 March  \u00b7  Edit this page", font=small, fill=dim)
    return img


def find_coeffs(target, source):
    """Coefficients for Image.transform(PERSPECTIVE) mapping target quad back to source quad."""
    rows = []
    for (tx, ty), (sx, sy) in zip(target, source):
        rows.append([tx, ty, 1, 0, 0, 0, -sx * tx, -sx * ty, sx])
        rows.append([0, 0, 0, tx, ty, 1, -sy * tx, -sy * ty, sy])
    # Solve the 8x8 system by Gaussian elimination.
    n = 8
    m = [r[:] for r in rows]
    for i in range(n):
        pivot = max(range(i, n), key=lambda r: abs(m[r][i]))
        m[i], m[pivot] = m[pivot], m[i]
        p = m[i][i]
        m[i] = [v / p for v in m[i]]
        for r in range(n):
            if r != i:
                factor = m[r][i]
                m[r] = [a - factor * b for a, b in zip(m[r], m[i])]
    return [m[i][8] for i in range(n)]


def monitor():
    """The documentation page photographed off a monitor: keystone, moire, glare, noise."""
    page = docs_page()
    pw, ph = page.size
    w, h = 1600, 1200
    rnd = random.Random(7)
    # The wall behind and the bezel around.
    img = Image.new("RGB", (w, h), (58, 52, 48))
    random.seed(3)
    img = ImageChops.add(img, Image.effect_noise((w, h), 14).convert("RGB"), scale=1.0, offset=-128)
    bezel = 22
    framed = Image.new("RGB", (pw + 2 * bezel, ph + 2 * bezel), (14, 14, 15))
    framed.paste(page, (bezel, bezel))
    # Moire: the pixel grid of the panel beating against the sensor.
    fw, fh = framed.size
    moire = Image.new("L", (fw, fh), 128)
    px = moire.load()
    for yy in range(fh):
        for xx in range(0, fw):
            v = 128 + 9 * math.sin(xx * 0.9 + yy * 0.07) + 7 * math.sin(yy * 1.3 - xx * 0.05)
            px[xx, yy] = int(v)
    framed = ImageChops.multiply(framed, Image.merge("RGB", [moire.point(lambda v: min(255, int(v * 2)))] * 3))
    # Keystone: seen a little from the left and from below.
    quad = [(70, 96), (w - 40, 60), (w - 58, h - 150), (92, h - 96)]
    coeffs = find_coeffs(quad, [(0, 0), (fw, 0), (fw, fh), (0, fh)])
    warped = framed.transform((w, h), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    mask = Image.new("L", (fw, fh), 255).transform((w, h), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    img.paste(warped, (0, 0), mask)
    # A desk edge and a glare band across the glass.
    d = ImageDraw.Draw(img)
    d.rectangle([0, h - 70, w, h], fill=(92, 70, 50))
    glare = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glare).polygon([(1050, 0), (1300, 0), (900, h), (650, h)], fill=38)
    glare = glare.filter(ImageFilter.GaussianBlur(70))
    img.paste(Image.new("RGB", (w, h), (235, 240, 255)), (0, 0), glare)
    # Sensor: soft focus, grain, a darker corner.
    img = img.filter(ImageFilter.GaussianBlur(0.9))
    random.seed(11)
    img = ImageChops.add(img, Image.effect_noise((w, h), 7).convert("RGB"), scale=1.0, offset=-128)
    vign = Image.new("L", (w, h), 0)
    ImageDraw.Draw(vign).ellipse([-w * 0.2, -h * 0.25, w * 1.25, h * 1.2], fill=255)
    vign = vign.filter(ImageFilter.GaussianBlur(160))
    img = Image.composite(img, Image.blend(img, Image.new("RGB", (w, h), (0, 0, 0)), 0.4), vign)
    _ = rnd
    return img


# ------------------------------------------------------------------------ ui

def settings_ui():
    w, h = 1080, 2340
    bg = (18, 18, 20)
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    clock = font("seguisb.ttf", 34)
    title = font("seguisb.ttf", 64)
    item = font("segoeui.ttf", 40)
    subf = font("segoeui.ttf", 30)
    btn = font("seguisb.ttf", 34)
    nav = font("segoeui.ttf", 28)
    white = (236, 236, 240)
    dim = (150, 152, 160)
    d.text((60, 40), "9:41", font=clock, fill=white)
    d.rounded_rectangle([w - 150, 46, w - 70, 80], 8, outline=white, width=3)
    d.rectangle([w - 144, 52, w - 96, 74], fill=white)
    d.text((60, 150), "Settings", font=title, fill=white)
    d.rounded_rectangle([50, 270, w - 50, 370], 50, fill=(36, 37, 42))
    d.ellipse([90, 300, 126, 336], outline=dim, width=4)
    d.line([(120, 332), (138, 350)], fill=dim, width=4)
    d.text((165, 296), "Search settings", font=item, fill=dim)
    rows = [
        ("Network & internet", "Wi-Fi, mobile data, hotspot", (70, 130, 240)),
        ("Connected devices", "Bluetooth, pairing, printing", (60, 170, 120)),
        ("Notifications", "History, conversations, do not disturb", (220, 90, 90)),
        ("Battery", "82% - should last until about 11 PM", (230, 170, 50)),
        ("Storage", "64% used - 46 GB free", (160, 110, 230)),
        ("Sound & vibration", "Volume, haptics, ringtone", (60, 160, 200)),
        ("Display", "Dark theme, font size, brightness", (240, 140, 60)),
        ("Privacy", "Permissions, account activity, personal data", (120, 180, 80)),
    ]
    y = 430
    for name, what, colour in rows:
        d.ellipse([60, y + 8, 140, y + 88], fill=colour)
        d.text((180, y), name, font=item, fill=white)
        d.text((180, y + 54), what, font=subf, fill=dim)
        y += 150
    # A dialog over the list.
    card = [70, 1690, w - 70, 2100]
    d.rounded_rectangle(card, 44, fill=(44, 46, 54))
    d.text((120, 1730), "Turn on battery saver?", font=font("seguisb.ttf", 44), fill=white)
    paragraph(d, 120, 1810, "Battery saver turns on dark theme and limits background activity, some visual "
              "effects and network connections until the phone is charged.", subf, w - 240, 44, (200, 202, 210))
    d.text((w - 480, 2018), "No thanks", font=btn, fill=(140, 180, 255))
    d.rounded_rectangle([w - 300, 2000, w - 110, 2070], 35, fill=(140, 180, 255))
    d.text((w - 274, 2014), "Turn on", font=btn, fill=(16, 28, 60))
    # Bottom navigation.
    d.rectangle([0, 2170, w, h], fill=(28, 29, 33))
    for i, label in enumerate(["Home", "Search", "Library", "Profile"]):
        cx = w * (2 * i + 1) / 8
        d.rounded_rectangle([cx - 28, 2200, cx + 28, 2244], 10, outline=white if i == 0 else dim, width=4)
        d.text((cx - nav.getlength(label) / 2, 2262), label, font=nav, fill=white if i == 0 else dim)
    return img


# --------------------------------------------------------------------- manga

ROTATE = set("\u30fc\u301c\u2026\u2015")


def vertical_text(d, img, columns, right, top, f, pitch, step, fill=INK):
    """Columns of Japanese, right to left, each top to bottom. `right` is the first column's centre."""
    x = right
    for col in columns:
        y = top
        for ch in col:
            cw = f.getlength(ch)
            if ch in ROTATE:
                tile = Image.new("L", (step, step), 0)
                ImageDraw.Draw(tile).text(((step - cw) / 2, 0), ch, font=f, fill=255)
                tile = tile.rotate(-90)
                img.paste(Image.new("RGB", tile.size, fill), (int(x - step / 2), int(y)), tile)
            elif ch in "\u3001\u3002":
                d.text((x - cw / 2 + step * 0.35, y - step * 0.35), ch, font=f, fill=fill)
            else:
                d.text((x - cw / 2, y), ch, font=f, fill=fill)
            y += step
        x -= pitch


def tone(size, spacing=9, radius=1.6, angle=45):
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


def apply_tone(img, box, spacing=9, radius=1.6, colour=(90, 90, 96)):
    x0, y0, x1, y1 = box
    mask = tone((x1 - x0, y1 - y0), spacing, radius)
    img.paste(Image.new("RGB", mask.size, colour), (x0, y0), mask)


def balloon(d, img, box, columns, f, tail=None, pitch=50, step=42, fill="white", ink=INK, width=4, dx=0):
    """An oval speech balloon with vertical text centred in it."""
    x0, y0, x1, y1 = box
    d.ellipse(box, fill=fill, outline=ink if fill == "white" else "white", width=width)
    if tail:
        d.polygon(tail, fill=fill)
        d.line(tail[:2], fill=ink if fill == "white" else "white", width=width)
        d.line(tail[1:], fill=ink if fill == "white" else "white", width=width)
    longest = max(len(c) for c in columns)
    span = pitch * (len(columns) - 1)
    cx = (x0 + x1) / 2 + dx
    vertical_text(d, img, columns, cx + span / 2, (y0 + y1) / 2 - longest * step / 2, f, pitch, step,
                  fill=ink if fill == "white" else (245, 245, 245))


def framed(img, box, draw_fn):
    layer = Image.new("RGB", img.size, "white")
    draw_fn(layer, ImageDraw.Draw(layer))
    img.paste(layer.crop(box), box[:2])
    ImageDraw.Draw(img).rectangle(box, outline=INK, width=6)


def manga2():
    w, h = 1000, 1420
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    talk = font("YuGothB.ttc", 36)
    narr = font("YuGothM.ttc", 30)
    side = font("YuGothM.ttc", 22)
    shout = font("YuGothB.ttc", 58)
    sfx = font("YuGothB.ttc", 110)
    m, g = 34, 20

    # Panel 1: a street at night, screentone sky, a balloon with a tail and small side text.
    a = (m, m, w - m, 440)

    def night(layer, ld):
        apply_tone(layer, a, spacing=8, radius=1.5)
        for k in range(7):
            x = a[0] + 40 + k * 135
            ld.rectangle([x, 250 - (k * 37) % 120, x + 90, a[3]], fill="white", outline=INK, width=4)
            for wy in range(270 - (k * 37) % 120, a[3] - 20, 44):
                ld.rectangle([x + 18, wy, x + 38, wy + 22], fill=(60, 60, 66))
                ld.rectangle([x + 54, wy, x + 74, wy + 22], outline=INK, width=2)
        ld.ellipse([120, 70, 200, 150], fill="white", outline=INK, width=4)

    framed(img, a, night)
    balloon(d, img, (560, 54, 880, 420), ["\u6628\u65e5\u306e\u591c\u3001", "\u99c5\u524d\u3067\u5f7c\u3092", "\u898b\u304b\u3051\u305f\u3093\u3060"],
            talk, tail=[(600, 320), (520, 420), (625, 355)], dx=18)
    # The aside Lens tends to miss: small light lettering inside the balloon, left of the main text.
    vertical_text(d, img, ["\u3048\u3063\u2026"], 618, 150, side, 26, 26, fill=(60, 60, 66))
    # Narration on a gradient.
    nb = (m + 26, m + 26, m + 176, m + 300)
    grad = Image.linear_gradient("L").resize((nb[2] - nb[0], nb[3] - nb[1]))
    box_img = Image.composite(Image.new("RGB", grad.size, (255, 250, 235)), Image.new("RGB", grad.size, (214, 220, 232)), grad)
    img.paste(box_img, nb[:2])
    d.rectangle(nb, outline=INK, width=4)
    vertical_text(d, img, ["\u305d\u308c\u306f\u96e8\u306e", "\u964d\u308b\u591c\u3060\u3063\u305f"], nb[2] - 44, nb[1] + 26, narr, 56, 34)

    # Panel 2: two joined balloons.
    b = (m, 440 + g, w // 2 + 60, 900)

    def room(layer, ld):
        for k in range(18):
            ld.line([(b[0], b[1] + k * 30), (b[2], b[1] + k * 30 + 60)], fill=(200, 200, 204), width=2)
        ld.rectangle([b[0] + 40, 700, b[0] + 230, b[3]], fill="white", outline=INK, width=5)
        ld.ellipse([b[0] + 80, 590, b[0] + 190, 710], fill="white", outline=INK, width=5)

    framed(img, b, room)
    # Drawn as one outline: both ovals in ink, then both interiors in white.
    o1 = (b[0] + 190, b[1] + 16, b[0] + 420, b[1] + 300)
    o2 = (b[0] + 300, b[1] + 180, b[0] + 540, b[1] + 440)
    for o in (o1, o2):
        d.ellipse(o, fill=INK)
    for o in (o1, o2):
        d.ellipse([o[0] + 4, o[1] + 4, o[2] - 4, o[3] - 4], fill="white")
    vertical_text(d, img, ["\u672c\u5f53\u306b\uff1f", "\u8ab0\u304b\u3068\u4e00\u7dd2", "\u3060\u3063\u305f\u306e\uff1f"],
                  (o1[0] + o1[2]) / 2 + 50, o1[1] + 40, talk, 50, 40)
    vertical_text(d, img, ["\u3044\u3084\u3001", "\u4e00\u4eba\u3067\u5098\u3082", "\u3055\u3055\u305a\u306b\u2026"],
                  (o2[0] + o2[2]) / 2 + 56, o2[1] + 44, talk, 50, 36)

    # Panel 3: a shout in a spiky balloon, a sound effect on the art, a balloon cut by the frame.
    c = (w // 2 + 60 + g, 440 + g, w - m, 900)

    def burst(layer, ld):
        cx0, cy0 = (c[0] + c[2]) / 2, c[1] + 300
        for k in range(36):
            ang = k * math.pi / 18
            ld.line([(cx0 + 120 * math.cos(ang), cy0 + 120 * math.sin(ang)),
                     (cx0 + 520 * math.cos(ang), cy0 + 520 * math.sin(ang))], fill=INK, width=2)
        ld.text((c[0] + 30, c[3] - 150), "\u30c9\u30f3", font=sfx, fill=INK)

    framed(img, c, burst)
    cx0, cy0 = (c[0] + c[2]) / 2 + 20, c[1] + 150
    spikes = []
    for k in range(28):
        ang = k * math.pi / 14
        r = 150 if k % 2 == 0 else 104
        spikes.append((cx0 + r * 1.05 * math.cos(ang), cy0 + r * 0.92 * math.sin(ang)))
    d.polygon(spikes, fill="white", outline=INK)
    d.line(spikes + [spikes[0]], fill=INK, width=4)
    vertical_text(d, img, ["\u5618\u3067", "\u3057\u3087\uff01"], cx0 + 34, cy0 - 62, shout, 66, 60)
    # A balloon the panel's edge cuts.
    cut = Image.new("RGB", img.size, "white")
    cd = ImageDraw.Draw(cut)
    cd.rectangle([0, 0, w, h], fill=(255, 0, 255))
    ob = (c[2] - 150, c[3] - 250, c[2] + 110, c[3] + 40)
    cd.ellipse(ob, fill="white", outline=INK, width=4)
    region = (c[0] + 3, c[1] + 3, c[2] - 3, c[3] - 3)
    piece = cut.crop(region)
    mask = piece.convert("RGB").point(lambda v: v)
    key = Image.new("L", piece.size, 0)
    kp = key.load()
    pp = piece.load()
    for yy in range(piece.size[1]):
        for xx in range(piece.size[0]):
            if pp[xx, yy] != (255, 0, 255):
                kp[xx, yy] = 255
    img.paste(mask, region[:2], key)
    vertical_text(d, img, ["\u3061\u3087\u3063\u3068", "\u5f85\u3063\u3066\u2026"], c[2] - 52, c[3] - 205, narr, 44, 34)

    # Panel 4: black panel, a dark balloon with white lettering and a thought cloud.
    e = (m, 900 + g, w - m, h - m)

    def dark(layer, ld):
        ld.rectangle(e, fill=(24, 24, 28))
        for k in range(60):
            rr = random.Random(k)
            x, y = rr.randint(e[0], e[2]), rr.randint(e[1], e[3])
            ld.line([(x, y), (x + rr.randint(-40, 40), y + rr.randint(40, 160))], fill=(70, 70, 78), width=2)

    framed(img, e, dark)
    balloon(d, img, (e[0] + 60, e[1] + 40, e[0] + 330, e[1] + 400), ["\u3042\u306e\u65e5\u304b\u3089", "\u4f55\u304b\u304c", "\u5909\u308f\u3063\u305f"],
            talk, fill=(24, 24, 28), pitch=50, step=42)
    # Thought cloud: overlapping puffs.
    puffs = [(650, e[1] + 120, 90), (740, e[1] + 100, 100), (830, e[1] + 140, 84), (690, e[1] + 220, 96),
             (790, e[1] + 240, 100), (860, e[1] + 220, 70)]
    for x, y, r in puffs:
        d.ellipse([x - r - 4, y - r - 4, x + r + 4, y + r + 4], fill=INK)
    for x, y, r in puffs:
        d.ellipse([x - r, y - r, x + r, y + r], fill="white")
    for x, y, r in ((610, e[1] + 350, 22), (580, e[1] + 400, 13)):
        d.ellipse([x - r, y - r, x + r, y + r], fill="white", outline=INK, width=4)
    vertical_text(d, img, ["\u307e\u3055\u304b\u3001", "\u3042\u306e\u624b\u7d19\u2026"], 790, e[1] + 80, talk, 54, 42)
    return img


# --------------------------------------------------------------------- novel

def novel():
    w, h = 900, 1280
    img = Image.new("RGB", (w, h), (250, 246, 236))
    d = ImageDraw.Draw(img)
    name = "yumin.ttf" if os.path.exists(os.path.join(FONTS, "yumin.ttf")) else "YuGothM.ttc"
    f = font(name, 30)
    texts = [
        "\u305d\u306e\u753a\u306b\u306f\u3001\u53e4\u3044\u6642\u8a08\u53f0\u304c\u3072\u3068\u3064\u3060\u3051\u3042\u3063\u305f\u3002\u671d\u306b\u306a\u308b\u3068\u9418\u304c\u4e03\u56de\u9cf4\u308a\u3001"
        "\u30d1\u30f3\u5c4b\u306e\u4e3b\u4eba\u304c\u5e97\u306e\u6238\u3092\u958b\u3051\u308b\u3002\u5b50\u3069\u3082\u305f\u3061\u306f\u9418\u306e\u97f3\u3092\u5408\u56f3\u306b\u5742\u9053\u3092\u99c6\u3051\u304a\u308a\u3001"
        "\u5b66\u6821\u3078\u5411\u304b\u3046\u3002\u8ab0\u3082\u6642\u8a08\u53f0\u306e\u4e2d\u3092\u898b\u305f\u3053\u3068\u306f\u306a\u304b\u3063\u305f\u304c\u3001\u753a\u306e\u4eba\u3005\u306f\u307f\u306a\u3001"
        "\u305d\u306e\u9418\u306e\u97f3\u3092\u4fe1\u3058\u3066\u66ae\u3089\u3057\u3066\u3044\u305f\u3002",
        "\u3042\u308b\u51ac\u306e\u671d\u3001\u9418\u306f\u9cf4\u3089\u306a\u304b\u3063\u305f\u3002\u30d1\u30f3\u5c4b\u306e\u4e3b\u4eba\u306f\u6238\u306e\u524d\u3067\u9996\u3092\u304b\u3057\u3052\u3001"
        "\u5b50\u3069\u3082\u305f\u3061\u306f\u5742\u306e\u4e0a\u3067\u7acb\u3061\u6b62\u307e\u3063\u305f\u3002\u96ea\u306f\u3057\u305a\u304b\u306b\u964d\u308a\u3064\u3065\u3051\u3001\u753a\u306f\u4e00\u65e5\u3058\u3085\u3046\u3001"
        "\u307e\u308b\u3067\u606f\u3092\u3072\u305d\u3081\u3066\u3044\u308b\u3088\u3046\u3060\u3063\u305f\u3002\u5915\u65b9\u306b\u306a\u3063\u3066\u3001\u3072\u3068\u308a\u306e\u5c11\u5973\u304c"
        "\u6642\u8a08\u53f0\u306e\u6249\u3092\u305d\u3063\u3068\u62bc\u3057\u305f\u3002",
    ]
    step, pitch, per = 38, 56, 26
    x = w - 110
    top = 120
    for text in texts:
        chars = "\u3000" + text  # a paragraph starts one cell down
        for start in range(0, len(chars), per):
            vertical_text(d, img, [chars[start:start + per]], x, top, f, pitch, step, fill=(30, 28, 26))
            x -= pitch
        x -= 10
    d.text((w / 2 - 20, h - 70), "\u2015 12 \u2015", font=font("segoeui.ttf", 22), fill=(120, 116, 108))
    return img


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    document().save(os.path.join(out, "page-document.png"), optimize=True)
    monitor().save(os.path.join(out, "page-monitor.jpg"), quality=84, optimize=True)
    settings_ui().save(os.path.join(out, "page-settings.png"), optimize=True)
    manga2().save(os.path.join(out, "page-manga2.png"), optimize=True)
    novel().save(os.path.join(out, "page-novel.png"), optimize=True)
    for name in sorted(os.listdir(out)):
        with Image.open(os.path.join(out, name)) as im:
            print(name, im.size, os.path.getsize(os.path.join(out, name)))


main()
