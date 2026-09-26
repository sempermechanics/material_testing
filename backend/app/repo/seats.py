"""Institution seat administration: clearing a device lock, holding and revoking a seat.
"""
from datetime import timedelta

from .. import errors, statuses
from ..config import settings
from ..licenses import (
    KIND_INSTITUTION,
    MODE_LICENSED,
    STATUS_ACTIVE,
    STATUS_REVOKED,
    as_utc,
    normalize_kind,
)

from . import _base
from ._base import (
    db,
    _lease_clear_patch,
    _license_mode,
    _mode_patch,
    _now,
    _run_tx,
    _seat_lease_counted,
    _seat_ref,
)
from .devices import (
    _retire_device,
)
from .license_admin import (
    _drop_user_to_demo_if_licensed,
)


#: Who asked for a device change. Only the holder is rate-limited; see
#: `clear_device_lock`.
ACTOR_SELF = "self"
ACTOR_STAFF = "staff"
ACTOR_IT = "it"


def _live_holder(license_id: str, lic: dict, ref, scope: str, uid: str):
    """The holder's `(user_ref, user)` if a device-lock clear may act on them, else None.

    None for a revoked licence, for a seat that is revoked or on hold, and for an
    account that has since moved to a different licence. The release once
    checked only the last condition: **New device** on a held seat let its
    member, by then on Demo, register a different phone, a change Demo accounts
    do not otherwise get (TD-127).
    """
    if (lic.get("status") or "") == STATUS_REVOKED:
        return None
    if scope == "seat":
        seat = ref.get()
        if not seat.exists or (seat.to_dict() or {}).get("status") != STATUS_ACTIVE:
            return None
        holder = uid
    else:
        holder = lic.get("redeemedByUid") or ""
    if not holder:
        return None
    user_ref = db().collection("users").document(holder)
    snap = user_ref.get()
    if not snap.exists:
        return None
    user = snap.to_dict() or {}
    if user.get("licenseId") != license_id:
        return None
    return user_ref, user


def _settle_holder(license_id: str, lic: dict, ref, scope: str, uid: str) -> str:
    """Finish a device change on the holder's account. The released device id, or "".

    Clearing the lock is only one of the three bindings a device change moves:

    - **Mode.** A device change is usually preceded by the holder trying the
      new device: `revalidate_device_lock` finds the mismatch and demotes the
      account in place, writing `mode: demo` onto the user document. It returns
      early for an account that reads as demo, so it would never reach the bind
      branch and the holder would sit on Demo holding a live licence. The mode
      is re-stamped here. Nothing is resurrected: `effective_mode` still
      re-applies expiry, grace and the floating-lease check, so a licence that
      has run out stays demo either way.
    - **Registered device.** `POST /v1/devices/register` refuses any device but
      `users/{uid}.activeDeviceId` with `device_conflict`, and nothing else
      empties that field short of suspending the account. It is dropped here
      and the old device retired (`_retire_device`), so the new phone can
      register.
    - **Release hold.** The released id is stamped (`releasedDeviceId`,
      `releasedAt`) so registration can hold it off for
      `DEVICE_RELEASE_HOLD_HOURS`: the old phone's upload worker re-registers as
      soon as it reads `device_not_active`, and would otherwise take the account
      straight back (`repo.devices.released_device_held`).

    One read of the user and one batch, so the mode and the release land
    together. Nothing is written where `_live_holder` says no: a revoked
    licence, a seat that is revoked or on hold, an account that has moved to a
    different licence.

    Deliberately outside the caller's transaction: reading `users/{uid}` and
    then writing it inside one takes a lock on that document, which is what
    starved out concurrent claims before `_drop_superseded_demo` moved the
    same guarded read out of `claim_seat`.
    """
    live = _live_holder(license_id, lic, ref, scope, uid)
    if live is None:
        return ""
    user_ref, user = live
    patch = {
        **_mode_patch(_license_mode(lic)),
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    }
    released = user.get("activeDeviceId") or ""
    batch = db().batch()
    if released:
        patch.update({
            "activeDeviceId": _base.firestore.DELETE_FIELD,
            "releasedDeviceId": released,
            "releasedAt": _now(),
        })
        device_ref = db().collection("devices").document(released)
        if device_ref.get().exists:
            _retire_device(batch, device_ref, statuses.DEVICE_SUPERSEDED)
    batch.update(user_ref, patch)
    batch.commit()
    return released


def clear_device_lock(license_id: str, uid: str = "", *,
                      actor: str = ACTOR_STAFF) -> tuple[str, dict | None]:
    """Unbind a licence or a seat from the device it is on. Error code, or "".

    One primitive with three callers — Semper staff, institution IT, and the
    holder — because there is one operation. Since Gap A, clearing the lock is
    the *whole* device change: an empty lock reads as `_LOCK_UNBOUND`, and
    `revalidate_device_lock` binds it to whatever signs in next, first writer
    wins. Nothing is re-activated and nothing is typed.

    **Clearing is not revoking.** Entitlement, seat, lease and data are all
    untouched; the lock goes empty and the holder's device binding is released,
    so the new phone can register. A holder demoted in place by the mismatch
    they hit on the new device gets their mode back. Both are
    `_settle_holder`; without it, clearing would be half a device change.

    `uid` selects the seat on an institution licence. An individual licence
    holds its lock on the licence document itself, so `uid` is ignored there.

    The cooldown applies to `ACTOR_SELF` alone. A second factor proves *who*
    is asking, not *how often*, so one person could otherwise re-bind daily
    and pass a single licence round a lab. It is counted against
    `deviceChangedAt`, which only this path writes: a staff or IT clear
    neither reads nor writes that stamp, so a support request always works
    however recently the holder changed device themselves.

    On success the second element is the audit detail, including the device
    that was given up — the other half of the record `revalidate_device_lock`
    writes when the replacement binds. `previousDeviceId` is the lock's device
    and `releasedDeviceId` the registered one the account was signed out of;
    they can differ, and either can be empty (a lock that never bound, an
    account with nothing registered, or a holder `_settle_holder` skips). On `device_change_too_soon` it is
    `{"nextChangeAllowedAt": <ISO instant>}`, so the refusal can say when.
    """
    lic_snap = db().collection("licenses").document(license_id).get()
    if not lic_snap.exists:
        return errors.LICENSE_NOT_FOUND, None
    lic = lic_snap.to_dict() or {}
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        if not uid:
            return errors.SEAT_NOT_FOUND, None
        ref, scope = _seat_ref(license_id, uid), "seat"
    else:
        ref, scope = db().collection("licenses").document(license_id), "license"

    detail = {"scope": scope, "licenseId": license_id, "uid": uid, "actor": actor}
    not_found = errors.SEAT_NOT_FOUND if scope == "seat" else errors.LICENSE_NOT_FOUND

    if actor != ACTOR_SELF:
        # No invariant to protect: staff and IT have no cooldown, and two
        # clears landing together produce the same empty lock. A plain read
        # and update keeps the support path out of the transaction machinery
        # entirely.
        snap = ref.get()
        if not snap.exists:
            return not_found, None
        previous = (snap.to_dict() or {}).get("deviceIdLock") or ""
        ref.update({"deviceIdLock": "", "updatedAt": _base.firestore.SERVER_TIMESTAMP})
        released = _settle_holder(license_id, lic, ref, scope, uid)
        return "", {**detail, "previousDeviceId": previous, "releasedDeviceId": released}

    now = _now()
    cooldown = timedelta(days=max(0, settings.SELF_DEVICE_CHANGE_COOLDOWN_DAYS))

    @_base.firestore.transactional
    def _clear(tx) -> tuple[str, dict | None]:
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return not_found, None
        doc = snap.to_dict() or {}
        changed = as_utc(doc.get("deviceChangedAt"))
        if cooldown and changed and now - changed < cooldown:
            # The refusal says when, as the success does: "not yet" with no
            # date left the holder guessing how long to wait.
            return errors.DEVICE_CHANGE_TOO_SOON, {
                "nextChangeAllowedAt": (changed + cooldown).isoformat(),
            }
        tx.update(ref, {
            "deviceIdLock": "",
            "deviceChangedAt": now,
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        return "", {
            **detail,
            "previousDeviceId": doc.get("deviceIdLock") or "",
            "nextChangeAllowedAt": (now + cooldown).isoformat() if cooldown else "",
        }

    # Two self-service clears at once is the only way to lose on contention,
    # and the cooldown is exactly what one of them must lose. The winner has
    # just stamped `now`, so the next change is a cooldown from here.
    err, cleared = _run_tx(
        _clear, on_contended=lambda: (errors.DEVICE_CHANGE_TOO_SOON, {
            "nextChangeAllowedAt": (now + cooldown).isoformat(),
        }),
    )
    if not err:
        cleared = {**(cleared or {}),
                   "releasedDeviceId": _settle_holder(license_id, lic, ref, scope, uid)}
    return err, cleared


def set_seat_enabled(license_id: str, uid: str, enabled: bool) -> str:
    """Hold or resume a seat. Error code, or "".

    Hold drops the holder to Demo but does NOT free the slot — the seat still
    counts against maxSeats so IT can resume it without a fresh activation. A
    floating lease is released, though: a seat on hold cannot check out, so a
    lease left on it would only fill the pool until it expired. Resume
    restores the licensed mode in place, no data migration.

    A revoked seat, or a seat on a revoked licence, is refused either way.
    Resuming one used to set it active again without taking a slot back —
    licensed, on the roster, and not counted in `seatsUsed` — and holding one
    made a freed slot look occupied.
    """
    lic_ref = db().collection("licenses").document(license_id)
    ref = _seat_ref(license_id, uid)

    @_base.firestore.transactional
    def _set(tx) -> str:
        snap = ref.get(transaction=tx)
        lic_snap = lic_ref.get(transaction=tx)
        if not snap.exists:
            return errors.SEAT_NOT_FOUND
        seat = snap.to_dict() or {}
        if seat.get("status") == "revoked":
            return errors.SEAT_REVOKED
        if lic_snap.exists and (lic_snap.to_dict() or {}).get("status") == "revoked":
            return errors.LICENSE_REVOKED
        patch = {
            "status": "active" if enabled else "disabled",
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        }
        release = not enabled and _seat_lease_counted(seat)
        if release:
            patch.update(_lease_clear_patch())
        tx.update(ref, patch)
        if release and lic_snap.exists:
            tx.update(lic_ref, {"leasesActive": _base.firestore.Increment(-1)})
        return ""

    # Losing means a revoke, checkout or another hold landed on this seat
    # first. Nothing was written; IT tries again against the new state.
    err = _run_tx(_set, on_contended=lambda: errors.SEAT_BUSY)
    if err:
        return err
    if not enabled:
        _drop_user_to_demo_if_licensed(uid, license_id)
    else:
        user_ref = db().collection("users").document(uid)
        user_snap = user_ref.get()
        if user_snap.exists and (user_snap.to_dict() or {}).get("licenseId") == license_id:
            user_ref.update({
                **_mode_patch(MODE_LICENSED),
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            })
    return ""


def revoke_institution_seat(license_id: str, uid: str) -> bool:
    """Single-seat revoke: drops the holder to Demo and frees the slot
    (decrements seatsUsed) so another roster member can take it.

    Transactional for the same reason `claim_seat` is, and against the mirror
    image of its race: two concurrent revokes of one seat both read a status
    that is not yet "revoked", both decrement, and the pool undercounts by one
    forever. A counted lease is dropped in the same commit — a revoked seat
    must not keep occupying a floating slot, and one that ran out unswept is
    still in the count (see `_seat_lease_counted`).
    """
    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)

    @_base.firestore.transactional
    def _revoke(tx) -> bool:
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            return False
        seat = seat_snap.to_dict() or {}
        if seat.get("status") == "revoked":
            return True  # idempotent: already revoked, counters already settled
        lic_snap = lic_ref.get(transaction=tx)
        seats_used = int((lic_snap.to_dict() or {}).get("seatsUsed") or 0) if lic_snap.exists else 0
        held_lease = _seat_lease_counted(seat)

        tx.update(seat_ref, {
            "status": "revoked",
            # Stamped separately from `updatedAt` because reconciliation asks
            # "has the holder been back since the revoke?", and `updatedAt`
            # moves for any later write to the seat (a staff device clear, for
            # one) which would quietly reset that question.
            "revokedAt": _base.firestore.SERVER_TIMESTAMP,
            **_lease_clear_patch(),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
        counters = {}
        if seats_used > 0:
            counters["seatsUsed"] = _base.firestore.Increment(-1)
        if held_lease:
            counters["leasesActive"] = _base.firestore.Increment(-1)
        if counters and lic_snap.exists:
            tx.update(lic_ref, counters)
        return True

    # On contention the other writer won and did the same thing. Re-read to
    # answer precisely rather than reporting a failure that did not happen.
    revoked = _run_tx(_revoke, on_contended=lambda: seat_ref.get().exists)
    if revoked:
        _drop_user_to_demo_if_licensed(uid, license_id)
    return revoked
