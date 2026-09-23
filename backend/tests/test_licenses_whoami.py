"""Who am I and what do I run: the admin-of listing for institution IT contacts."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)


# ===== who am I, and what do I run (E) ======================================
# One sign-in page has an address and nothing else. `/v1/me` says what the
# address is entitled to; the listing below says whether it also administers
# somebody else's licence. Between them they decide which of three dashboards
# the person lands on, in two calls made once.


@pytest.mark.asyncio
async def test_me_reports_the_seat_and_the_lease(client, monkeypatch):
    """Seating and the lease ride along so a page needs one call, not two."""
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    expiry = datetime.now(timezone.utc) + timedelta(hours=8)
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER, "licenseSeating": "floating", "leaseExpiresAt": expiry,
    })
    body = (await client.get("/v1/me")).json()
    assert body["license"]["seating"] == "floating"
    assert body["license"]["leaseExpiresAt"] == expiry.isoformat()


@pytest.mark.asyncio
async def test_me_calls_an_assigned_seat_assigned_and_holds_no_lease(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    body = (await client.get("/v1/me")).json()
    assert body["license"]["seating"] == "assigned"
    assert body["license"]["leaseExpiresAt"] is None


def _mint_run_by(admin_emails, domain="lab.org", **kw):
    """An institution licence naming these addresses as its IT contacts."""
    return repo.create_institution_license(
        domain_lock=domain, admin_emails=admin_emails,
        created_by_uid="admin", max_seats=5, **kw,
    )["license"]["id"]


def test_the_licences_an_address_administers(store):
    mine = _mint_run_by(["dev@local", "other@lab.org"])
    _mint_run_by(["someone.else@lab.org"], domain="other.org")
    found = repo.list_licenses_administered_by("dev@local")
    assert [lic["id"] for lic in found] == [mine]
    # Same redaction as the IT-facing summary: no key plaintext anywhere.
    assert "key" not in found[0]
    assert found[0]["keyPrefix"].startswith("SEMP-")
    assert found[0]["maxSeats"] == 5


def test_the_address_is_matched_case_insensitively(store):
    mine = _mint_run_by(["Dev@Local"])
    assert [lic["id"] for lic in repo.list_licenses_administered_by("DEV@local")] == [mine]


def test_administering_nothing_is_an_empty_list_not_an_error(store):
    _mint_run_by(["someone.else@lab.org"])
    assert repo.list_licenses_administered_by("dev@local") == []
    assert repo.list_licenses_administered_by("") == []


def test_a_revoked_licence_is_not_something_you_still_administer(store):
    license_id = _mint_run_by(["dev@local"])
    repo.revoke_license(license_id, "admin")
    assert repo.list_licenses_administered_by("dev@local") == []


def test_an_individual_licence_never_appears_in_the_listing(store):
    """It has no `adminEmails` to match, and it is not a roster to manage."""
    _mint_individual("dev@local")
    assert repo.list_licenses_administered_by("dev@local") == []


@pytest.mark.asyncio
async def test_the_listing_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    mine = _mint_run_by(["dev@local"])
    resp = await client.get("/v1/institutions/licenses")
    assert resp.status_code == 200
    assert [lic["id"] for lic in resp.json()["licenses"]] == [mine]


@pytest.mark.asyncio
async def test_an_unverified_address_cannot_read_a_roster_listing(client, monkeypatch):
    """`adminEmails` names addresses. An address nobody has proved they own
    must not reach a customer's roster — the same gate the seat routes use."""
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "emailVerified": False})
    resp = await client.get("/v1/institutions/licenses")
    assert resp.status_code == 403
    assert resp.json()["detail"] == "email_not_verified"
