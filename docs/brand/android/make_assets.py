"""Makes the Play Store images in the style of the App Store ones: 1080 x 2160 screenshots, each a serif headline and
an illustration on cream over the real app screen in a phone frame, and the 1024 x 500 feature graphic.

    python3 make_assets.py [--art <folder>] --serif SourceSerif4Display-Regular.ttf --font Roboto-Regular.ttf

--art is the folder with the owner's illustrations, art/ here unless given: android-1.png to android-7.png (portrait,
1024 x 1536) and feature.png (landscape, 1536 x 1024). Frames 1 to 6 and the feature graphic each need theirs, and a
missing one stops the run before anything changes; frame 7 is made only when android-7.png exists. raw/ holds the
emulator captures. A run replaces every screenshot-*.png.

--serif is the headline type: Source Serif 4 Display (SIL Open Font License), from Adobe's source-serif release on
GitHub. --font is the app's type, Roboto (Apache 2.0), for the name beside the logo: the variable Roboto-Regular.ttf
from an Android image's /system/fonts, or Roboto from Google Fonts. Neither font is committed.
"""
import argparse
import glob
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageStat

HERE = os.path.dirname(os.path.abspath(__file__))
ICON = os.path.join(HERE, "..", "common", "logo", "app-icon-preview.png")
NAVY, CREAM = (38, 38, 74), (255, 248, 231)
W, H = 1080, 2160  # 2:1, the longest shape Play allows
FRAMES = [  # headline lines and raw capture of frame N
    (["Talk.", "It types."], "1-chat-listening.png"),
    (["Your words,", "in any app"], "2-chat-text.png"),
    (["Long notes,", "hands free"], "3-notes.png"),
    (["Works offline.", "Nothing leaves", "your phone."], "4-try.png"),
    (["Names spelled", "your way"], "5-dictionary.png"),
    (["Every take,", "saved on", "your phone"], "6-history.png"),
    (["Speak your", "language"], "7-languages.png"),
]
# The illustration is ART_W wide at the top right, EDGE from the edges, drawn down to the phone's top; it shrinks only
# when its drawing would come closer than GAP to the phone.
ART_W, EDGE, GAP = 480, 24, 24
TEXT_LEFT, TEXT_TOP = 72, 120
# PHONE_W is the screen's width in the frame. The corners stay small enough not to cut the status bar's clock, which
# the emulator draws close to its square corner.
PHONE_W, BEZEL, RADIUS, BOTTOM = 640, 18, 70, 60
SERIF = FONT = None


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    if f.get_variation_axes():
        f.set_variation_by_axes([weight] + [axis["default"] for axis in f.get_variation_axes()[1:]])
    return f


def fit_font(lines, max_width, largest=124, smallest=60):
    """The largest serif size at which every line fits, as the App Store frames pick theirs."""
    for size in range(largest, smallest - 1, -2):
        f = ImageFont.truetype(SERIF, size)
        if all(f.getlength(line) <= max_width for line in lines):
            return f
    return ImageFont.truetype(SERIF, smallest)


def rounded_mask(size, radius, scale=4):
    big = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius * scale, fill=255)
    return big.resize(size, Image.LANCZOS)


def illustration(path):
    """The illustration at [path] with its own cream moved onto CREAM (a few levels at most), so its box doesn't show
    and every image shares one background."""
    art = Image.open(path).convert("RGB")
    bg = ImageStat.Stat(art.crop((0, 0, art.width, 16))).median
    return Image.merge("RGB", [band.point(lambda v, d=c - b: v + d) for band, b, c in zip(art.split(), bg, CREAM)])


def ink(art):
    """The box around the drawing: every pixel clearly off the cream."""
    diff = ImageChops.difference(art, Image.new("RGB", art.size, CREAM)).convert("L")
    return diff.point(lambda v: 255 if v > 24 else 0).getbbox()


def phone(capture):
    """The capture PHONE_W wide in a navy frame with rounded corners."""
    shot = Image.open(os.path.join(HERE, "raw", capture)).convert("RGB")
    shot = shot.resize((PHONE_W, round(shot.height * PHONE_W / shot.width)), Image.LANCZOS)
    body = Image.new("RGBA", (PHONE_W + 2 * BEZEL, shot.height + 2 * BEZEL), NAVY + (255,))
    body.putalpha(rounded_mask(body.size, RADIUS))
    body.paste(shot, (BEZEL, BEZEL), rounded_mask(shot.size, RADIUS - BEZEL))
    return body


def logo_row(canvas, x, y, icon_size, name_size):
    """The rounded app icon and the name beside it, in the app's type."""
    icon = Image.open(ICON).convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
    canvas.paste(icon, (x, y), icon)
    draw = ImageDraw.Draw(canvas)
    draw.text((x + icon_size * 1.25, y + icon_size / 2), "ThumbFree", font=font(name_size, 500), fill=NAVY, anchor="lm")


def screenshot(lines, art_file, capture, first):
    canvas = Image.new("RGB", (W, H), CREAM)
    body = phone(capture)
    body_top = H - BOTTOM - body.height
    art = illustration(art_file)
    left, _, _, bottom = ink(art)
    scale = min(ART_W / art.width, (body_top - GAP - EDGE) / bottom)
    art = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    canvas.paste(art.crop((0, 0, art.width, body_top - EDGE)), (W - EDGE - art.width, EDGE))
    text_right = W - EDGE - art.width + round(left * scale) - 40  # the headline stops short of the drawing
    canvas.paste(body, ((W - body.width) // 2, body_top), body)
    top = TEXT_TOP
    if first:  # the ThumbFree mark on the first frame, as on the App Store
        logo_row(canvas, TEXT_LEFT, 56, 92, 54)
        top += 90
    f = fit_font(lines, text_right - TEXT_LEFT)
    draw = ImageDraw.Draw(canvas)
    for i, line in enumerate(lines):
        draw.text((TEXT_LEFT, top + i * round(f.size * 1.12)), line, font=f, fill=NAVY)
    return canvas


def feature_graphic(art_dir):
    """The owner's landscape feature.png, as wide as the canvas, or smaller when its drawing would come closer than
    20 px to the top or bottom, right-aligned and the drawing centred in the height. The logo, the name and "Talk. It
    types." go on the left, clear of the drawing."""
    art = illustration(os.path.join(art_dir, "feature.png"))
    x0, y0, x1, y1 = ink(art)
    scale = min(1024 / art.width, 460 / (y1 - y0))
    art = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGB", (1024, 500), CREAM)
    x = 1024 - art.width
    canvas.paste(art, (x, 250 - round((y0 + y1) / 2 * scale)))
    logo_row(canvas, 64, 72, 84, 50)
    headline = ImageFont.truetype(SERIF, 100)
    assert 64 + headline.getlength("It types.") <= x + x0 * scale - 40, "the headline runs into the drawing"
    draw = ImageDraw.Draw(canvas)
    for i, line in enumerate(["Talk.", "It types."]):
        draw.text((64, 190 + i * 112), line, font=headline, fill=NAVY)
    return canvas


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--art", default=os.path.join(HERE, "art"), help="the owner's android-N.png and feature.png")
    parser.add_argument("--serif", required=True, help="Source Serif 4 Display .ttf, for the headlines")
    parser.add_argument("--font", required=True, help="Roboto .ttf (variable or static), for the name")
    args = parser.parse_args()
    SERIF, FONT = args.serif, args.font
    arts = {n: os.path.join(args.art, f"android-{n}.png") for n in range(1, len(FRAMES) + 1)}
    if not os.path.exists(arts[7]):
        del arts[7]  # frame 7 is optional, made only with its own illustration
    missing = [path for path in [*arts.values(), os.path.join(args.art, "feature.png")] if not os.path.exists(path)]
    if missing:
        sys.exit("Missing illustrations, so nothing was changed:\n  " + "\n  ".join(missing))
    for old in glob.glob(os.path.join(HERE, "screenshot-*.png")):
        os.remove(old)
    for n, art_file in arts.items():
        lines, capture = FRAMES[n - 1]
        name = f"screenshot-{n}-{os.path.splitext(capture)[0][2:]}.png"
        screenshot(lines, art_file, capture, n == 1).save(os.path.join(HERE, name), optimize=True)
        print(name)
    feature_graphic(args.art).save(os.path.join(HERE, "feature-graphic.png"), optimize=True)
    print("feature-graphic.png")
