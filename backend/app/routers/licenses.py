from fastapi import APIRouter, Depends, Header, HTTPException

from .. import audit, errors, firestore_repo as repo
from .. import rate_limit
from ..deps import current_user
from ..models import LicenseActivate
from ..validation import require_header_identifier

router = APIRouter()


@router.post("/v1/licenses/activate")
def activate_license(
    body: LicenseActivate,
    user=Depends(current_user),
    x_device_id: str = Header(default=""),
):
    """Redeem a Professional (or re-entered) key locked to this email and device."""
    if not rate_limit.license_activate_bucket.allow(user["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    code, config = repo.activate_license(
        user["uid"], user.get("email") or "", device_id, body.key,
    )
    if code:
        status = {
            errors.LICENSE_NOT_FOUND: 404,
            errors.USER_NOT_FOUND: 404,
            errors.LICENSE_ALREADY_REDEEMED: 409,
            errors.LICENSE_SEATS_EXHAUSTED: 409,
            # 403, not 410: the key is real and may be renewed in place, so
            # this is "you may not use it", not "it is gone".
            errors.LICENSE_EXPIRED: 403,
        }.get(code, 403)
        raise HTTPException(status, code)
    audit.record(
        user["uid"], device_id, action="LICENSE_ACTIVATE",
        detail={"plan": (config or {}).get("plan")},
    )
    return {"config": config}


#: Lease outcomes that are the pool working as designed rather than a fault.
#: `no_floating_seat` is a 200 elsewhere — see the checkout docstring — but as
#: an explicit checkout it is a refusal the caller asked for and gets 409.
_LEASE_STATUS = {
    errors.NO_LICENSE: 404,
    errors.LICENSE_NOT_FOUND: 404,
    errors.LICENSE_REVOKED: 403,
    errors.LICENSE_EXPIRED: 403,
    errors.NOT_ELIGIBLE: 403,
    errors.SEATING_NOT_FLOATING: 409,
    errors.NO_FLOATING_SEAT: 409,
}


@router.post("/v1/licenses/checkout")
def checkout_lease(
    user=Depends(current_user),
    x_device_id: str = Header(default=""),
):
    """Take or renew a floating seat.

    Re-calling this IS the heartbeat: renewing an existing lease extends it
    without consuming a second slot, which is why there is no separate
    heartbeat route. The response carries `leaseExpiresAt` and
    `leaseHeartbeatMinutes` so the app knows when to call again.

    Deliberately unaudited. A client calls this every half hour per active
    user; `FILE_DOWNLOAD` and `lastSeenAt` both record what an unconditional
    write on a per-request path costs. The roster changes that matter —
    joining and leaving the license — are audited where they happen.

    `409 no_floating_seat` means the pool is full right now. It is not an
    error in the account: the member stays eligible and demo, and the app
    offers to try again rather than treating it as a dead end.
    """
    if not rate_limit.institution_bucket.allow(user["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    code, config = repo.checkout_lease(user, device_id)
    if code:
        raise HTTPException(_LEASE_STATUS.get(code, 403), code)
    return {"config": config}


@router.post("/v1/licenses/release")
def release_lease(user=Depends(current_user)):
    """Give a floating seat back so someone else can take it.

    Idempotent — releasing a lease that already lapsed frees nothing and still
    succeeds. Audited, unlike checkout: a release is a discrete act, not a
    heartbeat.
    """
    if not rate_limit.institution_bucket.allow(user["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    code, config = repo.release_lease(user)
    if code:
        raise HTTPException(_LEASE_STATUS.get(code, 403), code)
    audit.record(
        user["uid"], action="INSTITUTION_LEASE_RELEASE",
        target={"type": "lease", "id": f"{user.get('licenseId')}/{user['uid']}"},
    )
    return {"config": config}
