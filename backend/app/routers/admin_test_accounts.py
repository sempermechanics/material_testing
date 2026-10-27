"""Staff test accounts for QC and CI (ADR-027), under /v1/admin.

Reading the list takes the ordinary admin token, like every staff read.
Create, extend, a new password, releasing one phone and end take the fresh
staff step-up (`attested_or_mfa_admin_fresh`), the tier a whole-licence
revoke takes: each hands over, prolongs, moves or ends a working login. The flows are in
`services/testaccounts.py`.
"""
from fastapi import APIRouter, Depends, Response

from .. import rate_limit
from ..deps import admin_user, rate_limited
from ..models import AdminTestAccountCreate, AdminTestAccountExtend
from ..services import testaccounts as test_accounts
from ..step_up import attested_or_mfa_admin_fresh
from ..validation import DeviceId, Uid

router = APIRouter(prefix="/v1/admin/test-accounts")

#: A response that carries a password must not sit in any cache.
_NO_STORE = {"Cache-Control": "no-store"}


@router.post("", status_code=201, dependencies=[rate_limited(rate_limit.admin_bucket)])
def create_test_account(body: AdminTestAccountCreate, response: Response,
                        ctx=Depends(attested_or_mfa_admin_fresh)):
    """Create a pre-approved, licensed test login: `{uid, email, password,
    purpose, expiresAt, maxDevices, licenseId}`. The password is in this
    answer only. 403 `test_accounts_disabled` when `TEST_ACCOUNT_DOMAINS` is
    empty or does not hold the address's domain; 409 `email_in_use` for an
    address that has an account, an Auth user, a licence, or is staff's."""
    response.headers.update(_NO_STORE)
    return test_accounts.create(body, ctx.user["uid"])


@router.get("")
def list_test_accounts(admin=Depends(admin_user)):
    """Every test account: `{accounts: [{uid, email, purpose, expiresAt,
    maxDevices, note, createdAt, activeDevices, devices, status}]}`, newest
    first; `devices` is `[{deviceId, app, model, registeredAt}]`, its ACTIVE
    phones.
    `status` is `active`, `expired` (past its date, not yet ended by the
    daily job) or `suspended`."""
    rate_limit.enforce(rate_limit.admin_bucket, admin["uid"])
    return test_accounts.listing()


@router.post("/{uid}/extend", dependencies=[rate_limited(rate_limit.admin_bucket)])
def extend_test_account(uid: Uid, body: AdminTestAccountExtend,
                        ctx=Depends(attested_or_mfa_admin_fresh)):
    """End the account and its licence `days` from now (1..90). Answers the
    account's row, as the list shows it. 404 `user_not_found` for a uid that
    is not a test account."""
    return test_accounts.extend(uid, body.days, ctx.user["uid"])


@router.post("/{uid}/password", dependencies=[rate_limited(rate_limit.admin_bucket)])
def reset_test_account_password(uid: Uid, response: Response,
                                ctx=Depends(attested_or_mfa_admin_fresh)):
    """A new password, `{password}`, once; the account's refresh tokens are
    revoked."""
    response.headers.update(_NO_STORE)
    return test_accounts.reset_password(uid, ctx.user["uid"])


@router.post("/{uid}/devices/{device_id}/release",
             dependencies=[rate_limited(rate_limit.admin_bucket)])
def release_test_account_device(uid: Uid, device_id: DeviceId,
                                ctx=Depends(attested_or_mfa_admin_fresh)):
    """Release one ACTIVE phone of the account so another can take its place
    (TD-249); the released phone may not register for this account again.
    Answers the account's row. 404 `user_not_found` for a uid that is not a
    test account, `device_not_found` for a device that is not one of its
    ACTIVE phones."""
    return test_accounts.release_device(ctx.user["uid"], uid, device_id)


@router.delete("/{uid}", status_code=204,
               dependencies=[rate_limited(rate_limit.admin_bucket)])
def end_test_account(uid: Uid, ctx=Depends(attested_or_mfa_admin_fresh)):
    """End the account now: suspended, tokens and licence revoked, its data
    erased as its own erasure would, its Firebase Auth users deleted."""
    test_accounts.end(uid, ctx.user["uid"])
    return Response(status_code=204)
