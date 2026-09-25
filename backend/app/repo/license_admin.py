"""Licence administration by Semper staff: listing, renewal fan-out, whole-key revoke.
"""
from .. import errors
from ..licenses import (
    KIND_INSTITUTION,
    MODE_DEMO,
    MODE_LICENSED,
    as_utc,
    normalize_kind,
)

from . import _base
from ._base import (
    _cursor_page,
    db,
    _lease_clear_patch,
    _license_mode,
    _mode_patch,
    _now,
)
from .claims import (
    _license_mirror_patch,
)
from .invites import (
    _delete_license_invites,
)
from .mint import (
    _license_public,
)


def list_licenses(limit: int = 50, page_token: str | None = None) -> tuple[list, str | None]:
    col = db().collection("licenses")
    docs, next_token = _cursor_page(col, col, limit, page_token)
    return [_license_public(d.id, d.to_dict() or {}) for d in docs], next_token


def revoke_license(license_id: str, admin_uid: str) -> dict | None:
    """Whole-key revoke. Every redeemer (individual redeemer, or every
    institution seat holder) drops to Demo and every occupied seat is freed.

    Leases go with the seats. They used to survive the revoke: each seat kept
    its `leaseExpiresAt`, the licence kept its `leasesActive`, and the console
    showed a revoked pool with seats in use until every lease ran out."""
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}
    ref.update({
        "status": "revoked",
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
        "revokedByUid": admin_uid,
    })
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        for seat_doc in ref.collection("seats").stream():
            seat = seat_doc.to_dict() or {}
            seat_uid = seat.get("uid") or seat_doc.id
            _drop_user_to_demo_if_licensed(seat_uid, license_id)
            seat_doc.reference.update({
                "status": "revoked",
                "revokedAt": _base.firestore.SERVER_TIMESTAMP,
                **_lease_clear_patch(),
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            })
        ref.update({"seatsUsed": 0, "leasesActive": 0})
        _delete_license_invites(license_id)
    else:
        redeemer = lic.get("redeemedByUid")
        if redeemer and _license_mode(lic) == MODE_LICENSED:
            _drop_user_to_demo_if_licensed(redeemer, license_id)
        _delete_license_invites(license_id, lic.get("emailLock") or "")
    return _license_public(license_id, {**lic, "status": "revoked"})


class LicenseTermsRejected(Exception):
    """A licence edit `update_license` refuses. `code` is the 422 detail."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def expiry_change_error(lic: dict, expires_at) -> str:
    """Why this new `expiresAt` may not be applied to `lic`, or "".

    The edit route is the desk's Extend button, and every holder follows the
    licence the moment it is written. A date already past ended the licence
    for all of them; one earlier than the current expiry shortened it; and
    on a perpetual licence it turned an unending licence into a timed one
    with no grace, because perpetual licences are minted without
    `graceDays`. Each of those was then reported on the desk as "extended".
    Ending a licence early is `revoke`; converting a perpetual licence is a
    new key.
    """
    new = as_utc(expires_at)
    if new is None:
        return ""
    if new <= _now():
        return errors.EXPIRY_IN_PAST
    current = as_utc(lic.get("expiresAt"))
    if current is None:
        return errors.LICENSE_PERPETUAL
    if new < current:
        return errors.EXPIRY_BEFORE_CURRENT
    return ""


def update_license(license_id: str, patch: dict, admin_uid: str) -> dict | None:
    """Change a license's terms and push them to everyone already holding it.

    Renewal is the reason this exists. `_license_mirror_patch` snapshots the
    terms onto each user at activation so the hot read path needs no Firestore
    lookup; the cost is that editing the license alone reaches nobody. So this
    writes the document and then fans the new mirror out — to the individual
    redeemer, or to every seat on an institution license.

    The fan-out is bounded by `seatsUsed` and renewal is rare, which is what
    makes this the right side of the trade against a per-request read. Revoked
    seats are skipped: they hold no entitlement to refresh, and touching them
    would quietly resurrect a revoked member on the next resolve.

    Returns the updated public license, or None if there is no such license.
    Raises `LicenseTermsRejected`, before writing anything, for an expiry
    that `expiry_change_error` refuses.
    """
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}

    update = {k: v for k, v in patch.items() if v is not None}
    clear_cap = bool(update.pop("clearMaxAnalyses", False))
    if clear_cap:
        update["maxAnalyses"] = _base.firestore.DELETE_FIELD
    if not update:
        return _license_public(license_id, lic)
    err = expiry_change_error(lic, update.get("expiresAt"))
    if err:
        raise LicenseTermsRejected(err)
    if "graceDays" in update:
        update["graceDays"] = max(0, int(update["graceDays"]))
    update["updatedAt"] = _base.firestore.SERVER_TIMESTAMP
    update["updatedByUid"] = admin_uid
    ref.update(update)

    merged = {**lic, **update}
    if clear_cap:
        # The sentinel is for Firestore; the mirror and the response read the
        # licence as it now stands, without a cap.
        merged.pop("maxAnalyses", None)
    mirror = _license_mirror_patch(merged)
    for uid in _license_holder_uids(ref, merged):
        _refresh_license_mirror(uid, license_id, mirror)
    return _license_public(license_id, merged)


def _license_holder_uids(ref, lic: dict) -> list[str]:
    """Everyone currently entitled by this license."""
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        redeemer = lic.get("redeemedByUid")
        return [redeemer] if redeemer else []
    out = []
    for seat_doc in ref.collection("seats").stream():
        seat = seat_doc.to_dict() or {}
        if seat.get("status") == "revoked":
            continue
        out.append(seat.get("uid") or seat_doc.id)
    return out


def _refresh_license_mirror(uid: str, license_id: str, mirror: dict) -> None:
    """Re-stamp one holder's copy of the license terms.

    Guarded on `licenseId` for the same reason as the demo drop below: a user
    who has since moved to a different license must not have this one's terms
    written over theirs.
    """
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if not user_snap.exists:
        return
    if (user_snap.to_dict() or {}).get("licenseId") != license_id:
        return
    user_ref.update({**mirror, "updatedAt": _base.firestore.SERVER_TIMESTAMP})


def _drop_user_to_demo_if_licensed(uid: str, license_id: str) -> None:
    """Drop a user to Demo only if they are still pointed at this exact
    license — activation is in-place (same uid/doc, no data migration), and
    downgrade must never delete or hide existing sessions/files, only stop
    new analysis creation once the account is back over the Demo cap.

    The holder's copy of a floating lease goes too. Every caller has just
    taken the seat's lease away (revoke, hold, whole-licence revoke), and the
    copy left behind read as a seat still in use on the account page."""
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if user_snap.exists and (user_snap.to_dict() or {}).get("licenseId") == license_id:
        user_ref.update({
            **_mode_patch(MODE_DEMO),
            "leaseExpiresAt": _base.firestore.DELETE_FIELD,
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            # The instant from which "has this account been back since?" is
            # asked. `_touch_user` throttles lastSeenAt to the hour, which
            # would leave reconciliation reading a landed revoke as unlanded
            # for that long; the checkpoint makes the account's next request
            # bypass the throttle exactly once. Written inside the update the
            # demotion already performs, so it costs no extra write here.
            "seenCheckpointAt": _base.firestore.SERVER_TIMESTAMP,
        })
