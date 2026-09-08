from fastapi import APIRouter, Depends, Header, HTTPException

from .. import audit, firestore_repo as repo
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
        raise HTTPException(429, "rate_limited")
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    code, config = repo.activate_license(
        user["uid"], user.get("email") or "", device_id, body.key,
    )
    if code:
        status = {
            "license_not_found": 404,
            "user_not_found": 404,
            "license_already_redeemed": 409,
            "license_seats_exhausted": 409,
            # 403, not 410: the key is real and may be renewed in place, so
            # this is "you may not use it", not "it is gone".
            "license_expired": 403,
        }.get(code, 403)
        raise HTTPException(status, code)
    audit.record(
        user["uid"], device_id, action="LICENSE_ACTIVATE",
        detail={"plan": (config or {}).get("plan")},
    )
    return {"config": config}
