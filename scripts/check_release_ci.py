#!/usr/bin/env python3
"""Fail a release unless the commit's full-matrix CI passed (readiness TE6-009).

The Release workflow re-runs legal, Android tier 1 and the backend gate, but
not the secret scan, the console pages, the emulator tier, the Firestore rules
or the signed-release tier; "green full-matrix CI OK on the commit" was a line
in RELEASING.md. This reads the check runs GitHub holds for the commit and
requires, for each job in REQUIRED, that its latest run (highest id) completed
with `success`. `skipped` is not enough: a push to main runs the emulator and
release tiers only when its diff touches them, so dispatch CI with `full_ci`
on the commit (or use its weekly run) before releasing.

Input is `gh api --paginate --slurp repos/OWNER/REPO/commits/SHA/check-runs`
(a list of pages), or a single page object. Stdlib only.

Usage: python3 scripts/check_release_ci.py --runs runs.json [--sha SHA]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

#: ci.yml job names (`name:`), which are the check-run names.
REQUIRED = (
    "Secret scan (full history)",
    "Legal pages match their source",
    "Console pages",
    "Console accessibility",
    "Engine pin is a tagged release",
    "Engine host tests (ASan+UBSan)",
    "Tier 1 — Static checks",
    "Tier 1 — App fast",
    "Tier 3 — Emulator E2E",
    "Tier 4 — Backend",
    "Firestore rules",
    "Tier 5 — Signed release",
    "CI OK",
)


def _runs(payload) -> list[dict]:
    pages = payload if isinstance(payload, list) else [payload]
    return [run for page in pages for run in (page or {}).get("check_runs", [])]


def evaluate(payload, required: tuple[str, ...] = REQUIRED) -> list[str]:
    """One line per required job whose latest check run is not a success."""
    latest: dict[str, dict] = {}
    for run in _runs(payload):
        name = run.get("name")
        if name in required and (name not in latest or run.get("id", 0) > latest[name].get("id", 0)):
            latest[name] = run
    problems = []
    for name in required:
        run = latest.get(name)
        if run is None:
            problems.append(f"{name}: no check run on this commit")
        elif run.get("status") != "completed":
            problems.append(f"{name}: {run.get('status')} (check run {run.get('id')})")
        elif run.get("conclusion") != "success":
            problems.append(f"{name}: {run.get('conclusion')} (check run {run.get('id')})")
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--runs", type=Path, required=True, help="check runs JSON (gh api --paginate --slurp)")
    ap.add_argument("--sha", default="the commit", help="the commit, for messages")
    args = ap.parse_args(argv)
    problems = evaluate(json.loads(args.runs.read_text(encoding="utf-8")))
    if problems:
        print(f"Full-matrix CI has not passed on {args.sha}:", file=sys.stderr)
        for line in problems:
            print(f"  {line}", file=sys.stderr)
        print("Dispatch Actions -> CI -> Run workflow on main with full_ci ticked, wait for CI OK, "
              "then run the release again (RELEASING.md, Release checklist step 2).", file=sys.stderr)
        return 1
    print(f"Full-matrix CI passed on {args.sha}: {', '.join(REQUIRED)}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
