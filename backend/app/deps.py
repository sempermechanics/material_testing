"""FastAPI dependencies: user auth (Google ID token) and device assertion."""
import base64
import hashlib

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from fastapi import Header, HTTPException, Request

from . import audit, firestore_repo as repo
from .config import settings
from .google_auth import verify_google_id_token

_DEV_USER = {"uid": "dev-user", "email": "dev@local", "role": "admin",
             "access_status": "APPROVED", "activeDeviceId": "dev-device"}
_DEV_DEVICE = {"deviceId": "dev-device", "uid": "dev-user", "status": "ACTIVE"}


async def current_user(authorization: str = Header(default="")) -> dict:
    if settings.DEV_INSECURE_AUTH:
        return _DEV_USER
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing_bearer")
    try:
        claims = verify_google_id_token(authorization[7:])
    except Exception:  # noqa: BLE001
        audit.record(action="AUTH_DENIED", outcome="DENIED", detail={"stage": "id_token"})
        raise HTTPException(401, "invalid_token")
    user = repo.get_or_create_user(claims)
    if user["access_status"] != "APPROVED":
        raise HTTPException(403, "not_approved")
    return user


async def verified_device(
    request: Request,
    authorization: str = Header(default=""),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> dict:
    user = await current_user(authorization)
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
