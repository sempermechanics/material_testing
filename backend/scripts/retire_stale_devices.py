#!/usr/bin/env python3
"""Retire ACTIVE device records no account binds any more (ADR-033).

Until ADR-033 a real account held one phone per app, and a signed request was
accepted only from the phone its `users/{uid}.activeDeviceId` named. Since
ADR-033 an account holds several phones, and any of its ACTIVE device records
signs. Records that were left ACTIVE by older paths (a registration that lost
a race before registration became one transaction, a release that left one
behind) would then sign again. This retires them before that backend serves:

  devices/{id}  status ACTIVE -> SUPERSEDED, revokedAt, retiredBy

A record is kept when its account names it as `activeDeviceId` for the
record's app, or when its account is a test account (ADR-027: those already
held several phones). Every other ACTIVE record is retired: its account is
gone, or binds another phone for that app. Nothing else is touched.

Run order (the owner runs it; staging and production share this project's
default database, so there is one run):

  1. Dry-run, to see the counts and ids:
         python scripts/retire_stale_devices.py --project indicvision-dic-app
  2. `--apply`, right before the ADR-033 backend deploys:
         python scripts/retire_stale_devices.py --project indicvision-dic-app --apply

Safe to re-run: a retired record is no longer ACTIVE, so a second run finds
nothing. Reads every ACTIVE device record and each owner's user document once.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from google.cloud import firestore  # noqa: E402

from app import apps, statuses  # noqa: E402

PAGE = 400
RETIRED_BY = "stale-device-sweep"


def is_stale(dev: dict, user: dict | None) -> bool:
    """Whether the ACTIVE record `dev` is one no account binds: its account
    is gone, or names another phone for the record's app and is not a test
    account."""
    if not user:
        return True
    if isinstance(user.get("testAccount"), dict) and user["testAccount"]:
        return False
    app = apps.canonical(dev.get("app"))
    if app not in apps.ALL:
        return True
    return apps.read_field(user, "activeDeviceId", app) != dev.get("_id")


def find_stale(client) -> list[tuple[str, str]]:
    """`(device id, uid)` of every stale ACTIVE record, by device id."""
    users: dict[str, dict | None] = {}
    out = []
    query = client.collection("devices").where("status", "==", statuses.DEVICE_ACTIVE)
    for snap in query.stream():
        dev = {**(snap.to_dict() or {}), "_id": snap.id}
        uid = dev.get("uid") or ""
        if uid not in users:
            user_snap = client.collection("users").document(uid).get() if uid else None
            users[uid] = (user_snap.to_dict() or {}) if user_snap and user_snap.exists else None
        if is_stale(dev, users[uid]):
            out.append((snap.id, uid))
    return sorted(out)


def retire(client, stale: list[tuple[str, str]]) -> int:
    """Mark each record SUPERSEDED, PAGE per batch. The count written."""
    for start in range(0, len(stale), PAGE):
        batch = client.batch()
        for device_id, _ in stale[start:start + PAGE]:
            batch.update(client.collection("devices").document(device_id), {
                "status": statuses.DEVICE_SUPERSEDED,
                "revokedAt": firestore.SERVER_TIMESTAMP,
                "retiredBy": RETIRED_BY,
            })
        batch.commit()
    return len(stale)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="write (default: dry-run)")
    args = parser.parse_args()
    client = firestore.Client(project=args.project)
    stale = find_stale(client)
    verb = "retired" if args.apply else "would retire"
    print(f"{verb} {len(stale)} stale ACTIVE device record(s)")
    for device_id, uid in stale:
        print(f"  {device_id}  uid={uid or '-'}")
    if args.apply and stale:
        retire(client, stale)
    return 0


if __name__ == "__main__":
    sys.exit(main())
