"""FastAPI dependencies: user auth (Google ID token) and device assertion."""
import base64
import binascii
import hashlib
import logging

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from fastapi import Depends, Header, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from . import audit, firestore_repo as repo
from .config import settings
from .google_auth import verify_id_token
from .validation import require_header_identifier
from . import observability as obs

log = logging.getLogger("indic.auth")

_DEV_USER = {"uid": "dev-user", "email": "dev@local", "role": "admin",
             "access_status": "APPROVED", "activeDeviceId": "dev-device",
             "emailVerified": True, "plan": "professional"}
_DEV_DEVICE = {"deviceId": "dev-device", "uid": "dev-user", "status": "ACTIVE"}


def _client_bearer(authorization: str, x_forwarded_authorization: str) -> str:
    """The end-user's bearer token.

    Behind API Gateway / ESPv2 the gateway replaces `Authorization` with its own
    backend service-account token and moves the original client token to
    `X-Forwarded-Authorization`. Direct (non-gateway) calls just use
    `Authorization`. Prefer the forwarded header when present.
    """
    return x_forwarded_authorization or authorization


def current_user(
    request: Request,
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
    x_device_id: str = Header(default=""),
) -> dict:
    """Resolve the caller from a Google ID token.

    Plain `def` (no awaits) so FastAPI/Starlette runs this in the threadpool —
    `verify_id_token` and Firestore must not block the event loop. Sets
    `request.state.uid` so access logs work on authn-only routes (not only
    device-attested ones).
    """
    if settings.DEV_INSECURE_AUTH:
        user = _DEV_USER
    else:
        bearer = _client_bearer(authorization, x_forwarded_authorization)
        if not bearer.startswith("Bearer "):
            log.warning("no bearer token: authorization=%s x_forwarded=%s",
                        bool(authorization), bool(x_forwarded_authorization))
            raise HTTPException(401, "missing_bearer")
        try:
            claims = verify_id_token(bearer[7:])
        except Exception as e:  # noqa: BLE001
            log.warning("id_token verify FAILED (x_forwarded_present=%s): %s",
                        bool(x_forwarded_authorization), e)
            audit.record(action="AUTH_DENIED", outcome="DENIED", detail={"stage": "id_token"})
            raise HTTPException(401, "invalid_token")
        try:
            require_header_identifier(
                str(claims.get("sub", "")), name="uid", maximum=128
            )
        except HTTPException as exc:
            raise HTTPException(401, "invalid_token") from exc
        try:
            user = repo.get_or_create_user(claims, device_id=x_device_id or None)
        except repo.DeviceInUseError as exc:
            raise HTTPException(409, "device_in_use") from exc
        if user["access_status"] != "APPROVED":
            raise HTTPException(403, "not_approved")
        # Re-validate the license/seat device lock on every call that carries
        # X-Device-Id — not just at activation time. A revoked key, a disabled
        # or revoked campus seat, or a device that no longer matches the lock
        # drops the account to Demo immediately (fails closed); it never
        # touches the account's stored sessions/files.
        if x_device_id:
            user = repo.revalidate_device_lock(user, x_device_id)
    try:
        request.state.uid = user["uid"]
        obs.bind_uid(user["uid"])
    except Exception:  # noqa: BLE001 - logging enrichment must never fail a request
        pass
    return user


def admin_user(user: dict = Depends(current_user)) -> dict:
    """Authenticated caller that is an admin (role=admin or in ADMIN_EMAILS).

    ID-token based (no device signature) so list/read admin screens and curl in
    dev mode work without a registered device. **Mutating** admin routes
    (approve / revoke / config-patch) additionally require `verified_device` so
    a stolen ID token alone cannot change access — see those handlers.
    """
    email = (user.get("email") or "").lower()
    if user.get("role") != "admin" and email not in settings.ADMIN_EMAILS:
        raise HTTPException(403, "not_admin")
    return user


async def verified_device(
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """Device-attested caller: active device + single-use nonce + ECDSA signature.

    Keeps `async` only for `await request.body()`. Firestore device/nonce lookups
    run in the threadpool so they do not stall the event loop.
    """
    if settings.DEV_INSECURE_AUTH:
        return {"user": user, "device": _DEV_DEVICE}

    x_device_id = require_header_identifier(
        x_device_id, name="device_id", maximum=128
    )
    x_nonce = require_header_identifier(
        x_nonce, name="nonce", maximum=128
    )
    if not x_signature or len(x_signature) > 512:
        raise HTTPException(400, "invalid_signature")

    dev = await run_in_threadpool(repo.get_device, x_device_id)
    if not dev or dev["uid"] != user["uid"] or dev["status"] != "ACTIVE":
        raise HTTPException(409, "device_not_active")
    if not await run_in_threadpool(repo.consume_nonce, x_nonce, user["uid"], x_device_id):
        raise HTTPException(401, "nonce_invalid_or_replayed")
    # current_user already re-validated the lock for THIS x_device_id when it
    # was present on the request — but device-attested routes are the ones
    # that actually spend the entitlement (create a session, download a file),
    # so re-check here too rather than trust a value resolved before the
    # signature/nonce were even verified.
    user = await run_in_threadpool(repo.revalidate_device_lock, user, x_device_id)

    body = await request.body()
    msg = (x_nonce + request.method + request.url.path).encode() + hashlib.sha256(body).digest()
    try:
        pub = load_pem_public_key(dev["publicKeyPem"].encode())
        signature = base64.b64decode(x_signature, validate=True)
        pub.verify(signature, msg, ec.ECDSA(hashes.SHA256()))
    except (binascii.Error, InvalidSignature, ValueError):
        audit.record(user["uid"], x_device_id, action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "signature"})
        raise HTTPException(401, "bad_signature")
    try:
        request.state.device_id = x_device_id
        obs.bind_device(x_device_id)
    except Exception:  # noqa: BLE001
        pass
    return {"user": user, "device": dev}


async def device_or_legacy_reader(
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    """`verified_device` with a temporary compatibility window for `/uploads`.

    Testers still run a build that fetches the resume list (which carries Drive
    upload capability URLs) with an ID token only — no device signature. Rejecting
    them the moment the backend goes private would kill their resume path. Until
    the fleet has moved, accept an unattested read here; every other route that
    mints or consumes those URLs stays strictly device-attested.

    This never opens a downgrade: a client that presents *any* device header, or
    any deployment with REQUIRE_ATTESTED_UPLOADS=1, is held to the full
    `verified_device` path. Flip the flag once `legacy_unattested_uploads` is
    zero, then delete this wrapper.
    """
    if settings.REQUIRE_ATTESTED_UPLOADS or x_device_id or x_nonce or x_signature:
        return await verified_device(request, user, x_device_id, x_nonce, x_signature)
    obs.log_event(log, logging.WARNING, "legacy_unattested_uploads", uid=user["uid"])
    return {"user": user, "device": {}}
