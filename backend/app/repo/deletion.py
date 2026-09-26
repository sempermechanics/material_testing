"""Deleting a licence, and holding it for 30 days in case that was a mistake.

Revoke keeps the licence as the audit record, so the desk fills with them. A
delete takes the licence and its seats out of `licenses` altogether and
copies them to `deleted_licenses/{id}` (seats under `deleted_seats`), each
stamped with `purgeAt` thirty days on. A Firestore TTL policy on `purgeAt`
removes the copy after that (docs/backend/BACKEND_SETUP_CONSOLE.md); until
then the desk lists it and can restore it. The audit log row
(`ADMIN_LICENSE_DELETE`) outlives the purge.

Deleting is revoking first: holders drop to Demo exactly as a revoke drops
them, and their sessions and files are untouched. Their account then stops
pointing at a licence that no longer exists, so the next request gives it a
Demo key of its own, as for a new account. Restoring puts each holder back
unless they have taken another licence since — one licence per person holds
across a restore too.

Nothing is removed until the copy is written. System Demo keys are not
deleted here: there is one per account, and the account would only be
issued another.
"""
from datetime import timedelta

from .. import errors
from ..licenses import (
    KIND_INSTITUTION,
    MODE_DEMO,
    STATUS_REVOKED,
    as_utc,
    normalize_kind,
)

from . import _base
from ._base import (
    _BATCH_LIMIT,
    db,
    _delete_refs,
    _license_mode,
    _load_user,
    _now,
    _update_refs,
)
from .claims import (
    _drop_superseded_demo,
    _individual_member_patch,
    _institution_member_patch,
)
from .holders import (
    licence_held_by,
)
from .invites import (
    _write_invite,
)
from .mint import (
    _license_public,
)
from .license_admin import (
    get_license_public,
    revoke_license,
    _demo_drop_patch,
    _users_on_license,
)


DELETED = "deleted_licenses"
DELETED_SEATS = "deleted_seats"
#: How long a deleted licence can be restored. The TTL policy on `purgeAt`
#: removes it some time after this — Firestore deletes expired documents
#: within about a day, not at the instant — so restore also checks the date.
HOLD_DAYS = 30
#: What a tombstone adds to the licence it holds; stripped on restore.
_TOMBSTONE_FIELDS = ("priorStatus", "deletedAt", "deletedByUid", "purgeAt")
_LEASE_FIELDS = ("leaseExpiresAt", "leaseDeviceId", "lastHeartbeatAt")
#: The licence a user document carries: the pointer and the mirrored terms.
_USER_LICENCE_FIELDS = (
    "licenseId", "licenseKind", "licensePrefix", "licenseExpiresAt", "licenseGraceDays",
    "licenseDuration", "licenseSeating", "licenseMaxAnalyses",
)


def _set_refs(writes: list) -> None:
    """Applies every `(reference, data)` as a set, chunked like `_update_refs`."""
    for start in range(0, len(writes), _BATCH_LIMIT):
        batch = db().batch()
        for ref, data in writes[start:start + _BATCH_LIMIT]:
            batch.set(ref, data)
        batch.commit()


def _deleted_public(license_id: str, tomb: dict) -> dict:
    return {
        **_license_public(license_id, tomb),
        "priorStatus": tomb.get("priorStatus") or "",
        "deletedAt": tomb.get("deletedAt"),
        "deletedByUid": tomb.get("deletedByUid") or "",
        "purgeAt": tomb.get("purgeAt"),
    }


def delete_license(license_id: str, admin_uid: str) -> tuple[str, dict | None]:
    """Delete a licence into the 30-day hold. Returns (error, deleted row)."""
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return errors.LICENSE_NOT_FOUND, None
    lic = snap.to_dict() or {}
    if _license_mode(lic) == MODE_DEMO and (lic.get("createdByUid") or "") == "system":
        return errors.DEMO_KEY_NOT_DELETABLE, None
    seats = list(ref.collection("seats").stream())
    purge_at = _now() + timedelta(days=HOLD_DAYS)

    # The copy first, as the licence stood before the revoke below, so a
    # restore can put back what was live.
    tomb_ref = db().collection(DELETED).document(license_id)
    tomb = {
        **lic,
        "priorStatus": lic.get("status") or "",
        "deletedAt": _base.firestore.SERVER_TIMESTAMP,
        "deletedByUid": admin_uid,
        "purgeAt": purge_at,
    }
    tomb_ref.set(tomb)
    _set_refs([
        (tomb_ref.collection(DELETED_SEATS).document(s.id), {**(s.to_dict() or {}), "purgeAt": purge_at})
        for s in seats
    ])

    revoke_license(license_id, admin_uid)
    holders = [lic.get("redeemedByUid") or ""] + [(s.to_dict() or {}).get("uid") or s.id for s in seats]
    detach = {**_demo_drop_patch(), **{f: _base.firestore.DELETE_FIELD for f in _USER_LICENCE_FIELDS}}
    _update_refs([(user_ref, detach) for user_ref in _users_on_license(holders, license_id)])

    _delete_refs([s.reference for s in seats])
    ref.delete()
    return "", _deleted_public(license_id, {**tomb, "deletedAt": _now()})


def list_deleted_licenses(limit: int = 50) -> list[dict]:
    """Licences deleted and still held, most recently deleted first."""
    query = (db().collection(DELETED)
             .order_by("deletedAt", direction=_base.firestore.Query.DESCENDING)
             .limit(limit))
    return [_deleted_public(d.id, d.to_dict() or {}) for d in query.stream()]


def restore_license(license_id: str, admin_uid: str) -> tuple[str, dict | None]:
    """Bring a deleted licence back within its hold. Returns (error, licence).

    A licence that was live comes back live, with each holder re-attached
    unless they hold another live licence now; a seat whose holder has moved
    on comes back revoked, and an individual licence whose holder has moved
    on comes back unused. Leases do not come back: a floating member checks
    out again. A licence that was already revoked comes back revoked.
    """
    tomb_ref = db().collection(DELETED).document(license_id)
    snap = tomb_ref.get()
    if not snap.exists:
        return errors.DELETED_LICENSE_NOT_FOUND, None
    tomb = snap.to_dict() or {}
    purge_at = as_utc(tomb.get("purgeAt"))
    if purge_at is not None and purge_at <= _now():
        return errors.DELETED_LICENSE_PURGED, None
    ref = db().collection("licenses").document(license_id)
    if ref.get().exists:
        return errors.LICENSE_EXISTS, None

    lic = {k: v for k, v in tomb.items() if k not in _TOMBSTONE_FIELDS}
    live = (tomb.get("priorStatus") or "") != STATUS_REVOKED
    lic["status"] = tomb.get("priorStatus") or lic.get("status") or ""
    lic["restoredAt"] = _base.firestore.SERVER_TIMESTAMP
    lic["restoredByUid"] = admin_uid
    institution = normalize_kind(lic.get("kind")) == KIND_INSTITUTION
    seat_docs = list(tomb_ref.collection(DELETED_SEATS).stream())

    back: list[str] = []
    seat_writes = []

    def _free(uid: str) -> bool:
        user = _load_user(uid) if uid else None
        return bool(user) and not licence_held_by(user.get("email") or "", user=user,
                                                  exclude_id=license_id)

    if institution:
        on_roster = 0
        for doc in seat_docs:
            seat = {k: v for k, v in (doc.to_dict() or {}).items()
                    if k != "purgeAt" and k not in _LEASE_FIELDS}
            uid = seat.get("uid") or doc.id
            if live and seat.get("status") != STATUS_REVOKED:
                if _free(uid):
                    on_roster += 1
                    if seat.get("status") != "disabled":
                        back.append(uid)
                else:
                    seat.update({"status": STATUS_REVOKED, "revokedAt": _now()})
            seat_writes.append((ref.collection("seats").document(doc.id), seat))
        lic["seatsUsed"] = on_roster
        lic["leasesActive"] = 0
    elif live:
        redeemer = lic.get("redeemedByUid") or ""
        if redeemer and _free(redeemer):
            back.append(redeemer)
        elif redeemer:
            lic["status"] = "unused"
            lic.pop("redeemedByUid", None)
            lic.pop("redeemedAt", None)

    ref.set(lic)
    _set_refs(seat_writes)
    patch = (_institution_member_patch if institution else _individual_member_patch)(license_id, lic)
    users = db().collection("users")
    _update_refs([(users.document(uid), patch) for uid in back])
    for uid in back:
        # The Demo key the account was given while this licence was gone.
        _drop_superseded_demo(uid, license_id)
    address = (lic.get("emailLock") or "").strip().lower()
    if live and not institution and lic["status"] == "unused" and address \
            and not licence_held_by(address, exclude_id=license_id):
        # Still waiting for its first sign-in: promise it to the address again.
        _write_invite(license_id, address, admin_uid)

    _delete_refs([d.reference for d in seat_docs])
    tomb_ref.delete()
    return "", get_license_public(license_id)
