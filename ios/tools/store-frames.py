#!/usr/bin/env python3
"""Builds the App Store screenshots: an illustration, a headline and the real app screen in a phone frame.

Apple's 6.9 inch iPhone size (1320 x 2868, RGB, no alpha). The illustrations come from the maintainer (made with the
prompts in docs/brand/ios/prompts.md); the screens are the raw captures from tools/store-screenshots.sh. Needs Pillow.

Usage: tools/store-frames.py [ART_DIR SCREENS_DIR OUT_DIR]
With no arguments it reads the repository's docs/brand/ios/art/ and raw/ and writes docs/brand/ios/screenshots/.
Each frame in FRAMES names its illustration in ART_DIR (1.png to 6.png, languages.png, names.png), so a frame keeps its
art when the order changes; a missing file leaves that frame without art. SCREENS_DIR holds the raw captures.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

W, H = 1320, 2868
NAVY = (38, 38, 74)
CREAM = (255, 248, 231)
SERIF = "/System/Library/Fonts/NewYork.ttf"
SANS = "/System/Library/Fonts/SFNS.ttf"
FRAMES = [  # headline lines, raw screen (also the output's name), illustration in ART_DIR
    (["Talk.", "It types."], "1-talk.png", "1.png"),
    (["Your words,", "right where", "you type"], "2-typed.png", "2.png"),
    (["Dictate", "long notes"], "3-note.png", "3.png"),
    (["Works offline.", "Your voice is", "never uploaded."], "4-private.png", "4.png"),
    (["Speak in", "25 languages"], "5-languages.png", "languages.png"),
    (["A full", "keyboard, too"], "6-keyboard.png", "5.png"),
    (["Names spelled", "your way"], "7-dictionary.png", "names.png"),
    (["Your takes,", "saved on", "your iPhone"], "8-history.png", "6.png"),
]
ART_BOX = (600, 860)          # the illustration's box, top right
TEXT_LEFT, TEXT_TOP = 96, 170
PHONE_W, BEZEL, RADIUS, BOTTOM = 820, 22, 120, 110


def fit_font(lines, max_width, largest=150, smallest=70):
    for size in range(largest, smallest - 1, -4):
        font = ImageFont.truetype(SERIF, size)
        if all(font.getlength(line) <= max_width for line in lines):
            return font
    return ImageFont.truetype(SERIF, smallest)


def frame(lines, screen, art, icon, first):
    bg = art.getpixel((8, 8))[:3] if art else CREAM
    canvas = Image.new("RGB", (W, H), bg)
    draw = ImageDraw.Draw(canvas)
    top = TEXT_TOP
    if first and icon:  # the ThumbFree mark on the first frame
        mark = icon.resize((110, 110), Image.LANCZOS)
        canvas.paste(mark, (TEXT_LEFT, 90), mark)
        draw.text((TEXT_LEFT + 136, 112), "ThumbFree", font=ImageFont.truetype(SANS, 64), fill=NAVY)
        top += 110
    art_w = 0
    if art:
        scale = min(ART_BOX[0] / art.width, ART_BOX[1] / art.height)
        a = art.resize((int(art.width * scale), int(art.height * scale)), Image.LANCZOS)
        art_w = a.width
        canvas.paste(a, (W - a.width - 30, 40))
    font = fit_font(lines, W - TEXT_LEFT - art_w - 60)
    step = int(font.size * 1.12)
    for i, line in enumerate(lines):
        draw.text((TEXT_LEFT, top + i * step), line, font=font, fill=NAVY)
    shot = screen.convert("RGB")
    ph = int(shot.height * PHONE_W / shot.width)
    s = shot.resize((PHONE_W, ph), Image.LANCZOS)
    phone = Image.new("RGBA", (PHONE_W + 2 * BEZEL, ph + 2 * BEZEL), (0, 0, 0, 0))
    ImageDraw.Draw(phone).rounded_rectangle((0, 0, phone.width - 1, phone.height - 1), radius=RADIUS, fill=NAVY + (255,))
    mask = Image.new("L", (PHONE_W, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, PHONE_W - 1, ph - 1), radius=RADIUS - BEZEL, fill=255)
    phone.paste(s, (BEZEL, BEZEL), mask)
    canvas.paste(phone, ((W - phone.width) // 2, H - phone.height - BOTTOM), phone)
    return canvas


def main():
    brand = Path(__file__).resolve().parents[2] / "docs" / "brand" / "ios"
    args = sys.argv[1:] or [brand / "art", brand / "raw", brand / "screenshots"]
    if len(args) != 3:
        sys.exit(__doc__)
    art_dir, screens_dir, out_dir = map(Path, args)
    out_dir.mkdir(parents=True, exist_ok=True)
    icon_path = Path(__file__).resolve().parent.parent / "App/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
    icon = Image.open(icon_path).convert("RGBA") if icon_path.exists() else None
    if icon:  # the store's rounded icon shape
        m = Image.new("L", icon.size, 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, icon.width - 1, icon.height - 1), radius=int(icon.width * 0.22), fill=255)
        icon.putalpha(m)
    for n, (lines, screen_name, art_name) in enumerate(FRAMES, start=1):
        art_file = art_dir / art_name
        art = Image.open(art_file).convert("RGB") if art_file.exists() else None
        out = frame(lines, Image.open(screens_dir / screen_name), art, icon, n == 1)
        path = out_dir / screen_name
        out.save(path)
        print(path, out.size, "art" if art else "no art")


if __name__ == "__main__":
    main()
