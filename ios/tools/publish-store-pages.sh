#!/usr/bin/env bash
# Publishes ThumbFree's privacy policy and support page as a GitHub Pages project site:
# https://<you>.github.io/ThumbFree/privacy.html and .../support.html, the two URLs App Store Connect requires.
# Run it yourself: it updates the two pages on the gh-pages branch of the public repository <you>/ThumbFree, where the
# website lives, and stops if that branch is missing. It writes and commits only privacy.html and support.html: the
# site's home page (index.html) and every other file on gh-pages belong to the website and are never changed or deleted.
# Needs the GitHub CLI logged in (`gh auth status`). Re-run it after editing docs/privacy.md or docs/support.md.
set -euo pipefail
cd "$(dirname "$0")/.."
GIT=${GIT:-git} # GIT picks another git
user=$(gh api user --jq .login)
repo=ThumbFree
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

$GIT clone --quiet "https://github.com/$user/$repo.git" "$work/site"
if ! $GIT -C "$work/site" checkout --quiet gh-pages 2>/dev/null; then
  echo "$user/$repo has no gh-pages branch: the website lives there, so it must exist before these pages." >&2
  exit 1
fi

# A small Markdown to HTML converter: headings, paragraphs, lists, bold, links. Enough for these two pages.
python3 - "$work/site" <<'PY'
import html, re, sys
from pathlib import Path
out = Path(sys.argv[1])

def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)

def page(md, title):
    body, para, in_list = [], [], False
    def flush():
        nonlocal para
        if para:
            body.append("<p>" + inline(" ".join(para)) + "</p>")
            para = []
    for line in md.splitlines():
        s = line.strip()
        if s.startswith("- "):
            flush()
            if not in_list:
                body.append("<ul>")
                in_list = True
            body.append("<li>" + inline(s[2:]) + "</li>")
            continue
        if in_list:
            body.append("</ul>")
            in_list = False
        m = re.match(r"(#{1,3}) (.*)", s)
        if m:
            flush()
            n = len(m.group(1))
            body.append(f"<h{n}>" + inline(m.group(2)) + f"</h{n}>")
        elif not s:
            flush()
        else:
            para.append(s)
    flush()
    if in_list:
        body.append("</ul>")
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>body{{font:17px/1.55 -apple-system,system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 20px;color:#26264a;background:#fff8e7}}a{{color:#26264a}}h1{{font-size:30px}}h2{{margin-top:32px}}</style>
</head><body>
{chr(10).join(body)}
<p><a href="./">ThumbFree</a></p>
</body></html>
"""

(out / "privacy.html").write_text(page(Path("docs/privacy.md").read_text(), "ThumbFree privacy policy"))
(out / "support.html").write_text(page(Path("docs/support.md").read_text(), "ThumbFree support"))
PY

cd "$work/site"
$GIT add -- privacy.html support.html
if $GIT diff --cached --quiet; then
  echo "The pages are already up to date."
else
  # The project's name and email on the commit, not your personal ones: this repository is public.
  $GIT -c user.name=ThumbFree -c user.email=thumbfree.app@gmail.com commit --quiet -m "ThumbFree: privacy policy and support pages"
  $GIT push --quiet origin gh-pages
fi
# GitHub often turns Pages on by itself when gh-pages arrives, so "already enabled" (409) is fine: then Pages must answer.
gh api -X POST "repos/$user/$repo/pages" -f "source[branch]=gh-pages" -f "source[path]=/" >/dev/null 2>&1 || \
  gh api "repos/$user/$repo/pages" >/dev/null
echo "Published (live within a minute or two):"
echo "  Privacy policy: https://$user.github.io/$repo/privacy.html"
echo "  Support:        https://$user.github.io/$repo/support.html"
