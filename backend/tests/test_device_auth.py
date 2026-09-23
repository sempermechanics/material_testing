"""The device-attestation dependency (deps.verified_device): ECDSA-P256
signature over nonce+method+path+sha256(body), device-active check, and nonce
consumption. Uses a real keypair and a fake request — no live backend, and the
DEV_INSECURE_AUTH bypass is explicitly off."""
import base64
import hashlib

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import HTTPException
from starlette.requests import Request

from app import deps


@pytest.fixture
def keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return priv, pem


def _make_request(method: str, path: str, body: bytes) -> Request:
    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    scope = {
        "type": "http",
        "method": method,
        "path": path,
        "headers": [],
        "query_string": b"",
    }
    return Request(scope, receive)


def _sign(priv, nonce: str, method: str, path: str, body: bytes) -> str:
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    sig = priv.sign(msg, ec.ECDSA(hashes.SHA256()))
    return base64.b64encode(sig).decode()


@pytest.fixture
def wired(monkeypatch, keypair):
    """verified_device with the bypass off, a canned approved user, an active
    device holding our public key, and a nonce that consumes once."""
    priv, pem = keypair
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", False)

    def fake_current_user(*a, **k):
        return {"uid": "u1", "email": "a@b.com", "access_status": "APPROVED"}

    monkeypatch.setattr(deps, "current_user", fake_current_user)
    monkeypatch.setattr(
        deps.repo, "get_device",
        lambda did: {"deviceId": did, "uid": "u1", "status": "ACTIVE", "publicKeyPem": pem},
    )
    consumed = {"used": False}

    def fake_consume(nonce, uid, device_id):
        if consumed["used"]:
            return False
        consumed["used"] = True
        return True

    monkeypatch.setattr(deps.repo, "consume_nonce", fake_consume)
    return priv


async def _call(priv, method="POST", path="/v1/sessions", body=b'{"x":1}', nonce="n1", sig=None):
    request = _make_request(method, path, body)
    user = {"uid": "u1", "email": "a@b.com", "access_status": "APPROVED"}
    return await deps.verified_device(
        request=request,
        user=user,
        x_device_id="d1",
        x_nonce=nonce,
        x_signature=sig if sig is not None else _sign(priv, nonce, method, path, body),
    )


async def test_valid_signature_accepted(wired):
    ctx = await _call(wired)
    assert ctx["user"]["uid"] == "u1"
    assert ctx["device"]["deviceId"] == "d1"


async def test_tampered_body_rejected(wired):
    priv = wired
    # Sign the real body, then present a different one.
    good_sig = _sign(priv, "n1", "POST", "/v1/sessions", b'{"x":1}')
    with pytest.raises(HTTPException) as e:
        await _call(priv, body=b'{"x":999}', sig=good_sig)
    assert e.value.status_code == 401


async def test_wrong_key_rejected(monkeypatch, wired):
    # A signature from a different key must not verify against the stored PEM.
    attacker = ec.generate_private_key(ec.SECP256R1())
    bad_sig = _sign(attacker, "n1", "POST", "/v1/sessions", b'{"x":1}')
    with pytest.raises(HTTPException) as e:
        await _call(wired, sig=bad_sig)
    assert e.value.status_code == 401


async def test_inactive_device_rejected(monkeypatch, wired):
    monkeypatch.setattr(
        deps.repo, "get_device",
        lambda did: {"deviceId": did, "uid": "u1", "status": "REVOKED", "publicKeyPem": ""},
    )
    with pytest.raises(HTTPException) as e:
        await _call(wired)
    assert e.value.status_code == 409


async def test_device_owned_by_other_user_rejected(monkeypatch, wired):
    monkeypatch.setattr(
        deps.repo, "get_device",
        lambda did: {"deviceId": did, "uid": "someone-else", "status": "ACTIVE", "publicKeyPem": ""},
    )
    with pytest.raises(HTTPException) as e:
        await _call(wired)
    assert e.value.status_code == 409


async def test_nonce_replay_rejected(wired):
    # First call consumes the nonce; the identical replay must fail 401.
    await _call(wired)
    with pytest.raises(HTTPException) as e:
        await _call(wired)
    assert e.value.status_code == 401


# --- client-minted timestamped nonces (no /v1/challenge round-trip) ---------

def _client_nonce(ts=None, rand="A" * 22):
    import time

    return f"t1.{int(time.time() if ts is None else ts)}.{rand}"


@pytest.fixture
def claims(monkeypatch):
    """claim_client_nonce backed by a list: single use, like Firestore create()."""
    seen = []

    def fake_claim(nonce, uid, device_id, expire_at):
        if nonce in seen:
            return False
        seen.append(nonce)
        return True

    def no_lookup(*a):
        pytest.fail("a client nonce must not be looked up as a challenge")

    monkeypatch.setattr(deps.repo, "claim_client_nonce", fake_claim)
    monkeypatch.setattr(deps.repo, "consume_nonce", no_lookup)
    return seen


async def test_fresh_client_nonce_accepted_without_a_challenge(wired, claims):
    nonce = _client_nonce()
    ctx = await _call(wired, nonce=nonce)
    assert ctx["device"]["deviceId"] == "d1"
    assert claims == [nonce]


async def test_client_nonce_replay_rejected(wired, claims):
    nonce = _client_nonce()
    await _call(wired, nonce=nonce)
    with pytest.raises(HTTPException) as e:
        await _call(wired, nonce=nonce)
    assert e.value.status_code == 401
    assert e.value.detail == "nonce_invalid_or_replayed"


@pytest.mark.parametrize("skew", [-600, 600])
async def test_client_nonce_outside_the_window_rejected_without_a_write(wired, claims, skew):
    """A stale nonce, or a phone clock far off, is refused before any write;
    the client then retries once with a server challenge."""
    import time

    with pytest.raises(HTTPException) as e:
        await _call(wired, nonce=_client_nonce(time.time() + skew))
    assert e.value.status_code == 401
    assert claims == []


async def test_client_nonce_is_claimed_only_after_the_signature_verifies(wired, claims):
    """A forged request must not burn the nonce the real device is about to use."""
    attacker = ec.generate_private_key(ec.SECP256R1())
    nonce = _client_nonce()
    bad = _sign(attacker, nonce, "POST", "/v1/sessions", b'{"x":1}')
    with pytest.raises(HTTPException):
        await _call(wired, nonce=nonce, sig=bad)
    assert claims == []
    await _call(wired, nonce=nonce)  # the genuine call still goes through
    assert claims == [nonce]


async def test_malformed_client_nonce_falls_to_the_challenge_path(wired, monkeypatch):
    """`t1.` with a short random part is not a client nonce; it is looked up as a
    challenge, finds nothing, and is refused: one rejection path."""
    monkeypatch.setattr(deps.repo, "consume_nonce", lambda *a: False)
    with pytest.raises(HTTPException) as e:
        await _call(wired, nonce=_client_nonce(rand="short"))
    assert e.value.status_code == 401


async def test_client_nonces_can_be_switched_off(wired, claims, monkeypatch):
    monkeypatch.setattr(deps.settings, "CLIENT_NONCE_WINDOW_SECONDS", 0)
    monkeypatch.setattr(deps.repo, "consume_nonce", lambda *a: False)
    with pytest.raises(HTTPException):
        await _call(wired, nonce=_client_nonce())
    assert claims == []


def test_claim_client_nonce_is_single_use(monkeypatch):
    from datetime import datetime, timezone

    import fake_firestore

    from app import firestore_repo as repo

    fake_firestore.install(monkeypatch)
    exp = datetime.now(timezone.utc)
    assert repo.claim_client_nonce("t1.1.x", "u1", "d1", exp) is True
    assert repo.claim_client_nonce("t1.1.x", "u1", "d1", exp) is False
