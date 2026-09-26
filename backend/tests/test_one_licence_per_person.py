"""One licence per person (repo/holders.py).

A person is an address; the device is a lock on the licence they hold and is
changed in place. Every grant path — a staff mint, a key typed in the app, IT
adding a roster member — refuses someone who already holds or is promised a
different live licence. A revoked or lapsed licence, or the system Demo key,
does not count: replacing those is what a new licence is for.
"""
from datetime import datetime, timedelta, timezone

import pytest

from app import firestore_repo as repo

from license_helpers import _mint_individual, _mint_institution, _signed_in


def _claimed(store, uid):
    return repo.ensure_entitlement({**store._data["users"][uid], "uid": uid}, "and-1")


def _past(days=60):
    return datetime.now(timezone.utc) - timedelta(days=days)


# ---- who holds what ----

def test_nobody_holds_a_licence_until_one_is_minted_for_them(store):
    store._data["users"] = {}
    assert repo.licence_held_by("solo@lab.org") == ""
    minted = _mint_individual()
    assert repo.licence_held_by("solo@lab.org") == minted["license"]["id"]
    assert repo.licence_held_by("Solo@Lab.org ") == minted["license"]["id"]


def test_the_licence_being_granted_is_not_counted_against_itself(store):
    store._data["users"] = {}
    minted = _mint_individual()
    assert repo.licence_held_by("solo@lab.org", exclude_id=minted["license"]["id"]) == ""


def test_an_account_holding_a_licence_counts(store):
    store._data["users"] = {}
    minted = _mint_individual()
    _signed_in(store, "solo-1", "solo@lab.org")
    _claimed(store, "solo-1")
    # The invite is consumed by the claim; the account's own pointer is what
    # says they hold it now.
    assert store._data["licenseInvites"] == {}
    assert repo.licence_held_by("solo@lab.org") == minted["license"]["id"]


def test_a_roster_invite_counts(store):
    store._data["users"] = {}
    inst = _mint_institution()
    err, _, invite = repo.add_institution_member(inst["license"]["id"], "new@university.edu")
    assert err == "" and invite
    assert repo.licence_held_by("new@university.edu") == inst["license"]["id"]


def test_a_revoked_licence_does_not_count(store):
    store._data["users"] = {}
    minted = _mint_individual()
    repo.revoke_license(minted["license"]["id"], "admin")
    assert repo.licence_held_by("solo@lab.org") == ""


def test_a_licence_past_its_grace_does_not_count(store):
    store._data["users"] = {}
    minted = _mint_individual()
    store._data["licenses"][minted["license"]["id"]].update(
        {"expiresAt": _past(), "graceDays": 7, "duration": "timed"})
    assert repo.licence_held_by("solo@lab.org") == ""


def test_a_licence_in_its_grace_still_counts(store):
    store._data["users"] = {}
    minted = _mint_individual()
    store._data["licenses"][minted["license"]["id"]].update(
        {"expiresAt": _past(2), "graceDays": 14, "duration": "timed"})
    assert repo.licence_held_by("solo@lab.org") == minted["license"]["id"]


def test_the_demo_key_does_not_count(store):
    store._data["users"] = {}
    user = _signed_in(store, "solo-1", "solo@lab.org")
    after = repo.ensure_entitlement(user, "and-1")
    assert after["mode"] == "demo" and after["licenseId"]
    assert repo.licence_held_by("solo@lab.org") == ""


# ---- staff mint ----

@pytest.mark.asyncio
async def test_the_desk_refuses_a_second_licence_for_one_address(client, store):
    store._data["users"] = {}
    first = _mint_individual()
    before = set(store._data["licenses"])

    resp = await client.post("/v1/admin/licenses", json={"emailLock": "solo@lab.org"})

    assert resp.status_code == 409
    assert resp.json()["detail"] == f"email_already_licensed: {first['license']['id']}"
    assert set(store._data["licenses"]) == before


@pytest.mark.asyncio
async def test_the_desk_issues_again_once_the_first_is_revoked(client, store):
    store._data["users"] = {}
    first = _mint_individual()
    repo.revoke_license(first["license"]["id"], "admin")

    resp = await client.post("/v1/admin/licenses", json={"emailLock": "solo@lab.org"})

    assert resp.status_code == 200
    assert resp.json()["inviteError"] == ""


@pytest.mark.asyncio
async def test_the_desk_issues_to_someone_whose_licence_has_lapsed(client, store):
    store._data["users"] = {}
    first = _mint_individual()
    _signed_in(store, "solo-1", "solo@lab.org")
    _claimed(store, "solo-1")
    store._data["licenses"][first["license"]["id"]].update(
        {"expiresAt": _past(), "graceDays": 7, "duration": "timed"})

    resp = await client.post("/v1/admin/licenses", json={"emailLock": "solo@lab.org"})

    assert resp.status_code == 200
    # The lapsed licence is no reason to leave the new one unattached.
    assert resp.json()["claimedByUid"] == "solo-1"
    assert store._data["users"]["solo-1"]["licenseId"] == resp.json()["license"]["id"]


@pytest.mark.asyncio
async def test_the_desk_refuses_an_address_on_a_roster(client, store):
    store._data["users"] = {}
    inst = _mint_institution()
    _signed_in(store, "m-1", "member@university.edu")
    assert repo.add_institution_member(inst["license"]["id"], "member@university.edu")[0] == ""

    resp = await client.post("/v1/admin/licenses", json={"emailLock": "member@university.edu"})

    assert resp.status_code == 409
    assert resp.json()["detail"] == f"email_already_licensed: {inst['license']['id']}"


# ---- a key typed in the app ----

def test_a_typed_institution_key_does_not_move_an_individual_holder(store):
    store._data["users"] = {}
    solo = _mint_individual(email="dr@university.edu")
    _signed_in(store, "dr-1", "dr@university.edu")
    _claimed(store, "dr-1")
    inst = _mint_institution()

    err, cfg = repo.activate_license("dr-1", "dr@university.edu", "and-1", inst["key"])

    assert (err, cfg) == ("already_licensed", None)
    assert store._data["users"]["dr-1"]["licenseId"] == solo["license"]["id"]
    assert store._data["licenses"][inst["license"]["id"]].get("seatsUsed", 0) == 0


def test_re_entering_the_key_one_holds_still_works(store):
    store._data["users"] = {}
    _signed_in(store, "solo-1", "solo@lab.org")
    minted = _mint_individual()
    assert repo.activate_license("solo-1", "solo@lab.org", "and-1", minted["key"])[0] == ""
    assert repo.activate_license("solo-1", "solo@lab.org", "and-1", minted["key"])[0] == ""


@pytest.mark.asyncio
async def test_the_app_route_answers_409(client, store):
    """End to end through the route, as the dev user (`dev@local`)."""
    store._data["users"] = {"dev-user": {
        "email": "dev@local", "access_status": "APPROVED", "emailVerified": True}}
    solo = _mint_individual(email="dev@local")
    inst = repo.create_institution_license(
        domain_lock="local", admin_emails=["it@local"], created_by_uid="admin")
    headers = {"X-Device-Id": "dev-device"}
    first = await client.post("/v1/licenses/activate", json={"key": solo["key"]}, headers=headers)
    assert first.status_code == 200, first.text

    resp = await client.post("/v1/licenses/activate", json={"key": inst["key"]}, headers=headers)

    assert resp.status_code == 409
    assert resp.json()["detail"] == "already_licensed"
    assert store._data["users"]["dev-user"]["licenseId"] == solo["license"]["id"]


# ---- IT adding a roster member ----

def test_it_cannot_put_an_individual_holder_on_its_roster(store):
    store._data["users"] = {}
    solo = _mint_individual(email="dr@university.edu")
    _signed_in(store, "dr-1", "dr@university.edu")
    _claimed(store, "dr-1")
    inst = _mint_institution()

    err, seat, invite = repo.add_institution_member(inst["license"]["id"], "dr@university.edu")

    assert (err, seat, invite) == ("member_already_licensed", None, None)
    assert store._data["users"]["dr-1"]["licenseId"] == solo["license"]["id"]


def test_it_cannot_invite_an_address_a_licence_waits_for(store):
    """No account yet, but an individual licence is locked to the address and
    waiting for their first sign-in."""
    store._data["users"] = {}
    _mint_individual(email="new@university.edu")
    inst = _mint_institution()

    err, _, invite = repo.add_institution_member(inst["license"]["id"], "new@university.edu")

    assert (err, invite) == ("member_already_licensed", None)


def test_re_adding_a_member_is_still_a_no_op(store):
    store._data["users"] = {}
    inst = _mint_institution()
    _signed_in(store, "m-1", "member@university.edu")
    assert repo.add_institution_member(inst["license"]["id"], "member@university.edu")[0] == ""
    err, seat, _ = repo.add_institution_member(inst["license"]["id"], "member@university.edu")
    assert err == "" and seat["uid"] == "m-1"
