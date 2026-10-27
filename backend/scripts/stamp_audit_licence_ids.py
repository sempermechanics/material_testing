#!/usr/bin/env python3
"""Stamp `licenseId` and `expireAt` on audit rows written before the backend did (TD-34, TD-199).

Since 2026-10-18 every row whose target is a licence, a seat, an invite or a
lease carries `licenseId` (`audit.licence_of`), and a licence's audit export
reads only those (`repo/audit_log.page_license_audit`). Rows from before then
are absent from every export. This adds the field to them, derived from the
row's own `target` exactly as `record` derives it.

Since TD-199 (retention one year, the owner's decision of 2026-10-22) every
new row also carries `expireAt` = its time + `AUDIT_RETENTION_DAYS`
(`repo/audit_log.add_audit_row`), which the Firestore TTL policy on
`audit_logs.expireAt` deletes. Rows from before then carry none and would be
kept for ever; the same pass gives each `expireAt` = its own `ts` +
`AUDIT_RETENTION_DAYS` (365 unless the environment sets it). A row with no
`ts` is left without one and counted. A row already older than the retention
gets an `expireAt` in the past, and TTL deletes it once the policy is on.

Nothing else changes: not `ts`, not `schemaVersion`, not the action or its
detail, and a field a row already has is never overwritten.

`audit_logs` is append-only (TD-199): this is the one rewrite of it after
migration 001, so `--apply` refuses to run without `--decision`, the owner's
decision that allows it, which goes in the ledger (`_migrations/{LEDGER_ID}`)
with the counts. The alternative the owner may choose instead is to state the
start date in the console and the export; then this never runs.

Dry-run is the default. Idempotent: a row that already has both fields is
skipped, so an interrupted run is resumed by running it again.

    python scripts/stamp_audit_licence_ids.py --project indicvision-dic-app
    python scripts/stamp_audit_licence_ids.py --project indicvision-dic-app \\
        --apply --decision "TD-34: backfill approved by <owner>, <date>"

Run it after the backend that stamps new rows is deployed, or rows written
in between are missed (a rerun picks them up).
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from google.cloud import firestore  # noqa: E402

from app.audit import licence_of  # noqa: E402
from app.config import settings  # noqa: E402

COLLECTION = "audit_logs"
LEDGER = "_migrations"
LEDGER_ID = "td246_stamp_audit_licence_ids"
PAGE = 400


def _updates(row: dict, retention: timedelta, counts: Counter) -> dict:
    """The fields this row lacks and can be given, counting each outcome."""
    updates: dict = {}
    if row.get("licenseId"):
        counts["already"] += 1
    elif license_id := licence_of(row.get("target")):
        counts["stamped"] += 1
        updates["licenseId"] = license_id
    else:
        counts["no_licence"] += 1
    ts = row.get("ts")
    if row.get("expireAt"):
        counts["expire_already"] += 1
    elif isinstance(ts, datetime):
        counts["expire_stamped"] += 1
        updates["expireAt"] = ts + retention
    else:
        counts["no_ts"] += 1
    return updates


def stamp(client, *, apply: bool, decision: str, page: int = PAGE,
          retention_days: int | None = None) -> Counter:
    """Walk `audit_logs` by document id and stamp every row without
    `licenseId` (a licence row) or `expireAt` (a row with a `ts`). Returns the
    counts `main` prints."""
    decision = (decision or "").strip()
    if apply and not decision:
        raise SystemExit("--apply rewrites the append-only audit log: pass --decision "
                         "with the owner decision that allows it (TD-34).")
    days = settings.AUDIT_RETENTION_DAYS if retention_days is None else retention_days
    retention = timedelta(days=days)
    ledger = client.collection(LEDGER).document(LEDGER_ID)
    if apply:
        ledger.set({
            "name": LEDGER_ID, "collections": [COLLECTION], "decision": decision,
            "retentionDays": days,
            "startedAt": firestore.SERVER_TIMESTAMP, "completedAt": None,
        })
    counts: Counter = Counter()
    cursor = None
    while True:
        query = client.collection(COLLECTION).order_by("__name__").limit(page)
        if cursor is not None:
            query = query.start_after(cursor)
        docs = list(query.stream())
        if not docs:
            break
        batch = client.batch()
        writes = 0
        for doc in docs:
            counts["scanned"] += 1
            updates = _updates(doc.to_dict() or {}, retention, counts)
            if not updates:
                continue
            counts["changed"] += 1
            if apply:
                batch.update(doc.reference, updates)
                writes += 1
        if writes:
            batch.commit()
        cursor = docs[-1]
    if apply:
        # Last, so an interrupted run leaves completedAt empty and says so.
        ledger.update({"completedAt": firestore.SERVER_TIMESTAMP,
                       "scanned": counts["scanned"], "changed": counts["changed"],
                       "licenseIdStamped": counts["stamped"],
                       "expireAtStamped": counts["expire_stamped"]})
    return counts


def _client(project: str):
    return firestore.Client(project=project)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="write (default: dry-run)")
    parser.add_argument("--decision", default="",
                        help="the owner decision that allows rewriting audit_logs (required with --apply)")
    args = parser.parse_args()
    counts = stamp(_client(args.project), apply=args.apply, decision=args.decision)
    verb = "stamped" if args.apply else "would stamp"
    print(f"{counts['scanned']} row(s) scanned, {counts['already']} already carry licenseId, "
          f"{counts['no_licence']} name no licence; {counts['expire_already']} already carry "
          f"expireAt, {counts['no_ts']} have no ts.")
    print(f"  {verb} licenseId on {counts['stamped']}, expireAt "
          f"(ts + {settings.AUDIT_RETENTION_DAYS} d) on {counts['expire_stamped']}; "
          f"{counts['changed']} row(s) in all")
    return 0


if __name__ == "__main__":
    sys.exit(main())
