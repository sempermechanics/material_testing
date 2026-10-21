"""The step-up tiers: a caller held to an attested device, or to a second
factor on a recent sign-in.

Split out of `deps.py` (TD-293), which keeps identity (`current_user`,
`admin_user`) and device attestation (`verified_device`). This module builds
on those, never the reverse: `deps` does not import it.
"""
import logging
import time
from dataclasses import replace

from fastapi import Depends, Header, HTTPException, Request
from firebase_admin import auth as fb_auth
from starlette.concurrency import run_in_threadpool

from . import audit, errors, rate_limit
from . import observability as obs
from .config import settings
from .deps import _DEV_DEVICE, Caller, _client_bearer, admin_user, current_user, verified_device
from .google_auth import verify_id_token

log = logging.getLogger("semper.auth")


def _second_factor(claims: dict) -> str:
    """The second factor the token records, or "" when there was none.

    Firebase sets `firebase.sign_in_second_factor` only on a token minted after
    an MFA challenge actually completed. Enrolment alone does not set it, so
    this is "they proved a second factor for *this* session", not "they own
    one" — which is the property worth checking.
    """
    firebase = claims.get("firebase")
    if not isinstance(firebase, dict):
        return ""
    return str(firebase.get("sign_in_second_factor") or "")


def second_factor(request: Request) -> str:
    """The second factor the request's verified ID token records, or ""."""
    return _second_factor(getattr(request.state, "claims", None) or {})


def _auth_age_seconds(claims: dict) -> float | None:
    """How long ago this session authenticated, or None if the token does not
    say. None is treated as too old: a token that will not state its own age
    cannot satisfy a freshness requirement."""
    raw = claims.get("auth_time")
    try:
        return time.time() - float(raw)
    except (TypeError, ValueError):
        return None


def _step_up(base, window: str, doc: str, *, device_needs_second_factor: bool):
    """A route dependency: `base`'s caller, held to `step_up_check` with the
    freshness window `settings.<window>` (read per request, so a test or a
    redeploy that changes it is honoured) and the tier's device rule."""
    async def dependency(
        request: Request,
        user: dict = Depends(base),
        x_device_id: str = Header(default=""),
        x_nonce: str = Header(default=""),
        x_signature: str = Header(default=""),
    ) -> Caller:
        return await step_up_check(request, user, x_device_id, x_nonce, x_signature,
                                   max_age_seconds=getattr(settings, window),
                                   device_needs_second_factor=device_needs_second_factor)

    dependency.__doc__ = doc
    return dependency


#: State-changing Semper-staff caller: an attested device, or a 2FA browser.
#:
#: The device path is preferred: a request carrying any device header is held
#: to the full `verified_device` check, and the device must have been
#: registered from a second-factor sign-in (`stepUpEligible`, ADR-010 device
#: trust): registration takes a bare ID token, so without that a stolen staff
#: token could register its own key and sign as staff. The browser path exists because a browser cannot
#: produce an ECDSA attestation. It needs a completed **second factor** on the
#: ID token (so a token stolen from a password-only session is refused) and a
#: **recent** sign-in (ADMIN_WEB_REAUTH_SECONDS, so a token that leaks later
#: stops working). That is weaker than attestation — a phished live MFA
#: session inside the window can mint a licence — and is enabled because staff
#: administer licences from a computer; ADMIN_WEB_MFA_ENABLED=0 withdraws it.
#: Reads do not go through here, only the routes that change state.
attested_or_mfa_admin = _step_up(
    admin_user, "ADMIN_WEB_REAUTH_SECONDS",
    "Semper staff, attested device or a second factor on a recent sign-in.",
    device_needs_second_factor=True,
)
#: The same, with the tighter window whole-licence revoke and delete ask for:
#: the console forces password (or Google re-auth) plus TOTP first.
attested_or_mfa_admin_fresh = _step_up(
    admin_user, "ADMIN_WEB_REVOKE_REAUTH_SECONDS",
    "Semper staff, step-up within the revoke window.",
    device_needs_second_factor=True,
)
#: The same step-up for an account holder acting on their own licence: moving
#: it to a new device, pulling an analysis out through a browser. The question
#: does not change with who asks; only the tier below it does, so this
#: authorises nothing beyond what the holder already holds.
#:
#: The holder's own phone steps up here without having been registered from
#: a second-factor sign-in: an ordinary account has no second factor, and the
#: same phone signature already reaches every device-attested route
#: (`verified_device`: file content, account export and erasure), so asking
#: more of this tier alone would protect nothing and lock every holder out.
attested_or_mfa_user = _step_up(
    current_user, "ADMIN_WEB_REAUTH_SECONDS",
    "An account holder, attested device or a second factor on a recent sign-in.",
    device_needs_second_factor=False,
)


async def step_up_check(
    request: Request,
    user: dict,
    x_device_id: str,
    x_nonce: str,
    x_signature: str,
    *,
    max_age_seconds: int,
    device_needs_second_factor: bool,
) -> Caller:
    """The step-up itself, shared by the admin, user and institution tiers.

    Institution IT calls it directly, after its membership check, so a
    foreign licence 404s without disclosing that MFA was the next gate.

    A device attestation is preferred and checked in full whenever the request
    carries any device header, so the phone keeps exactly the guarantee it
    had. The browser path — second factor, recent sign-in — is the fallback,
    governed by ADMIN_WEB_MFA_ENABLED and the caller's freshness window: the
    trade being made is the same one either way, and a deployment that
    withdraws the browser path should withdraw all of it.

    `device_needs_second_factor` (staff and IT): the device path also needs
    a device registered from a second-factor sign-in (`stepUpEligible`),
    else 403 `mfa_required`, which is what the phone is missing. The holder
    tier passes False; see `attested_or_mfa_user`.
    """
    if settings.DEV_INSECURE_AUTH:
        return Caller(user, _DEV_DEVICE, via="dev")

    if x_device_id or x_nonce or x_signature:
        ctx = await verified_device(request, user, x_device_id, x_nonce, x_signature)
        if device_needs_second_factor and ctx.device.get("stepUpEligible") is not True:
            audit.record(user["uid"], ctx.device_id, action="AUTH_DENIED", outcome="DENIED",
                         detail={"stage": "device_second_factor"})
            raise HTTPException(403, errors.MFA_REQUIRED)
        return replace(ctx, via="device")

    if not settings.ADMIN_WEB_MFA_ENABLED:
        # No device headers and no browser path: this is the attestation-only
        # posture, and the honest answer is that the call needs a device.
        raise HTTPException(400, errors.INVALID_SIGNATURE)

    claims = getattr(request.state, "claims", None) or {}
    factor = _second_factor(claims)
    if not factor:
        audit.record(user["uid"], action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "second_factor"})
        raise HTTPException(403, errors.MFA_REQUIRED)

    age = _auth_age_seconds(claims)
    if age is None or age > max_age_seconds:
        audit.record(user["uid"], action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "reauth", "ageSeconds": age})
        raise HTTPException(403, errors.REAUTH_REQUIRED)

    if settings.CHECK_REVOKED_ON_STEP_UP:
        await run_in_threadpool(_require_not_revoked, request, user["uid"])

    request.state.device_id = ""
    return Caller(user, via="mfa", second_factor=factor)


def _require_not_revoked(request: Request, uid: str) -> None:
    """Re-verify the browser caller's bearer with revocation checked, or 401.

    Only the second-factor path asks (`CHECK_REVOKED_ON_STEP_UP`): a browser
    session is what a revoked or disabled user can keep using until the token
    expires, while a phone's signed path is held to its device record.

    A revoked token or a disabled account is `invalid_token`, audited as stage
    `revoked`. Any other failure of the check — the runtime account lost
    `roles/firebaseauth.admin` on the Auth project, or the Auth API is down —
    also refuses, 401 `invalid_token`, audited as stage `revocation_check` and
    logged as `revocation_check_failed`. Fail closed, because whoever turned
    the setting on asked for revoked users to be kept out, and a check that
    cannot answer cannot say this one is not. Not 503: the console and app
    already handle `invalid_token`, and a new wire code would reach neither.

    The one exception is this instance's own Auth Admin budget
    (`rate_limit.auth_admin_bucket`, TD-239). Spent, the check is not asked
    and the answer is 503 `auth_check_busy` with Retry-After, logged as
    `auth_admin_rate_limited`: the token may well be fine, and telling the
    caller it is invalid would sign a working console out.
    """
    bearer = _client_bearer(
        request.headers.get("authorization", ""),
        request.headers.get("x-forwarded-authorization", ""),
    )
    token = bearer[7:] if bearer.startswith("Bearer ") else ""
    if not rate_limit.auth_admin_allowed():
        obs.log_event(log, logging.WARNING, "auth_admin_rate_limited", uid=uid,
                      stage="revocation_check", outcome="refused",
                      errorCode=errors.AUTH_CHECK_BUSY, dependency="firebase_auth")
        raise HTTPException(
            503, errors.AUTH_CHECK_BUSY,
            headers={"Retry-After": str(rate_limit.auth_admin_bucket.retry_after(
                rate_limit.AUTH_ADMIN_KEY))},
        )
    try:
        verify_id_token(token, check_revoked=True)
    except (fb_auth.RevokedIdTokenError, fb_auth.UserDisabledError) as e:
        obs.log_event(log, logging.WARNING, "step_up_revoked", uid=uid,
                      stage="revoked", errorCode=type(e).__name__)
        audit.record(uid, action="AUTH_DENIED", outcome="DENIED", detail={"stage": "revoked"})
        raise HTTPException(401, errors.INVALID_TOKEN) from e
    except Exception as e:  # noqa: BLE001 - fail closed; see the docstring
        obs.log_event(log, logging.ERROR, "revocation_check_failed", uid=uid,
                      stage="revocation_check", errorCode=type(e).__name__,
                      dependency="firebase_auth")
        audit.record(uid, action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "revocation_check"})
        raise HTTPException(401, errors.INVALID_TOKEN) from e
