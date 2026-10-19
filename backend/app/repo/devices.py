"""Registered devices and the challenge / client nonces they sign with.
"""
import secrets
from datetime import timedelta

from google.api_core.exceptions import AlreadyExists

from .. import apps, errors, statuses
from ..errors import Refusal
from ..models import DeviceReg

from . import _base
from ._base import (
    _BATCH_LIMIT,
    db,
    _load_user,
    _now,
    _run_tx,
    SCHEMA_VERSION,
    _get,
    _commit,
    _stream,
    _set_doc,
    _create_doc,
)
from .entitlement import (
    ensure_entitlement,
)


# ---------------- devices ----------------
def get_device(device_id: str):
    snap = _get(db().collection("devices").document(device_id))
    return {**snap.to_dict(), "deviceId": device_id} if snap.exists else None


def _release_patch(device_id: str, app: str = apps.SEMPER) -> dict:
    """The `users/{uid}` fields that release `device_id` from `app`: the binding
    dropped and the id stamped, so `repo.devlock.released_device_held` holds it
    off while the new phone registers. Shared by a lock clear and a staff phone
    release."""
    return {
        apps.field("activeDeviceId", app): _base.firestore.DELETE_FIELD,
        apps.field("releasedDeviceId", app): device_id,
        apps.field("releasedAt", app): _now(),
    }


def _retire_device(batch, device_ref, status: str) -> None:
    """Retire `device_ref` in `batch`, so its id stops counting against another account.

    The one way a device leaves service: superseded by a lock clear or a staff
    release (`_release_devices`), or revoked with its account
    (`set_user_status`). The caller drops the app's `activeDeviceId`
    field on `users/{uid}` in the same batch.
    """
    batch.update(device_ref, {
        "status": status,
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
    })




def _release_devices(user_ref, devices: dict[str, str], patch: dict) -> None:
    """Release each `{app: device id}` from the account, in one batch with
    `patch`: the app's binding dropped and the id held off (`_release_patch`),
    and the device retired as superseded. Shared by a lock clear
    (`repo.seats._settle_holder`) and a staff phone release
    (`repo.users.release_account_device`), so the two cannot release a phone
    differently."""
    patch = dict(patch)
    batch = db().batch()
    for app, device in devices.items():
        patch.update(_release_patch(device, app))
        device_ref = db().collection("devices").document(device)
        if _get(device_ref).exists:
            _retire_device(batch, device_ref, statuses.DEVICE_SUPERSEDED)
    batch.update(user_ref, patch)
    _commit(batch)


def forget_device_sessions(uid: str, device_ids) -> int:
    """Stop the account's analyses counting toward each of `device_ids`'
    demo cap: drop `deviceId` from the account's sessions created from them.
    Returns how many were changed.

    For a phone that staff or institution IT took off the account (a staff
    release, a staff or IT lock clear): the phone may go to someone else, and
    `count_device_sessions` would otherwise hold its next holder's demo to the
    previous holder's analyses (TD-276). The analyses stay the account's and
    still count toward its own cap. Never for a holder's own device change:
    that would let a second account on the same phone shed the first one's
    count. Two equality filters, served by the automatic indexes; chunked to
    the batch write cap.
    """
    changed = 0
    for device_id in {d for d in device_ids if d}:
        query = (db().collection("sessions")
                 .where("uid", "==", uid).where("deviceId", "==", device_id))
        refs = [snap.reference for snap in _stream(query)]
        for start in range(0, len(refs), _BATCH_LIMIT):
            batch = db().batch()
            for ref in refs[start:start + _BATCH_LIMIT]:
                batch.update(ref, {"deviceId": _base.firestore.DELETE_FIELD})
            _commit(batch)
        changed += len(refs)
    return changed


def register_device(uid: str, body: DeviceReg, app: str = apps.SEMPER, *,
                    step_up_eligible: bool = False) -> dict:
    """Bind `body.deviceId` to `uid` for `app`, or raise `Refusal`.

    The checks and the writes are one transaction (B5): the account must not
    already be bound to a different device for this app (`device_conflict`;
    a device change goes through a lock clear or a staff release, which drop
    the binding first), and the device must not be ACTIVE for another account
    (`device_in_use`). Read in the router ahead of a batch, two phones
    registering at once both passed and both stayed ACTIVE. The same device id
    re-registering is the reinstall heal: its key and stamp are replaced.

    `step_up_eligible` is whether the registering token proved a second factor
    (ADR-010, device trust). Stamped on every registration, a heal included,
    because a registration replaces the key: only a device registered from a
    second-factor sign-in may step up for staff or institution IT
    (`step_up.step_up_check`).
    """
    dev = {
        "uid": uid,
        "app": app,
        "publicKeyPem": body.publicKeyPem,
        "status": statuses.DEVICE_ACTIVE,
        "stepUpEligible": bool(step_up_eligible),
        "model": body.model,
        "osVersion": body.osVersion,
        "appVersion": body.appVersion,
        "registeredAt": _base.firestore.SERVER_TIMESTAMP,
        "lastAssertionAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    user_ref = db().collection("users").document(uid)
    device_ref = db().collection("devices").document(body.deviceId)
    # Only one device may be active per account and app (the app's
    # `activeDeviceId` field is the single binding, ADR-010).
    active_field = apps.field("activeDeviceId", app)

    @_base.firestore.transactional
    def _register(tx):
        user_snap = _get(user_ref, tx)
        bound = (user_snap.to_dict() or {}).get(active_field) if user_snap.exists else None
        if bound and bound != body.deviceId:
            raise Refusal(errors.DEVICE_CONFLICT)
        existing = _get(device_ref, tx)
        held = (existing.to_dict() or {}) if existing.exists else {}
        if held.get("status") == statuses.DEVICE_ACTIVE and held.get("uid") != uid:
            raise Refusal(errors.DEVICE_IN_USE)
        tx.set(device_ref, dev)
        # A registration ends any release hold: either the new phone has
        # arrived, or the hold has run out and the old one is back.
        tx.update(user_ref, {
            active_field: body.deviceId,
            apps.field("releasedDeviceId", app): _base.firestore.DELETE_FIELD,
            apps.field("releasedAt", app): _base.firestore.DELETE_FIELD,
        })

    def _contended():
        raise Refusal(errors.CLAIM_CONTENDED)

    _run_tx(_register, on_contended=_contended)
    user = _load_user(uid)
    if user:
        ensure_entitlement(user, body.deviceId)
    return {**dev, "deviceId": body.deviceId}


# ---------------- challenge / nonce ----------------
def issue_nonce(uid: str, device_id: str) -> str:
    nonce = secrets.token_urlsafe(32)
    _set_doc(db().collection("challenges").document(nonce), {
        "uid": uid,
        "deviceId": device_id,
        "expireAt": _now() + timedelta(seconds=120),
        "schemaVersion": SCHEMA_VERSION,
    })
    return nonce


def consume_nonce(nonce: str, uid: str, device_id: str) -> bool:
    """Atomically claim a challenge. Delete only when uid/device/expiry match.

    A plain get→delete race let two concurrent callers both read a live nonce;
    wrapping in a transaction means only one commit wins. Invalid callers must
    not delete — otherwise a wrong-uid probe would burn a valid challenge.
    """
    ref = db().collection("challenges").document(nonce)
    # More than the default five attempts: this document is the hottest in the
    # service and losing the race means denying a legitimate caller.

    @_base.firestore.transactional
    def _consume(tx):
        snap = _get(ref, tx)
        if not snap.exists:
            return False
        d = snap.to_dict()
        if d.get("uid") != uid or d.get("deviceId") != device_id:
            return False
        exp = d.get("expireAt")
        if not (exp and exp > _now()):
            return False
        tx.delete(ref)
        return True

    # Concurrent consumption of one nonce is by definition a replay, and we
    # could not commit — so deny. Fail closed: claiming the nonce here would be
    # the one outcome that breaks single-use. A legitimate client never races
    # itself on a nonce; it just fetches a fresh challenge.
    return _run_tx(_consume, on_contended=lambda: False)


def claim_client_nonce(nonce: str, uid: str, device_id: str, expire_at) -> bool:
    """Record a client-minted (timestamped) nonce as used. False = replay.

    One `create()` — it fails with AlreadyExists when the document is there,
    so two concurrent claims cannot both win, without a read or a transaction.
    Stored in `challenges` so the existing TTL policy on `expireAt` reclaims it;
    server-issued IDs never contain '.', so the two kinds cannot collide.
    """
    try:
        _create_doc(db().collection("challenges").document(nonce), {
            "uid": uid,
            "deviceId": device_id,
            "kind": "client",
            "expireAt": expire_at,
            "schemaVersion": SCHEMA_VERSION,
        })
        return True
    except AlreadyExists:
        return False
