"""Seat and lease counters: what the consoles and the pool read as taken.

Each of these once left a count, a lease or a licence saying something that
was no longer true: a revoked seat resumed without a slot, a lease cleared
after expiry and never uncounted, a revoked pool still "in use", an erased
account still holding its seat, an invite stranded behind a Demo key.
"""
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


def _mint_floating(max_seats=2):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
        seating="floating",
    )


def _roster(store, *uids):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "access_status": "APPROVED", "mode": "demo"}
        for uid in uids
    }


def _user(store, uid):
    return {**store._data["users"][uid], "uid": uid}


def _leased_pool(store, max_seats=1):
    """A floating pool with u1 holding its only lease."""
    _roster(store, "u1", "u2")
    license_id = _mint_floating(max_seats=max_seats)["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.add_institution_member(license_id, "u2@university.edu")
    err, _ = repo.checkout_lease(_user(store, "u1"), "dev-1")
    assert err == ""
    return license_id


def _expire_unswept(store, license_id, uid):
    """The lease runs out and nothing has swept it yet."""
    seat = store._data[f"licenses/{license_id}/seats"][uid]
    seat["leaseExpiresAt"] = datetime.now(timezone.utc) - timedelta(minutes=5)


# ------------------------------------------------------- leases after expiry


def test_releasing_an_expired_unswept_lease_gives_its_slot_back(store):
    license_id = _leased_pool(store)
    _expire_unswept(store, license_id, "u1")

    err, _ = repo.release_lease(_user(store, "u1"))

    assert err == ""
    assert store._data["licenses"][license_id]["leasesActive"] == 0
    err, _ = repo.checkout_lease(_user(store, "u2"), "dev-2")
    assert err == "", "the slot must not leak"


def test_revoking_a_seat_whose_lease_ran_out_gives_its_slot_back(store):
    license_id = _leased_pool(store)
    _expire_unswept(store, license_id, "u1")

    assert repo.revoke_institution_seat(license_id, "u1") is True

    assert store._data["licenses"][license_id]["leasesActive"] == 0


def test_taking_up_an_expired_unswept_lease_again_counts_it_once(store, monkeypatch):
    license_id = _leased_pool(store, max_seats=2)
    _expire_unswept(store, license_id, "u1")
    # The sweep is bounded and runs outside the claim; model the lease it missed.
    monkeypatch.setattr(repo, "_sweep_expired_leases", lambda *a: 0)

    err, _ = repo.checkout_lease(_user(store, "u1"), "dev-1")

    assert err == ""
    assert store._data["licenses"][license_id]["leasesActive"] == 1


# ----------------------------------------------------------- hold and resume


def test_a_removed_seat_cannot_be_resumed(store):
    """Resume used to set a revoked seat active again: licensed, on the
    roster, and not counted in seatsUsed."""
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution(max_seats=1)
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.revoke_institution_seat(license_id, "u1")

    assert repo.set_seat_enabled(license_id, "u1", True) == "seat_revoked"
    assert repo.set_seat_enabled(license_id, "u1", False) == "seat_revoked"

    assert store._data[f"licenses/{license_id}/seats"]["u1"]["status"] == "revoked"
    assert store._data["users"]["u1"]["mode"] == "demo"
    assert store._data["licenses"][license_id]["seatsUsed"] == 0


def test_a_seat_on_a_revoked_licence_cannot_be_resumed(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.set_seat_enabled(license_id, "u1", False)
    store._data["licenses"][license_id]["status"] = "revoked"

    assert repo.set_seat_enabled(license_id, "u1", True) == "license_revoked"
    assert store._data["users"]["u1"]["mode"] == "demo"


def test_an_unknown_seat_is_not_found(store):
    license_id = _mint_institution()["license"]["id"]
    assert repo.set_seat_enabled(license_id, "nobody", True) == "seat_not_found"


def test_holding_a_seat_releases_its_lease(store):
    """A seat on hold cannot check out, so a lease left on it only fills the
    pool until it expires."""
    license_id = _leased_pool(store)

    assert repo.set_seat_enabled(license_id, "u1", False) == ""

    seat = store._data[f"licenses/{license_id}/seats"]["u1"]
    assert "leaseExpiresAt" not in seat
    assert store._data["licenses"][license_id]["leasesActive"] == 0
    assert "leaseExpiresAt" not in store._data["users"]["u1"]
    err, _ = repo.checkout_lease(_user(store, "u2"), "dev-2")
    assert err == ""


def test_revoking_a_seat_clears_the_holders_lease_copy(store):
    license_id = _leased_pool(store)
    assert "leaseExpiresAt" in store._data["users"]["u1"]

    repo.revoke_institution_seat(license_id, "u1")

    assert "leaseExpiresAt" not in store._data["users"]["u1"]


# ---------------------------------------------------- whole-licence revoke


def test_revoking_a_floating_licence_clears_every_lease(store):
    license_id = _leased_pool(store, max_seats=2)
    repo.checkout_lease(_user(store, "u2"), "dev-2")
    assert store._data["licenses"][license_id]["leasesActive"] == 2

    repo.revoke_license(license_id, "admin")

    assert store._data["licenses"][license_id]["leasesActive"] == 0
    for uid in ("u1", "u2"):
        assert "leaseExpiresAt" not in store._data[f"licenses/{license_id}/seats"][uid]
        assert "leaseExpiresAt" not in store._data["users"][uid]
        assert store._data["users"][uid]["mode"] == "demo"


# ---------------------------------------------------------- account erasure


def test_erasing_an_account_frees_its_institution_seat(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution(max_seats=1)
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert store._data["licenses"][license_id]["seatsUsed"] == 1

    repo.delete_all_user_data("u1")

    assert store._data["licenses"][license_id]["seatsUsed"] == 0
    assert store._data[f"licenses/{license_id}/seats"]["u1"]["status"] == "revoked"
    assert "u1" not in store._data["users"]


def test_erasing_an_account_frees_its_individual_licence_for_a_new_one(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), "dev-1")
    assert store._data["licenses"][license_id]["redeemedByUid"] == "solo-1"

    repo.delete_all_user_data("solo-1")

    lic = store._data["licenses"][license_id]
    assert lic["status"] == "unused"
    assert "redeemedByUid" not in lic
    assert lic["deviceIdLock"] == ""
    # Signing up again at the same address is licensed on the first request.
    out = repo.ensure_entitlement(_signed_in(store, "solo-2", "solo@lab.org"), "dev-2")
    assert out["licenseId"] == license_id
    assert out["mode"] == "licensed"


def test_erasing_a_demo_account_leaves_its_demo_key(store):
    store._data["users"] = {}
    out = repo.ensure_entitlement(_signed_in(store, "d1", "demo@lab.org"), "dev-1")
    demo_id = out["licenseId"]

    repo.delete_all_user_data("d1")

    assert store._data["licenses"][demo_id]["redeemedByUid"] == "d1"


# ------------------------------------------------------ invites behind Demo


def _full_roster_with_invite(store):
    """An assigned licence with its one seat taken, and a newcomer invited."""
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution(max_seats=1)
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.add_institution_member(license_id, "new@university.edu")
    out = repo.ensure_entitlement(_signed_in(store, "n1", "new@university.edu"), "dev-n")
    assert out["mode"] == "demo" and out["licenseId"] != license_id
    return license_id, out["licenseId"]


def test_an_invite_blocked_by_a_full_roster_is_claimed_once_a_seat_frees(store):
    """The Demo key minted after the failed claim used to short-circuit every
    later claim, so the freed seat never reached the invited person."""
    license_id, demo_id = _full_roster_with_invite(store)
    assert store._data["users"]["n1"]["inviteBlockedAt"] is not None

    repo.revoke_institution_seat(license_id, "u1")
    store._data["users"]["n1"]["inviteBlockedAt"] = (
        datetime.now(timezone.utc) - repo._INVITE_RETRY - timedelta(seconds=1)
    )
    out = repo.ensure_entitlement(_user(store, "n1"), "dev-n")

    assert out["licenseId"] == license_id
    assert out["mode"] == "licensed"
    assert "inviteBlockedAt" not in out
    assert "inviteBlockedAt" not in store._data["users"]["n1"]
    assert demo_id not in store._data["licenses"], "the superseded Demo key is dropped"
    assert repo.list_institution_invites(license_id) == []


def test_a_blocked_invite_is_not_retried_on_every_request(store, monkeypatch):
    license_id, _demo_id = _full_roster_with_invite(store)
    repo.revoke_institution_seat(license_id, "u1")
    calls = []
    real = repo._invite_ref
    monkeypatch.setattr(repo, "_invite_ref",
                        lambda address: calls.append(address) or real(address))

    out = repo.ensure_entitlement(_user(store, "n1"), "dev-n")

    assert out["mode"] == "demo"
    assert calls == [], "a fresh block must wait out the retry interval"


def test_a_withdrawn_invite_clears_the_block(store):
    license_id, _demo_id = _full_roster_with_invite(store)
    invite = repo.list_institution_invites(license_id)[0]
    repo.revoke_institution_invite(license_id, invite["id"])
    store._data["users"]["n1"]["inviteBlockedAt"] = (
        datetime.now(timezone.utc) - repo._INVITE_RETRY - timedelta(seconds=1)
    )

    repo.ensure_entitlement(_user(store, "n1"), "dev-n")

    assert "inviteBlockedAt" not in store._data["users"]["n1"]


# ------------------------------------------------------------------- HTTP


@pytest.mark.asyncio
async def test_resuming_a_removed_seat_over_http_is_a_conflict(client, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "access_status": "APPROVED", "mode": "demo"},
        "student": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("student", "a@university.edu", "dev-1", minted["key"])
    repo.revoke_institution_seat(license_id, "student")

    resp = await client.patch(
        f"/v1/institutions/licenses/{license_id}/seats/student",
        json={"enabled": True},
    )

    assert resp.status_code == 409, resp.text
    assert resp.json()["detail"] == "seat_revoked"
