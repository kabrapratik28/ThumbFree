#!/usr/bin/env python3
"""Writes Shared/EmojiData.swift, the emoji picker's list: Apple's layout with Unicode's names.

The layout (Apple's sections, the order of the emoji in them, the emoji as Apple's keyboard types them, and the Frequently
Used a new iPhone starts with) comes from tools/emoji/apple-order.txt, which tools/dump-apple-emoji.sh reads from Apple's
Emoji keyboard on a Simulator. Each emoji's Unicode name (what VoiceOver reads), the emoji version that added it and its
skin-tone variants come from Unicode's emoji-test.txt, and the search keywords from CLDR's English annotations.

Once a year, after iOS adds that year's emoji (usually the spring x.4 update) and Xcode has its Simulator:
    TF_SIM="iPhone Air" tools/dump-apple-emoji.sh   # on a Simulator whose Emoji keyboard was never used
    python3 tools/gen-emoji.py https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt \
      --annotations https://raw.githubusercontent.com/unicode-org/cldr-json/48.2.0/cldr-json/cldr-annotations-full/annotations/en/annotations.json \
      --derived https://raw.githubusercontent.com/unicode-org/cldr-json/48.2.0/cldr-json/cldr-annotations-derived-full/annotationsDerived/en/annotations.json
with the newest Unicode and CLDR releases in place of 18.0.0 and 48.2.0 (each input pinned, so the output can be
reproduced), then add that iOS's line to EmojiCatalog.drawnVersion in Shared/Emoji.swift and run EmojiTests on that
Simulator. The generated file's header records where each input came from and its SHA-256.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "Shared", "EmojiData.swift")
ORDER = os.path.join(ROOT, "tools", "emoji", "apple-order.txt")

# Apple's section titles and the EmojiCategory cases they become, in Apple's order.
SECTIONS = {
    "FREQUENTLY USED": "recents",
    "SMILEYS & PEOPLE": "smileysAndPeople",
    "ANIMALS & NATURE": "animalsAndNature",
    "FOOD & DRINK": "foodAndDrink",
    "ACTIVITY": "activity",
    "TRAVEL & PLACES": "travelAndPlaces",
    "OBJECTS": "objects",
    "SYMBOLS": "symbols",
    "FLAGS": "flags",
}
SKIN_TONES = range(0x1F3FB, 0x1F400)
TONE_NAMES = {"light skin tone", "medium-light skin tone", "medium skin tone", "medium-dark skin tone", "dark skin tone"}
LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*\S+\s+E(\d+\.\d+)\s+(.+)$")


def key(emoji):
    """Apple types some emoji with the emoji selector (U+FE0F) where Unicode's list has none, or the other way round."""
    return emoji.replace("️", "")


def read(source):
    if os.path.exists(source):
        with open(source, encoding="utf-8") as f:
            return f.read()
    with urllib.request.urlopen(source) as response:
        return response.read().decode("utf-8")


def unicode_list(text):
    """Returns ({key: (version, name)}, [(base key, variant, version, name)], version, date) from emoji-test.txt."""
    version = re.search(r"^# Version: (\S+)", text, re.M).group(1)
    date = re.search(r"^# Date: (\S+)", text, re.M).group(1).rstrip(",")
    emoji, variants, by_name = {}, [], {}
    for line in text.splitlines():
        match = LINE.match(line)
        if not match:
            continue
        scalars = [int(c, 16) for c in match.group(1).split()]
        text_, added, name = "".join(map(chr, scalars)), match.group(2), match.group(3)
        if any(s in SKIN_TONES for s in scalars):
            variants.append((text_, added, name))
        else:
            emoji[key(text_)] = (added, name)
            by_name[name] = key(text_)
    tones = []
    for text_, added, name in variants:
        # The base is the emoji named like the variant without its tones ("man: light skin tone, red hair" belongs to
        # "man: red hair"); a two-person variant whose base has a shorter name ("kiss: person, person, light skin tone,
        # medium skin tone" is a "kiss") falls back to the name before the colon.
        head, _, rest = name.partition(": ")
        kept = [part for part in rest.split(", ") if part not in TONE_NAMES]
        base = by_name.get(head + (": " + ", ".join(kept) if kept else "")) or by_name.get(head)
        if base is None:
            sys.exit(f"no base emoji for the skin-tone variant {name!r}")
        tones.append((base, text_, added, name))
    return emoji, tones, version, date


def apple_layout():
    """Returns ([(case, [emoji])] in Apple's order, the iOS it was read on) from tools/emoji/apple-order.txt."""
    with open(ORDER, encoding="utf-8") as f:
        lines = f.read().splitlines()
    sections = {}
    for line in lines[1:]:
        title, emoji = line.split("\t")
        if title not in SECTIONS:
            sys.exit(f"apple-order.txt has a section this script does not know: {title!r}")
        sections[SECTIONS[title]] = emoji.split(" ")
    if list(sections) != list(SECTIONS.values()) or sum(len(e) for e in sections.values()) < 1_800:
        sys.exit(f"apple-order.txt is not a whole read of Apple's keyboard: {[(c, len(e)) for c, e in sections.items()]}")
    ios = re.search(r"iOS [0-9.]+", lines[0])
    return [(case, sections.get(case, [])) for case in SECTIONS.values()], ios.group(0) if ios else "iOS unknown"


def keywords(annotations, derived):
    """Returns {key: [keyword]}: CLDR's English keywords for each emoji (derived ones for sequences), for search."""
    found = {}
    for source, top in ((derived, "annotationsDerived"), (annotations, "annotations")):
        for emoji, entry in json.loads(source)[top]["annotations"].items():
            # A dash in a keyword (the police car's "5-0" has an en dash) becomes the hyphen the keys type.
            found[key(emoji)] = [word.replace("\u2013", "-").replace("\u2014", "-") for word in entry.get("default", [])]
    return found


def swift(text, annotations, derived, inputs):
    names, tones, version, date = unicode_list(text)
    words = keywords(annotations, derived)
    layout, source = apple_layout()
    missing = [e for _, emoji in layout for e in emoji if key(e) not in names]
    if missing:
        sys.exit(f"Apple's keyboard shows emoji this emoji-test.txt lacks ({' '.join(missing[:10])}): use a newer one.")
    shown = {key(e): e for case, emoji in layout if case != "recents" for e in emoji}
    out = [
        f"// Generated by tools/gen-emoji.py from tools/emoji/apple-order.txt (Apple's Emoji keyboard, {source})",
        f"// and Unicode's emoji-test.txt, version {version} ({date}). Do not edit by hand. Inputs (SHA-256):",
    ] + [f"// {sha} {source}" for source, sha in inputs] + [
        "",
        "/// The emoji picker's list, laid out as Apple's Emoji keyboard: its sections, its order, and each emoji as Apple's",
        "/// keyboard types it. Each line is the emoji, the Unicode emoji version that added it (`EmojiCatalog` leaves out what",
        "/// this iOS cannot draw) and its Unicode name, which VoiceOver reads.",
        "enum EmojiData {",
        f'    static let unicodeVersion = "{version}"',
        "",
        "    /// Apple's Frequently Used on an iPhone whose Emoji keyboard was never used.",
        f'    static let recentsDefault = "{" ".join(dict(layout)["recents"])}"',
        "",
        "    static let categories: [(EmojiCategory, String)] = [",
    ]
    for case, emoji in layout:
        if case == "recents":
            continue
        if not emoji:
            sys.exit(f"apple-order.txt has no emoji for {case}")
        out.append(f'        (.{case}, """')
        out += [f"        {e} {names[key(e)][0]} {names[key(e)][1]}" for e in emoji]
        out.append('        """),')
    out += [
        "    ]",
        "",
        "    /// The skin-tone variants of the emoji shown, in Unicode's order. Each line is the base emoji (as the picker shows",
        "    /// it), the variant, the Unicode emoji version that added the variant, and its name.",
        '    static let skinTones = """',
    ]
    out += [f"        {shown[base]} {variant} {added} {name}" for base, variant, added, name in tones if base in shown]
    out += [
        '        """',
        "",
        "    /// CLDR's English keywords for each emoji shown, for search (read on the first search). Each line is the emoji",
        "    /// and its keywords, split by bars.",
        '    static let keywords = """',
    ]
    out += [f"        {e} {'|'.join(words.get(key(e), []))}" for e in shown.values()]
    out += ['        """', "}", ""]
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser(description="Writes Shared/EmojiData.swift.")
    parser.add_argument("emoji_test", help="Unicode's emoji-test.txt for one release, a path or a URL")
    parser.add_argument("--annotations", required=True, help="CLDR's English annotations.json for one release")
    parser.add_argument("--derived", required=True, help="CLDR's English annotationsDerived.json for the same release")
    args = parser.parse_args()
    texts = [read(source) for source in (args.emoji_test, args.annotations, args.derived)]
    with open(ORDER, encoding="utf-8") as f:
        order = f.read()
    # Each input as given (a local file by its name only, so the header is the same on every machine) and its SHA-256.
    sources = ["tools/emoji/apple-order.txt"] + [os.path.basename(s) if os.path.exists(s) else s
                                                 for s in (args.emoji_test, args.annotations, args.derived)]
    inputs = [(s, hashlib.sha256(t.encode("utf-8")).hexdigest()) for s, t in zip(sources, [order, *texts])]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(swift(*texts, inputs))
    print(f"wrote {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
