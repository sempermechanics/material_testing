#!/usr/bin/env python3
"""Split each licence from before ADR-033 into a Semper and a Semper Classroom licence.

Before ADR-033 one licence held for both apps and locked one phone per app
(`deviceIdLock`, `deviceIdLockMaterialTesting`). Since then a licence names
its `app` and licenses the phones activated on it
(`licenses/{id}/activations/{deviceId}`), and the backend runs whichever model
the licence names, so it served both before this script. This script moves
every licence to the new model, keeping each phone licensed:

  licenses/{id}               stamped `app: semper`, `devicesActive`,
                              `maxDevices`, `splitInto: <twin>`; its locks stay,
                              for a rollback
  licenses/{twin}             new: the same terms for Semper Classroom, a fresh
                              key nobody is shown, `splitFrom: <id>`
  .../activations/{deviceId}  one per locked phone, on the licence of the
                              lock's app, `activatedBy: migration`
  .../seats/{uid}             an institution's roster, copied to the twin
                              without its leases; `devicesActive` per member
  users/{uid}                 `licenseApp: semper`, and the twin in Semper
                              Classroom's mirror (`apps.licence_patch`'s fields)
  licenseInvites/...          each pending or held invite, again under
                              Semper Classroom's key for the twin
  audit_logs/licence-split-{id}  one `LICENSE_SPLIT` row per split

Not split:
  - a licence that names its `app` already (counted as `already`);
  - a Demo key: it is not a licence anyone was sold, and stays app-less;
  - a CI test account's licence: one app-less licence for every app is what
    Tier 3's emulator rotation needs (ADR-027);
  - a revoked licence: stamped `app: semper` and nothing else;
  - a deleted licence (`deleted_licenses`): restored, it is app-less and the
    next run splits it.

A holder who already holds a live Semper Classroom licence of their own keeps
it: an individual licence then gets no twin, and an institution member is left
off the twin's roster. Each such case, and an invite whose Semper Classroom
key already promises another live licence, is listed as a conflict.

Run order (staging and production share this project and database):

  1. A dry run, to see the counts and the conflicts:
         python scripts/split_licences_per_app.py --project indicvision-dic-app
  2. A rehearsal on the QC domain only:
         python scripts/split_licences_per_app.py --project indicvision-dic-app \
             --only-domain qa.sempermechanics.com --apply
  3. Everything, once the backend from ADR-033 step 5 serves all traffic:
         python scripts/split_licences_per_app.py --project indicvision-dic-app --apply

Safe to re-run and to resume. The twin's id is reserved on the licence first
(`splitPending`), so a run that stops part-way finishes the same twin next
time, and every later write is skipped where it is already in place. The twin
is built before anything points at it: its activations, then its roster, then
the licence, then the holders' mirrors and invites. The stamp on the original
is conditional on the licence not having changed since it was read; a licence
that changed is read again and redone.

A phone a legacy build binds while its licence is being split may be missed.
The holder taps Activate, or a legacy build activates it itself
(`IMPLICIT_ACTIVATION=legacy`). Run at a quiet hour.
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

from google.api_core.exceptions import AlreadyExists, FailedPrecondition, NotFound

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import apps  # noqa: E402
from app.config import settings  # noqa: E402
from app.licenses import (  # noqa: E402
    KIND_INSTITUTION,
    MODE_DEMO,
    SEATING_FLOATING,
    email_domain,
    generate_key,
    invite_id,
    key_hash,
    key_prefix,
    normalize_email,
    normalize_kind,
    normalize_seating,
)
from app.repo._base import PURPOSE_CI, SCHEMA_VERSION, _license_mode  # noqa: E402
from app.repo.holders import licence_is_live  # noqa: E402

PAGE = 200
#: Reads of one licence before a stamp that keeps failing its precondition
#: is allowed to raise.
ATTEMPTS = 3

SEMPER = apps.SEMPER
CLASSROOM = apps.SEMPER_CLASSROOM
#: `activatedBy` on a phone this script activated.
ACTOR = "migration"
ACTION = "LICENSE_SPLIT"

ACTIVATIONS = "activations"
SEATS = "seats"
INVITES = "licenseInvites"

#: A seat's lease, which is one phone's and stays with the original.
LEASE_FIELDS = ("leaseExpiresAt", "leaseDeviceId", "lastHeartbeatAt")
#: The licence fields the twin does not copy: its own key, counters it
#: recounts, the original's Semper lock, and markers of this script and of an
#: upgrade.
TWIN_DROPS = ("keyPrefix", "keyHash", "createdAt", "updatedAt", "seatsUsed", "leasesActive",
              "devicesActive", "maxDevices", "splitPending", "splitInto", "supersedes",
              "supersededBy", "upgradePendingId")
#: The user mirror fields the twin's holder does not inherit: their own
#: values are written instead, and a lease or a waiting upgrade belongs to
#: one licence.
MIRROR_SKIPS = ("licenseId", "licenseApp", "licensePrefix", "leaseExpiresAt", "leaseDeviceId",
                "pendingLicenseId")


def _lock_names(app: str) -> set[str]:
    """Every field that holds `app`'s device lock."""
    return set(apps.written_fields("deviceIdLock", app, ""))


@dataclass
class Tally:
    """What the walk saw. `counts` keys: `scanned`, `already`, `demo`, `ci`,
    `outside`, `revoked` (stamped), `split`, `resumed`, `twins`, `seats`,
    `activations.semper`, `activations.semperclassroom`, `mirrors`,
    `invites`, `conflicts`."""

    counts: Counter = field(default_factory=Counter)
    #: One line per conflict: the document, and why it was left alone.
    conflicts: list[str] = field(default_factory=list)

    def merge(self, other: "Tally") -> None:
        self.counts.update(other.counts)
        self.conflicts.extend(other.conflicts)


@dataclass
class Store:
    """The client and its write sentinels: `google.cloud.firestore`, or the
    test double."""

    client: object
    delete_field: object
    server_timestamp: object
    apply: bool


def _data(snap) -> dict:
    return (snap.to_dict() or {}) if snap is not None and snap.exists else {}


def _update(store: Store, snap, patch: dict) -> None:
    """Write `patch` to the document `snap` read, on real Firestore only if it
    is unchanged since. The test double's snapshots carry no `update_time`."""
    update_time = getattr(snap, "update_time", None)
    if update_time is None:
        snap.reference.update(patch)
    else:
        snap.reference.update(
            patch, option=store.client.write_option(last_update_time=update_time))


def _create(ref, data: dict) -> bool:
    """Create the document unless it exists. True when it was written."""
    try:
        ref.create(data)
    except AlreadyExists:
        return False
    return True


def _is_institution(lic: dict) -> bool:
    return normalize_kind(lic.get("kind")) == KIND_INSTITUTION


def _floating(lic: dict) -> bool:
    return _is_institution(lic) and normalize_seating(lic.get("seating")) == SEATING_FLOATING


def _in_domain(lic: dict, domain: str | None) -> bool:
    if not domain:
        return True
    own = (lic.get("domainLock") or "").strip().lower() or email_domain(lic.get("emailLock") or "")
    return own == domain


def classify(lic: dict, holder: dict | None, domain: str | None) -> str:
    """What the walk does with one licence: `split`, `resume`, `revoked`, or
    a reason to leave it (`already`, `demo`, `ci`, `outside`)."""
    if lic.get("splitPending"):
        return "resume"
    if lic.get("app"):
        return "already"
    if not _in_domain(lic, domain):
        return "outside"
    if _license_mode(lic) == MODE_DEMO:
        return "demo"
    if (lic.get("status") or "") == "revoked":
        return "revoked"
    tag = (holder or {}).get("testAccount")
    if lic.get("testAccount") is True and isinstance(tag, dict) and tag.get("purpose") == PURPOSE_CI:
        return "ci"
    return "split"


def max_devices(lic: dict, active: int) -> int | None:
    """The phone count a split licence is stamped with, as a mint would set
    it: 1 for an individual, one per seat for an assigned institution (never
    fewer than the phones it already licenses), unset for a floating one or
    one without a seat cap."""
    if lic.get("maxDevices") is not None:
        return max(int(lic["maxDevices"]), active)
    if not _is_institution(lic):
        return max(1, active)
    if _floating(lic) or lic.get("maxSeats") is None:
        return None
    return max(int(lic["maxSeats"]), active)


class Split:
    """One licence's split: what it would write, and the writes."""

    def __init__(self, store: Store, snap, tally: Tally):
        self.store = store
        self.client = store.client
        self.snap = snap
        self.lic = _data(snap)
        self.id = snap.id
        self.ref = snap.reference
        self.tally = tally
        self.institution = _is_institution(self.lic)
        self.floating = _floating(self.lic)

    # ------------------------------------------------------------ reads

    def _licence(self, license_id: str) -> dict:
        if not license_id:
            return {}
        return _data(self.client.collection("licenses").document(license_id).get())

    def _holders(self) -> dict[str, tuple[dict, object]]:
        """`{uid: (holder record, its snapshot)}`: the redeemer of an
        individual licence, every seat but a revoked one of an institution."""
        if not self.institution:
            uid = self.lic.get("redeemedByUid") or ""
            return {uid: (self.lic, self.snap)} if uid else {}
        out = {}
        for seat in self.ref.collection(SEATS).stream():
            body = _data(seat)
            if (body.get("status") or "") != "revoked":
                out[seat.id] = (body, seat)
        return out

    def _users(self, uids) -> dict[str, object]:
        return {uid: self.client.collection("users").document(uid).get() for uid in uids}

    def _classroom_conflict(self, user: dict, twin_id: str) -> str:
        """The live Semper Classroom licence `user` already holds, other than
        the twin, or ""."""
        held = apps.read_field(user, "licenseId", CLASSROOM) or ""
        if not held or held == twin_id:
            return ""
        return held if licence_is_live(self._licence(held)) else ""

    # ------------------------------------------------------------ the split

    def _reserve(self) -> tuple[str, str]:
        """The twin's id and key prefix, reserved on the licence
        (`splitPending`) so a resumed run finishes the same twin. A dry run
        reserves nothing."""
        pending = self.lic.get("splitPending")
        if isinstance(pending, dict) and pending.get("id"):
            return pending["id"], pending.get("prefix") or ""
        key = generate_key()
        twin_id, prefix = key_hash(key), key_prefix(key)
        if self.store.apply:
            _update(self.store, self.snap, {"splitPending": {"id": twin_id, "prefix": prefix}})
        return twin_id, prefix

    def _seed(self, license_id: str, app: str, holders: dict) -> Counter:
        """Activate each holder's locked phone for `app` on `license_id`.
        Returns the active phones per member, as the licence now has them."""
        if self.floating:
            return Counter()  # the lease is the activation
        col = self.client.collection("licenses").document(license_id).collection(ACTIVATIONS)
        planned = Counter()
        for uid, (holder, _snap) in holders.items():
            lock = apps.read_field(holder, "deviceIdLock", app) or ""
            if not lock or (self.institution and holder.get("status") != "active"):
                continue
            planned[uid] += 1
            if not self.store.apply:
                self.tally.counts[f"activations.{app}"] += 1
                continue
            if _create(col.document(lock), {
                "uid": uid,
                "app": apps.stored_name(app),
                "status": "active",
                "activatedAt": self.store.server_timestamp,
                "activatedBy": ACTOR,
                "activatedByUid": "",
                "deviceModel": "",
                "schemaVersion": SCHEMA_VERSION,
            }):
                self.tally.counts[f"activations.{app}"] += 1
        if not self.store.apply:
            return planned
        # Counted from what is stored, so a resumed run counts a phone an
        # earlier run activated once.
        return Counter((_data(s).get("uid") or "") for s in col.where("status", "==", "active").stream())

    def _twin(self, twin_id: str, prefix: str, holders: dict) -> int:
        """Build the twin: its activations, its roster, then the licence.
        Returns how many members it has."""
        active = self._seed(twin_id, CLASSROOM, holders)
        twin_ref = self.client.collection("licenses").document(twin_id)
        lock_names = _lock_names(SEMPER) | _lock_names(CLASSROOM)
        members = 0
        if self.institution:
            for uid, (seat, _snap) in holders.items():
                members += 1
                body = {k: v for k, v in seat.items() if k not in LEASE_FIELDS and k not in lock_names}
                lock = apps.read_field(seat, "deviceIdLock", CLASSROOM) or ""
                if lock:
                    body.update(apps.written_fields("deviceIdLock", CLASSROOM, lock))
                body["devicesActive"] = active.get(uid, 0)
                if self.store.apply:
                    if _create(twin_ref.collection(SEATS).document(uid), body):
                        self.tally.counts["seats"] += 1
                else:
                    self.tally.counts["seats"] += 1
        body = {k: v for k, v in self.lic.items() if k not in TWIN_DROPS and k not in lock_names}
        body.update({
            "keyPrefix": prefix,
            "keyHash": twin_id,
            "deviceIdLock": "",
            "app": apps.stored_name(CLASSROOM),
            "splitFrom": self.id,
            "devicesActive": sum(active.values()),
            "createdAt": self.store.server_timestamp,
            "updatedAt": self.store.server_timestamp,
            "schemaVersion": SCHEMA_VERSION,
        })
        if not self.institution:
            lock = apps.read_field(self.lic, "deviceIdLock", CLASSROOM) or ""
            if lock:
                body.update(apps.written_fields("deviceIdLock", CLASSROOM, lock))
        else:
            body["seatsUsed"] = members
            if "leasesActive" in self.lic:
                body["leasesActive"] = 0
        count = max_devices(self.lic, sum(active.values()))
        if count is not None:
            body["maxDevices"] = count
        if not self.store.apply or _create(twin_ref, body):
            self.tally.counts["twins"] += 1
        return members

    def _mirror(self, uid: str, snap, twin_id: str, prefix: str) -> None:
        """Mark the holder's account split, and put the twin in Semper
        Classroom's mirror, its terms the ones the account already shows."""
        user = _data(snap)
        patch: dict = {}
        if user.get("licenseApp") != apps.stored_name(SEMPER):
            patch["licenseApp"] = apps.stored_name(SEMPER)
        if twin_id:
            wanted = {name: user[name] for name in apps.LICENCE_MIRROR_FIELDS
                      if name not in MIRROR_SKIPS and name in user}
            wanted.update(licenseId=twin_id, licensePrefix=prefix,
                          licenseApp=apps.stored_name(CLASSROOM))
            for name, value in wanted.items():
                for k, v in apps.written_fields(name, CLASSROOM, value).items():
                    if user.get(k) != v:
                        patch[k] = v
        if not patch:
            return
        self.tally.counts["mirrors"] += 1
        if self.store.apply:
            patch["updatedAt"] = self.store.server_timestamp
            _update(self.store, snap, patch)

    def _invites(self, twin_id: str) -> None:
        """Promise the twin to every address the original is promised to, under
        Semper Classroom's key."""
        col = self.client.collection(INVITES)
        for inv in col.where("licenseId", "==", self.id).stream():
            body = _data(inv)
            address = normalize_email(body.get("email") or "")
            if not address:
                continue
            key = invite_id(address, CLASSROOM)
            if body.get("held"):
                ref = col.document(f"held_{twin_id}_{key}")
            else:
                ref = col.document(key)
                existing = _data(ref.get())
                held_by = existing.get("licenseId") or ""
                if held_by and held_by != twin_id and licence_is_live(self._licence(held_by)):
                    self._conflict(f"{INVITES}/{inv.id}",
                                   f"its Semper Classroom key already promises {held_by}")
                    continue
            data = {**body, "email": address, "licenseId": twin_id, "app": CLASSROOM,
                    "schemaVersion": SCHEMA_VERSION}
            if not self.store.apply:
                self.tally.counts["invites"] += 1
            elif _data(ref.get()).get("licenseId") != twin_id:
                ref.set(data)
                self.tally.counts["invites"] += 1

    def _stamp(self, twin_id: str, holders: dict) -> Counter:
        """Activate the original's Semper phones, count them per member, and
        stamp it `app: semper`, conditional on it not having changed."""
        active = self._seed(self.id, SEMPER, holders)
        patch = {
            "app": apps.stored_name(SEMPER),
            "devicesActive": sum(active.values()),
            "splitPending": self.store.delete_field,
            "updatedAt": self.store.server_timestamp,
        }
        count = max_devices(self.lic, sum(active.values()))
        if count is not None:
            patch["maxDevices"] = count
        if twin_id:
            patch["splitInto"] = twin_id
        if self.store.apply:
            if self.institution:
                for uid, (seat, seat_snap) in holders.items():
                    if seat.get("devicesActive") != active.get(uid, 0):
                        seat_snap.reference.update({"devicesActive": active.get(uid, 0)})
            _update(self.store, self.snap, patch)
        return active

    def _audit(self, twin_id: str, members: int, skipped: list[str]) -> None:
        if not self.store.apply:
            return
        now = datetime.now(timezone.utc)
        _create(self.client.collection("audit_logs").document(f"licence-split-{self.id}"), {
            "ts": self.store.server_timestamp,
            "uid": None,
            "deviceId": None,
            "ip": None,
            "action": ACTION,
            "outcome": "OK",
            "target": {"type": "license", "id": self.id},
            "licenseId": self.id,
            "detail": {"actor": ACTOR, "twin": twin_id, "app": SEMPER, "twinApp": CLASSROOM,
                       "members": members, "skippedHolders": skipped},
            "expireAt": now + timedelta(days=settings.AUDIT_RETENTION_DAYS),
            "schemaVersion": SCHEMA_VERSION,
        })

    def _conflict(self, path: str, why: str) -> None:
        self.tally.counts["conflicts"] += 1
        self.tally.conflicts.append(f"{path}: {why}")

    def run(self) -> None:
        holders = self._holders()
        users = self._users(holders)
        pending = self.lic.get("splitPending")
        known_twin = (pending.get("id") or "") if isinstance(pending, dict) else ""
        # A holder with a live Semper Classroom licence of their own keeps it.
        skipped = []
        for uid in list(holders):
            other = self._classroom_conflict(_data(users[uid]), known_twin)
            if other:
                skipped.append(uid)
                self._conflict(f"users/{uid}",
                               f"already holds Semper Classroom licence {other}; "
                               f"left off the twin of {self.id}")
        twin_holders = {uid: h for uid, h in holders.items() if uid not in skipped}
        # An individual licence whose one holder keeps their own gets no twin.
        twin = self.institution or not skipped
        twin_id = prefix = ""
        if twin:
            twin_id, prefix = self._reserve()
            if self.store.apply and not known_twin:
                # Re-read, so the stamp's precondition is the reserved licence.
                self.snap = self.ref.get()
                self.lic = _data(self.snap)
            self._twin(twin_id, prefix, twin_holders)
        for uid, (_holder, _snap) in holders.items():
            user = _data(users[uid])
            if (user.get("licenseId") or "") != self.id:
                continue  # moved on to another licence: nothing of theirs to split
            self._mirror(uid, users[uid], twin_id if uid in twin_holders else "", prefix)
        if twin:
            self._invites(twin_id)
        self._stamp(twin_id, holders)
        self._audit(twin_id, len(twin_holders), skipped)


def _stamp_revoked(store: Store, snap) -> None:
    if store.apply:
        _update(store, snap, {"app": apps.stored_name(SEMPER),
                              "updatedAt": store.server_timestamp})


def _holder_of(client, lic: dict) -> dict:
    uid = lic.get("redeemedByUid") or ""
    if not uid or lic.get("testAccount") is not True:
        return {}
    return _data(client.collection("users").document(uid).get())


def split_one(store: Store, snap, domain: str | None, tally: Tally) -> str:
    """Split, stamp or leave one licence; returns what was done. A stamp
    whose precondition fails reads the licence again and redoes it."""
    for attempt in range(ATTEMPTS):
        seen = Tally()
        lic = _data(snap)
        what = classify(lic, _holder_of(store.client, lic), domain)
        try:
            if what in ("split", "resume"):
                Split(store, snap, seen).run()
            elif what == "revoked":
                _stamp_revoked(store, snap)
        except (FailedPrecondition, NotFound):
            if attempt == ATTEMPTS - 1:
                raise
            snap = snap.reference.get()
            if not snap.exists:
                return "gone"
            continue
        tally.merge(seen)
        return what
    return "gone"  # pragma: no cover - the loop returns or raises


def split(client, apply: bool, delete_field, server_timestamp, *,
          only_domain: str | None = None) -> Tally:
    """Walk every licence by id, PAGE at a time. `delete_field` and
    `server_timestamp` are the client's sentinels."""
    store = Store(client, delete_field, server_timestamp, apply)
    domain = (only_domain or "").strip().lower() or None
    tally = Tally()
    col = client.collection("licenses")
    cursor = None
    while True:
        query = col.order_by("__name__").limit(PAGE)
        if cursor is not None:
            query = query.start_after(cursor)
        docs = list(query.stream())
        if not docs:
            return tally
        for snap in docs:
            if _data(snap).get("splitFrom"):
                continue  # a twin, minted by this run or an earlier one
            tally.counts["scanned"] += 1
            tally.counts[split_one(store, snap, domain, tally)] += 1
        cursor = docs[-1]


def report(tally: Tally, apply: bool) -> list[str]:
    """The lines `main` prints."""
    c = tally.counts
    verb = "" if apply else "would be "
    lines = [
        f"licenses: {c['scanned']} scanned; {c['split'] + c['resume']} {verb}split "
        f"({c['resume']} resumed), {c['revoked']} revoked {verb}stamped semper; left alone: "
        f"{c['already']} already per app, {c['demo']} Demo, {c['ci']} CI test account, "
        f"{c['outside']} outside the domain.",
        f"twins {verb}minted: {c['twins']}; seats {verb}copied: {c['seats']}; phones {verb}activated: "
        f"{c['activations.' + SEMPER]} Semper, {c['activations.' + CLASSROOM]} Semper Classroom; "
        f"accounts {verb}updated: {c['mirrors']}; invites {verb}copied: {c['invites']}.",
        f"conflicts: {c['conflicts']}.",
    ]
    shown = tally.conflicts[:20]
    lines.extend(f"  {line}" for line in shown)
    if len(tally.conflicts) > len(shown):
        lines.append(f"  and {len(tally.conflicts) - len(shown)} more")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    parser.add_argument("--apply", action="store_true", help="write (default: dry run)")
    parser.add_argument("--only-domain", default="",
                        help="only licences at this domain (emailLock or domainLock), "
                             "e.g. qa.sempermechanics.com for a rehearsal")
    args = parser.parse_args()

    from google.cloud import firestore

    tally = split(firestore.Client(project=args.project), args.apply, firestore.DELETE_FIELD,
                  firestore.SERVER_TIMESTAMP, only_domain=args.only_domain)
    for line in report(tally, args.apply):
        print(line)
    if not args.apply:
        print("Dry run: nothing written. Pass --apply to write.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
