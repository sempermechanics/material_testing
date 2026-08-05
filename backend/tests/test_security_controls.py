import json
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import rate_limit
from app.config import settings

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.asyncio
async def test_api_security_headers(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    response = await client.get("/healthz")
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["content-security-policy"] == "frame-ancestors 'none'"
    assert response.headers["referrer-policy"] == "no-referrer"
    assert "camera=()" in response.headers["permissions-policy"]
    assert "strict-transport-security" not in response.headers


@pytest.mark.asyncio
async def test_hsts_only_on_cloud_run_https(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    monkeypatch.setattr(settings, "ON_CLOUD_RUN", True)
    insecure = await client.get("/healthz", headers={"x-forwarded-proto": "http"})
    assert "strict-transport-security" not in insecure.headers
    secure = await client.get("/healthz", headers={"x-forwarded-proto": "https"})
    assert secure.headers["strict-transport-security"] == "max-age=31536000"


def test_firestore_rules_are_deny_all_and_wired_to_existing_hosting():
    rules = (ROOT / "firestore.rules").read_text(encoding="utf-8")
    assert "allow read, write: if false;" in rules
    assert "if true" not in rules

    config = json.loads(
        (ROOT / "firebase-hosting" / "firebase.json").read_text(encoding="utf-8")
    )
    assert config["firestore"]["rules"] == "../firestore.rules"
    assert config["hosting"]["site"] == "indicvision-dic-app-auth"
    keys = {
        header["key"]
        for entry in config["hosting"]["headers"]
        if entry["source"] == "**"
        for header in entry["headers"]
    }
    assert {
        "X-Content-Type-Options",
        "X-Frame-Options",
        "Content-Security-Policy",
        "Referrer-Policy",
        "Permissions-Policy",
        "Strict-Transport-Security",
    } <= keys


@pytest.mark.asyncio
async def test_health_rate_limit_returns_429(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: False)
    response = await client.get("/healthz")
    assert response.status_code == 429
    assert response.json()["detail"] == "rate_limited"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("bucket_name", "method", "path", "json_body"),
    [
        (
            "device_register_bucket",
            "post",
            "/v1/devices/register",
            None,
        ),
        (
            "file_complete_bucket",
            "post",
            "/v1/files/file-1/complete",
            {"sessionId": "session-1", "driveFileId": "drive-1", "bytes": 1},
        ),
        (
            "session_verify_bucket",
            "get",
            "/v1/sessions?verify=true",
            None,
        ),
    ],
)
async def test_costly_routes_have_in_process_limit(
    client, monkeypatch, bucket_name, method, path, json_body
):
    monkeypatch.setattr(getattr(rate_limit, bucket_name), "allow", lambda key: False)
    if bucket_name == "device_register_bucket":
        public_key = ec.generate_private_key(ec.SECP256R1()).public_key()
        json_body = {
            "deviceId": "device-123",
            "publicKeyPem": public_key.public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            ).decode(),
        }
    response = await client.request(method, path, json=json_body)
    assert response.status_code == 429
    assert response.json()["detail"] == "rate_limited"
