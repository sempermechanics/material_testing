"""Reconciliation of an institution's intended seats against the accounts that hold them.
"""

from .. import errors
from ..licenses import (
    KIND_INSTITUTION,
    MODE_LICENSED,
    as_utc,
    normalize_kind,
)

from ._base import (
    db,
)
from .user_config import (
    _stored_mode,
)


# ---------------- reconciliation: intended seats vs entitled accounts ----------------
# IT's roster is a statement of intent. `seatsUsed` moves the instant a seat is
# revoked, so the institution console can only ever show what IT *meant* to
# happen. What actually happened is spread across two other places: the
# holder's user document, which `revoke_institution_seat` demotes outside its
# transaction and therefore best-effort, and the holder's device, which keeps
# working off a cached /v1/config until it next comes back. Reconciliation is
# the read that puts all three side by side.

#: Why a revoked seat is still counted as running. Returned per seat, because
#: the two causes want opposite responses: one is a fault to repair, the other
#: a lag to wait out.
STILL_LICENSED = "still_licensed"
NO_CHECKIN_SINCE_REVOKE = "no_checkin_since_revoke"
#: Why a revoked seat is settled.
MOVED_ON = "moved_on"
NO_ACCOUNT = "no_account"
CHECKED_IN = "checked_in"
#: An active seat nobody has taken up. Counted inside `active`, not against it.
NEVER_CLAIMED = "never_claimed"


def _seat_revoked_at(seat: dict):
    """When the seat was revoked, as well as we can know it.

    Both revoke paths stamp `revokedAt`. A seat revoked before that field
    existed has only `updatedAt`, which for a revoked seat is the same instant
    — nothing writes to one again in the ordinary course.
    """
    return seat.get("revokedAt") or seat.get("updatedAt")


def reconcile_institution_seats(license_id: str) -> tuple[str, dict | None]:
    """Compare every seat on an institution licence against its holder.

    Returns `(error_code, report)`; exactly one of the two is set.

    Three buckets, and the one that matters is the third:

    * **active** — the seat is on the roster. `neverClaimed` counts the subset
      nobody has signed in to take up; they are invited, not entitled.
    * **revokedConfirmed** — the seat is revoked and the revoke has landed:
      the account moved on, never existed, or is demoted *and* has made a
      request since, which is when its device last re-read its entitlement.
    * **revokedStillRunning** — the seat is revoked and something is still
      entitled by it. `still_licensed` means the demotion never landed and the
      backend itself would answer "licensed"; revoking the seat again repairs
      it, the operation being idempotent and re-running the demotion.
      `no_checkin_since_revoke` means the record is right and the device has
      simply not been back to hear it — nothing to repair, and Gap B's
      periodic refresh is what shortens it.

    Costs one read per seat, which is why it is admin-tier and on demand
    rather than a field on any hot path. The holders come back in one batched
    `get_all` rather than one round trip per seat (TD-62).
    """
    lic_snap = db().collection("licenses").document(license_id).get()
    if not lic_snap.exists:
        return errors.LICENSE_NOT_FOUND, None
    lic = lic_snap.to_dict() or {}
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        # An individual licence has one redeemer and no roster, so there are
        # no two counts to reconcile; saying so beats returning an empty
        # report that reads like a clean bill of health.
        return errors.KIND_NOT_INSTITUTION, None

    seats, counts = [], {
        "active": 0, "neverClaimed": 0,
        "revokedConfirmed": 0, "revokedStillRunning": 0,
    }
    entitled = 0
    roster = [
        (seat_doc.to_dict() or {}, seat_doc.id)
        for seat_doc in db().collection("licenses").document(license_id)
        .collection("seats").stream()
    ]
    uids = [seat.get("uid") or seat_id for seat, seat_id in roster]
    users_ref = db().collection("users")
    # get_all yields snapshots in no particular order; key them by id.
    holders = {
        snap.id: snap.to_dict()
        for snap in db().get_all([users_ref.document(uid) for uid in dict.fromkeys(uids)])
        if snap.exists
    } if uids else {}
    for (seat, _seat_id), uid in zip(roster, uids):
        user = holders.get(uid)

        on_this_license = bool(user) and user.get("licenseId") == license_id
        holds = on_this_license and _stored_mode(user) == MODE_LICENSED
        revoked_at = _seat_revoked_at(seat)
        last_seen = user.get("lastSeenAt") if user else None

        if seat.get("status") != "revoked":
            bucket = "active"
            reason = "" if holds else NEVER_CLAIMED
            if not holds:
                counts["neverClaimed"] += 1
        elif holds:
            bucket, reason = "revokedStillRunning", STILL_LICENSED
        elif not user:
            bucket, reason = "revokedConfirmed", NO_ACCOUNT
        elif not on_this_license:
            bucket, reason = "revokedConfirmed", MOVED_ON
        elif _seen_since(last_seen, revoked_at):
            bucket, reason = "revokedConfirmed", CHECKED_IN
        else:
            bucket, reason = "revokedStillRunning", NO_CHECKIN_SINCE_REVOKE

        counts[bucket] += 1
        if holds:
            entitled += 1
        seats.append({
            "uid": uid,
            "email": seat.get("email") or (user or {}).get("email") or "",
            "status": seat.get("status") or "active",
            "bucket": bucket,
            "reason": reason,
            "revokedAt": revoked_at if seat.get("status") == "revoked" else None,
            "lastSeenAt": last_seen,
            "userMode": _stored_mode(user) if user else "",
            "userLicenseId": (user or {}).get("licenseId") or "",
        })

    intended = int(lic.get("seatsUsed") or 0)
    return "", {
        "licenseId": license_id,
        "maxSeats": int(lic.get("maxSeats") or 0),
        # What IT believes, straight off the counter their console reads...
        "intended": intended,
        # ...what the counter would say if recounted from the seats themselves.
        # A difference between these two is a counter drift, a different fault
        # from anything the buckets describe.
        "intendedRecounted": counts["active"],
        # Accounts the backend would answer "licensed" for under this licence
        # right now. Equals active - neverClaimed + revokedStillRunning's
        # still_licensed half, by construction.
        "entitled": entitled,
        "counts": counts,
        "seats": seats,
    }


def _seen_since(last_seen, revoked_at) -> bool:
    """Has the account made a request since the seat was revoked?

    `lastSeenAt` is throttled to `_LAST_SEEN_THROTTLE`, so on its own it
    would answer this an hour late. The revoke stamps `seenCheckpointAt` on
    the same write that demotes the holder, and `_touch_user` treats a
    lastSeenAt older than that checkpoint as stale — so the account's first
    request after a revoke moves the stamp regardless of the throttle and
    this reads exactly.

    It still errs in one direction, and deliberately in the safe one: a seat
    revoked before either field existed, or one whose holder was already
    pointed elsewhere when the revoke ran (so no demotion write happened),
    has no checkpoint, and a missing revoke date is answered False. Over-
    reporting a revoke as unlanded is the safe way to be wrong; the opposite
    would tell an operator a device had been told when it had not.
    """
    last_seen, revoked_at = as_utc(last_seen), as_utc(revoked_at)
    if last_seen is None:
        return False
    if revoked_at is None:
        # No stamp at all: the seat predates both fields. We cannot date the
        # revoke, so we cannot claim the account has been back since it.
        return False
    return last_seen > revoked_at
