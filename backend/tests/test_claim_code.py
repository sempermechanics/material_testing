"""`POST /v1/me/claim`: a claim code redeemed on the web account page.

There are no runtime licence keys: the account is the anchor, and a paid
entitlement reaches it as a one-time code typed on the account page. The
route is the app's activation (`repo.activate_license`) at the user step-up
tier: a browser with a second factor on a recent sign-in, or a signed device.
"""
import base64
import hashlib
import json
import logging
import time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import deps, errors, rate_limit
import repo_view as repo
from app.licenses import key_hash, key_prefix
from license_helpers import _mint_individual, _mint_institution

UID = "claimer"
EMAIL = "ann@university.edu"
PATH = "/v1/me/claim"
BEARER = {"Authorization": "Bearer ok"}


def _events(caplog, event: str) -> list[dict]:
    return [json.loads(r.message) for r in caplog.records
            if r.message.startswith("{") and json.loads(r.message).get("event") == event]


@pytest.fixture
def web(store, audited, monkeypatch):
    """A signed-in, approved, verified account in a browser that has just
    passed its second factor. `web.claims` is what the ID token says."""
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps.settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(deps.settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    monkeypatch.setattr(deps.settings, "CHECK_REVOKED_ON_STEP_UP", False)
    store._data["users"] = {UID: {"uid": UID, "email": EMAIL, "access_status": "APPROVED",
                                  "emailVerified": True}}
    store.claims = {"firebase": {"sign_in_second_factor": "totp"},
                    "auth_time": time.time() - 60}
    monkeypatch.setattr(deps, "verify_id_token",
                        lambda _t, **_k: {"sub": UID, "email": EMAIL, "email_verified": True,
                                          **store.claims})
    repo.patch(monkeypatch, "get_or_create_user",
               lambda claims, device_id=None: dict(store._data["users"][UID]))
    store.audited = audited
    return store


def _claim_rows(store):
    return [r for r in store.audited if r["action"] == "LICENSE_CLAIM_CODE"]


# ---------------- success ----------------
async def test_a_browser_claims_an_individual_licence(web, client, caplog):
    minted = _mint_individual(email=EMAIL)
    code = minted["key"]
    license_id = minted["license"]["id"]
    typed = "  " + code.lower().replace("-", " ") + " "

    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post(PATH, json={"code": typed}, headers=BEARER)

    assert r.status_code == 200, r.text
    config = r.json()["config"]
    assert config["mode"] == "licensed" and config["cloudBackupEnabled"] is True
    user = web._data["users"][UID]
    assert user["licenseId"] == license_id
    lic = web._data["licenses"][license_id]
    assert lic["redeemedByUid"] == UID
    # No device named, so none bound: the phone's next request binds it.
    assert not lic.get("deviceIdLock")
    [row] = _claim_rows(web)
    assert row["outcome"] == "OK"
    assert row["target"] == {"type": "license", "id": license_id}
    assert row["detail"]["keyPrefix"] == key_prefix(code)
    assert row["detail"]["via"] == "web"
    assert row["deviceId"] is None
    [event] = _events(caplog, "activation")
    assert (event["outcome"], event["via"], event["licenseId"]) == ("ok", "web", license_id)
    # The code itself is never written down, in the audit row or the log.
    canonical = code.replace("-", "")
    assert canonical not in json.dumps(web.audited) and code not in json.dumps(web.audited)
    assert canonical not in caplog.text and code not in caplog.text


async def test_a_browser_claim_leaves_an_existing_device_lock_alone(web, client):
    """A licence minted against a phone keeps that phone: the browser names no
    device, so there is nothing to compare, and the lock stays to be checked
    on the phone's own requests."""
    minted = _mint_individual(email=EMAIL, device_id_lock="old-phone")
    r = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    assert r.status_code == 200, r.text
    assert web._data["licenses"][minted["license"]["id"]]["deviceIdLock"] == "old-phone"


async def test_a_browser_claims_an_institution_seat_by_domain(web, client):
    minted = _mint_institution(max_seats=5)
    license_id = minted["license"]["id"]

    r = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)

    assert r.status_code == 200, r.text
    assert r.json()["config"]["licenseKind"] == "institution"
    seat = web._data[f"licenses/{license_id}/seats"][UID]
    assert seat["status"] == "active" and seat["email"] == EMAIL
    assert not seat.get("deviceIdLock")
    assert web._data["licenses"][license_id]["seatsUsed"] == 1
    assert web._data["users"][UID]["licenseId"] == license_id


async def test_claiming_the_same_code_again_is_idempotent(web, client):
    minted = _mint_individual(email=EMAIL)
    first = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    web._data["users"][UID] = {**web._data["users"][UID]}
    again = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    assert (first.status_code, again.status_code) == (200, 200)


# ---------------- refusals, with real licences ----------------
async def test_an_unknown_code_is_not_found_and_audited_by_prefix(web, client, caplog):
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post(PATH, json={"code": "SEMP-ZZZZ-ZZZZ-ZZZZ-ZZZZ"}, headers=BEARER)
    assert r.status_code == 404
    assert r.json()["detail"] == "license_not_found"
    [row] = _claim_rows(web)
    assert row["outcome"] == "DENIED"
    assert row["detail"] == {"keyPrefix": "SEMP-ZZZZ", "via": "web",
                             "errorCode": "license_not_found"}
    assert "target" not in row or not row["target"]
    [event] = _events(caplog, "activation")
    assert (event["outcome"], event["errorCode"], event["via"]) == (
        "refused", "license_not_found", "web")
    assert "licenseId" not in event


async def test_someone_elses_licence_is_an_email_mismatch(web, client):
    minted = _mint_individual(email="bob@lab.org")
    r = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (403, "license_email_mismatch")
    assert "licenseId" not in web._data["users"][UID]


async def test_another_domain_cannot_take_an_institution_seat(web, client):
    minted = repo.create_institution_license(
        domain_lock="elsewhere.edu", admin_emails=["it@elsewhere.edu"], created_by_uid="admin")
    r = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (403, "license_email_mismatch")


async def test_a_revoked_licence_is_refused(web, client):
    minted = _mint_individual(email=EMAIL)
    repo.revoke_license(minted["license"]["id"], "admin")
    r = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (403, "license_revoked")


async def test_a_full_institution_is_refused(web, client):
    minted = _mint_institution(max_seats=1)
    web._data["licenses"][minted["license"]["id"]]["seatsUsed"] = 1
    r = await client.post(PATH, json={"code": minted["key"]}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (409, "license_seats_exhausted")


# ---------------- every refusal code reaches the wire as activation's ----------
@pytest.mark.parametrize("code,status", [
    ("user_not_found", 404), ("license_not_found", 404), ("license_expired", 403),
    ("already_licensed", 409), ("license_revoked", 403), ("license_email_mismatch", 403),
    ("license_device_mismatch", 403), ("license_already_redeemed", 409),
    ("license_seats_exhausted", 409), ("license_seat_disabled", 409),
    ("claim_contended", 503),
])
async def test_each_refusal_has_activations_status(web, client, monkeypatch, code, status):
    def refuse(*_a, **_k):
        raise errors.Refusal(code)

    repo.patch(monkeypatch, "activate_license", refuse)
    r = await client.post(PATH, json={"code": "SEMP-ABCD-EFGH"}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (status, code)
    [row] = _claim_rows(web)
    assert (row["outcome"], row["detail"]["errorCode"]) == ("DENIED", code)


# ---------------- who may claim ----------------
async def test_a_browser_without_a_second_factor_is_sent_to_step_up(web, client, monkeypatch):
    called = []
    repo.patch(monkeypatch, "activate_license", lambda *a, **k: called.append(a))
    web.claims = {}
    r = await client.post(PATH, json={"code": "SEMP-ABCD-EFGH"}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (403, "mfa_required")
    assert called == []


async def test_a_stale_second_factor_must_sign_in_again(web, client, monkeypatch):
    called = []
    repo.patch(monkeypatch, "activate_license", lambda *a, **k: called.append(a))
    web.claims = {"firebase": {"sign_in_second_factor": "totp"},
                  "auth_time": time.time() - 3600}
    r = await client.post(PATH, json={"code": "SEMP-ABCD-EFGH"}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (403, "reauth_required")
    assert called == []


async def test_an_unverified_address_cannot_claim(web, client, monkeypatch):
    called = []
    repo.patch(monkeypatch, "activate_license", lambda *a, **k: called.append(a))
    web._data["users"][UID]["emailVerified"] = False
    r = await client.post(PATH, json={"code": "SEMP-ABCD-EFGH"}, headers=BEARER)
    assert (r.status_code, r.json()["detail"]) == (403, "email_not_verified")
    assert called == []


async def test_no_token_is_401(web, client):
    r = await client.post(PATH, json={"code": "SEMP-ABCD-EFGH"})
    assert r.status_code == 401


def _sign(priv, nonce, method, path, body: bytes) -> str:
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    return base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


async def test_a_signed_device_claims_and_binds_its_own_device(web, client, caplog,
                                                                monkeypatch):
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    web._data["devices"] = {"phone-1": {"uid": UID, "status": "ACTIVE", "publicKeyPem": pem}}
    web._data["users"][UID]["activeDeviceId"] = "phone-1"
    web.claims = {}  # no second factor: the device signature is the step-up
    repo.patch(monkeypatch, "consume_nonce", lambda *a, **k: True)
    minted = _mint_individual(email=EMAIL)
    body = json.dumps({"code": minted["key"]}).encode()
    headers = {**BEARER, "Content-Type": "application/json", "X-Device-Id": "phone-1",
               "X-Nonce": "n-1", "X-Signature": _sign(priv, "n-1", "POST", PATH, body)}
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post(PATH, content=body, headers=headers)

    assert r.status_code == 200, r.text
    assert web._data["licenses"][minted["license"]["id"]]["deviceIdLock"] == "phone-1"
    [row] = _claim_rows(web)
    assert (row["deviceId"], row["detail"]["via"]) == ("phone-1", "app")
    assert _events(caplog, "activation")[0]["via"] == "app"


# ---------------- the body and the rate limit ----------------
@pytest.mark.parametrize("body", [
    {}, {"code": "short"}, {"code": "S" * 65}, {"code": "SEMP-ABCD-EFGH", "key": "x"},
])
async def test_a_malformed_body_is_422(web, client, body):
    r = await client.post(PATH, json=body, headers=BEARER)
    assert r.status_code == 422


async def test_the_claim_shares_activations_rate_limit(web, client, monkeypatch):
    called = []
    repo.patch(monkeypatch, "activate_license", lambda *a, **k: called.append(a))
    monkeypatch.setattr(rate_limit.license_activate_bucket, "allow", lambda _uid: False)
    r = await client.post(PATH, json={"code": "SEMP-ABCD-EFGH"}, headers=BEARER)
    assert r.status_code == 429
    assert r.json()["detail"] == "rate_limited"
    assert "Retry-After" in r.headers
    assert called == []


def test_the_prefix_is_the_whole_of_what_is_kept():
    code = "SEMP-ABCD-EFGH-JKLM-NPQR"
    assert key_prefix(code) == "SEMP-ABCD"
    assert key_hash(code) == key_hash(code.lower().replace("-", " "))
