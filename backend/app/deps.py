"""FastAPI dependencies: user auth (Google ID token) and device assertion."""
import base64
import hashlib
import logging

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from fastapi import Header, HTTPException, Request

from . import audit, firestore_repo as repo
from .config import settings
from .google_auth import verify_id_token

log = logging.getLogger("indic.auth")

_DEV_USER = {"uid": "dev-user", "email": "dev@local", "role": "admin",
             "access_status": "APPROVED", "activeDeviceId": "dev-device"}
_DEV_DEVICE = {"deviceId": "dev-device", "uid": "dev-user", "status": "ACTIVE"}


def _client_bearer(authorization: str, x_forwarded_authorization: str) -> str:
    """The end-user's bearer token.

    Behind API Gateway / ESPv2 the gateway replaces `Authorization` with its own
    backend service-account token and moves the original client token to
    `X-Forwarded-Authorization`. Direct (non-gateway) calls just use
    `Authorization`. Prefer the forwarded header when present.
    """
    return x_forwarded_authorization or authorization


async def current_user(
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
) -> dict:
    if settings.DEV_INSECURE_AUTH:
        return _DEV_USER
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
    user = repo.get_or_create_user(claims)
    if user["access_status"] != "APPROVED":
        raise HTTPException(403, "not_approved")
    return user


async def admin_user(
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
) -> dict:
    """Authenticated caller that is an admin (role=admin or in ADMIN_EMAILS).

    ID-token based (no device signature) so it works from an in-app admin screen
    or from curl in dev mode. Admin actions are low-frequency and audited.
    """
    user = await current_user(authorization, x_forwarded_authorization)
    email = (user.get("email") or "").lower()
    if user.get("role") != "admin" and email not in settings.ADMIN_EMAILS:
        raise HTTPException(403, "not_admin")
    return user


async def verified_device(
    request: Request,
    authorization: str = Header(default=""),
    x_forwarded_authorization: str = Header(default=""),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    user = await current_user(authorization, x_forwarded_authorization)
    # Surface the caller to the access-log middleware (best-effort).
    try:
        request.state.uid = user["uid"]
    except Exception:  # noqa: BLE001 - logging enrichment must never fail a request
        pass
    if settings.DEV_INSECURE_AUTH:
        return {"user": user, "device": _DEV_DEVICE}

    dev = repo.get_device(x_device_id)
    if not dev or dev["uid"] != user["uid"] or dev["status"] != "ACTIVE":
        raise HTTPException(409, "device_not_active")
    if not repo.consume_nonce(x_nonce, user["uid"], x_device_id):
        raise HTTPException(401, "nonce_invalid_or_replayed")

    body = await request.body()
    msg = (x_nonce + request.method + request.url.path).encode() + hashlib.sha256(body).digest()
    try:
        pub = load_pem_public_key(dev["publicKeyPem"].encode())
        pub.verify(base64.b64decode(x_signature), msg, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        audit.record(user["uid"], x_device_id, action="AUTH_DENIED", outcome="DENIED",
                     detail={"stage": "signature"})
        raise HTTPException(401, "bad_signature")
    return {"user": user, "device": dev}
