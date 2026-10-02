"""Checks the website before a commit: python3 tools/check-site.py

- The inline script's hash is in index.html's Content-Security-Policy (it prints the right one if not).
- The JSON-LD parses, every local file a page points to exists (for a page in a folder, such as beta/, from that
  folder), and every image has a width, a height and an alt.
- The Android test page, beta/, loads no Meta pixel and not site.js, which holds the pixel's code.
- The copy follows the project's writing rules: no em-dashes, none of the filler words, no email but the public one.
- The four pages the app stores link (privacy and support, iPhone and Android) are byte for byte the last commit's.
- A store that site.js marks live has a real address. As a note, not a failure: when index.html still tells visitors
  without JavaScript that a live store is coming soon.
"""
import base64
import hashlib
import json
import os
import re
import subprocess
import sys

SITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PROTECTED = ["privacy.html", "support.html", "android-privacy.html", "android-support.html"]
FILLER = ["delve", "leverage", "robust", "seamless", "crucial", "comprehensive", "foster"]
EMAIL = "thumbfree.app@gmail.com"
SITE_URL = "https://kabrapratik28.github.io/ThumbFree/"
problems, notes = [], []

pages = sorted(os.path.relpath(os.path.join(root, name), SITE) for root, _, files in os.walk(SITE) if ".git" not in root
               for name in files if name.endswith(".html"))
for page in pages:
    html = open(os.path.join(SITE, page), encoding="utf-8").read()
    if page.startswith("beta/") and re.search(r"facebook|site\.js", html):
        problems.append(f"{page}: the test page must load no Meta pixel and not site.js")
    for script in re.findall(r"<script>(.*?)</script>", html, re.S):
        digest = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
        if f"'sha256-{digest}'" not in html:
            problems.append(f"{page}: the CSP needs 'sha256-{digest}' for its inline script")
    for data in re.findall(r'<script type="application/ld\+json">(.*?)</script>', html, re.S):
        try:
            json.loads(data)
        except ValueError as e:
            problems.append(f"{page}: JSON-LD doesn't parse: {e}")
    refs = re.findall(r'(?:src|href)="([^"]+)"', html) + re.findall(r"url\(([^)]+)\)", html) + [
        u.split()[0] for s in re.findall(r'srcset="([^"]+)"', html) for u in s.split(",")] + [
        c for c in re.findall(r'content="([^"]+)"', html) if c.startswith(SITE_URL)]
    for ref in refs:
        base = os.path.dirname(page)  # a relative address starts from the page's folder, a site address from the top
        for prefix in (SITE_URL, "/ThumbFree/"):
            if ref.startswith(prefix):
                ref, base = ref[len(prefix):], ""
        if re.match(r"(https?:|mailto:|#|data:)", ref):
            continue
        path = os.path.join(SITE, base, ref.split("#")[0].split("?")[0])
        if not os.path.isfile(os.path.join(path, "index.html") if os.path.isdir(path) else path):
            problems.append(f"{page}: missing file {ref}")
    for img in re.findall(r"<img\b[^>]*>", html):
        for attr in ("width=", "height=", "alt="):
            if attr not in img:
                problems.append(f"{page}: an image has no {attr[:-1]}: {img[:80]}")

for root, _, files in os.walk(SITE):
    if ".git" in root:
        continue
    for name in files:
        if not name.endswith((".html", ".js", ".md", ".xml", ".txt")) or name == "OFL.txt":
            continue
        path = os.path.join(root, name)
        text = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, SITE)
        if "—" in text:
            problems.append(f"{rel}: has an em-dash")
        for word in FILLER:
            if re.search(rf"\b{word}", text, re.I):
                problems.append(f"{rel}: uses '{word}'")
        for email in set(re.findall(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", text)) - {EMAIL}:
            problems.append(f"{rel}: has an email other than {EMAIL}: {email}")

script = open(os.path.join(SITE, "site.js"), encoding="utf-8").read()
index = open(os.path.join(SITE, "index.html"), encoding="utf-8").read()
for store, name in (("android", "Google Play"), ("ios", "the App Store")):
    block = re.search(rf"{store}: {{\s*url: '([^']*)',.*\n\s*live: (true|false)", script)
    if not block:
        problems.append(f"site.js: can't read the {store} store settings")
    elif block.group(2) == "true":
        if store == "ios" and not re.search(r"/id\d+", block.group(1)):
            problems.append("site.js: the App Store is live but its URL still lacks the app's number")
        if f"Coming soon to {name}" in index:
            notes.append(f"note: {store} is live in site.js, but index.html still says Coming soon to {name} to visitors"
                         " without JavaScript (the comment above the hero's store buttons has the links to paste)")

for page in PROTECTED:
    saved = subprocess.run(["git", "-C", SITE, "show", f"HEAD:{page}"], capture_output=True)
    if saved.returncode:
        problems.append(f"{page}: can't read the last commit's copy: {saved.stderr.decode().strip()}")
    elif saved.stdout != open(os.path.join(SITE, page), "rb").read():
        problems.append(f"{page}: changed, but the app stores link it and it must stay as it is")

print("\n".join(problems + notes) or f"OK: {len(pages)} pages")
sys.exit(1 if problems else 0)
