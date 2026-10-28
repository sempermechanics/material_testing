"""The app's OpenAPI document is committed, and any change to it is a reviewed diff.

`contracts/app-openapi.json` is `app.openapi()` written with sorted keys. This
test regenerates it and fails on any difference, so a removed route, a renamed
field or a new required request field cannot reach a deploy without showing up
in the PR (ADR-029). Production serves no /openapi.json (main.py), so this file
is the only copy anyone can diff.

Changed the API on purpose: rewrite the snapshot and commit it with the change,

    UPDATE_OPENAPI_SNAPSHOT=1 python -m pytest tests/test_openapi_snapshot.py

and say in the PR whether the diff breaks a shipped client (ADR-029 lists what
counts). The rewrite refuses a breaking change that
`contracts/app-openapi.breaking.txt` does not declare, and
`test_openapi_breaking.py` classifies the diff on every run. The app and the consoles read response bodies the snapshot does not
type yet (no route declares a response_model); `test_wire_contracts.py` pins
those that the Android app reads.
"""
from __future__ import annotations

import difflib
import json
import os
from pathlib import Path

import openapi_diff
from app.main import app

SNAPSHOT = Path(__file__).resolve().parents[1] / "contracts" / "app-openapi.json"
UPDATE_ENV = "UPDATE_OPENAPI_SNAPSHOT"


def render() -> str:
    """The document as committed: sorted keys, two-space indent, LF, final newline."""
    return json.dumps(app.openapi(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def test_openapi_matches_the_committed_snapshot():
    current = render()
    if os.environ.get(UPDATE_ENV) == "1":
        if SNAPSHOT.is_file():
            undeclared = sorted(set(openapi_diff.breaking_changes(
                json.loads(SNAPSHOT.read_text(encoding="utf-8")), json.loads(current),
            )) - openapi_diff.acknowledged())
            assert not undeclared, (
                "Not rewritten: breaking API changes (ADR-029). Declare each line in "
                "contracts/app-openapi.breaking.txt first:\n  " + "\n  ".join(undeclared))
        SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
        SNAPSHOT.write_text(current, encoding="utf-8", newline="\n")
    committed = SNAPSHOT.read_text(encoding="utf-8") if SNAPSHOT.is_file() else ""
    if current != committed:
        diff = "".join(difflib.unified_diff(
            committed.splitlines(keepends=True), current.splitlines(keepends=True),
            fromfile="contracts/app-openapi.json (committed)", tofile="app.openapi() (now)",
            n=2,
        ))
        lines = diff.splitlines()
        shown = "\n".join(lines[:80]) + (f"\n... {len(lines) - 80} more lines" if len(lines) > 80 else "")
        raise AssertionError(
            "The API surface changed. If that is intended, rewrite the snapshot with "
            f"{UPDATE_ENV}=1 and say in the PR whether it breaks a shipped client (ADR-029).\n"
            + shown
        )


def test_the_snapshot_is_deterministic():
    # A second render in the same process must be byte-identical, or the
    # snapshot would flap on dict order rather than on a real change.
    app.openapi_schema = None
    first = render()
    app.openapi_schema = None
    assert render() == first
