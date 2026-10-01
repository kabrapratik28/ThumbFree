#!/usr/bin/env bash
# A review page for the App Store listing: build/store-review/index.html, with contact-sheet.png beside it. It
# shows the listing text as the App Store does (name, subtitle, promotional text, description, from the repository's
# docs/brand/ios/listing.md) and the store images in their order (docs/brand/ios/screenshots/, from
# tools/store-frames.py), with Apple's numbers checked, and the support page's contact email. No App Preview video on
# the page, only the screenshots. Local files only: nothing is uploaded or submitted.
# Usage: tools/store-review.sh [<folder of screenshots>]   (then open build/store-review/index.html)
set -euo pipefail
cd "$(dirname "$0")/.."
shots="${1:-../docs/brand/ios/screenshots}"
out=build/store-review
mkdir -p "$out"
frames=("$shots"/[0-9]-*.png)
[ -f "${frames[0]}" ] || { echo "No screenshots in $shots: run tools/store-screenshots.sh, then tools/store-frames.py"; exit 1; }
swift tools/store-compose.swift sheet "$out/contact-sheet.png" "${frames[@]}"
cp App/Assets.xcassets/AppIcon.appiconset/AppIcon.png "$out/icon.png"
for f in "${frames[@]}"; do sips -g pixelWidth -g pixelHeight -g hasAlpha "$f" | awk '/pixel|hasAlpha/ {print $2}' | paste -sd ' ' -; done > "$out/sizes.txt"
python3 - "$out" "${frames[@]}" <<'PY'
import html, os, re, sys, urllib.parse
out, frames = sys.argv[1], sys.argv[2:]
listing = open("../docs/brand/ios/listing.md").read()
def cell(label): return re.search(rf"^\| {re.escape(label)}[^|]*\| (.+?) \|$", listing, re.M).group(1)
def block(tag): return listing.split(f"<!-- {tag} -->\n")[1].split(f"\n<!-- /{tag} -->")[0]
def link(path): return urllib.parse.quote(os.path.relpath(path, out))
e = html.escape
name, subtitle = (re.sub(r" \[\d+\]$", "", cell(label)) for label in ("Name", "Subtitle"))
email = re.search(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", open("docs/support.md").read()).group()
captions = dict(line.split("|", 1) for line in open("tools/store-screenshots.sh").read().split("<<'EOF'\n")[1].split("\nEOF")[0].splitlines())
sizes = open(f"{out}/sizes.txt").read().split("\n")
media = []
for i, f in enumerate(frames):
    words = captions.get(os.path.basename(f)[:-4], "").replace("|", " ")
    media.append(f'<figure><img src="{link(f)}" alt="{e(words)}"><figcaption>{i + 1}. {e(words)}</figcaption></figure>')
rows = "".join(f"<tr><td>{i + 1}. {e(os.path.basename(f))}</td><td>{e(sizes[i].replace(' ', ' x ', 1).replace(' no', ', no alpha').replace(' yes', ', ALPHA'))}</td></tr>"
               for i, f in enumerate(frames))
page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{e(name)}: App Store listing for review</title>
<style>
body {{ font: 17px/1.45 -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; color: #1d1d1f; background: #fff; }}
main {{ max-width: 1080px; margin: 0 auto; padding: 24px 28px 64px; }}
.banner {{ background: #FFD35A; color: #1F1B3A; padding: 12px 28px; font-weight: 600; }}
.app {{ display: flex; gap: 22px; align-items: center; margin: 28px 0 8px; }}
.app img {{ width: 128px; height: 128px; border-radius: 28px; box-shadow: 0 0 0 1px rgba(0,0,0,.08); }}
h1 {{ font-size: 30px; margin: 0; }} h2 {{ font-size: 22px; margin: 36px 0 12px; }}
.subtitle {{ color: #6e6e73; font-size: 20px; margin: 2px 0 12px; }}
.get {{ background: #0071e3; color: #fff; border-radius: 16px; padding: 4px 18px; font-weight: 700; font-size: 15px; }}
.meta {{ color: #6e6e73; font-size: 14px; margin-left: 10px; }}
.media {{ display: flex; gap: 16px; overflow-x: auto; padding-bottom: 12px; }}
figure {{ margin: 0; flex: 0 0 auto; width: 260px; }}
figure img {{ width: 260px; aspect-ratio: 886 / 1920; border-radius: 22px; border: 1px solid #d2d2d7; display: block; background: #000; }}
figcaption {{ font-size: 13px; color: #6e6e73; margin-top: 6px; }}
.promo {{ font-size: 19px; }}
.description {{ white-space: pre-wrap; border-top: 1px solid #d2d2d7; padding-top: 16px; }}
table {{ border-collapse: collapse; font-size: 15px; }} td {{ border-bottom: 1px solid #e5e5ea; padding: 6px 16px 6px 0; vertical-align: top; }}
.sheet {{ width: 100%; border: 1px solid #d2d2d7; border-radius: 12px; }}
</style></head><body>
<div class="banner">A local preview: nothing here has been uploaded or submitted.</div>
<main>
<div class="app"><img src="icon.png" alt="">
<div><h1>{e(name)}</h1><div class="subtitle">{e(subtitle)}</div><span class="get">Get</span><span class="meta">{e(cell("Price"))} · {e(cell("Devices"))} · {e(cell("Primary category"))}</span></div></div>
<h2>Screenshots</h2><div class="media">{"".join(media)}</div>
<p class="promo">{e(block("promo"))}</p>
<div class="description">{e(block("description"))}</div>
<h2>Information</h2>
<table><tr><td>Category</td><td>{e(cell("Primary category"))}, {e(cell("Secondary category"))}</td></tr>
<tr><td>Age rating</td><td>{e(cell("Age rating").split(" (")[0])}</td></tr><tr><td>Price</td><td>{e(cell("Price"))}</td></tr>
<tr><td>Compatibility</td><td>{e(cell("Devices"))}</td></tr><tr><td>Keywords (not shown on the page)</td><td>{e(block("keywords"))}</td></tr>
<tr><td>Contact (support page)</td><td>{e(email)}</td></tr></table>
<h2>Checked against Apple's specifications</h2>
<p>The screenshots (App Store Connect Help, Screenshot specifications: 6.9-inch portrait 1260 x 2736, 1290 x 2796 or 1320 x 2868, PNG or JPEG, no alpha, one to ten):</p>
<table>{rows}</table>
<h2>Contact sheet</h2><img class="sheet" src="contact-sheet.png" alt="All screenshots">
</main></body></html>
"""
open(f"{out}/index.html", "w").write(page)
PY
rm "$out/sizes.txt"
echo "Open $PWD/$out/index.html"
