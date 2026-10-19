import logging

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from .. import apps, audit, errors, repo
from .. import observability as obs
from .. import rate_limit
from ..errors import Refusal
from ..licenses import key_hash, key_prefix
from ..deps import attested_or_mfa_user, current_user, rate_limited, request_app
from ..models import LicenseActivate, LicenseClaim
from ..validation import require_header_identifier
from ._shared import named_app

log = logging.getLogger("semper.license")
router = APIRouter()


def _licence_event(event: str, license_id: str | None, refusal: Refusal | None = None,
                   *, uid: str, via: str | None = None) -> None:
    """One structured line per redemption or checkout, for the log-based
    metrics that count them per licence and app (A7.3). `appId` and
    `appVersion` come from the request context; no email is logged.

    `uid` is passed by the handler, which holds the user (TD-245): the
    request's bound uid is set by `current_user` inside a threadpool
    dependency, whose context this handler does not share.

    `via` says where a code was redeemed (`POST /v1/me/claim`); the app's
    own route leaves it out, so a line without it is the installed app."""
    obs.log_event(
        log, logging.INFO, event,
        uid=uid or None,
        licenseId=license_id or None,
        outcome="refused" if refusal else "ok",
        errorCode=refusal.code if refusal else None,
        via=via,
    )


def _typed_licence_id(key: str, refusal: Refusal | None) -> str | None:
    """The licence a typed key names, for the log. None when no licence has
    that key: the hash of a mistyped key identifies nothing worth counting."""
    if refusal is not None and refusal.code == errors.LICENSE_NOT_FOUND:
        return None
    return key_hash(key)


@router.post("/v1/licenses/activate")
def activate_license(
    body: LicenseActivate,
    user=Depends(current_user),
    x_device_id: str = Header(default=""),
    app=Depends(request_app),
):
    """Redeem a Professional (or re-entered) key locked to this email and device
    (the device of the app that asks, ADR-010).

    Kept, unchanged, for compatibility with any installed caller. There are
    no runtime licence keys: the account is the anchor, and a code is
    redeemed on the web account page (`POST /v1/me/claim`, below), which
    runs this same `repo.activate_license`. No released build ever offered
    a key field (v1.2-beta.4 carries an `activateLicense` client with no
    caller), and current builds do not call this route. Retire it with
    TD-278.
    """
    rate_limit.enforce(rate_limit.license_activate_bucket, user["uid"])
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    try:
        config = repo.activate_license(
            user["uid"], user.get("email") or "", device_id, body.key, app,
        )
    except Refusal as refusal:
        _licence_event("activation", _typed_licence_id(body.key, refusal), refusal,
                       uid=user["uid"])
        raise
    _licence_event("activation", _typed_licence_id(body.key, None), uid=user["uid"])
    audit.record(
        user["uid"], device_id, action="LICENSE_ACTIVATE",
        detail={"mode": (config or {}).get("mode")},
    )
    return {"config": config}


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
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    device_id = require_header_identifier(x_device_id, name="device_id", maximum=128)
    try:
        config = repo.checkout_lease(user, device_id)
    except Refusal as refusal:
        _licence_event("checkout", user.get("licenseId"), refusal, uid=user["uid"])
        raise
    _licence_event("checkout", user.get("licenseId"), uid=user["uid"])
    return {"config": config}


@router.post("/v1/licenses/release")
def release_lease(user=Depends(current_user)):
    """Give a floating seat back so someone else can take it.

    Idempotent — releasing a lease that already lapsed frees nothing and still
    succeeds. Audited, unlike checkout: a release is a discrete act, not a
    heartbeat.
    """
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    config = repo.release_lease(user)
    audit.record(
        user["uid"], action="INSTITUTION_LEASE_RELEASE",
        target={"type": "lease", "id": f"{user.get('licenseId')}/{user['uid']}"},
    )
    return {"config": config}


@router.post(
    "/v1/licenses/unbind",
    dependencies=[rate_limited(rate_limit.license_activate_bucket)],
)
def unbind_device(
    ctx=Depends(attested_or_mfa_user),
    header_app=Depends(request_app),
    app: str = Query(default=""),
):
    """"Use Semper on a different device" — the holder's own device change.

    Until now only institution IT could unbind a device, which left an
    individual customer, and any member whose IT is slow, writing to support
    for something they can prove they are entitled to do. Since Gap A,
    clearing the lock is the whole operation: the licence or seat goes
    unbound, and the next device to sign in binds it, first writer wins.

    **Clearing is not revoking.** The entitlement, the seat, the lease and
    every analysis stay exactly as they are; only the lock goes empty. Nothing
    has to be typed on the new device.

    Step-up rather than plain USER: this is worth more than a bearer token,
    and it is reachable from a browser. But a second factor proves *who* is
    asking, not *how often*, so it is also the one caller subject to
    SELF_DEVICE_CHANGE_COOLDOWN_DAYS — one person could otherwise re-bind
    daily and pass a single licence round a lab. Staff and IT are not, so a
    support request always works.

    The order on the new device matters and the app should follow it: sign in
    (USER-tier, so `POST /v1/devices/register` works before any licence
    binds), let the first authed request bind the lock, and only then restore.
    File content is device-attested, so restoring first fails on a device the
    user has legitimately just moved to.

    Moves one app's device (ADR-010): the app that asks, by `X-App-Id`, or
    from a browser, which cannot send that header, `?app=materialtesting`.
    Each app has its own cooldown. No app named is Semper.
    """
    named = named_app(app, header_app)
    user = ctx.user
    license_id = user.get("licenseId") or ""
    if not license_id:
        raise HTTPException(404, errors.NO_LICENSE)
    # Too soon is 429 with the instant and a Retry-After (`errors.STATUS`): the
    # answer is "not yet", and nothing about the entitlement has changed.
    cleared = repo.clear_device_lock(license_id, user["uid"], actor=repo.ACTOR_SELF, app=named)
    audit.record(
        user["uid"], action="LICENSE_DEVICE_UNBIND",
        target={"type": cleared.get("scope") or "license", "id": license_id},
        detail={k: str(v) for k, v in cleared.items()},
    )
    return {"licenseId": license_id, "app": named, "deviceIdLock": "",
            "previousDeviceId": cleared.get(apps.field("previousDeviceId", named)) or "",
            "nextChangeAllowedAt": cleared.get("nextChangeAllowedAt") or ""}


#: `via` on a claim's event and audit row, by how the caller stepped up
#: (`deps.Caller.via`): a browser with a second factor is the web account
#: page; a signed request is the app.
_CLAIM_VIA = {"mfa": "web", "device": "app"}


@router.post(
    "/v1/me/claim",
    dependencies=[rate_limited(rate_limit.license_activate_bucket)],
)
def claim_code(
    body: LicenseClaim,
    ctx=Depends(attested_or_mfa_user),
    app=Depends(request_app),
):
    """Redeem a one-time claim code onto the signed-in account.

    How a paid entitlement reaches an account now that there are no runtime
    licence keys: staff mint a code, the holder types it on the web account
    page, and the account carries the licence from then on (an individual
    licence locked to their address, or a seat on an institution licence
    their address's domain may join). The same `repo.activate_license` as
    `POST /v1/licenses/activate`, so the same checks, refusal codes and
    statuses (`errors.STATUS`), and the same answer, `{"config": …}`, after
    which the page re-reads `/v1/me`.

    The user step-up, as for unbind and the bundle: an attested device, or
    a browser with a second factor on a recent sign-in. A code is worth a
    licence, so a bare ID token is not enough to spend it. The address is
    the whole claim to an email- or domain-locked licence, so an unverified
    one is refused first (403 `email_not_verified`), as institution invites
    and IT routes refuse it.

    From a browser no device is named, so none is compared or bound: the
    lock is settled by the phone's own next request (`revalidate_device_lock`).
    A signed request is checked against its device for the asking app, as
    the app route is (ADR-010).

    Rate-limited on the activation bucket, shared with the app route, so
    the two cannot be used together to guess codes twice as fast; in the
    decorator, ahead of the signature check, so a 429 leaves a signed
    request's nonce unspent. Each attempt logs `event="activation"` with
    `via`, and writes a `LICENSE_CLAIM_CODE` audit row holding the code's
    prefix and the outcome, never the code.
    """
    user = ctx.user
    uid = user["uid"]
    address = repo.verified_address(user)
    if not address:
        raise HTTPException(403, errors.EMAIL_NOT_VERIFIED)
    device_id = ctx.device_id or ""
    via = _CLAIM_VIA.get(ctx.via, ctx.via or "web")
    detail = {"keyPrefix": key_prefix(body.code), "via": via}
    try:
        config = repo.activate_license(uid, address, device_id, body.code, app)
    except Refusal as refusal:
        _licence_event("activation", _typed_licence_id(body.code, refusal), refusal,
                       uid=uid, via=via)
        audit.record(uid, device_id or None, action="LICENSE_CLAIM_CODE", outcome="DENIED",
                     detail={**detail, "errorCode": refusal.code})
        raise
    license_id = key_hash(body.code)
    _licence_event("activation", license_id, uid=uid, via=via)
    audit.record(
        uid, device_id or None, action="LICENSE_CLAIM_CODE", outcome="OK",
        target={"type": "license", "id": license_id},
        detail={**detail, "mode": (config or {}).get("mode")},
    )
    return {"config": config}
