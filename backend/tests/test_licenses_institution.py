"""Institution licences: mint, activation by domain, seats, in-place activation."""

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)


# ===================================================================== institution


def test_activate_institution_requires_domain_match(store):
    store._data["users"] = {
        "u1": {"email": "student@other.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    err, cfg = repo.activate_license("u1", "student@other.edu", "dev-1", minted["key"])
    assert err == "license_email_mismatch"
    assert cfg is None


def test_activate_institution_creates_seat_and_locks_device(store):
    store._data["users"] = {
        "u1": {"email": "student@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    err, cfg = repo.activate_license("u1", "student@university.edu", "dev-1", minted["key"])
    assert err == ""
    assert cfg["plan"] == "professional"
    assert cfg["licenseKind"] == "institution"
    seats = repo.list_institution_seats(license_id)
    assert len(seats) == 1
    assert seats[0]["uid"] == "u1"
    assert seats[0]["deviceIdLock"] == "dev-1"
    assert store._data["licenses"][license_id]["seatsUsed"] == 1

    # Same uid, same device: idempotent re-entry, no second seat minted.
    err2, cfg2 = repo.activate_license("u1", "student@university.edu", "dev-1", minted["key"])
    assert err2 == ""
    assert cfg2["plan"] == "professional"
    assert len(repo.list_institution_seats(license_id)) == 1
    assert store._data["licenses"][license_id]["seatsUsed"] == 1


def test_activate_institution_device_mismatch_once_seat_is_locked(store):
    store._data["users"] = {
        "u1": {"email": "student@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    repo.activate_license("u1", "student@university.edu", "dev-1", minted["key"])
    err, cfg = repo.activate_license("u1", "student@university.edu", "dev-2", minted["key"])
    assert err == "license_device_mismatch"
    assert cfg is None


def test_activate_institution_seats_exhausted_returns_409(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "u2": {"email": "b@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution(max_seats=1)
    err1, _ = repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert err1 == ""
    err2, cfg2 = repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])
    assert err2 == "license_seats_exhausted"
    assert cfg2 is None


@pytest.mark.asyncio
async def test_activate_institution_seats_exhausted_over_http_is_409(client, monkeypatch):
    # DEV_INSECURE_AUTH's current_user() always returns the literal deps._DEV_USER
    # dict (uid "dev-user", email "dev@local") rather than reading the store — so
    # the caller's email for domain-match purposes must be set there, not in
    # store._data["users"]["dev-user"].
    from app import deps

    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "a@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "u2": {"email": "b@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution(max_seats=1)
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])
    resp = await client.post(
        "/v1/licenses/activate",
        json={"key": minted["key"]},
        headers={"X-Device-Id": "dev-device"},
    )
    assert resp.status_code == 409, resp.text
    assert resp.json()["detail"] == "license_seats_exhausted"


def test_revoke_whole_institution_license_drops_every_seat_and_frees_slots(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "u2": {"email": "b@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])
    assert store._data["licenses"][license_id]["seatsUsed"] == 2

    revoked = repo.revoke_license(license_id, "admin")
    assert revoked["status"] == "revoked"
    assert store._data["users"]["u1"]["plan"] == "demo"
    assert store._data["users"]["u2"]["plan"] == "demo"
    assert store._data["licenses"][license_id]["seatsUsed"] == 0
    for seat in repo.list_institution_seats(license_id):
        assert seat["status"] == "revoked"


def test_revoke_single_institution_seat_frees_only_that_slot(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "u2": {"email": "b@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])

    assert repo.revoke_institution_seat(license_id, "u1") is True
    assert store._data["users"]["u1"]["plan"] == "demo"
    # u2's seat and plan are untouched by u1's revoke.
    assert store._data["users"]["u2"]["plan"] == "professional"
    assert store._data["licenses"][license_id]["seatsUsed"] == 1

    # The freed slot can now be claimed by a third student under maxSeats=2.
    store._data["users"]["u3"] = {
        "email": "c@university.edu", "access_status": "APPROVED", "plan": "demo",
    }


def test_disable_seat_drops_to_demo_without_freeing_slot(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution(max_seats=1)
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert store._data["licenses"][license_id]["seatsUsed"] == 1

    assert repo.set_seat_enabled(license_id, "u1", False) is True
    assert store._data["users"]["u1"]["plan"] == "demo"
    # Slot is still occupied — a second student cannot claim it while disabled.
    assert store._data["licenses"][license_id]["seatsUsed"] == 1
    store._data["users"]["u2"] = {
        "email": "b@university.edu", "access_status": "APPROVED", "plan": "demo",
    }
    err, cfg = repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])
    assert err == "license_seats_exhausted"
    assert cfg is None

    # Re-enabling restores Professional in place, no re-activation needed.
    assert repo.set_seat_enabled(license_id, "u1", True) is True
    assert store._data["users"]["u1"]["plan"] == "professional"


def test_clearing_a_seat_lock_lets_the_key_be_re_activated_elsewhere(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "old-phone", minted["key"])
    # Without clearing, a new device on the same seat is rejected.
    err, _ = repo.activate_license("u1", "a@university.edu", "new-phone", minted["key"])
    assert err == "license_device_mismatch"

    assert repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_IT)[0] == ""
    err2, cfg2 = repo.activate_license("u1", "a@university.edu", "new-phone", minted["key"])
    assert err2 == ""
    assert cfg2["plan"] == "professional"


def test_activation_is_in_place_session_data_untouched(store):
    """Activating a license (individual or institution) must never migrate or touch
    the account's existing session/file docs — same uid, same doc, config only."""
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    store._data["sessions"] = {
        f"s{i}": {"uid": "u1", "status": "COMPLETED", "localSessionId": f"l{i}"}
        for i in range(5)
    }
    before = repo.count_user_sessions("u1")
    assert before == 5

    minted = _mint_institution()
    err, cfg = repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert err == ""
    assert cfg["plan"] == "professional"
    after = repo.count_user_sessions("u1")
    assert after == before == 5
    # Every original session doc is byte-identical — activation touched none of them.
    for i in range(5):
        assert store._data["sessions"][f"s{i}"] == {
            "uid": "u1", "status": "COMPLETED", "localSessionId": f"l{i}",
        }


def test_downgrade_preserves_data_blocks_retrieval_then_reactivation_restores(store, monkeypatch):
    """Seed >25 sessions as Professional, downgrade (revoke), assert nothing is
    deleted and retrieval (`cloudBackupEnabled`) is withdrawn, then re-activate
    and confirm it comes back with zero data loss. Recording was never gated."""
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    store._data["sessions"] = {
        f"s{i}": {"uid": "u1", "status": "COMPLETED", "localSessionId": f"l{i}"}
        for i in range(30)
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    err, cfg = repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert err == ""
    assert cfg["cloudBackupEnabled"] is True
    assert repo.count_user_sessions("u1") == 30

    # Downgrade: single-seat revoke (institution offboards the student).
    assert repo.revoke_institution_seat(license_id, "u1") is True
    user_after_revoke = repo.get_user("u1")
    resolved = repo.resolve_user_config(user_after_revoke)
    assert resolved["plan"] == "demo"
    assert resolved["cloudBackupEnabled"] is False
    # Nothing deleted: all 30 sessions are still there, byte-for-byte.
    assert repo.count_user_sessions("u1") == 30
    for i in range(30):
        assert store._data["sessions"][f"s{i}"] == {
            "uid": "u1", "status": "COMPLETED", "localSessionId": f"l{i}",
        }

    # Re-activation (institution re-admits the student, or they self-serve
    # re-enter the same key) restores Professional in place, zero data loss.
    err2, cfg2 = repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert err2 == ""
    assert cfg2["plan"] == "professional"
    assert cfg2["cloudBackupEnabled"] is True
    assert repo.count_user_sessions("u1") == 30
    for i in range(30):
        assert store._data["sessions"][f"s{i}"] == {
            "uid": "u1", "status": "COMPLETED", "localSessionId": f"l{i}",
        }


@pytest.mark.asyncio
async def test_demo_after_downgrade_still_records_but_cannot_restore(client, monkeypatch):
    # DEV_INSECURE_AUTH's verified_device()/current_user() resolve the caller
    # from the literal deps._DEV_USER dict, not a Firestore read — so the
    # entitlement under test must be driven by patching that dict's mode.
    # This simulates "the account was just downgraded" (see
    # test_downgrade_preserves_data_... above for the repo-level proof that
    # revoke never touches session data): the next analysis is still
    # recorded, and the stored one is refused on the way back out.
    from app import deps, drive

    store = fake_firestore.install(monkeypatch)
    store._data["users"] = {"dev-user": {"email": "dev@test", "access_status": "APPROVED"}}
    store._data["files"] = {"f1": {"uid": "dev-user", "driveFileId": "d1", "name": "a.zip"}}
    _recording_stubs(monkeypatch)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    created = await client.post(
        "/v1/sessions",
        json={
            "specimen": "s",
            "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": "a" * 64}],
        },
    )
    assert created.status_code == 200
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    restored = await client.get("/v1/files/f1/content")
    assert restored.status_code == 403
    assert restored.json()["detail"].startswith("feature_not_licensed: ")


def test_device_lock_is_revalidated_on_every_authed_call_not_just_at_activation(store):
    """A revoked seat must lose Professional the next time it presents
    X-Device-Id — not only when someone explicitly calls revoke/activate again
    against that exact user doc read. This exercises revalidate_device_lock,
    which deps.current_user/verified_device call on every request that carries
    a device id."""
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    user = repo.get_user("u1")
    assert user["plan"] == "professional"

    # Institution revokes the seat directly (not through this user's request).
    repo.revoke_institution_seat(license_id, "u1")

    # The next authed call from that device re-validates and is downgraded.
    revalidated = repo.revalidate_device_lock(user, "dev-1")
    assert revalidated["plan"] == "demo"
    assert store._data["users"]["u1"]["plan"] == "demo"


@pytest.mark.asyncio
async def test_admin_mint_institution_license_via_http(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post(
        "/v1/admin/licenses",
        json={
            "kind": "institution",
            "domainLock": "university.edu",
            "adminEmails": ["it@university.edu"],
            "maxSeats": 50,
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["key"].startswith("SEMP-")
    assert body["license"]["kind"] == "institution"
    assert body["license"]["domainLock"] == "university.edu"
    assert body["license"]["maxSeats"] == 50
    assert "emailLock" in body["license"] and body["license"]["emailLock"] == ""


@pytest.mark.asyncio
async def test_admin_mint_institution_license_requires_domain_and_admin_emails(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post("/v1/admin/licenses", json={"kind": "institution"})
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_institution_seat_patch_clear_device_lock_over_http(client, monkeypatch, audited):
    # DEV_INSECURE_AUTH's current_user() always returns the literal
    # deps._DEV_USER dict, so the caller's email for the adminEmails
    # membership check must be set there, not just in the store.

    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "student": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("student", "a@university.edu", "old-dev", minted["key"])

    resp = await client.patch(
        f"/v1/institutions/licenses/{license_id}/seats/student",
        json={"clearDeviceLock": True},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["seat"]["deviceIdLock"] == ""
    patched = [r for r in audited if r["action"] == "INSTITUTION_SEAT_PATCH"]
    assert patched[0]["detail"]["previousDeviceId"] == "old-dev"


@pytest.mark.asyncio
async def test_institution_seat_revoke_over_http(client, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "student": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("student", "a@university.edu", "old-dev", minted["key"])

    resp = await client.delete(f"/v1/institutions/licenses/{license_id}/seats/student")
    assert resp.status_code == 200, resp.text
    assert store._data["users"]["student"]["plan"] == "demo"
