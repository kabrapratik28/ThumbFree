"""Checks the Play Store assets in this folder against Play's rules, and the listing's text against its limits and our
wording rules. Exits non-zero on a problem.

    python3 check.py
"""
import glob
import os
import re
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MB = 1024 * 1024
problems = []


def check_image(path, size, max_bytes, screenshot=True):
    image = Image.open(path)
    name = os.path.basename(path)
    w, h = image.size
    print(f"{name}: {w} x {h}, {image.mode}, {image.format}, {os.path.getsize(path) / 1024:.0f} KB, long/short {max(w, h) / min(w, h):.3f}")
    if (w, h) != size:
        problems.append(f"{name} is {w} x {h}, not {size[0]} x {size[1]}")
    if screenshot and max(w, h) > 2 * min(w, h):  # Play's screenshot rule; the feature graphic is 1024 x 500 by spec
        problems.append(f"{name}: the long side is over twice the short side")
    with open(path, "rb") as f:
        header = f.read(26)  # the PNG signature, then IHDR: width, height, bit depth, colour type
    if image.format != "PNG" or image.mode != "RGB" or header[24] != 8 or header[25] != 2:
        problems.append(f"{name} must be a 24-bit PNG without alpha (is {image.format} {image.mode})")
    if os.path.getsize(path) > max_bytes:
        problems.append(f"{name} is over {max_bytes // MB} MB")


shots = sorted(glob.glob(os.path.join(HERE, "screenshot-*.png")))
if not 6 <= len(shots) <= 8:
    problems.append(f"{len(shots)} phone screenshots; the plan is 6 to 8 (Play takes 2 to 8)")
for shot in shots:
    check_image(shot, (1080, 2160), 8 * MB)
check_image(os.path.join(HERE, "feature-graphic.png"), (1024, 500), 1 * MB, screenshot=False)

listing = open(os.path.join(HERE, "store-listing.md"), encoding="utf-8").read()
limits = {"title": 30, "short": 80, "full": 4000}
banned = ["leverage", "seamless", "robust", "crucial", "delve", "foster", "load-bearing", "spine", "structural", "cutting-edge", "game-changer"]
for key, limit in limits.items():
    text = re.search(rf"<!-- {key} -->\n(.*?)\n<!-- /{key} -->", listing, re.S).group(1).strip()
    print(f"{key}: {len(text)} of {limit} characters")
    if len(text) > limit:
        problems.append(f"{key} is {len(text)} characters, over {limit}")
for path in [os.path.join(HERE, "store-listing.md")]:
    text = open(path, encoding="utf-8").read()
    if "—" in text:
        problems.append(f"{os.path.basename(path)} has an em-dash")
    for word in banned:
        if re.search(rf"\b{re.escape(word)}\b", text, re.I):
            problems.append(f"{os.path.basename(path)} uses \"{word}\"")

print("\n".join(problems) if problems else "All checks passed.")
sys.exit(1 if problems else 0)
