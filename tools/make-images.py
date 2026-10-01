"""Makes the website's images in img/ from the brand kit in the ThumbFree repository.

    python3 tools/make-images.py --brand <ThumbFree>/docs/brand --ios-raw <folder of iOS captures> \
        --ios-art <folder of iOS illustrations> --serif SourceSerif4Display-Regular.ttf --font Roboto-Medium.ttf

--brand needs android/art/ (the owner's illustrations), android/raw/ (the Android captures) and common/logo/.
--ios-raw holds the iPhone app's App Store captures (1-talk.png ...), 1320 x 2868, and --ios-art its illustrations
(1.png to 6.png), of which the privacy section uses the shield, 4.png.
--serif is Source Serif 4 Display (SIL OFL), the store images' headline type; --font is Roboto (Apache 2.0), for the
name beside the logo. Only the Open Graph image uses them, and neither is committed.

Every picture is written twice, AVIF and WebP, at twice the size the page shows it. The official store badges and
the web font are not made here: the badges come unchanged from Apple and Google, and the font is a subset of the
serif (see fonts/README.md).
"""
import argparse
import os
import shutil

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageStat

SITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(SITE, "img")
NAVY, CREAM = (38, 38, 74), (255, 248, 231)

ART = {  # output name: illustration, width in px (twice the page's size)
    "hero-wide": ("feature.png", 1120),
    "hero-walk": ("android-1.png", 360),
    "art-any-app": ("android-2.png", 400),
    "art-fast": ("android-3.png", 400),
    "art-private": ("android-4.png", 400),
    "art-dictionary": ("android-5.png", 400),
    "art-history": ("android-6.png", 400),
    "art-languages": ("android-7.png", 400),
}
SHOT_W = 480  # the page shows the captures 240 px wide


def illustration(path):
    """The illustration with its own cream moved onto CREAM (a few levels at most), as the store images' make_assets.py
    does, so no box shows around it on the page."""
    art = Image.open(path).convert("RGB")
    bg = ImageStat.Stat(art.crop((0, 0, art.width, 16))).median
    art = Image.merge("RGB", [band.point(lambda v, d=c - b: v + d) for band, b, c in zip(art.split(), bg, CREAM)])
    # The paper's grain becomes flat cream, so the encoder draws no faint blocks in the background.
    diff = ImageChops.difference(art, Image.new("RGB", art.size, CREAM)).convert("L")
    art.paste(CREAM, mask=diff.point(lambda v: 255 if v <= 8 else 0))
    return art


def ink(art, pad=12):
    """The drawing's box, every pixel clearly off the cream, with a little room around it."""
    diff = ImageChops.difference(art, Image.new("RGB", art.size, CREAM)).convert("L")
    x0, y0, x1, y1 = diff.point(lambda v: 255 if v > 24 else 0).getbbox()
    return max(0, x0 - pad), max(0, y0 - pad), min(art.width, x1 + pad), min(art.height, y1 + pad)


def width(image, w):
    return image.resize((w, round(image.height * w / image.width)), Image.LANCZOS)


def save(image, name):
    image.save(os.path.join(OUT, name + ".avif"), quality=60, speed=4)
    image.save(os.path.join(OUT, name + ".webp"), quality=80, method=6)
    print(f"{name}  {image.width} x {image.height}")


def og_image(art_dir, logo_dir, serif, font):
    """The 1200 x 630 link preview: the logo, the name and "Talk. It types." on the left, the feature illustration on
    the right, everything inside the middle 1200 x 600 that every site shows uncropped."""
    canvas = Image.new("RGB", (1200, 630), CREAM)
    art = illustration(os.path.join(art_dir, "feature.png"))
    art = art.crop(ink(art))
    art = width(art, 620) if art.height * 620 / art.width <= 560 else art.resize(
        (round(art.width * 560 / art.height), 560), Image.LANCZOS)
    canvas.paste(art, (1200 - 40 - art.width, (630 - art.height) // 2))
    icon = Image.open(os.path.join(logo_dir, "app-icon-preview.png")).convert("RGBA").resize((96, 96), Image.LANCZOS)
    canvas.paste(icon, (72, 80), icon)
    draw = ImageDraw.Draw(canvas)
    draw.text((72 + 96 + 24, 80 + 48), "ThumbFree", font=ImageFont.truetype(font, 56), fill=NAVY, anchor="lm")
    headline = ImageFont.truetype(serif, 118)
    for i, line in enumerate(["Talk.", "It types."]):
        draw.text((68, 220 + i * 130), line, font=headline, fill=NAVY)
    draw.text((72, 520), "Free, offline dictation. Open source.", font=ImageFont.truetype(font, 34), fill=NAVY)
    # JPEG keeps it far under the 300 KB some apps allow for a preview; 4:4:4 keeps the lines crisp.
    canvas.save(os.path.join(SITE, "og-image.jpg"), quality=90, subsampling=0, optimize=True)
    print("og-image.jpg  1200 x 630")


def icons(logo_dir):
    tile = Image.open(os.path.join(logo_dir, "app-icon-preview.png")).convert("RGBA")
    tile.save(os.path.join(SITE, "favicon.ico"), sizes=[(16, 16), (32, 32), (48, 48)])
    # iOS rounds the corners itself and wants no transparency, so the touch icon is the square artwork.
    square = Image.open(os.path.join(logo_dir, "app-icon.png")).convert("RGB")
    square.resize((180, 180), Image.LANCZOS).save(os.path.join(SITE, "apple-touch-icon.png"), optimize=True)
    shutil.copyfile(os.path.join(logo_dir, "app-icon-preview.svg"), os.path.join(OUT, "logo.svg"))
    print("favicon.ico, apple-touch-icon.png, img/logo.svg")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--brand", required=True, help="the ThumbFree repository's docs/brand")
    parser.add_argument("--ios-raw", required=True, help="the iPhone app's App Store captures")
    parser.add_argument("--ios-art", required=True, help="the iPhone app's App Store illustrations")
    parser.add_argument("--serif", required=True, help="Source Serif 4 Display .ttf")
    parser.add_argument("--font", required=True, help="Roboto Medium .ttf")
    args = parser.parse_args()
    art_dir = os.path.join(args.brand, "android", "art")
    logo_dir = os.path.join(args.brand, "common", "logo")
    os.makedirs(OUT, exist_ok=True)
    for name, (file, w) in ART.items():
        art = illustration(os.path.join(art_dir, file))
        save(width(art.crop(ink(art)), w), name)
    shield = illustration(os.path.join(args.ios_art, "4.png"))
    save(width(shield.crop(ink(shield)), 400), "art-privacy")
    android_raw = os.path.join(args.brand, "android", "raw")
    for prefix, folder in (("android-", android_raw), ("iphone-", args.ios_raw)):
        for file in sorted(f for f in os.listdir(folder) if f.endswith(".png")):
            save(width(Image.open(os.path.join(folder, file)).convert("RGB"), SHOT_W), prefix + file[:-4])
    og_image(art_dir, logo_dir, args.serif, args.font)
    icons(logo_dir)
