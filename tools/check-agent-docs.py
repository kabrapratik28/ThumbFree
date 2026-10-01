#!/usr/bin/env python3
"""Checks the AGENTS.md and CLAUDE.md files against the rules in the root AGENTS.md. Standard library only.

  tools/check-agent-docs.py              checks this repository; prints each violation and exits 1 if there is any
  tools/check-agent-docs.py --self-test  proves that each rule fails when broken, on made-up files

The files are the ones git would commit (tracked, or new and not ignored), so the submodule and ignored folders are
never checked. The rules:
  root-size   the root AGENTS.md exists and has at most 200 lines
  folder-size every other AGENTS.md has at most 60 lines
  pointer     every CLAUDE.md is exactly the one line @AGENTS.md
  pair        every AGENTS.md has a CLAUDE.md beside it, and every CLAUDE.md an AGENTS.md
  private     no email address, home folder path, device serial, secret, private URL or path into a private folder
  repeat      a folder AGENTS.md repeats no line of the root AGENTS.md (lines of 40 characters or more)
  link        every relative markdown link points to a file or folder that exists
The last rule in the root file, updating a folder's AGENTS.md in the same change as its rules, is for review: no script
can tell a change of rules from a change of code.
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT_MAX, FOLDER_MAX, REPEAT_MIN = 200, 60, 40
POINTER = "@AGENTS.md"
PRIVATE = [
    ("an email address", re.compile(r"\b[\w.+-]+@[\w-]+(\.[\w-]+)*\.[A-Za-z]{2,}\b")),
    ("a home folder path", re.compile(r"(/Users/|/home/|[A-Za-z]:\\Users\\)[\w.-]+")),
    ("a device serial", re.compile(r"\b(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{12,16}\b")),
    ("a secret", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----|\b(ghp_|github_pat_|hf_|sk-|xox[abp]-|AKIA|AIza)[\w-]{16,}")),
    ("a secret", re.compile(r"(?i)\b(password|passwd|api[_-]?key|secret|token)\s*[:=]\s*\S{6,}")),
    ("a private URL", re.compile(r"(?i)\bfile://|https?://(localhost|127\.|10\.|192\.168\.|[\w.-]+\.(local|internal|corp|lan)\b)")),
    ("a path into a private folder", re.compile(r"(\.superpowers|testdata/private)/[\w.-]+")),
]
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")


def repo_files(root):
    """Paths relative to root that git would commit: tracked files, and new ones git does not ignore."""
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "--cached", "--others", "--exclude-standard"],
                             capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        sys.exit(f"needs git to list the repository's files: {e}")
    return [p for p in out.splitlines() if os.path.isfile(os.path.join(root, p))]


def norm(line):
    return re.sub(r"\s+", " ", re.sub(r"^\s*([-*]|\d+\.)\s+", "", line)).strip().lower()


def check(root, files):
    """[(path, rule, message)] for the agent files among files."""
    agents = sorted(f for f in files if os.path.basename(f) == "AGENTS.md")
    claudes = sorted(f for f in files if os.path.basename(f) == "CLAUDE.md")
    read = lambda f: open(os.path.join(root, f), encoding="utf-8").read()
    out = []
    if "AGENTS.md" not in agents:
        out.append(("AGENTS.md", "root-size", "the root AGENTS.md is missing"))
    root_lines = {norm(l) for l in read("AGENTS.md").splitlines()} if "AGENTS.md" in agents else set()
    for f in agents:
        text = read(f)
        lines = text.splitlines()
        limit, rule = (ROOT_MAX, "root-size") if f == "AGENTS.md" else (FOLDER_MAX, "folder-size")
        if len(lines) > limit:
            out.append((f, rule, f"{len(lines)} lines, at most {limit}"))
        if os.path.join(os.path.dirname(f), "CLAUDE.md") not in claudes:
            out.append((f, "pair", "no CLAUDE.md beside it"))
        if f != "AGENTS.md":
            for n, line in enumerate(lines, 1):
                if len(norm(line)) >= REPEAT_MIN and norm(line) in root_lines:
                    out.append((f"{f}:{n}", "repeat", "repeats a line of the root AGENTS.md"))
        for n, line in enumerate(lines, 1):
            for target in LINK.findall(line):
                path = target.split("#")[0]
                if path and not re.match(r"[a-z]+:", path) and not os.path.exists(
                        os.path.normpath(os.path.join(root, os.path.dirname(f), path))):
                    out.append((f"{f}:{n}", "link", f"{target} does not exist"))
    for f in claudes:
        if read(f) not in (POINTER, POINTER + "\n"):
            out.append((f, "pointer", f"must be exactly the one line {POINTER}"))
        if os.path.join(os.path.dirname(f), "AGENTS.md") not in agents:
            out.append((f, "pair", "no AGENTS.md beside it"))
    for f in agents + claudes:
        for n, line in enumerate(read(f).splitlines(), 1):
            for what, pattern in PRIVATE:
                m = pattern.search(line)
                if m:
                    out.append((f"{f}:{n}", "private", f"{what}: {m.group(0)}"))
    return out


def self_test():
    """Each rule, broken on its own in a tree that otherwise passes, is reported by name."""
    good = {
        "AGENTS.md": "# Root\n\nPlanning goes in `.superpowers/`, which git ignores. See [tools](tools/AGENTS.md).\n"
                     "Every device command names its serial, such as emulator-5554.\n",
        "CLAUDE.md": POINTER + "\n",
        "tools/AGENTS.md": "# Tools\n\nScripts take the serial as their first argument. See [the root](../AGENTS.md).\n",
        "tools/CLAUDE.md": POINTER + "\n",
    }
    root_line = "Every device command names its serial, such as emulator-5554."
    broken = [
        ("root-size", {"AGENTS.md": good["AGENTS.md"] + "x\n" * ROOT_MAX}),
        ("folder-size", {"tools/AGENTS.md": good["tools/AGENTS.md"] + "x\n" * FOLDER_MAX}),
        ("pointer", {"tools/CLAUDE.md": "See AGENTS.md for the rules.\n"}),
        ("pointer", {"tools/CLAUDE.md": POINTER + "\nMore notes.\n"}),
        ("pair", {"docs/AGENTS.md": "# Docs\n"}),
        ("pair", {"docs/CLAUDE.md": POINTER + "\n"}),
        ("private", {"tools/AGENTS.md": good["tools/AGENTS.md"] + "Ask someone@example.com.\n"}),
        ("repeat", {"tools/AGENTS.md": good["tools/AGENTS.md"] + root_line + "\n"}),
        ("link", {"tools/AGENTS.md": good["tools/AGENTS.md"] + "See [the plan](../docs/plan.md).\n"}),
    ]
    private_lines = ["/Users/someone/src", "adb -s 1A2B3C4D5E6F7G", "hf_" + "a" * 30, "token = abcdef123456",
                     "http://localhost:8080/x", "`.superpowers/plans/task.md`", "testdata/private/clip.wav"]
    missing_root = dict(good)
    del missing_root["AGENTS.md"], missing_root["CLAUDE.md"]

    def run(files):
        with tempfile.TemporaryDirectory() as root:
            for path, text in files.items():
                os.makedirs(os.path.dirname(os.path.join(root, path)), exist_ok=True)
                open(os.path.join(root, path), "w", encoding="utf-8").write(text)
            return check(root, list(files))

    assert run(good) == [], run(good)
    for rule, change in broken:
        rules = {r for _, r, _ in run({**good, **change})}
        assert rules == {rule}, (rule, rules)
    for line in private_lines:
        rules = {r for _, r, _ in run({**good, "tools/AGENTS.md": good["tools/AGENTS.md"] + line + "\n"})}
        assert rules == {"private"}, (line, rules)
    assert "root-size" in {r for _, r, _ in run(missing_root)}
    print(f"self-test: pass (every rule broken on its own: {len(broken)} cases, and {len(private_lines)} more private ones)")


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if sys.argv[1:]:
        sys.exit(__doc__)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = repo_files(root)
    violations = check(root, files)
    for path, rule, message in violations:
        print(f"{path}: {rule}: {message}")
    n = sum(os.path.basename(f) in ("AGENTS.md", "CLAUDE.md") for f in files)
    if violations:
        sys.exit(f"{len(violations)} violation(s) in {n} agent files")
    print(f"agent docs: {n} files, every rule passes")


if __name__ == "__main__":
    main()
