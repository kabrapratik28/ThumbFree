"""Makes the repository README's screenshots: the store captures in a navy phone frame, 480 px wide with 256 colours.

Run it after the Android or iPhone captures in `raw/` change. Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[4]
COPIES = {
    "android-1-listening.png": "docs/brand/android/raw/1-chat-listening.png",
    "android-2-typed.png": "docs/brand/android/raw/2-chat-text.png",
    "iphone-1-listening.png": "docs/brand/ios/raw/1-talk.png",
    "iphone-2-typed.png": "docs/brand/ios/raw/2-typed.png",
}
WIDTH, BEZEL, RADIUS = 480, 12, 48  # px: the whole image, the frame, and the frame's outer corners
NAVY = (38, 38, 74)


def rounded(size, radius, scale):
    """A rounded-rectangle mask, drawn `scale` times larger and scaled down so its edge is smooth (1: a hard edge)."""
    mask = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, mask.width - 1, mask.height - 1), radius * scale, fill=255)
    return mask.resize(size, Image.LANCZOS)


for name, source in COPIES.items():
    shot = Image.open(ROOT / source).convert("RGB")
    inner = WIDTH - 2 * BEZEL
    shot = shot.resize((inner, round(shot.height * inner / shot.width)), Image.LANCZOS)
    frame = Image.new("RGB", (WIDTH, shot.height + 2 * BEZEL), NAVY)
    frame.paste(shot, (BEZEL, BEZEL), rounded(shot.size, RADIUS - BEZEL, 4))
    # Median cut keeps the white message box apart from the grey screen behind it; octree merges the two.
    # 255 colours leave index 255 for the corners outside the frame, which PNG transparency then hides.
    image = frame.quantize(255, Image.Quantize.MEDIANCUT, kmeans=4)
    image.putpalette((image.getpalette() + [0] * 768)[:765] + [255, 255, 255])
    image.paste(255, mask=rounded(frame.size, RADIUS, 1).point(lambda a: 255 - a))
    image.save(Path(__file__).parent / name, optimize=True, transparency=255)
    print(name, image.size)
