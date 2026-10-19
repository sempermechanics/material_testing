"""Seat documents carry `schemaVersion` (TD-254).

FIRESTORE_SCHEMA_RUNBOOK.md: every newly written server-owned document
includes it. Every seat write stamps it: the claim that creates the seat, a
re-claim, a hold, a revoke, a device clear or bind, a lease checkout, release
or sweep, a whole-licence revoke and a restore. Readers tolerate a seat
written before this, which has none.
"""
from datetime import datetime, timedelta, timezone

import repo_view as repo
from app.repo._base import SCHEMA_VERSION
from refusals import attempt


def _seats(store, license_id):
    return store._data[f"licenses/{license_id}/seats"]


def _mint(seating="assigned", max_seats=None):
    return repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin", max_seats=max_seats, seating=seating,
    )["license"]["id"]


def _roster(store, *uids):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "access_status": "APPROVED", "mode": "demo"}
        for uid in uids
    }


def _user(store, uid):
    return {**store._data["users"][uid], "uid": uid}


def _unstamp(store, license_id, uid):
    """A seat as written before TD-254."""
    _seats(store, license_id)[uid].pop("schemaVersion", None)


def test_a_new_seat_is_stamped(store):
    _roster(store, "u1")
    license_id = _mint()
    repo.add_institution_member(license_id, "u1@university.edu")
    assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION


def test_each_seat_write_restamps_an_old_seat(store):
    _roster(store, "u1")
    license_id = _mint()
    repo.add_institution_member(license_id, "u1@university.edu")

    steps = [
        lambda: repo.set_seat_enabled(license_id, "u1", False),
        lambda: repo.set_seat_enabled(license_id, "u1", True),
        lambda: repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_STAFF),
        lambda: repo.bind_device_lock(repo._seat_ref(license_id, "u1"), "dev-9"),
        lambda: repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_SELF),
        lambda: repo.revoke_institution_seat(license_id, "u1"),
    ]
    for step in steps:
        _unstamp(store, license_id, "u1")
        step()
        assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION


def test_a_re_claim_restamps_an_old_seat(store):
    """The key typed in the app on a seat IT already added: the claim's
    update branch, which binds the device."""
    _roster(store, "u1")
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin")
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    _unstamp(store, license_id, "u1")
    repo.activate_license("u1", "u1@university.edu", "dev-1", minted["key"])
    seat = _seats(store, license_id)["u1"]
    assert (seat["deviceIdLock"], seat["schemaVersion"]) == ("dev-1", SCHEMA_VERSION)


def test_lease_writes_stamp_the_seat(store):
    _roster(store, "u1")
    license_id = _mint("floating", max_seats=1)
    repo.add_institution_member(license_id, "u1@university.edu")

    _unstamp(store, license_id, "u1")
    assert attempt(repo.checkout_lease, _user(store, "u1"), "dev-1")[0] == ""
    assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION

    _unstamp(store, license_id, "u1")
    store._data["users"]["u1"]["licenseId"] = license_id
    assert attempt(repo.release_lease, _user(store, "u1"))[0] == ""
    assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION

    # An expired, unswept lease is reclaimed by the next checkout's sweep.
    assert attempt(repo.checkout_lease, _user(store, "u1"), "dev-1")[0] == ""
    _seats(store, license_id)["u1"]["leaseExpiresAt"] = (
        datetime.now(timezone.utc) - timedelta(minutes=5))
    _unstamp(store, license_id, "u1")
    lic_ref = repo.db().collection("licenses").document(license_id)
    assert repo._sweep_expired_leases(lic_ref, datetime.now(timezone.utc)) == 1
    assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION


def test_a_licence_revoke_and_restore_stamp_its_seats(store):
    _roster(store, "u1")
    license_id = _mint()
    repo.add_institution_member(license_id, "u1@university.edu")

    _unstamp(store, license_id, "u1")
    repo.revoke_license(license_id, "admin")
    assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION

    license_id = _mint()
    repo.add_institution_member(license_id, "u1@university.edu")
    _unstamp(store, license_id, "u1")
    repo.delete_license(license_id, "admin")
    repo.restore_license(license_id, "admin")
    assert _seats(store, license_id)["u1"]["schemaVersion"] == SCHEMA_VERSION


def test_a_seat_without_a_version_still_reads(store):
    _roster(store, "u1")
    license_id = _mint()
    repo.add_institution_member(license_id, "u1@university.edu")
    _unstamp(store, license_id, "u1")

    [seat] = repo.list_institution_seats(license_id)
    assert (seat["uid"], seat["status"]) == ("u1", "active")
    assert "schemaVersion" not in seat
    assert repo.institution_seat(license_id, "u1")["email"] == "u1@university.edu"
    assert repo.reconcile_institution_seats(license_id)["counts"]["active"] == 1
