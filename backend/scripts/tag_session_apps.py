#!/usr/bin/env python3
"""Stamp `app` on every cloud session that was written without one (ADR-014).

`POST /v1/sessions` tags each new session with the app that asked
(`semper` or `materialtesting`, from `X-App-Id`), and `GET /v1/sessions` lists
only the asking app's. A session from before the tag reads as Semper's
(`repo/sessions.session_app`), so a Material Testing backup left untagged
would vanish from Material Testing's list. This finds those sessions and
tags them.

Attribution is the session's `deviceId`: `devices/{deviceId}.app` is
`materialtesting` for a phone Material Testing registered, so its sessions
are Material Testing's; anything else (`semper`, no `app`, no device
document, no `deviceId`) is Semper's. One device read per distinct device.

What this cannot see: a Material Testing build from before it sent
`X-App-Id` registered its phone as Semper, so its sessions are tagged
Semper. The `metadata.json` schema (`indic.session.metadata/6` for Material
Testing, `/3` for Semper) would tell them apart, but it is in Drive, not in
Firestore, and reading every session's metadata is not worth it for the few
pre-header lab builds. Retag those by hand if anyone asks.

Dry-run is the default; `--apply` writes. Idempotent: a session that already
has `app` is skipped, so a rerun after the backend deploy only picks up what
the old backend wrote in between.

    python scripts/tag_session_apps.py --project indicvision-dic-app
    python scripts/tag_session_apps.py --project indicvision-dic-app --apply

Staging and production run in this one project against the same default
database, so there is no separate staging run.
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import apps  # noqa: E402

PAGE = 400


def app_for_device(device: dict | None) -> str:
    """The app a session's device names: Material Testing only when the
    device document says so, Semper otherwise."""
    if (device or {}).get("app") == apps.MATERIAL_TESTING:
        return apps.MATERIAL_TESTING
    return apps.SEMPER


def tag_sessions(client, apply: bool) -> Counter:
    """Walk `sessions` by document id and tag every one without `app`.
    Returns the counts printed by `main`."""
    counts: Counter = Counter()
    devices: dict[str, dict | None] = {}

    def device(device_id: str) -> dict | None:
        if device_id not in devices:
            snap = client.collection("devices").document(device_id).get()
            devices[device_id] = (snap.to_dict() or {}) if snap.exists else None
        return devices[device_id]

    cursor = None
    while True:
        query = client.collection("sessions").order_by("__name__").limit(PAGE)
        if cursor is not None:
            query = query.start_after(cursor)
        docs = list(query.stream())
        if not docs:
            return counts
        batch = client.batch()
        writes = 0
        for doc in docs:
            counts["scanned"] += 1
            body = doc.to_dict() or {}
            if body.get("app"):
                counts["already_tagged"] += 1
                continue
            device_id = body.get("deviceId") or ""
            dev = device(device_id) if device_id else None
            if dev is None:
                counts["no_device"] += 1
            app = app_for_device(dev)
            counts[app] += 1
            if apply:
                batch.update(doc.reference, {"app": app})
                writes += 1
        if writes:
            batch.commit()
        cursor = docs[-1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="write the tags (default: dry-run)")
    args = parser.parse_args()

    from google.cloud import firestore

    counts = tag_sessions(firestore.Client(project=args.project), args.apply)
    verb = "tagged" if args.apply else "would tag"
    untagged = counts[apps.SEMPER] + counts[apps.MATERIAL_TESTING]
    print(f"{counts['scanned']} session(s) scanned, {counts['already_tagged']} already tagged.")
    print(f"  {verb} {untagged}: {counts[apps.SEMPER]} {apps.SEMPER}, "
          f"{counts[apps.MATERIAL_TESTING]} {apps.MATERIAL_TESTING}")
    if counts["no_device"]:
        print(f"  {counts['no_device']} had no device document (or no deviceId) and read as "
              f"{apps.SEMPER}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
