"""Clickwrap acceptance and the separate product-improvement consent.

The two are deliberately different things: acceptance of the Terms is required
and version-checked; the consent is optional, off until granted, and revocable.
Both must work for a PENDING account, because the gate runs at registration,
before an operator approves anyone.
"""
import fake_firestore
import pytest

from app import audit, deps, firestore_repo as repo, legal
from app.config import settings

UID = "terms-uid"


@pytest.fixture
def store(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    return store


@pytest.fixture
def audited(monkeypatch):
    seen = []
    monkeypatch.setattr(audit, "record", lambda *a, **k: seen.append(k))
    return seen


def _sign_in_as(monkeypatch, store, status):
    """Real auth path (DEV bypass off) for a token that resolves to UID."""
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": UID, "email": "t@e.com"})
    store._data["users"] = {UID: {"email": "t@e.com", "role": "user", "access_status": status}}
    return {"Authorization": "Bearer ok", "X-Device-Id": "dev-1"}


@pytest.mark.asyncio
async def test_me_reports_required_version_and_no_acceptance_yet(client, store, monkeypatch):
    store._data["users"] = {"dev-user": {"access_status": "APPROVED"}}
    resp = await client.get("/v1/me")
    assert resp.status_code == 200
    body = resp.json()
    assert body["terms"]["required_version"] == legal.TERMS_VERSION
    assert body["terms"]["accepted_version"] is None
    assert body["terms"]["terms_url"] == legal.TERMS_URL
    assert body["improvement_consent"] is None


@pytest.mark.asyncio
async def test_accept_records_version_device_and_audit(client, store, audited, monkeypatch):
    # Real auth path: the DEV bypass serves a static profile, so it could never
    # show the stored record back through /v1/me.
    headers = _sign_in_as(monkeypatch, store, "APPROVED")
    resp = await client.post("/v1/me/terms", json={"version": legal.TERMS_VERSION}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["terms"]["accepted_version"] == legal.TERMS_VERSION

    stored = store._data["users"][UID]["termsAccepted"]
    assert stored["version"] == legal.TERMS_VERSION
    assert stored["deviceId"] == "dev-1"
    assert stored["source"] == "app"
    assert [k["action"] for k in audited] == ["TERMS_ACCEPTED"]

    # And /v1/me now reflects it, which is what lets the app skip the gate.
    me = await client.get("/v1/me", headers=headers)
    assert me.json()["terms"]["accepted_version"] == legal.TERMS_VERSION


@pytest.mark.asyncio
async def test_accept_refuses_a_version_the_server_does_not_serve(client, store, audited):
    """An outdated app must not be able to record agreement to unseen terms."""
    store._data["users"] = {"dev-user": {"access_status": "APPROVED"}}
    resp = await client.post("/v1/me/terms", json={"version": "1999-01-01"})
    assert resp.status_code == 409
    assert resp.json()["detail"] == "terms_version_mismatch"
    assert "termsAccepted" not in store._data["users"]["dev-user"]
    assert audited == []


@pytest.mark.asyncio
async def test_consent_defaults_off_and_is_revocable(client, store, audited, monkeypatch):
    headers = _sign_in_as(monkeypatch, store, "APPROVED")
    bare = {"Authorization": headers["Authorization"]}  # no X-Device-Id: the console
    on = await client.put("/v1/me/consents", json={"improvement": True}, headers=bare)
    assert on.status_code == 200
    assert on.json()["improvement_consent"] is True
    assert (await client.get("/v1/me", headers=headers)).json()["improvement_consent"] is True

    off = await client.put("/v1/me/consents", json={"improvement": False}, headers=bare)
    assert off.json()["improvement_consent"] is False
    stored = store._data["users"][UID]["improvementConsent"]
    assert stored["granted"] is False
    assert stored["version"] == legal.TERMS_VERSION
    assert stored["source"] == "console"
    assert [k["detail"]["improvement"] for k in audited] == [True, False]


@pytest.mark.asyncio
async def test_pending_account_can_accept_but_is_still_not_approved(client, store, monkeypatch, audited):
    headers = _sign_in_as(monkeypatch, store, "PENDING")
    assert (await client.get("/v1/me", headers=headers)).status_code == 403

    accepted = await client.post("/v1/me/terms", json={"version": legal.TERMS_VERSION}, headers=headers)
    assert accepted.status_code == 200
    consent = await client.put("/v1/me/consents", json={"improvement": True}, headers=headers)
    assert consent.status_code == 200
    assert store._data["users"][UID]["termsAccepted"]["version"] == legal.TERMS_VERSION
    assert store._data["users"][UID]["access_status"] == "PENDING"


@pytest.mark.asyncio
async def test_suspended_account_cannot_accept(client, store, monkeypatch):
    headers = _sign_in_as(monkeypatch, store, "SUSPENDED")
    resp = await client.post("/v1/me/terms", json={"version": legal.TERMS_VERSION}, headers=headers)
    assert resp.status_code == 403
    assert "termsAccepted" not in store._data["users"][UID]


@pytest.mark.asyncio
async def test_unauthenticated_cannot_accept(client, store, monkeypatch):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    resp = await client.post("/v1/me/terms", json={"version": legal.TERMS_VERSION})
    assert resp.status_code == 401


def test_terms_version_matches_the_published_document():
    """The document is the contract; the constant is what the app enforces."""
    import pathlib
    doc = pathlib.Path(__file__).resolve().parents[2] / "docs" / "legal" / "TERMS_OF_SERVICE.md"
    text = doc.read_text(encoding="utf-8")
    line = next(row for row in text.splitlines() if row.startswith("**Version:**"))
    assert line.split("**Version:**", 1)[1].strip() == legal.TERMS_VERSION
