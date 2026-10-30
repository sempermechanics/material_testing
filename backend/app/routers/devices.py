from fastapi import APIRouter, Depends, Header, HTTPException, Request

from .. import apps, audit, errors, notify, repo
from .. import rate_limit
from ..deps import current_user, request_app
from ..step_up import second_factor
from ..models import DeviceReg
from ..validation import require_header_identifier

router = APIRouter()


@router.post("/v1/devices/register", status_code=201)
def register_device(body: DeviceReg, request: Request, user=Depends(current_user),
                    app=Depends(request_app), x_app_id: str = Header(default="")):
    # A retired app id (`apps.RETIRED`) reports the device id of the app that
    # succeeded it on the same phone, so registering would replace that app's
    # key and fail every call it signs (`bad_signature`). Nothing is written.
    if apps.is_retired(x_app_id):
        audit.record(user["uid"], body.deviceId, action="DEVICE_REGISTER", outcome="DENIED",
                     detail={"reason": "app_retired", "app": app})
        raise HTTPException(410, errors.APP_RETIRED)
    rate_limit.enforce(rate_limit.device_register_bucket, user["uid"])
    tag = repo.test_account_of(user)  # No hold and no mail (ADR-027).
    # A clear or a staff release just released THIS phone; its upload worker
    # re-registers on `device_not_active`, which would sign it straight back
    # in. Held whatever else the account has registered since: it holds
    # several phones (ADR-033). Installed builds read any 409 here as "bound
    # to another device".
    if tag is None and repo.released_device_held(user, body.deviceId, app):
        audit.record(user["uid"], body.deviceId, action="DEVICE_REGISTER", outcome="DENIED",
                     detail={"reason": "released", "app": app})
        raise HTTPException(409, errors.DEVICE_CONFLICT)
    # Whether this registration may later step up for staff or IT: the
    # token that registers the key proved a second factor (ADR-010, device
    # trust). An ordinary account has none, and its device is still its own.
    eligible = bool(second_factor(request))
    # The device bound to a different account (one account per device:
    # `device_in_use`) and a full test account are checked inside the
    # registration's transaction, so two registrations at once cannot both
    # pass. An account holds several phones per app (ADR-033); each app has
    # its own (ADR-010).
    try:
        registered = repo.register_device(user["uid"], body, app, step_up_eligible=eligible)
    except errors.Refusal as refusal:
        _audit_refused(user["uid"], refusal.code, body.deviceId, app)
        raise
    healed = bool(registered.get("healed"))
    _audit_rotated(user["uid"], registered.get("rotated"), app, body.deviceId, tag is not None)
    audit.record(user["uid"], body.deviceId, action="DEVICE_REBIND" if healed else "DEVICE_REGISTER",
                 detail={"app": app, "stepUpEligible": eligible, **({"testAccount": True} if tag else {})})
    if not healed and tag is None:  # A new device tells its holder (TD-232); a heal is not one.
        notify.device_registered(user["uid"], repo.verified_address(user), body.deviceId, app,
                                 model=body.model)
    return {"deviceId": body.deviceId, "healed": healed}


def _audit_refused(uid: str, code: str, device_id: str, app: str) -> None:
    """The DENIED row for a refused registration: the phone bound to another
    account (`device_in_use`, naming it), or a phone staff released from
    this test account (TD-249), which may not take its place back."""
    if code == errors.DEVICE_IN_USE:
        audit.record(uid, device_id, action="DEVICE_IN_USE", outcome="DENIED",
                     detail={"owner": (repo.get_device(device_id) or {}).get("uid")})
    elif code == errors.DEVICE_RELEASED:
        audit.record(uid, device_id, action="DEVICE_REGISTER", outcome="DENIED",
                     detail={"reason": "staff_released", "app": app, "testAccount": True})


def _audit_rotated(uid: str, rotated, app: str, by: str, test_account: bool) -> None:
    """One row per phone a full account gave up for `by`: a CI test
    account's oldest (TD-248), or a real account's oldest unlicensed phone
    past `MAX_PHONES_PER_APP` (ADR-033)."""
    for old in rotated or ():
        audit.record(uid, old, action="DEVICE_ROTATED",
                     detail={"app": app, "by": by, **({"testAccount": True} if test_account else {})})


@router.post("/v1/challenge")
def challenge(user=Depends(current_user), x_device_id: str = Header(default="")):
    x_device_id = require_header_identifier(
        x_device_id, name="device_id", maximum=128
    )
    rate_limit.enforce(rate_limit.challenge_bucket, user["uid"])
    return {"nonce": repo.issue_nonce(user["uid"], x_device_id)}
