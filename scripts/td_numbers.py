#!/usr/bin/env python3
"""Turn semperdic-app's TD numbers into this repo's (ADR-032).

This repo's TD numbers restarted at TD-1 on 2026-10-27; until then they were
shared with semperdic-app, which keeps the old ones. ``docs/ops/td-renumber-2026-10-27.csv``
maps every old number this repo cited to its new one, and says whether
semperdic-app uses that old number for the same row (``same_upstream``; not for
TD-199 or TD-349–TD-353). A sync runs upstream text through ``translate`` before
applying it, so the code and docs it brings cite this repo's rows:

  git diff OLD NEW -- PATHS | python scripts/td_numbers.py translate > upstream.patch

``TD-a–TD-b`` covers the mapped numbers from a to b, written as runs (they need
not be consecutive here). ``#…-td-n`` heading anchors follow their numbers. A
number the map cannot use (semperdic-app took it after 2026-10-26, its row there
differs, or this repo never cited it) becomes ``semperdic-app's TD-n``, and each one is listed on
stderr: give it a row here if this repo carries it. Never run it on this repo's
own files, whose numbers are already the new ones.

Usage:
  python scripts/td_numbers.py translate [--map CSV] < upstream.txt > here.txt
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

MAP = Path(__file__).resolve().parents[1] / "docs" / "ops" / "td-renumber-2026-10-27.csv"

# Not inside a word; a JSON escape such as `\n` just before is fine.
_START = r"(?:(?<=\\[ntr])|(?<![\w]))"
_ONE = rf"{_START}TD-(?P<n>\d+)(?!\d)"
_TD = re.compile(
    rf"(?P<rng>{_START}TD-(?P<a>\d+)(?P<dash>\s*(?:–|to|through)\s*)TD-(?P<b>\d+)(?!\d))|(?P<one>{_ONE})"
)
_ANCHOR = re.compile(r"(?<=[#-])td-(\d+)(?=[-)\]#]|\b)")


def load_map(path: Path, upstream_only: bool = True) -> dict[int, int]:
    """Old number to new; by default only the numbers semperdic-app uses for the same row."""
    with path.open(encoding="utf-8", newline="") as fh:
        return {
            int(r["old"][3:]): int(r["new"][3:])
            for r in csv.DictReader(fh)
            if not upstream_only or r.get("same_upstream", "yes") == "yes"
        }


def _runs(news: list[int], dash: str) -> str:
    out, start, prev = [], news[0], news[0]
    for n in news[1:] + [None]:
        if n is not None and n == prev + 1:
            prev = n
            continue
        out.append(f"TD-{start}" if start == prev else f"TD-{start}{dash}TD-{prev}")
        if n is not None:
            start = prev = n
    return ", ".join(out)


def translate(text: str, mapping: dict[int, int]) -> tuple[str, list[int]]:
    """The text with every TD number mapped, and the numbers the map lacked."""
    unknown: set[int] = set()

    def one(n: int) -> str:
        if n in mapping:
            return f"TD-{mapping[n]}"
        unknown.add(n)
        return f"semperdic-app's TD-{n}"

    def sub(m: re.Match) -> str:
        if m.group("one"):
            return one(int(m.group("n")))
        a, b = int(m.group("a")), int(m.group("b"))
        if a in mapping and b in mapping:
            return _runs(sorted(mapping[o] for o in mapping if a <= o <= b), m.group("dash"))
        return f"{one(a)}{m.group('dash')}{one(b)}"

    out = _TD.sub(sub, text)
    out = _ANCHOR.sub(lambda m: f"td-{mapping.get(int(m.group(1)), int(m.group(1)))}", out)
    return out, sorted(unknown)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    tr = sub.add_parser("translate", help="map upstream TD numbers on stdin to this repo's, on stdout")
    tr.add_argument("--map", type=Path, default=MAP)
    args = parser.parse_args(argv)
    text = sys.stdin.buffer.read().decode("utf-8")
    out, unknown = translate(text, load_map(args.map))
    sys.stdout.buffer.write(out.encode("utf-8"))
    for n in unknown:
        print(f"not in the map, written as semperdic-app's TD-{n}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
