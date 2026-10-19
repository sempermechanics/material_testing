"""Audit rows hold no email address (TD-242).

`audit_logs` survives account erasure by design, and FIRESTORE_DATA_PROTECTION.md
("Account-erasure audit policy") says its rows must not hold addresses. The
staff licence routes that take one record `obs.short_hash` of it instead, under
a key that says it is a hash. Target ids are opaque and stay as they are.
"""
import json

import pytest

from app import audit, observability as obs
from license_helpers import _mint_individual, _signed_in
import repo_view as repo


def _no_address(rows: list[dict]) -> None:
    text = json.dumps(rows, default=str)
    assert "@" not in text, text


def test_address_tags_are_short_hashes():
    assert audit.address_tag("IT@Uni.edu ") == obs.short_hash("it@uni.edu")
    assert audit.address_tags(["a@x.edu", "b@x.edu"]) == [
        obs.short_hash("a@x.edu"), obs.short_hash("b@x.edu")]
    assert audit.address_tags(None) == []
    assert audit.address_tag("") == ""


@pytest.mark.asyncio
async def test_an_individual_mint_records_the_hash_of_its_address(store, audited, client):
    resp = await client.post("/v1/admin/licenses", json={"emailLock": "solo@lab.org"})
    assert resp.status_code == 200, resp.text

    [row] = [r for r in audited if r["action"] == "ADMIN_LICENSE_MINT"]
    assert row["detail"]["emailLockHash"] == obs.short_hash("solo@lab.org")
    assert "emailLock" not in row["detail"]
    assert row["target"] == {"type": "license", "id": resp.json()["license"]["id"]}
    _no_address(audited)


@pytest.mark.asyncio
async def test_an_institution_mint_records_hashes_and_a_count(store, audited, client):
    resp = await client.post("/v1/admin/licenses", json={
        "kind": "institution", "domainLock": "uni.edu",
        "adminEmails": ["it@uni.edu", "ops@uni.edu"], "maxSeats": 3})
    assert resp.status_code == 200, resp.text

    [row] = [r for r in audited if r["action"] == "ADMIN_LICENSE_MINT"]
    assert row["detail"]["adminEmailHashes"] == [
        obs.short_hash("it@uni.edu"), obs.short_hash("ops@uni.edu")]
    assert row["detail"]["adminEmailCount"] == 2
    assert "adminEmails" not in row["detail"]
    assert row["detail"]["domainLock"] == "uni.edu"
    _no_address(audited)


@pytest.mark.asyncio
async def test_an_edit_of_the_admin_list_records_hashes(store, audited, client):
    minted = await client.post("/v1/admin/licenses", json={
        "kind": "institution", "domainLock": "uni.edu", "adminEmails": ["it@uni.edu"]})
    lid = minted.json()["license"]["id"]

    resp = await client.patch(f"/v1/admin/licenses/{lid}", json={
        "adminEmails": ["it@uni.edu", "new@uni.edu"], "maxSeats": 9})
    assert resp.status_code == 200, resp.text

    [row] = [r for r in audited if r["action"] == "ADMIN_LICENSE_EXTEND"]
    assert row["detail"]["adminEmailHashes"] == [
        obs.short_hash("it@uni.edu"), obs.short_hash("new@uni.edu")]
    assert row["detail"]["adminEmailCount"] == 2
    assert row["detail"]["maxSeats"] == "9"
    _no_address(audited)


@pytest.mark.asyncio
async def test_a_conversion_records_hashes(store, audited, client):
    store._data["users"] = {}
    lid = _mint_individual(email="dr@university.edu")["license"]["id"]
    _signed_in(store, "dr-1", "dr@university.edu")
    repo.ensure_entitlement({**store._data["users"]["dr-1"]}, "and-1")

    resp = await client.post(f"/v1/admin/licenses/{lid}/convert", json={
        "domainLock": "university.edu", "adminEmails": ["it@university.edu"]})
    assert resp.status_code == 200, resp.text

    [row] = [r for r in audited if r["action"] == "ADMIN_LICENSE_CONVERT"]
    assert row["detail"]["adminEmailHashes"] == [obs.short_hash("it@university.edu")]
    assert row["detail"]["adminEmailCount"] == 1
    assert row["detail"]["claimedByUid"] == "dr-1"
    _no_address(audited)
