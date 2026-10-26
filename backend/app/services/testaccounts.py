"""The staff test-account flows behind `routers/admin_test_accounts.py` and the
expiry job (ADR-027): create, extend, new password, release one phone, end,
and ending every account past its date.

The password a create or a reset makes is returned to the caller and sent to
the Firebase Auth project, and goes nowhere else: no log line, audit row or
exception here carries it.
"""
import logging
import secrets
from datetime import datetime, timedelta, timezone

from .. import apps, audit, errors, google_auth, repo, statuses
from .. import observability as obs
from ..config import settings
from ..errors import Refusal
from ..licenses import normalize_email
from ..models import AdminTestAccountCreate
from . import account as account_service

log = logging.getLogger("semper.admin")

#: How long a QC account lasts when staff name no `days`.
DEFAULT_QC_DAYS = 30
#: The furthest ahead a test account's end may be set, at create or extend.
MAX_DAYS = 90
#: The actor the expiry job's audit rows name.
SYSTEM_ACTOR = "system"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def new_password() -> str:
    """20 random URL-safe characters (120 bits): `token_urlsafe(15)`."""
    return secrets.token_urlsafe(15)


def _audit(actor_uid: str, action: str, uid: str, detail: dict) -> None:
    """An audit row for a test-account action: target the account, tagged
    `testAccount` so the trail separates it from real accounts."""
    audit.record(actor_uid, action=action, target={"type": "user", "id": uid},
                 detail={"testAccount": True, **detail})


def _refuse_address(email: str) -> None:
    """Refuse an address a test account may not take: outside
    `TEST_ACCOUNT_DOMAINS` (403 `test_accounts_disabled`, the feature off
    for it), or a staff address, or one already holding an account, an Auth
    user or a licence (409 `email_in_use`). A staff address is refused so a
    test account can never be an admin (`stepUpEligible`, ADR-010)."""
    domain = email.rsplit("@", 1)[-1]
    if not settings.TEST_ACCOUNT_DOMAINS or domain not in settings.TEST_ACCOUNT_DOMAINS:
        raise Refusal(errors.TEST_ACCOUNTS_DISABLED)
    if email in settings.ADMIN_EMAILS:
        raise Refusal(errors.EMAIL_IN_USE)
    if repo.find_user_by_email(email) is not None or repo.licence_held_by(email, user={}):
        raise Refusal(errors.EMAIL_IN_USE)
    if google_auth.auth_user_exists(email):
        raise Refusal(errors.EMAIL_IN_USE)


def _expiry(purpose: str, days: int | None):
    """When an account made now with `days` ends: None for a CI account that
    names no `days`, else `days` (QC default 30) from now."""
    if days is None and purpose == repo.PURPOSE_CI:
        return None
    return _now() + timedelta(days=days or DEFAULT_QC_DAYS)


def create(body: AdminTestAccountCreate, admin_uid: str) -> dict:
    """Make the Auth user, the approved account, and its licence, in that
    order; the answer carries the password, once. A failure after the Auth
    user exists removes what was made, so the address can be tried again."""
    email = normalize_email(body.email)
    _refuse_address(email)
    expires = _expiry(body.purpose, body.days)
    password = new_password()
    try:
        uid = google_auth.create_auth_user(email, password)
    except google_auth.EmailInUse as exc:
        raise Refusal(errors.EMAIL_IN_USE) from exc
    tag = {"purpose": body.purpose, "createdByUid": admin_uid, "createdAt": _now(),
           "expiresAt": expires, "maxDevices": body.maxDevices, "note": body.note}
    try:
        repo.create_test_account_user(uid, email, tag)
        minted = repo.create_individual_license(
            email_lock=email, created_by_uid=admin_uid, expires_at=expires,
            grace_days=0 if expires is not None else None, note=body.note, test_account=True,
        )
    except Exception:
        repo.delete_all_user_data(uid)
        google_auth.delete_auth_user(uid)
        raise
    license_id = minted["license"]["id"]
    if not minted["claimedByUid"]:
        # The invite still delivers it on the first sign-in.
        obs.log_event(log, logging.WARNING, "test_account_licence_not_attached", uid=uid,
                      licenseId=license_id, errorCode=minted["inviteError"] or minted["claimError"])
    _audit(admin_uid, "test_account_created", uid, {
        "purpose": body.purpose, "expiresAt": str(expires) if expires else None,
        "maxDevices": body.maxDevices, "licenseId": license_id,
        "addressTag": audit.address_tag(email), "noteLength": len(body.note),
    })
    return {"uid": uid, "email": email, "password": password, "purpose": body.purpose,
            "expiresAt": expires, "maxDevices": body.maxDevices, "licenseId": license_id}


def _require(uid: str) -> dict:
    user = repo.get_test_account(uid)
    if user is None:
        raise Refusal(errors.USER_NOT_FOUND)
    return user


def extend(uid: str, days: int, admin_uid: str) -> dict:
    """End the account `days` from now (at most `MAX_DAYS`): its tag and its
    licence, whose holder the licence edit re-stamps. Ends it sooner too, if
    that is what `days` says: staff set the date, the route does not
    second-guess it. Answers the account's row."""
    user = _require(uid)
    expires = _now() + timedelta(days=min(days, MAX_DAYS))
    licences = repo.licences_of_test_account(user)
    for license_id in licences:
        repo.update_license(license_id, {"expiresAt": expires, "allowShorten": True,
                                         "graceDays": 0}, admin_uid)
    tag = repo.set_test_account_expiry(uid, repo.test_account_of(user), expires)
    _audit(admin_uid, "test_account_extended", uid, {
        "expiresAt": str(expires), "days": days, "licenseIds": licences,
    })
    return repo.row_of_test_account({**user, "testAccount": tag})


def reset_password(uid: str, admin_uid: str) -> dict:
    """A new password, and every refresh token revoked, so a login handed to
    someone earlier stops working once its ID token expires."""
    _require(uid)
    password = new_password()
    google_auth.set_auth_password(uid, password)
    auth_uids = repo.sign_in_uids(uid)
    revoked = sum(1 for sub in auth_uids if google_auth.revoke_sessions(sub))
    _audit(admin_uid, "test_account_password_reset", uid, {
        "sessionsRevoked": revoked == len(auth_uids), "authUsers": len(auth_uids),
    })
    return {"password": password}


def release_device(actor_uid: str, uid: str, device_id: str) -> dict:
    """Release one ACTIVE phone of the test account (TD-314), so another can
    take its place without ending the account. The released phone may not
    register for this account again (`repo.staff_released_from`); its
    sessions stay. 404 `user_not_found` for a uid that is not a test account,
    `device_not_found` for a device that is not one of its ACTIVE phones.
    Answers the account's row."""
    user = _require(uid)
    device = repo.get_device(device_id) or {}
    if device.get("uid") != uid or device.get("status") != statuses.DEVICE_ACTIVE:
        raise Refusal(errors.DEVICE_NOT_FOUND)
    repo.release_test_device(uid, device_id)
    _audit(actor_uid, "test_account_device_released", uid, {
        "deviceId": device_id, "app": apps.canonical(device.get("app")),
    })
    return repo.row_of_test_account(user)


def end(uid: str, actor_uid: str, *, action: str = "test_account_ended") -> dict:
    """End the account now: suspend it, revoke its tokens and its licence,
    then erase it as the holder's own erasure does (`erase_data`, run as
    staff), Auth users included. The licence is revoked first so the erasure
    does not hand it back to the address (`_give_back_license`). Audited as
    `action`; returns the row's detail."""
    user = _require(uid)
    tag = repo.test_account_of(user)
    repo.set_user_status(uid, statuses.ACCESS_SUSPENDED)
    auth_uids = repo.sign_in_uids(uid)
    revoked = sum(1 for sub in auth_uids if google_auth.revoke_sessions(sub))
    licences = repo.licences_of_test_account(user)
    for license_id in licences:
        repo.revoke_license(license_id, actor_uid)
    erased = account_service.erase_data(user)
    detail = {"purpose": tag.get("purpose") or "", "licenseIds": licences,
              "sessionsRevoked": revoked == len(auth_uids), **erased}
    _audit(actor_uid, action, uid, detail)
    return detail


def expire_due() -> dict:
    """End every test account past its `expiresAt`, as `test_account_expired`.
    One failure does not stop the rest; it is logged, counted, and found
    again by tomorrow's run (the account is still past its date)."""
    due = repo.expired_test_accounts()
    ended, failed = [], []
    for user in due:
        try:
            end(user["uid"], SYSTEM_ACTOR, action="test_account_expired")
            ended.append(user["uid"])
        except Exception as e:  # noqa: BLE001 - one account must not stop the run
            obs.log_event(log, logging.ERROR, "test_account_expiry_failed", uid=user["uid"],
                          outcome="error", errorCode=type(e).__name__)
            failed.append(user["uid"])
    return {"expiredTestAccounts": len(due), "ended": ended, "failed": failed}


def listing() -> dict:
    return {"accounts": repo.list_test_accounts()}
