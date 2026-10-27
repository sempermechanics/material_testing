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
    PURPOSE_CI,
    _BATCH_LIMIT,
    _active_devices,
    _stream,
    db,
    _load_user,
    _now,
    _run_tx,
    SCHEMA_VERSION,
    _get,
    _commit,
    _set_doc,
    _create_doc,
    max_test_devices,
    staff_released_from,
    test_account_of,
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
    released_at = _now()
    return {
        **apps.written_fields("activeDeviceId", app, _base.firestore.DELETE_FIELD),
        **apps.written_fields("releasedDeviceId", app, device_id),
        **apps.written_fields("releasedAt", app, released_at),
    }


def _retire_device(batch, device_ref, status: str, extra: dict | None = None) -> None:
    """Retire `device_ref` in `batch` (or a transaction), so its id stops
    counting against another account; `extra` fields go in the same write.

    The one way a device leaves service: superseded by a lock clear or a staff
    release (`_release_devices`), revoked with its account
    (`set_user_status`), or one test-account phone released by staff
    (`repo.testaccounts.release_test_device`, stamped `staffReleased`). The
    caller drops the app's `activeDeviceId` field on `users/{uid}` in the
    same batch.
    """
    batch.update(device_ref, {
        "status": status,
        "revokedAt": _base.firestore.SERVER_TIMESTAMP,
        **(extra or {}),
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
    previous holder's analyses (TD-218). The analyses stay the account's and
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


def _oldest_active_devices(uid: str, app: str, *, exclude: str, count: int) -> list[str]:
    """The `count` devices ACTIVE for `uid` in `app` that registered first,
    `exclude` left out: the ones a full CI test account gives up for a new
    phone. A record without `registeredAt` counts as the oldest; ties go by id.
    The same equality query as `_active_devices`."""
    if count <= 0:
        return []
    found = []
    for snap in _stream(db().collection("devices").where("uid", "==", uid)):
        dev = snap.to_dict() or {}
        if (snap.id != exclude and dev.get("status") == statuses.DEVICE_ACTIVE
                and apps.same_app(dev.get("app"), app)):
            registered = dev.get("registeredAt")
            found.append((registered is not None, registered or 0, snap.id))
    found.sort(key=lambda row: (row[0], row[1], row[2]))
    return [device_id for _, _, device_id in found[:count]]


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

    A test account (ADR-027) is not bound to one device: it may register
    while fewer than its `maxDevices` devices are ACTIVE for `app`, and its
    `activeDeviceId` names the latest. A full QC account is refused
    (`device_conflict`); a full CI account instead retires its oldest ACTIVE
    phones as superseded in the same transaction, since every CI run is a
    fresh emulator with a new key (TD-248). The answer's `rotated` lists them.
    A phone staff released from the test account on its own
    (`staff_released_from`) is refused `device_released` for that account, so
    it cannot take back the place it gave up (TD-249); another account may
    still register it. A real account is unchanged.

    `step_up_eligible` is whether the registering token proved a second factor
    (ADR-010, device trust). Stamped on every registration, a heal included,
    because a registration replaces the key: only a device registered from a
    second-factor sign-in may step up for staff or institution IT
    (`step_up.step_up_check`).
    """
    dev = {
        "uid": uid,
        # The stored name, which every backend reads (ADR-030, TD-56).
        "app": apps.stored_name(app),
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
    rotated: list[str] = []

    @_base.firestore.transactional
    def _register(tx):
        rotated.clear()  # a contended transaction runs this again
        user_snap = _get(user_ref, tx)
        held_user = (user_snap.to_dict() or {}) if user_snap.exists else {}
        tag = test_account_of(held_user)
        if tag is not None:
            # A test account holds up to `maxDevices` phones per app (ADR-027).
            # Counted after the user document is read in this transaction:
            # every registration writes that document, so a second phone
            # registering at once retries and counts this one.
            over = (len(_active_devices(uid, app, exclude=body.deviceId))
                    - max_test_devices(tag) + 1)
            if over > 0 and tag.get("purpose") != PURPOSE_CI:
                raise Refusal(errors.DEVICE_CONFLICT)
            rotated.extend(_oldest_active_devices(uid, app, exclude=body.deviceId, count=over))
        else:
            bound = apps.read_field(held_user, "activeDeviceId", app) if user_snap.exists else None
            if bound and bound != body.deviceId:
                raise Refusal(errors.DEVICE_CONFLICT)
        existing = _get(device_ref, tx)
        held = (existing.to_dict() or {}) if existing.exists else {}
        if held.get("status") == statuses.DEVICE_ACTIVE and held.get("uid") != uid:
            raise Refusal(errors.DEVICE_IN_USE)
        if tag is not None and staff_released_from(held, uid):
            raise Refusal(errors.DEVICE_RELEASED)
        for old in rotated:  # the writes come after every read of the transaction
            tx.update(db().collection("devices").document(old), {
                "status": statuses.DEVICE_SUPERSEDED,
                "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            })
        tx.set(device_ref, dev)
        # A registration ends any release hold: either the new phone has
        # arrived, or the hold has run out and the old one is back.
        tx.update(user_ref, {
            **apps.written_fields("activeDeviceId", app, body.deviceId),
            **apps.written_fields("releasedDeviceId", app, _base.firestore.DELETE_FIELD),
            **apps.written_fields("releasedAt", app, _base.firestore.DELETE_FIELD),
        })

    def _contended():
        raise Refusal(errors.CLAIM_CONTENDED)

    _run_tx(_register, on_contended=_contended)
    user = _load_user(uid)
    if user:
        ensure_entitlement(user, body.deviceId)
    return {**dev, "deviceId": body.deviceId, "rotated": list(rotated)}


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
