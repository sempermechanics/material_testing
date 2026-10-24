#!/usr/bin/env python3
"""The engine gitlink is a tagged engine release and never moves backwards.

TE2-006: the `engine` submodule pin is the only link between this app and the
engine's own CI, which tests tagged releases. A pin to an untagged commit, or
one older than (or off the line of) the previous pin, ships engine code that
no engine release vouches for; MT-TE2-001 did both.

Checks the gitlink at --rev (default HEAD) against the one at --base (default
the first parent of --rev; in a pull request's merge commit that is the base
branch tip):

- the new pin must carry a `v*` tag in the engine repository;
- if it changed, it must be a descendant of the base pin. A deliberate
  downgrade passes with --allow-downgrade (CI: the PR label
  `engine-downgrade`).

Commits are looked up in --engine-git DIR, or else in a treeless bare clone of
the `.gitmodules` URL in a temporary directory. Stdlib only (and git).

Exit codes: 0 pass, 1 fail, 2 cannot check.
Usage: python3 scripts/check_engine_pin.py [--repo DIR] [--rev REV]
       [--base REV] [--engine-git DIR] [--allow-downgrade]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile

SUBMODULE = "engine"


class CheckError(Exception):
    """The check cannot run (exit 2)."""


def _git(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(["git", *args], capture_output=True, text=True, check=False)
    if check and proc.returncode != 0:
        raise CheckError(f"git {' '.join(args)}: {proc.stderr.strip()}")
    return proc


def gitlink(repo: str, rev: str) -> str | None:
    """The engine commit pinned at [rev], or None when [rev] or its gitlink is absent."""
    if _git("-C", repo, "rev-parse", "-q", "--verify", f"{rev}^{{commit}}", check=False).returncode != 0:
        return None
    out = _git("-C", repo, "ls-tree", rev, SUBMODULE).stdout.strip()
    if not out:
        return None
    mode, kind, rest = out.split(" ", 2)
    if mode != "160000" or kind != "commit":
        raise CheckError(f"{SUBMODULE} at {rev} is a {kind}, not a gitlink")
    return rest.split("\t", 1)[0]


def submodule_url(repo: str, rev: str) -> str:
    return _git("-C", repo, "config", "--blob", f"{rev}:.gitmodules", f"submodule.{SUBMODULE}.url").stdout.strip()


def tags_at(engine: str, sha: str) -> list[str]:
    out = _git("-C", engine, "tag", "--points-at", sha, "--list", "v*").stdout
    return sorted(out.split())


def is_ancestor(engine: str, older: str, newer: str) -> bool:
    return _git("-C", engine, "merge-base", "--is-ancestor", older, newer, check=False).returncode == 0


def label(engine: str, sha: str) -> str:
    tags = tags_at(engine, sha)
    return f"{sha[:12]} ({', '.join(tags)})" if tags else f"{sha[:12]} (untagged)"


def evaluate(engine: str, new: str, base: str | None, *, allow_downgrade: bool) -> tuple[bool, list[str]]:
    lines: list[str] = []
    if _git("-C", engine, "cat-file", "-e", f"{new}^{{commit}}", check=False).returncode != 0:
        return False, [f"FAIL engine pin {new[:12]} is not a commit of the engine repository"]
    tags = tags_at(engine, new)
    ok = True
    if tags:
        lines.append(f"PASS engine pin {label(engine, new)} is a tagged engine release")
    else:
        ok = False
        lines.append(f"FAIL engine pin {new[:12]} is not a tagged engine release (no v* tag points at it)")
    if base is None:
        lines.append("PASS no engine pin at the base; only the tag is checked")
    elif base == new:
        lines.append("PASS engine pin unchanged")
    elif is_ancestor(engine, base, new):
        lines.append(f"PASS engine pin moves forward from {label(engine, base)}")
    else:
        backwards = is_ancestor(engine, new, base)
        what = "moves backwards from" if backwards else "is not a descendant of"
        if allow_downgrade:
            lines.append(f"PASS engine pin {what} {label(engine, base)}; allowed by --allow-downgrade")
        else:
            ok = False
            lines.append(f"FAIL engine pin {what} {label(engine, base)}; label the PR engine-downgrade if intended")
    return ok, lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--repo", default=".", help="superproject checkout (default: .)")
    parser.add_argument("--rev", default="HEAD", help="revision to check (default: HEAD)")
    parser.add_argument("--base", help="revision to compare with (default: first parent of --rev)")
    parser.add_argument("--engine-git", help="engine repository holding the commits and tags")
    parser.add_argument("--allow-downgrade", action="store_true", help="a backwards or sideways pin passes")
    args = parser.parse_args(argv)
    try:
        new = gitlink(args.repo, args.rev)
        if new is None:
            raise CheckError(f"no engine gitlink at {args.rev}")
        base = gitlink(args.repo, args.base or f"{args.rev}^1")
        with tempfile.TemporaryDirectory() as tmp:
            engine = args.engine_git
            if engine is None:
                engine = f"{tmp}/engine.git"
                _git("clone", "-q", "--bare", "--filter=tree:0", submodule_url(args.repo, args.rev), engine)
            ok, lines = evaluate(engine, new, base, allow_downgrade=args.allow_downgrade)
    except CheckError as exc:
        print(f"ERROR {exc}", file=sys.stderr)
        return 2
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
