#!/usr/bin/env python3
"""Leak scan for this public repository: tracked text files and commit messages must not carry internal values.

Usage: python3 tools/leak_scan.py [<base>..<head>]
  Scans every tracked text file at HEAD, and the messages of the commits in the given range (or HEAD alone).
  Extra private patterns (one regex per line) may come from the environment variable LEAK_SCAN_EXTRA; they are kept
  out of the repository on purpose, because they are themselves the internal values. Without it, that part is
  reported as SKIPPED (not as passed).
Exit 0 clean, 1 on any hit (path:line rule — the matched text is not printed), 2 on usage errors.
"""
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

RULES = [
    # LAN host addresses (192.168.x.y). A range written as CIDR (address/prefix, e.g. network policy except lists) is
    # not a host address and is allowed. 10.x test addresses (trusted-proxy fixtures, the K3s default pod range) are
    # synthetic; the real host address is one of the private patterns below.
    ("lan-ipv4", re.compile(r"(?<![\d.])192\.168\.\d{1,3}\.\d{1,3}(?![\d.]|/\d)")),
    ("home-path", re.compile(r"/home/[A-Za-z0-9_.-]+|/Users/[A-Za-z0-9_.-]+/|[A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9_.-]+")),
    ("github-token", re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}|\bgithub_pat_[A-Za-z0-9_]{30,}")),
    ("aws-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("slack-token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("jwt-like-token", re.compile(r"\beyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}")),
    ("bearer-token", re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]{30,}")),
    ("command-id", re.compile(r"\bCMD-[0-9a-f]{12}\b")),
    ("decision-number", re.compile(r"\bF1\d(?:-[A-Z][A-Z0-9-]*)?\b")),
    ("work-tool-name", re.compile(r"\bOMB\b|\bomb-k3s\b|\b[Cc]laude\b|\b[Cc]odex\b")),
]


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True).stdout


def extra_rules():
    raw = os.environ.get("LEAK_SCAN_EXTRA", "")
    rules = []
    for i, line in enumerate(raw.splitlines(), 1):
        line = line.strip()
        if line and not line.startswith("#"):
            try:
                rules.append((f"private-pattern-{i}", re.compile(line, re.I)))
            except re.error:
                print(f"LEAK_SCAN_EXTRA line {i} is not a valid regex", file=sys.stderr)
                sys.exit(2)
    return rules


def scan(label, text, rules, hits):
    for n, line in enumerate(text.splitlines(), 1):
        for name, rx in rules:
            if rx.search(line):
                hits.append(f"{label}:{n} {name}")


def main(argv):
    if len(argv) > 1:
        print(__doc__, file=sys.stderr)
        return 2
    extra = extra_rules()
    rules = RULES + extra
    hits = []
    files = [f for f in git("ls-files", "-z").decode("utf-8").split("\0") if f]
    scanned = 0
    for path in files:
        try:
            data = open(path, "rb").read()
        except OSError:
            continue
        if b"\0" in data[:8192]:
            continue  # binary (Parquet and other data files)
        scanned += 1
        scan(path, data.decode("utf-8", "replace"), rules, hits)
    rng = argv[0] if argv else "HEAD^!"
    try:
        shas = git("rev-list", rng).decode().split() if ".." in rng or rng.endswith("^!") else [rng]
    except subprocess.CalledProcessError:
        shas = git("rev-list", "-1", "HEAD").decode().split()
    for sha in shas:
        scan(f"commit {sha[:12]} message", git("log", "-1", "--format=%B", sha).decode("utf-8", "replace"), rules, hits)
    for h in hits:
        print(h)
    print(f"leak-scan: {scanned} text files, {len(shas)} commit messages, {len(RULES)} public rules, "
          f"{len(extra)} private rules")
    if not extra:
        print("leak-scan: SKIPPED private patterns (LEAK_SCAN_EXTRA not set) — host name and internal ports "
              "were NOT checked in this run")
        if os.environ.get("GITHUB_ACTIONS"):
            print("::warning title=leak-scan::private patterns skipped (LEAK_SCAN_EXTRA not set)")
    print(f"leak-scan: {'clean' if not hits else f'{len(hits)} hits'}")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
