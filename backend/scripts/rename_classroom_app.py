#!/usr/bin/env python3
"""Rename Material Testing to Semper Classroom in stored records (ADR-030, TD-330).

Material Testing became Semper Classroom in 2026-10: its short name changed
from `materialtesting` to `semperclassroom` and its per-app field suffix from
`MaterialTesting` to `SemperClassroom` (`app/apps.py`). Stored records still
hold the old names. The backend reads both (`apps.canonical`,
`apps.read_field`) and writes both (`apps.written_fields`), so nothing breaks
without this; it is hygiene, and it makes the new fields complete so that
TD-330 can stop reading and writing the old ones.

  users/{uid}                   <name>SemperClassroom made equal to <name>MaterialTesting
  licenses/{id}                   "
  licenses/{id}/seats/{uid}       "
  devices/{id}, sessions/{sid}  app: materialtesting -> semperclassroom (--retag only)

The new field is copied where it is absent or differs from the old one, and
deleted where the old one is absent: the old field is the truth, the one every
backend writes and deletes and the only one `apps.read_field` reads, and a new
field a previous backend left behind is stale. The old field is never modified
or deleted. Documents whose new field differed or was deleted are counted and
listed. Audit and event collections are never touched: their rows are history.

`--retag` is TD-330's first step, not this rename's: a backend from before the
rename reads `semperclassroom` as an unknown app, so tags stay
`materialtesting` (`apps.stored_name`) while one could serve or be rolled
back to.

Run order:

  1. Dry-run, to see the counts:
         python scripts/rename_classroom_app.py --project indicvision-dic-app
  2. `--apply`, once the backend that dual-writes both names serves all
     traffic and rolling back past it is ruled out:
         python scripts/rename_classroom_app.py --project indicvision-dic-app --apply
  3. For TD-330, right before reads and tags move to the new names: `--apply`
     again, then `--apply --retag`.

Safe to re-run: a tag already renamed and a field already copied are skipped,
so a second run is a no-op. Each collection is walked by document id, PAGE
documents at a time, one batched write per page. On real Firestore every
update is conditional on the document not having changed since it was read;
a page whose batch fails that check (or names a document deleted meanwhile)
is read again and retried.

Staging and production run in this one project against the same default
database, so there is no separate staging run.
"""
from __future__ import annotations

import argparse
import functools
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from google.api_core.exceptions import FailedPrecondition, NotFound

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import apps  # noqa: E402

PAGE = 400
#: Reads of one page before a batch that keeps failing its preconditions is
#: allowed to raise.
ATTEMPTS = 3

OLD_SUFFIX = apps.legacy_field("", apps.SEMPER_CLASSROOM)  # "MaterialTesting"
NEW_SUFFIX = apps.field("", apps.SEMPER_CLASSROOM)  # "SemperClassroom"

#: The collections whose `app` tag is renamed.
TAGGED = ("devices", "sessions")
#: The collections whose suffixed fields are copied; seats are walked under
#: each licence (`licenses/{id}/seats`).
SUFFIXED = ("users", "licenses")
SEATS = "seats"

_PLAIN_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


@dataclass
class Tally:
    """What one collection's walk saw. `counts` keys: `scanned`, `changed`
    (documents written or that would be), and per kind: `retagged`,
    `already`, `other` for tags; `copied`, `present`, `differ`, `pruned` for
    fields."""

    counts: Counter = field(default_factory=Counter)
    #: Paths of documents whose new field differed from the old one, or had
    #: no old one.
    differ: list[str] = field(default_factory=list)

    def merge(self, other: "Tally") -> None:
        self.counts.update(other.counts)
        self.differ.extend(other.differ)


def is_legacy_tag(value) -> bool:
    """Whether a stored `app` value is Material Testing's old short name."""
    return isinstance(value, str) and value.strip().lower() == apps.LEGACY_MATERIAL_TESTING


def new_name(old: str) -> str | None:
    """The renamed field for a `...MaterialTesting` field, or None when the
    name is not one (or is not a plain identifier, which an update would read
    as a field path)."""
    if not old.endswith(OLD_SUFFIX) or old == OLD_SUFFIX or not _PLAIN_NAME.fullmatch(old):
        return None
    return old[: -len(OLD_SUFFIX)] + NEW_SUFFIX


def old_name(new: str) -> str | None:
    """The `...MaterialTesting` field a `...SemperClassroom` field mirrors, or
    None when the name is not one."""
    if not new.endswith(NEW_SUFFIX) or new == NEW_SUFFIX or not _PLAIN_NAME.fullmatch(new):
        return None
    return new[: -len(NEW_SUFFIX)] + OLD_SUFFIX


def retag_patch(doc, tally: Tally) -> dict | None:
    """The update that renames this document's `app` tag, or None."""
    body = doc.to_dict() or {}
    tally.counts["scanned"] += 1
    tag = body.get("app")
    if is_legacy_tag(tag):
        tally.counts["retagged"] += 1
        return {"app": apps.SEMPER_CLASSROOM}
    if tag == apps.SEMPER_CLASSROOM:
        tally.counts["already"] += 1
    else:
        tally.counts["other"] += 1
    return None


def copy_patch(doc, tally: Tally, delete_field) -> dict | None:
    """The update that copies each `...MaterialTesting` field this document
    has to its `...SemperClassroom` name where that is absent or differs, or
    None."""
    body = doc.to_dict() or {}
    tally.counts["scanned"] += 1
    patch: dict = {}
    differs = False
    for old, value in body.items():
        new = new_name(old)
        if new is None:
            continue
        if new not in body:
            patch[new] = value
            tally.counts["copied"] += 1
        elif body[new] == value:
            tally.counts["present"] += 1
        else:
            patch[new] = value
            tally.counts["differ"] += 1
            differs = True
    for new in body:
        old = old_name(new)
        if old is not None and old not in body:
            patch[new] = delete_field
            tally.counts["pruned"] += 1
            differs = True
    if differs:
        tally.differ.append(doc.reference.path)
    return patch or None


def _update(client, batch, doc, patch: dict) -> None:
    """Add `patch` to `batch`, on real Firestore only if `doc` is unchanged
    since it was read. The test double's snapshots carry no `update_time`."""
    update_time = getattr(doc, "update_time", None)
    if update_time is None:
        batch.update(doc.reference, patch)
    else:
        batch.update(doc.reference, patch,
                     option=client.write_option(last_update_time=update_time))


def pages(client, collection, plan, apply: bool, tally: Tally):
    """Walk `collection` by document id, PAGE at a time, writing each page's
    patches (from `plan(doc, tally)`) in one batch when `apply`. Yields each
    page once its batch is committed; its counts join `tally` then, so a
    retried page is counted once."""
    cursor = None
    while True:
        for attempt in range(ATTEMPTS):
            query = collection.order_by("__name__").limit(PAGE)
            if cursor is not None:
                query = query.start_after(cursor)
            docs = list(query.stream())
            if not docs:
                return
            seen = Tally()
            batch = client.batch()
            writes = 0
            for doc in docs:
                patch = plan(doc, seen)
                if patch is None:
                    continue
                seen.counts["changed"] += 1
                if apply:
                    _update(client, batch, doc, patch)
                    writes += 1
            try:
                if writes:
                    batch.commit()
            except (FailedPrecondition, NotFound):
                # Written or deleted since it was read: read the page again.
                if attempt == ATTEMPTS - 1:
                    raise
                continue
            break
        tally.merge(seen)
        yield docs
        cursor = docs[-1]


def rename(client, apply: bool, delete_field, retag: bool = False) -> dict[str, Tally]:
    """Make the new fields equal the old ones and, with `retag`, rename the
    tags. `delete_field` is the client's `DELETE_FIELD` sentinel. Returns a
    tally per collection, seats under the key `licenses/*/seats`."""
    copy = functools.partial(copy_patch, delete_field=delete_field)
    out: dict[str, Tally] = {}
    if retag:
        for name in TAGGED:
            out[name] = Tally()
            for _ in pages(client, client.collection(name), retag_patch, apply, out[name]):
                pass
    out["users"] = Tally()
    for _ in pages(client, client.collection("users"), copy, apply, out["users"]):
        pass
    out["licenses"] = Tally()
    seats = out["licenses/*/seats"] = Tally()
    for docs in pages(client, client.collection("licenses"), copy, apply, out["licenses"]):
        for licence in docs:
            for _ in pages(client, licence.reference.collection(SEATS), copy, apply, seats):
                pass
    return out


def report(tallies: dict[str, Tally], apply: bool) -> list[str]:
    """The lines `main` prints."""
    verb = "" if apply else "would be "
    lines = []
    for name, tally in tallies.items():
        c = tally.counts
        if name in TAGGED:
            lines.append(f"{name}: {c['scanned']} scanned, {c['retagged']} {verb}retagged "
                         f"{apps.LEGACY_MATERIAL_TESTING} -> {apps.SEMPER_CLASSROOM}, "
                         f"{c['already']} already {apps.SEMPER_CLASSROOM}, {c['other']} other.")
            continue
        lines.append(f"{name}: {c['scanned']} scanned, {c['changed']} {verb}changed: "
                     f"{c['copied']} field(s) {verb}copied *{OLD_SUFFIX} -> *{NEW_SUFFIX}, "
                     f"{c['present']} already present, {c['differ']} {verb}refreshed from the old field, "
                     f"{c['pruned']} {verb}deleted (no old field).")
        if tally.differ:
            shown = ", ".join(tally.differ[:10])
            more = f" and {len(tally.differ) - 10} more" if len(tally.differ) > 10 else ""
            lines.append(f"  differ: {shown}{more}")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="write (default: dry-run)")
    parser.add_argument("--retag", action="store_true",
                        help="also rename the app tags: TD-330 only, once no backend "
                             "from before the rename can serve")
    args = parser.parse_args()

    from google.cloud import firestore

    tallies = rename(firestore.Client(project=args.project), args.apply,
                     firestore.DELETE_FIELD, retag=args.retag)
    for line in report(tallies, args.apply):
        print(line)
    if not args.apply:
        print("Dry-run: nothing written. Pass --apply to write.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
