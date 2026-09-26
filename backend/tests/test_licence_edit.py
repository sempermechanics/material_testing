"""Editing a licence in place: upgrades, downgrades, how an institution
licence is run, and turning an individual licence into an institution one."""
from datetime import datetime, timedelta, timezone

import pytest

from app import firestore_repo as repo
from license_helpers import _mint_individual, _mint_institution, _signed_in


def _in(days):
    return datetime.now(timezone.utc) + timedelta(days=days)


def _claimed(store, uid):
    return repo.ensure_entitlement({**store._data["users"][uid], "uid": uid}, "and-1")


def _held(store, email="solo@lab.org", uid="solo-1", **kw):
    """An individual licence, claimed by an account on its own device."""
    store._data["users"] = {}
    minted = _mint_individual(email=email, **kw)
    _signed_in(store, uid, email)
    _claimed(store, uid)
    return minted["license"]["id"]


# ---- term changes ----

def test_shortening_is_refused_unless_asked_for(store):
    lid = _held(store, expires_at=_in(300))

    with pytest.raises(repo.LicenseTermsRejected) as exc:
        repo.update_license(lid, {"expiresAt": _in(30)}, "admin")
    assert exc.value.code == "expiry_before_current"

    out = repo.update_license(lid, {"expiresAt": _in(30), "allowShorten": True}, "admin")

    assert out["expiresAt"] < _in(31)
    assert store._data["users"]["solo-1"]["licenseExpiresAt"] < _in(31)


def test_a_past_date_is_refused_even_when_shortening(store):
    lid = _held(store, expires_at=_in(300))
    with pytest.raises(repo.LicenseTermsRejected) as exc:
        repo.update_license(lid, {"expiresAt": _in(-1), "allowShorten": True}, "admin")
    assert exc.value.code == "expiry_in_past"


def test_a_perpetual_licence_can_be_given_an_end_with_the_default_grace(store, monkeypatch):
    monkeypatch.setattr(repo.license_admin.settings, "LICENSE_GRACE_DAYS_DEFAULT", 9)
    lid = _held(store)

    with pytest.raises(repo.LicenseTermsRejected) as exc:
        repo.update_license(lid, {"expiresAt": _in(90)}, "admin")
    assert exc.value.code == "license_perpetual"

    repo.update_license(lid, {"expiresAt": _in(90), "allowShorten": True}, "admin")

    stored = store._data["licenses"][lid]
    assert (stored["duration"], stored["graceDays"]) == ("timed", 9)
    user = store._data["users"]["solo-1"]
    assert (user["licenseDuration"], user["licenseGraceDays"]) == ("timed", 9)


def test_making_a_licence_perpetual_drops_its_end(store):
    lid = _held(store, expires_at=_in(30), grace_days=5)

    out = repo.update_license(lid, {"perpetual": True}, "admin")

    stored = store._data["licenses"][lid]
    assert "expiresAt" not in stored and "graceDays" not in stored
    assert stored["duration"] == "perpetual" and out["duration"] == "perpetual"
    user = store._data["users"]["solo-1"]
    assert "licenseExpiresAt" not in user and "licenseGraceDays" not in user
    assert user["licenseDuration"] == "perpetual"


def test_roster_fields_are_refused_on_an_individual_licence(store):
    lid = _held(store)
    for patch in ({"maxSeats": 5}, {"seating": "floating"}, {"adminEmails": ["it@lab.org"]}):
        with pytest.raises(repo.LicenseTermsRejected) as exc:
            repo.update_license(lid, patch, "admin")
        assert exc.value.code == "institution_only"


def test_institution_it_contacts_can_be_replaced(store):
    lid = _mint_institution()["license"]["id"]
    repo.update_license(lid, {"adminEmails": ["new-it@university.edu"]}, "admin")
    assert store._data["licenses"][lid]["adminEmails"] == ["new-it@university.edu"]
    assert repo.list_licenses_administered_by("it@university.edu") == []


# ---- seating ----

def _roster(store, lid, n):
    for i in range(n):
        _signed_in(store, f"m-{i}", f"m{i}@university.edu")
        assert repo.add_institution_member(lid, f"m{i}@university.edu")[0] == ""


def test_switching_to_floating_needs_a_pool_size(store):
    store._data["users"] = {}
    lid = _mint_institution()["license"]["id"]
    with pytest.raises(repo.LicenseTermsRejected) as exc:
        repo.update_license(lid, {"seating": "floating"}, "admin")
    assert exc.value.code == "floating_needs_max_seats"


def test_switching_to_floating_tells_every_member_to_lease(store):
    store._data["users"] = {}
    lid = _mint_institution()["license"]["id"]
    _roster(store, lid, 3)

    repo.update_license(lid, {"seating": "floating", "maxSeats": 2}, "admin")

    stored = store._data["licenses"][lid]
    assert (stored["seating"], stored["maxSeats"], stored["leasesActive"]) == ("floating", 2, 0)
    assert {store._data["users"][f"m-{i}"]["licenseSeating"] for i in range(3)} == {"floating"}


def test_switching_to_assigned_must_seat_the_whole_roster(store):
    store._data["users"] = {}
    lid = _mint_institution()["license"]["id"]
    repo.update_license(lid, {"seating": "floating", "maxSeats": 2}, "admin")
    _roster(store, lid, 3)

    with pytest.raises(repo.LicenseTermsRejected) as exc:
        repo.update_license(lid, {"seating": "assigned"}, "admin")
    assert exc.value.code == "max_seats_below_used"

    repo.update_license(lid, {"seating": "assigned", "maxSeats": 3}, "admin")
    assert store._data["licenses"][lid]["seating"] == "assigned"


def test_leaving_floating_clears_every_lease(store):
    store._data["users"] = {}
    lid = _mint_institution()["license"]["id"]
    repo.update_license(lid, {"seating": "floating", "maxSeats": 5}, "admin")
    _roster(store, lid, 2)
    err, _ = repo.checkout_lease({**store._data["users"]["m-0"], "uid": "m-0"}, "dev-0")
    assert err == ""
    assert store._data["licenses"][lid]["leasesActive"] == 1

    repo.update_license(lid, {"seating": "assigned"}, "admin")

    assert store._data["licenses"][lid]["leasesActive"] == 0
    assert all(s["leaseExpiresAt"] is None for s in repo.list_institution_seats(lid))
    assert "leaseExpiresAt" not in store._data["users"]["m-0"]
    assert store._data["users"]["m-0"]["licenseSeating"] == "assigned"


# ---- the route ----

@pytest.mark.asyncio
async def test_an_unknown_field_is_refused_not_dropped(store, client):
    lid = _mint_institution()["license"]["id"]
    resp = await client.patch(f"/v1/admin/licenses/{lid}", json={"maxSeat": 5})
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_a_refused_edit_leaves_the_device_lock_alone(store, client):
    lid = _held(store, expires_at=_in(300))
    store._data["licenses"][lid]["deviceIdLock"] = "phone-1"

    resp = await client.patch(f"/v1/admin/licenses/{lid}", json={
        "clearDeviceLock": True, "expiresAt": _in(10).isoformat()})

    assert resp.status_code == 422
    assert resp.json()["detail"] == "expiry_before_current"
    assert store._data["licenses"][lid]["deviceIdLock"] == "phone-1"


# ---- individual to institution ----

def test_converting_seats_the_holder_on_the_same_device_and_terms(store):
    lid = _held(store, email="dr@university.edu", uid="dr-1",
                expires_at=_in(200), grace_days=4, max_analyses=500)
    store._data["licenses"][lid]["deviceIdLock"] = "phone-1"

    err, out = repo.convert_to_institution(
        lid, domain_lock="university.edu", admin_emails=["it@university.edu"],
        max_seats=10, seating="assigned", admin_uid="admin")

    assert err == ""
    new_id = out["license"]["id"]
    assert out["key"] and out["claimedByUid"] == "dr-1"
    new = store._data["licenses"][new_id]
    old = store._data["licenses"][lid]
    assert (new["kind"], new["domainLock"], new["maxSeats"], new["seatsUsed"]) == \
        ("institution", "university.edu", 10, 1)
    assert (new["expiresAt"], new["graceDays"], new["maxAnalyses"]) == \
        (old["expiresAt"], 4, 500)
    assert (old["status"], old["supersededBy"]) == ("revoked", new_id)
    user = store._data["users"]["dr-1"]
    assert (user["licenseId"], user["licenseKind"], user["mode"]) == \
        (new_id, "institution", "licensed")
    (seat,) = repo.list_institution_seats(new_id)
    assert (seat["uid"], seat["deviceIdLock"]) == ("dr-1", "phone-1")
    assert repo.licence_held_by("dr@university.edu") == new_id


def test_converting_before_first_sign_in_moves_the_invite(store):
    store._data["users"] = {}
    lid = _mint_individual(email="new@university.edu")["license"]["id"]

    err, out = repo.convert_to_institution(
        lid, domain_lock="university.edu", admin_emails=["it@university.edu"],
        max_seats=None, seating="assigned", admin_uid="admin")

    assert err == "" and out["claimedByUid"] == ""
    (invite,) = store._data["licenseInvites"].values()
    assert invite["licenseId"] == out["license"]["id"]
    _signed_in(store, "n-1", "new@university.edu")
    assert _claimed(store, "n-1")["licenseId"] == out["license"]["id"]


def test_converting_to_another_domain_is_refused_and_changes_nothing(store):
    lid = _held(store)
    before = set(store._data["licenses"])

    err, out = repo.convert_to_institution(
        lid, domain_lock="university.edu", admin_emails=["it@university.edu"],
        max_seats=None, seating="assigned", admin_uid="admin")

    assert (err, out) == ("convert_domain_mismatch", None)
    assert set(store._data["licenses"]) == before
    assert store._data["licenses"][lid]["status"] == "redeemed"


def test_only_a_live_individual_licence_converts(store):
    store._data["users"] = {}
    inst = _mint_institution()["license"]["id"]
    solo = _mint_individual(email="a@university.edu")["license"]["id"]
    repo.revoke_license(solo, "admin")
    kw = dict(domain_lock="university.edu", admin_emails=["it@university.edu"],
              max_seats=None, seating="assigned", admin_uid="admin")
    assert repo.convert_to_institution(inst, **kw)[0] == "license_not_convertible"
    assert repo.convert_to_institution(solo, **kw)[0] == "license_revoked"
    assert repo.convert_to_institution("nope", **kw)[0] == "license_not_found"


@pytest.mark.asyncio
async def test_the_convert_route(store, client):
    lid = _held(store, email="dr@university.edu", uid="dr-1")

    resp = await client.post(f"/v1/admin/licenses/{lid}/convert", json={
        "domainLock": "University.edu", "adminEmails": ["IT@university.edu"], "maxSeats": 5})

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["license"]["kind"] == "institution"
    assert body["license"]["adminEmails"] == ["it@university.edu"]
    missing = await client.post("/v1/admin/licenses/nope/convert", json={
        "domainLock": "university.edu", "adminEmails": ["it@university.edu"]})
    assert missing.status_code == 404
    floating = await client.post(f"/v1/admin/licenses/{lid}/convert", json={
        "domainLock": "university.edu", "adminEmails": ["it@university.edu"],
        "seating": "floating"})
    assert floating.status_code == 422
