#!/usr/bin/env python3
"""Idempotently add/advance Firestore schemaVersion fields.

Dry-run is the default. This is intentionally not a SQL-style migration
framework: Firestore rollback is restore-from-export plus application rollback.
"""
import argparse
import sys
from pathlib import Path

from google.cloud import firestore
from google.cloud.firestore_v1.field_path import FieldPath

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.firestore_repo import SCHEMA_VERSION  # noqa: E402

COLLECTIONS = ("users", "devices", "challenges", "sessions", "files", "audit_logs")
BATCH_LIMIT = 400


def migrate_collection(client, name: str, apply: bool) -> tuple[int, int]:
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
            current = (doc.to_dict() or {}).get("schemaVersion", 0)
            if not isinstance(current, int) or current < SCHEMA_VERSION:
                changed += 1
                page_changed += 1
                if apply:
                    batch.update(doc.reference, {"schemaVersion": SCHEMA_VERSION})
        if apply and page_changed:
            batch.commit()
        cursor = docs[-1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="perform writes (default: dry-run)")
    args = parser.parse_args()
    client = firestore.Client(project=args.project)
    total = 0
    for collection in COLLECTIONS:
        scanned, changed = migrate_collection(client, collection, args.apply)
        total += changed
        print(f"{collection}: scanned={scanned} would_change={changed}")
    print(f"{'changed' if args.apply else 'dry-run changes'}={total} target={SCHEMA_VERSION}")


if __name__ == "__main__":
    main()
