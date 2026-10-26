#!/usr/bin/env python3
"""Tie the release tag to the commit the Release workflow builds (TE6-008).

release.yml checks out github.sha (main's head at dispatch) and publishes the
GitHub Release under the free-text `version` input. Nothing compared the two:
a tag pushed on an older commit would label a build of a newer one, and a
malformed input went straight into versionName. This fails when:

  * `version` is not `v<major>.<minor>[.<patch>][-beta.<n>]` (v1.2-beta.5, v1.2);
  * the tag exists and does not point at `--sha` (lightweight or annotated).

A tag that does not exist yet passes: the publish job creates it at the built
commit (`target_commitish: github.sha`). Needs the repository's tags
(actions/checkout with fetch-depth: 0). Stdlib only.

Usage: python3 scripts/check_release_tag.py --version v1.2-beta.5 --sha SHA [--repo .]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

VERSION_RE = re.compile(r"v[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-beta\.[0-9]+)?")


def tag_commit(version: str, repo: Path) -> str | None:
    """The commit `refs/tags/<version>` points at (peeled), or None."""
    proc = subprocess.run(["git", "-C", str(repo), "rev-parse", "-q", "--verify", f"refs/tags/{version}^{{commit}}"],
                          capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        return None
    return proc.stdout.strip() or None


def check(version: str, sha: str, *, repo: Path = Path(".")) -> tuple[bool, str]:
    if not VERSION_RE.fullmatch(version):
        return False, (f"{version!r} is not a release version: want v<major>.<minor>[.<patch>][-beta.<n>], "
                       "e.g. v1.2-beta.5 (RELEASING.md, Versioning)")
    at = tag_commit(version, repo)
    if at is None:
        return True, f"tag {version} does not exist yet; publish creates it at {sha[:12]} (target_commitish)"
    if at != sha:
        return False, (f"tag {version} points at {at[:12]}, but this run builds {sha[:12]}: release the "
                       "commit the tag names (dispatch on it), or move the tag deliberately")
    return True, f"tag {version} points at the built commit {sha[:12]}"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--version", required=True, help="the Release workflow's version input")
    ap.add_argument("--sha", required=True, help="the commit being built (GITHUB_SHA)")
    ap.add_argument("--repo", type=Path, default=Path("."))
    args = ap.parse_args(argv)
    ok, why = check(args.version, args.sha, repo=args.repo)
    print(why, file=sys.stdout if ok else sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
