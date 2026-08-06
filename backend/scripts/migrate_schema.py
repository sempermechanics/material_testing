#!/usr/bin/env python3
"""Apply pending Firestore migrations, recording each in a ledger.

Dry-run is the default; `--apply` writes. Firestore rollback is still
restore-from-export plus application rollback (see FIRESTORE_SCHEMA_RUNBOOK.md)
— the ledger tells you *what* to roll back to, it does not undo writes.

Idempotent at two levels:
  * a migration already in the `_migrations` ledger is skipped entirely;
  * within a migration, documents already at the target version are skipped,
    so an interrupted run resumes rather than restarting.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from google.cloud import firestore
from google.cloud.firestore_v1.field_path import FieldPath

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import migrations  # noqa: E402
from app.firestore_repo import SCHEMA_VERSION  # noqa: E402

LEDGER = "_migrations"
BATCH_LIMIT = 400


def applied_migrations(client) -> set[str]:
    """Names of migrations recorded complete. An interrupted run leaves its
    entry without completedAt, so it is retried rather than assumed done."""
    out = set()
    for doc in client.collection(LEDGER).stream():
        data = doc.to_dict() or {}
        if data.get("completedAt") is not None:
            out.add(doc.id)
    return out


def _run_collection(client, migration, name: str, apply: bool) -> tuple[int, int]:
    """Walk one collection, cursor-paged. Returns (scanned, changed)."""
    scanned = changed = 0
    cursor = None
    while True:
        query = client.collection(name).order_by(FieldPath.document_id()).limit(BATCH_LIMIT)
        if cursor is not None:
            query = query.start_after(cursor)
        docs = list(query.stream())
        if not docs:
            return scanned, changed

        batch = client.batch()
        page_changed = 0
        for doc in docs:
            scanned += 1
            body = doc.to_dict() or {}
            current = body.get("schemaVersion", 0)
            if not isinstance(current, int):
                current = 0
            if current >= migration.to_version:
                continue  # already migrated: resume-safe
            update = migration.transform(body)
            if update is None:
                continue
            changed += 1
            page_changed += 1
            if apply:
                batch.update(doc.reference, {**update, "schemaVersion": migration.to_version})
        if apply and page_changed:
            batch.commit()
        cursor = docs[-1]


def run_migration(client, migration, apply: bool) -> dict:
    ledger_ref = client.collection(LEDGER).document(migration.name)
    if apply:
        ledger_ref.set({
            "name": migration.name,
            "fromVersion": migration.from_version,
            "toVersion": migration.to_version,
            "collections": list(migration.collections),
            "startedAt": firestore.SERVER_TIMESTAMP,
            "completedAt": None,
        })

    totals = {"scanned": 0, "changed": 0}
    for collection in migration.collections:
        scanned, changed = _run_collection(client, migration, collection, apply)
        totals["scanned"] += scanned
        totals["changed"] += changed
        print(f"  {collection}: scanned={scanned} "
              f"{'changed' if apply else 'would_change'}={changed}")

    if apply:
        # Written only after every collection succeeded — a crash mid-run leaves
        # completedAt null so the next run picks the migration up again.
        ledger_ref.update({
            "completedAt": firestore.SERVER_TIMESTAMP,
            "scanned": totals["scanned"],
            "changed": totals["changed"],
        })
    return totals


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="perform writes (default: dry-run)")
    args = parser.parse_args()

    chain = migrations.discover()
    if chain and chain[-1].to_version != SCHEMA_VERSION:
        raise SystemExit(
            f"app.firestore_repo.SCHEMA_VERSION is {SCHEMA_VERSION} but the "
            f"migration chain ends at {chain[-1].to_version}. Deploying code and "
            "migrations that disagree writes documents at a version no migration "
            "produces. Fix one of them before running."
        )

    client = firestore.Client(project=args.project)
    done = applied_migrations(client)
    todo = migrations.pending(done)
    if not todo:
        print(f"Nothing to do — {len(done)} migration(s) already applied, at v{SCHEMA_VERSION}.")
        return

    grand_total = 0
    for migration in todo:
        print(f"{migration.name}: v{migration.from_version} -> v{migration.to_version}")
        grand_total += run_migration(client, migration, args.apply)["changed"]
    verb = "changed" if args.apply else "dry-run changes"
    print(f"{verb}={grand_total} target=v{SCHEMA_VERSION}")


if __name__ == "__main__":
    main()
