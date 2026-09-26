"""The device lock on a licence or seat: bind on first use, check, and revalidate.
"""
import logging
import random
import time
from datetime import timedelta

from .. import errors
from ..config import settings
from ..licenses import (
    as_utc,
    KIND_INSTITUTION,
    MODE_DEMO,
    MODE_LICENSED,
    normalize_kind,
)
from ..observability import DependencyError

from . import _base
from ._base import (
    db,
    _now,
    _mode_patch,
    _run_tx,
    _seat_ref,
)
from .user_config import (
    effective_mode,
)


log = logging.getLogger("indic.firestore")


# Verdicts from _device_lock_state. "Unbound" is deliberately distinct from
# "matches": both let the request through, but only one of them is a
# instruction to write.
_LOCK_OK = "ok"
_LOCK_UNBOUND = "unbound"
_LOCK_VIOLATION = "violation"


def _device_lock_state(user: dict, device_id: str) -> tuple[str, object | None]:
    """Judge `device_id` against this account's entitlement.

    Returns the verdict and, when the lock is still empty, the document that
    holds it. Three outcomes rather than the bool this replaced, because "no
    lock yet" and "lock matches" are the same answer to *may this device
    proceed* and opposite answers to *what should be written*.

    Empty locks are now the normal way a licence starts life, not an edge
    case. An individual licence minted against an email alone, and an
    institution seat created from an invite or added by IT, both reach a
    device for the first time with nothing bound — so the caller binds, and
    the licence ties itself to a device without anyone typing a key.

    Individual: the lock lives on the licence, one device for the licence.
    Institution: on the seat, one device per member.
    """
    license_id = user.get("licenseId")
    if not license_id:
        return _LOCK_OK, None
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return _LOCK_OK, None
    lic = snap.to_dict() or {}
    if (lic.get("status") or "") == "revoked":
        return _LOCK_VIOLATION, None
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        return _lock_verdict(lic.get("deviceIdLock"), device_id, ref)
    seat_ref = _seat_ref(license_id, user.get("uid") or "")
    seat_snap = seat_ref.get()
    if not seat_snap.exists:
        return _LOCK_OK, None
    seat = seat_snap.to_dict() or {}
    if seat.get("status") in ("revoked", "disabled"):
        return _LOCK_VIOLATION, None
    return _lock_verdict(seat.get("deviceIdLock"), device_id, seat_ref)


def _lock_verdict(locked, device_id: str, ref) -> tuple[str, object | None]:
    """The individual and institution branches differ only in which document
    carries the lock, so the comparison itself lives in one place."""
    locked = locked or ""
    if not locked:
        return _LOCK_UNBOUND, ref
    return (_LOCK_OK, None) if locked == device_id else (_LOCK_VIOLATION, None)


#: Whole bind transactions tried before giving up, each with the client's own
#: `_TX_ATTEMPTS` retries inside it. The client retries an aborted transaction
#: at once, so contenders that collided keep colliding in step; the jittered
#: pause between rounds is what lets one of them through.
_BIND_ROUNDS = 3
_BIND_BACKOFF_S = 0.05


class DeviceLockContended(DependencyError):
    """Every attempt to bind an empty device lock lost to contention, and the
    lock is still empty. Nobody holds the licence, so this is "try again",
    never a mismatch."""

    def __init__(self):
        super().__init__(errors.DEVICE_LOCK_CONTENDED, "firestore")


def bind_device_lock(ref, device_id: str) -> bool:
    """Claim an empty device lock for `device_id`. True if this call bound it,
    False if the lock is held (by another device, or already by this one).

    Transactional rather than a bare update: two devices signing in at once
    both read an empty lock, and with a plain write the later one would win,
    so the licence would silently follow whichever request Firestore happened
    to order second. Re-reading inside the transaction makes the first binding
    stick and turns the second device into a mismatch on its next request,
    which is the answer a device lock exists to give.

    Losing the race is not the same as someone winning it. Firestore aborts
    contended transactions, and a burst of them can all run out of retries
    with nothing committed — the emulator does this to eight concurrent binds.
    So a starved round re-reads the lock: held means another caller won and
    this one answers False; still empty means nobody won, and the bind runs
    again. Only after `_BIND_ROUNDS` does it give up, raising
    `DeviceLockContended` rather than returning False, because False would
    tell `_activate_individual` this device is a mismatch for a licence no
    device holds.
    """

    @_base.firestore.transactional
    def _bind(tx) -> bool:
        snap = ref.get(transaction=tx)
        if not snap.exists or ((snap.to_dict() or {}).get("deviceIdLock") or ""):
            return False
        tx.update(ref, {"deviceIdLock": device_id})
        return True

    for round_ in range(_BIND_ROUNDS):
        bound = _run_tx(_bind, on_contended=lambda: None)
        if bound is not None:
            return bound
        snap = ref.get()
        if not snap.exists or ((snap.to_dict() or {}).get("deviceIdLock") or ""):
            # Another device bound it first. This one is a mismatch from its
            # next request onward, which revalidate_device_lock will act on.
            return False
        if round_ + 1 < _BIND_ROUNDS:
            time.sleep(random.uniform(0, _BIND_BACKOFF_S * 2 ** round_))
    raise DeviceLockContended()


def check_device_lock(user: dict, device_id: str) -> bool:
    """True if `device_id` may still use this account's entitlement.

    An unbound lock passes: it is not a violation, it is a licence that has
    not met a device yet. Binding is revalidate_device_lock's job, because
    only it knows the caller is a real authed request rather than a check.
    """
    return _device_lock_state(user, device_id)[0] != _LOCK_VIOLATION



def released_device_held(user: dict, device_id: str) -> bool:
    """Whether `device_id` is a phone a device-lock clear released, still held off.

    A device-lock clear (`repo.seats._settle_holder`) empties `activeDeviceId` so the new phone can
    register, and stamps the old id. Without this check the old phone gets it
    straight back: its next signed call reads `device_not_active`, the upload
    worker re-registers, and the new phone meets `device_conflict` again.
    Registering any other device clears the stamp (`register_device`), and after
    `DEVICE_RELEASE_HOLD_HOURS` the old phone may return.
    """
    if not device_id or user.get("releasedDeviceId") != device_id:
        return False
    hold = timedelta(hours=max(0, settings.DEVICE_RELEASE_HOLD_HOURS))
    released = as_utc(user.get("releasedAt"))
    return bool(hold) and released is not None and _now() - released < hold


def _may_bind(user: dict, device_id: str) -> bool:
    """Whether `device_id` may take this account's empty lock: its registered
    phone, or, while nothing is registered, any phone but one a clear released
    and still holds off.

    Any device used to bind. A phone refused at `POST /v1/devices/register`
    (`device_conflict`) still sends its config and profile calls, and those
    took the lock: the account then held a lock on one phone and a
    registration on another, and the registered phone read as a mismatch and
    was dropped to Demo (2026-09-26, a Pixel 6 after an emulator's refused
    sign-in). The same path let a released phone's upload worker retake the
    lock during its hold.
    """
    active = user.get("activeDeviceId") or ""
    if active:
        return device_id == active
    return not released_device_held(user, device_id)


def revalidate_device_lock(user: dict, device_id: str | None) -> dict:
    """Re-check this account's entitlement against `device_id` on every authed
    call that carries X-Device-Id — activation is not "trust forever". A
    revoked license/seat, or a device that no longer matches the lock, drops
    the account to Demo immediately rather than waiting for the next explicit
    revoke/activate to notice. No-op (and no write) for Demo accounts, accounts
    with no license on file, or a call with no device id to check.

    Also the moment an unbound licence acquires its device. A licence minted
    against an email, or a seat added to a roster, carries no lock until
    someone actually signs in — so the first authed request that presents a
    device id binds it here, if that device may take it (`_may_bind`). That
    is what makes "we mint against your address and you sign in" tie a
    licence to a device with no key and no activation step; see
    _device_lock_state.

    This only ever *removes* entitlement in place — it never deletes or hides
    the account's sessions/files, and re-locking to a *different* device
    happens only after a staff, IT or self-service clear of the existing lock.
    """
    if not device_id or not user.get("licenseId"):
        return user
    if effective_mode(user) != MODE_LICENSED:
        return user
    verdict, ref = _device_lock_state(user, device_id)
    if verdict == _LOCK_UNBOUND:
        if not _may_bind(user, device_id):
            # Proceeds, but leaves the lock for the phone that may take it.
            return user
        try:
            bound = bind_device_lock(ref, device_id)
        except DeviceLockContended:
            # Nobody holds the lock, so this device is not a mismatch and the
            # request it rides on should not fail for it: leave the licence
            # unbound and let this device's next request bind it.
            log.info("device lock bind starved uid=%s license=%s",
                     user.get("uid"), user.get("licenseId"))
            return user
        if bound:
            log.info("device lock bound uid=%s license=%s",
                     user.get("uid"), user.get("licenseId"))
            # Imported here rather than at module scope: `audit` imports `db`
            # through the firestore_repo facade, which imports this module, so
            # the two cannot import each other eagerly.
            # The record matters because it is the second half of a device
            # change — `clear_device_lock` writes the device that was given
            # up, and this writes the one that took its place.
            from .. import audit
            audit.record(
                user.get("uid"), device_id, action="LICENSE_DEVICE_BIND",
                target={"type": "license", "id": user.get("licenseId")},
                detail={"deviceId": device_id},
            )
        return user
    if verdict == _LOCK_OK:
        return user
    uid = user.get("uid")
    if uid:
        db().collection("users").document(uid).update({
            **_mode_patch(MODE_DEMO),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
    return {**user, **_mode_patch(MODE_DEMO)}
