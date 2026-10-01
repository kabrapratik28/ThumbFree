#!/usr/bin/env python3
"""Makes git format-patch output pass git diff --check without changing what the patches do.

  tools/normalize-patches.py third_party/patches/00NN-*.patch

A blank context line becomes an empty line (git apply reads both the same), and a blank context line that ends the file
is dropped, with one line taken off its hunk's counts (less trailing context than leading is still a valid hunk).
"""
import re
import sys

for path in sys.argv[1:]:
    lines = open(path).read().split("\n")
    lines = ["" if line == " " else line for line in lines]
    if len(lines) > 1 and lines[-1] == "" and lines[-2] == "":
        lines.pop()
        for i in range(len(lines) - 1, -1, -1):
            m = re.match(r"^@@ -(\d+),(\d+) \+(\d+),(\d+) @@(.*)$", lines[i])
            if m:
                a, b, c, d, rest = m.groups()
                lines[i] = "@@ -%s,%d +%s,%d @@%s" % (a, int(b) - 1, c, int(d) - 1, rest)
                break
    open(path, "w").write("\n".join(lines))
