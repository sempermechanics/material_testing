#!/usr/bin/env python3
"""Check that every licence document can appear in the staff desk's list.

`GET /v1/admin/licenses` filters on `mode` and `status` and orders by
`createdAt` (`repo/license_admin.list_licenses`). Firestore leaves a document
out of such a query when it lacks a filtered or ordered field, so a licence
missing any of the three would vanish from the desk without an error.
Migration 002 backfilled `mode`, and `_write_license` stamps all three, so
this should report nothing; run it once before deploying the query.

Read-only. Costs one read per licence document.

    python scripts/check_licence_fields.py --project indic-prod
"""
from __future__ import annotations

import argparse
import sys

from google.cloud import firestore

FIELDS = ("mode", "status", "createdAt")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    args = parser.parse_args()

    client = firestore.Client(project=args.project)
    total = 0
    missing: dict[str, list[str]] = {field: [] for field in FIELDS}
    for doc in client.collection("licenses").stream():
        total += 1
        body = doc.to_dict() or {}
        for field in FIELDS:
            if body.get(field) in (None, ""):
                missing[field].append(doc.id)

    print(f"{total} licence document(s) checked.")
    bad = False
    for field, ids in missing.items():
        if ids:
            bad = True
            shown = ", ".join(i[:12] for i in ids[:10])
            more = f" and {len(ids) - 10} more" if len(ids) > 10 else ""
            print(f"  missing {field}: {len(ids)} ({shown}{more})")
    if not bad:
        print("  every licence has mode, status and createdAt.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
