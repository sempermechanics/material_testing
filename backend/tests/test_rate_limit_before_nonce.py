"""A 429 on a device-signed route must leave the nonce unspent.

The app's RetryOnTransient re-sends a 429'd request unchanged, on the promise
that the limit rejected it before anything happened. When the bucket was
checked inside the handler, `verified_device` had already claimed the nonce,
so the retry was refused as a replay: a batch erase of more than three
analyses left some in the cloud every time (Pixel 6, 2026-09-23).
"""
import ast
import base64
import hashlib
import pathlib
import time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import fake_firestore
from app import audit, deps, firestore_repo as repo, rate_limit
from app.config import settings

ROUTERS = pathlib.Path(__file__).resolve().parents[1] / "app" / "routers"

# Every dependency that ends in `verified_device` (and so spends a nonce).
SIGNED = {
    "verified_device",
    "attested_or_mfa_user",
    "attested_or_mfa_admin",
    "attested_or_mfa_admin_fresh",
    "device_or_legacy_reader",
    "institution_admin_stepup",
}


@pytest.fixture
def signed_user(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    user = {"uid": "u1", "email": "u1@example.com", "role": "user",
            "access_status": "APPROVED", "activeDeviceId": "dev-1"}
    store._data["users"] = {"u1": dict(user)}
    store._data["devices"] = {"dev-1": {"uid": "u1", "status": "ACTIVE", "publicKeyPem": pem}}
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "u1", "email": "u1@example.com", "email_verified": True},
    )
    monkeypatch.setattr(repo, "get_or_create_user", lambda claims, device_id=None: dict(user))
    return priv


def _headers(priv, method, path, nonce):
    msg = (nonce + method + path).encode() + hashlib.sha256(b"").digest()
    sig = base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
    return {"Authorization": "Bearer ok", "X-Device-Id": "dev-1",
            "X-Nonce": nonce, "X-Signature": sig}


@pytest.mark.asyncio
async def test_a_rate_limited_erase_can_be_resent_unchanged(signed_user, client, monkeypatch):
    claimed = []
    real_claim = repo.claim_client_nonce

    def spy(nonce, *a, **k):
        claimed.append(nonce)
        return real_claim(nonce, *a, **k)

    monkeypatch.setattr(repo, "claim_client_nonce", spy)
    path = "/v1/sessions/s-gone"
    headers = _headers(signed_user, "DELETE", path, f"t1.{int(time.time())}.{'A' * 22}")

    monkeypatch.setattr(rate_limit.erase_bucket, "allow", lambda key: False)
    r = await client.delete(path, headers=headers)
    assert r.status_code == 429
    assert r.json()["detail"] == "rate_limited"
    assert int(r.headers["retry-after"]) >= 1
    assert claimed == [], "the nonce was spent on a request the limit refused"

    # The client's retry: byte-for-byte the same request, now under the limit.
    monkeypatch.setattr(rate_limit.erase_bucket, "allow", lambda key: True)
    r = await client.delete(path, headers=headers)
    assert r.status_code == 404
    assert r.json()["detail"] == "session_not_found"

    # And the nonce is single-use once it has actually been spent.
    r = await client.delete(path, headers=headers)
    assert r.status_code == 401
    assert r.json()["detail"] == "nonce_invalid_or_replayed"


@pytest.mark.asyncio
async def test_a_rate_limited_request_never_reaches_the_challenge_store(signed_user, client, monkeypatch):
    """Same for a server-issued challenge, which is deleted when consumed."""
    monkeypatch.setattr(
        repo, "consume_nonce", lambda *a, **k: pytest.fail("challenge consumed before the limit"),
    )
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda key: False)
    path = "/v1/sessions"
    r = await client.post(path, headers=_headers(signed_user, "POST", path, "server-challenge-1"))
    assert r.status_code == 429


def _signed_routes():
    for p in sorted(ROUTERS.glob("*.py")):
        tree = ast.parse(p.read_text(encoding="utf-8"))
        for f in ast.walk(tree):
            if not isinstance(f, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            if not any(ast.unparse(d).startswith("router.") for d in f.decorator_list):
                continue
            defaults = f.args.defaults + [d for d in f.args.kw_defaults if d]
            if any(isinstance(d, ast.Call) and d.args and ast.unparse(d.args[0]) in SIGNED
                   for d in defaults):
                yield p.name, f


def test_no_signed_route_checks_its_bucket_in_the_handler():
    """Signed routes take their limit as `dependencies=[rate_limited(...)]`."""
    offenders = [
        f"{name}:{f.lineno} {f.name}"
        for name, f in _signed_routes()
        for node in ast.walk(f)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
        and (
            (node.func.attr == "allow" and ast.unparse(node.func.value).startswith("rate_limit."))
            or ast.unparse(node.func) == "rate_limit.enforce"
        )
    ]
    assert offenders == []


def test_the_handler_check_is_found_when_present():
    """The guard above recognises `rate_limit.enforce` in a handler body."""
    tree = ast.parse("def f():\n    rate_limit.enforce(rate_limit.erase_bucket, 'u')\n")
    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)]
    assert any(ast.unparse(c.func) == "rate_limit.enforce" for c in calls)


@pytest.mark.asyncio
@pytest.mark.parametrize("method,path,bucket,headers", [
    ("GET", "/healthz", "health_bucket", {}),
    ("POST", "/v1/challenge", "challenge_bucket", {"X-Device-Id": "dev-1"}),
    ("GET", "/v1/institutions/licenses", "institution_bucket", {}),
    ("GET", "/v1/sessions?verify=true", "session_verify_bucket", {}),
])
async def test_an_unsigned_429_says_when_to_retry(client, monkeypatch, method, path, bucket, headers):
    """TD-54: the in-handler limits used to answer a bare 429. The app honours
    `Retry-After`, and without it backs off less than a slow bucket refills."""
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(getattr(rate_limit, bucket), "allow", lambda key: False)
    r = await client.request(method, path, headers=headers)
    assert r.status_code == 429
    assert r.json()["detail"] == "rate_limited"
    assert int(r.headers["retry-after"]) >= 1


def test_the_signed_routes_are_found():
    """Guards the test above against silently matching nothing."""
    assert len(list(_signed_routes())) >= 20


def test_retry_after_is_the_wait_for_one_token():
    bucket = rate_limit.TokenBucket(rate_per_sec=0.2, burst=3.0)
    assert bucket.retry_after("fresh") == 1
    for _ in range(3):
        assert bucket.allow("k")
    assert not bucket.allow("k")
    assert bucket.retry_after("k") == 5
