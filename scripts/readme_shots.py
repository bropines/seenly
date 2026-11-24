#!/usr/bin/env python3
"""Build the README gallery, the hero banner and the social preview from the
README renders.

    ./gradlew readmeShots
    python3 scripts/readme_shots.py [--renders DIR ...] [--font path/to/font.ttf]

The renders come from app/shared/src/androidHostTest/.../readme/ReadmeShots.kt
and app/android/src/test/.../BubbleMenuShots.kt: the real screens, rendered by
Robolectric from made-up data (the pictures drawn by scripts/demo_images.py),
in English and in Russian. No device, nobody's data.

Writes docs/screenshots/{en,ru}/*.png and docs/social-preview.png. Needs Pillow.
"""
import argparse
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RENDERS = ["app/shared/build/readme-shots", "app/android/build/readme-shots"]
OUT = "docs/screenshots"
LANGS = ["en", "ru"]
NAMES = {
    "Home": "home", "ViewerManga": "manga", "ViewerSign": "sign", "ViewerText": "text-selection",
    "BubbleMenu": "screen-translation", "Settings": "settings", "Translators": "translators",
}
TAGLINE = {
    "en": "Translate the text in any picture, in place",
    "ru": "Переводит текст на любой картинке прямо на месте",
}
# Seenly's dark scheme: near-black violet, the blue-violet seed, a mint accent.
BG = (10, 9, 20)
GLOW = (91, 91, 214)
ACCENT = (110, 214, 170)
TEXT = (240, 238, 250)
SUBTLE = (190, 186, 225)
FAINT = (130, 126, 165)

# Kept small for the README: each gallery image under ~400 KB, the banner under ~800 KB.
GALLERY_WIDTH = 540
MAX_BYTES = 400_000
MAX_BANNER_BYTES = 800_000


def shot(dirs, name, lang):
    for d in dirs:
        found = glob.glob(os.path.join(d, f"{name}_{lang}.png"))
        if found:
            return Image.open(found[0]).convert("RGB")
    sys.exit(f"missing render {name}_{lang}.png in {dirs}; run ./gradlew readmeShots first")


def save_small(im, path, limit):
    """PNG, optimised; down to an adaptive palette when the full colour one is too big."""
    im.save(path, optimize=True)
    if os.path.getsize(path) > limit:
        im.convert("RGB").quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.FLOYDSTEINBERG) \
            .save(path, optimize=True)
    return os.path.getsize(path)


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def device(im, width, bezel=10):
    """A screen at `width`, in a thin dark bezel with the corners of a phone."""
    h = round(im.height * width / im.width)
    screen = im.resize((width, h), Image.LANCZOS)
    r = max(12, int(min(width, h) * 0.09))
    frame = rounded(Image.new("RGB", (width + 2 * bezel, h + 2 * bezel), (36, 34, 48)), r + bezel)
    frame.alpha_composite(rounded(screen, r), (bezel, bezel))
    return frame


def with_shadow(layer, blur=36, offset=(0, 28), alpha=170):
    pad = blur * 3
    canvas = Image.new("RGBA", (layer.width + 2 * pad, layer.height + 2 * pad), (0, 0, 0, 0))
    shade = Image.new("RGBA", layer.size, (0, 0, 0, alpha))
    shade.putalpha(Image.eval(layer.getchannel("A"), lambda a: a * alpha // 255))
    canvas.alpha_composite(shade, (pad + offset[0], pad + offset[1]))
    canvas = canvas.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer, (pad, pad))
    return canvas, pad


def backdrop(w, h):
    """Near-black with a blue-violet glow above and a faint mint one below: the app's dark theme."""
    base = Image.new("RGB", (w, h), BG)
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([w * 0.15, -h * 0.4, w * 0.85, h * 0.7], fill=GLOW)
    gd.ellipse([w * 0.55, h * 0.55, w * 1.1, h * 1.4], fill=ACCENT)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) // 4))
    return Image.blend(base, glow, 0.32).convert("RGBA")


def place(canvas, layer, cx, top):
    shadowed, pad = with_shadow(layer)
    canvas.alpha_composite(shadowed, (int(cx - shadowed.width / 2), int(top - pad)))


def font(path, size, bold):
    candidates = [path] if path else []
    candidates += [
        r"C:\Windows\Fonts\seguisb.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        "/mnt/c/Windows/Fonts/seguisb.ttf" if bold else "/mnt/c/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans" + ("-Bold" if bold else "") + ".ttf",
    ]
    for c in candidates:
        try:
            f = ImageFont.truetype(c, size)
            try:
                f.set_variation_by_name("Bold" if bold else "Regular")
            except Exception:
                pass
            return f
        except Exception:
            continue
    return ImageFont.load_default(size)


def hero(dirs, lang):
    """The banner on top of the README: a sign and a manga page translated, and text selected."""
    w, h = 1600, 800
    c = backdrop(w, h)
    place(c, device(shot(dirs, "ViewerSign", lang), 312), w * 0.29, 120)
    place(c, device(shot(dirs, "ViewerText", lang), 312), w * 0.71, 120)
    place(c, device(shot(dirs, "ViewerManga", lang), 360), w * 0.50, 48)
    return c.convert("RGB")


def social(dirs, font_path):
    """1280x640, what GitHub shows when the repository link is shared."""
    w, h = 1280, 640
    c = backdrop(w, h)
    d = ImageDraw.Draw(c)
    d.text((84, 200), "Seenly", font=font(font_path, 104, True), fill=TEXT)
    d.text((88, 336), "Translate the text in any", font=font(font_path, 36, False), fill=SUBTLE)
    d.text((88, 384), "picture, in place", font=font(font_path, 36, False), fill=SUBTLE)
    d.text((88, 470), "manga · signs · screenshots · the whole screen", font=font(font_path, 24, False), fill=FAINT)
    d.rounded_rectangle([88, 160, 168, 168], 4, fill=ACCENT)
    for name, cx, top in (("ViewerSign", 900, 120), ("ViewerManga", 1100, 60)):
        place(c, device(shot(dirs, name, "en"), 250, 6), cx, top)
    return c.convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--renders", action="append", default=[], help="a folder holding the *_en.png / *_ru.png renders")
    ap.add_argument("--font", default="", help="a TTF for the social preview's text")
    args = ap.parse_args()
    dirs = args.renders or RENDERS

    for lang in LANGS:
        out = os.path.join(OUT, lang)
        os.makedirs(out, exist_ok=True)
        for name, file in NAMES.items():
            im = shot(dirs, name, lang)
            im = im.resize((GALLERY_WIDTH, round(im.height * GALLERY_WIDTH / im.width)), Image.LANCZOS)
            size = save_small(im, os.path.join(out, f"{file}.png"), MAX_BYTES)
            print(f"{out}/{file}.png {size // 1024} KB")
        size = save_small(hero(dirs, lang), os.path.join(out, "hero.png"), MAX_BANNER_BYTES)
        print(f"{out}/hero.png {size // 1024} KB")

    size = save_small(social(dirs, args.font), "docs/social-preview.png", MAX_BANNER_BYTES)
    print(f"docs/social-preview.png {size // 1024} KB")


if __name__ == "__main__":
    main()
