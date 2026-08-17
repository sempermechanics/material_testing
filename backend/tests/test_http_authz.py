"""HTTP authz tests with DEV_INSECURE_AUTH off — 401/403 trust boundaries."""
import base64
import hashlib

import fake_firestore
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import audit, deps, firestore_repo as repo
from app.config import settings


@pytest.fixture
def secure(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


def _pem_and_priv():
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return priv, pem


def _sign(priv, nonce, method, path, body: bytes) -> str:
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    return base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


def _approved_user(**extra):
    return {
        "uid": "u-approved",
        "email": "user@example.com",
        "role": "user",
        "access_status": "APPROVED",
        "activeDeviceId": "dev-1",
        **extra,
    }


@pytest.mark.asyncio
async def test_missing_bearer_is_401(secure, client, monkeypatch):
    monkeypatch.setattr(deps, "verify_id_token", lambda *_: (_ for _ in ()).throw(AssertionError()))
    r = await client.get("/v1/me")
    assert r.status_code == 401
    assert r.json()["detail"] == "missing_bearer"


@pytest.mark.asyncio
async def test_invalid_token_is_401(secure, client, monkeypatch):
    def boom(_token):
        raise ValueError("bad token")

    monkeypatch.setattr(deps, "verify_id_token", boom)
    r = await client.get("/v1/me", headers={"Authorization": "Bearer not-a-jwt"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"


@pytest.mark.asyncio
async def test_pending_user_is_403(secure, client, monkeypatch):
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "u-pending", "email": "p@e.com", "email_verified": True},
    )
    secure._data["users"] = {
        "u-pending": {
            "email": "p@e.com", "role": "user", "access_status": "PENDING",
        },
    }
    monkeypatch.setattr(
        repo, "get_or_create_user",
        lambda claims, device_id=None: {**secure._data["users"]["u-pending"], "uid": "u-pending"},
    )
    r = await client.get("/v1/me", headers={"Authorization": "Bearer ok"})
    assert r.status_code == 403
    assert r.json()["detail"] == "not_approved"


@pytest.mark.asyncio
async def test_non_admin_cannot_list_users(secure, client, monkeypatch):
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": "u1", "email": "u@e.com"})
    monkeypatch.setattr(repo, "get_or_create_user", lambda claims, device_id=None: _approved_user())
    r = await client.get("/v1/admin/users", headers={"Authorization": "Bearer ok"})
    assert r.status_code == 403
    assert r.json()["detail"] == "not_admin"


@pytest.mark.asyncio
async def test_cross_user_session_is_404(secure, client, monkeypatch):
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": "u-approved", "email": "u@e.com"})
    monkeypatch.setattr(repo, "get_or_create_user", lambda claims, device_id=None: _approved_user())
    secure._data["sessions"] = {
        "s-other": {"uid": "someone-else", "status": "COMPLETED", "localSessionId": "x"},
    }
    r = await client.get("/v1/sessions/s-other/files", headers={"Authorization": "Bearer ok"})
    assert r.status_code == 404
    assert r.json()["detail"] == "session_not_found"


@pytest.mark.asyncio
async def test_admin_mutation_requires_device_attestation(secure, client, monkeypatch):
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "admin-1", "email": "admin@indicvision.com", "email_verified": True},
    )
    monkeypatch.setattr(
        repo, "get_or_create_user",
        lambda claims, device_id=None: {
            "uid": "admin-1", "email": "admin@indicvision.com",
            "role": "admin", "access_status": "APPROVED", "activeDeviceId": "adev",
        },
    )
    secure._data["users"] = {
        "admin-1": {
            "email": "admin@indicvision.com", "role": "admin", "access_status": "APPROVED",
        },
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }
    # ID token alone — no device headers — must not mutate.
    r = await client.post(
        "/v1/admin/users/target/approve",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code in (400, 401, 409)
    assert secure._data["users"]["target"]["access_status"] == "PENDING"


@pytest.mark.asyncio
async def test_admin_mutation_with_valid_device_attestation(secure, client, monkeypatch):
    priv, pem = _pem_and_priv()
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "admin-1", "email": "admin@indicvision.com", "email_verified": True},
    )
    monkeypatch.setattr(
        repo, "get_or_create_user",
        lambda claims, device_id=None: {
            "uid": "admin-1", "email": "admin@indicvision.com",
            "role": "admin", "access_status": "APPROVED", "activeDeviceId": "adev",
        },
    )
    secure._data["users"] = {
        "admin-1": {
            "email": "admin@indicvision.com", "role": "admin", "access_status": "APPROVED",
        },
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }
    secure._data["devices"] = {
        "adev": {"uid": "admin-1", "status": "ACTIVE", "publicKeyPem": pem},
    }
    monkeypatch.setattr(repo, "consume_nonce", lambda *a, **k: True)

    path = "/v1/admin/users/target/approve"
    body = b""
    nonce = "nonce-admin-1"
    sig = _sign(priv, nonce, "POST", path, body)
    r = await client.post(
        path,
        headers={
            "Authorization": "Bearer ok",
            "X-Device-Id": "adev",
            "X-Nonce": nonce,
            "X-Signature": sig,
        },
        content=body,
    )
    assert r.status_code == 200
    assert secure._data["users"]["target"]["access_status"] == "APPROVED"
