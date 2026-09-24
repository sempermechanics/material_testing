"""Registered devices and the challenge / client nonces they sign with.
"""
import secrets
from datetime import timedelta

from google.api_core.exceptions import AlreadyExists

from .. import statuses
from ..models import DeviceReg

from . import _base
from ._base import (
    db,
    _load_user,
    _now,
    _run_tx,
    SCHEMA_VERSION,
)
from .entitlement import (
    ensure_entitlement,
)


# ---------------- devices ----------------
def get_device(device_id: str):
    snap = db().collection("devices").document(device_id).get()
    return {**snap.to_dict(), "deviceId": device_id} if snap.exists else None


def user_has_active_device(uid: str) -> bool:
    u = db().collection("users").document(uid).get()
    return bool(u.exists and u.to_dict().get("activeDeviceId"))


def register_device(uid: str, body: DeviceReg) -> dict:
    dev = {
        "uid": uid,
        "publicKeyPem": body.publicKeyPem,
        "status": statuses.DEVICE_ACTIVE,
        "model": body.model,
        "osVersion": body.osVersion,
        "appVersion": body.appVersion,
        "registeredAt": _base.firestore.SERVER_TIMESTAMP,
        "lastAssertionAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    user_ref = db().collection("users").document(uid)
    # Only one device may be active per account (activeDeviceId is the single
    # binding). Superseded devices used to keep status ACTIVE forever, so an
    # account accumulated stale ACTIVE docs that still held their device ids
    # against other accounts via the device_in_use check.
    previous = user_ref.get().to_dict().get("activeDeviceId") if user_ref.get().exists else None
    batch = db().batch()
    if previous and previous != body.deviceId:
        batch.update(db().collection("devices").document(previous), {
            "status": "SUPERSEDED",
            "revokedAt": _base.firestore.SERVER_TIMESTAMP,
        })
    batch.set(db().collection("devices").document(body.deviceId), dev)
    batch.update(user_ref, {"activeDeviceId": body.deviceId})
    batch.commit()
    user = _load_user(uid)
    if user:
        ensure_entitlement(user, body.deviceId)
    return {**dev, "deviceId": body.deviceId}


# ---------------- challenge / nonce ----------------
def issue_nonce(uid: str, device_id: str) -> str:
    nonce = secrets.token_urlsafe(32)
    db().collection("challenges").document(nonce).set(
        {
            "uid": uid,
            "deviceId": device_id,
            "expireAt": _now() + timedelta(seconds=120),
            "schemaVersion": SCHEMA_VERSION,
        }
    )
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
        snap = ref.get(transaction=tx)
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
        db().collection("challenges").document(nonce).create(
            {
                "uid": uid,
                "deviceId": device_id,
                "kind": "client",
                "expireAt": expire_at,
                "schemaVersion": SCHEMA_VERSION,
            }
        )
        return True
    except AlreadyExists:
        return False
