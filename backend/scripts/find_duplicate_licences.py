#!/usr/bin/env python3
"""List people who hold more than one live licence.

One licence per person is enforced at every grant point
(`repo/holders.py`), but only from the release that added it. Before that a
staff mint, a typed key and IT adding a roster member each granted a second
licence regardless, so some people hold two. This finds them; ops resolves
each by hand — usually Extend on the one to keep, revoke on the other.

A person is an address, and it holds a licence four ways, all counted here:
an individual licence locked to it (`emailLock`), a roster seat, a pending
invite, or an account pointing at the licence. "Live" is `licence_is_live`:
not revoked, not past its grace, not a Demo key.

Read-only. Reads every licence, seat, invite and user document once.

    python scripts/find_duplicate_licences.py --project indicvision-dic-app

Staging and production run in this one project against the same default
database, so there is no separate staging run.
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from google.cloud import firestore  # noqa: E402

from app.licenses import (  # noqa: E402
    MODE_DEMO,
    MODES,
    grace_ends_at,
    normalize_email,
    normalize_mode,
)


def _mode(lic: dict) -> str:
    raw = lic.get("mode")
    if not (isinstance(raw, str) and raw.strip().lower() in MODES):
        raw = lic.get("plan")
    return normalize_mode(raw)


def _live(lic: dict, now: datetime) -> bool:
    if (lic.get("status") or "") == "revoked" or _mode(lic) == MODE_DEMO:
        return False
    ends = grace_ends_at(lic.get("expiresAt"), lic.get("graceDays") or 0)
    return ends is None or ends > now


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    args = parser.parse_args()

    client = firestore.Client(project=args.project)
    now = datetime.now(timezone.utc)
    live: dict[str, dict] = {}
    for doc in client.collection("licenses").stream():
        lic = doc.to_dict() or {}
        if _live(lic, now):
            live[doc.id] = lic

    # address -> licence id -> how the address holds it
    held: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for license_id, lic in live.items():
        if lic.get("emailLock"):
            held[normalize_email(lic["emailLock"])][license_id].add("emailLock")
        if (lic.get("kind") or "") == "institution":
            seats = client.collection("licenses").document(license_id).collection("seats")
            for seat in seats.stream():
                body = seat.to_dict() or {}
                if (body.get("status") or "active") != "revoked" and body.get("email"):
                    held[normalize_email(body["email"])][license_id].add("seat")
    for doc in client.collection("licenseInvites").stream():
        body = doc.to_dict() or {}
        if body.get("licenseId") in live and body.get("email"):
            held[normalize_email(body["email"])][body["licenseId"]].add("invite")
    for doc in client.collection("users").stream():
        body = doc.to_dict() or {}
        if body.get("licenseId") in live and body.get("email"):
            held[normalize_email(body["email"])][body["licenseId"]].add("account")

    dupes = {addr: ids for addr, ids in held.items() if len(ids) > 1}
    print(f"{len(live)} live licence(s), {len(held)} address(es) holding one.")
    if not dupes:
        print("  nobody holds more than one.")
        return 0
    print(f"  {len(dupes)} address(es) hold more than one:")
    for addr in sorted(dupes):
        print(f"  {addr}")
        for license_id, via in sorted(dupes[addr].items()):
            lic = live[license_id]
            print(f"    {lic.get('keyPrefix') or license_id[:12]}  {lic.get('kind') or 'individual'}"
                  f"  {lic.get('status') or ''}  via {', '.join(sorted(via))}  ({license_id})")
    return 1


if __name__ == "__main__":
    sys.exit(main())
