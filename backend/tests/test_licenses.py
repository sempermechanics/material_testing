"""License keys: format, demo issue at verification, activate locks, ops mint."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from app.config import settings
from app.licenses import canonicalize, generate_key, key_hash, key_prefix


@pytest.fixture
def store(monkeypatch):
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    return fake_firestore.install(monkeypatch)


def test_canonicalize_strips_separators():
    assert canonicalize("semp-ab12-cd34") == canonicalize("SEMPAB12CD34")
    assert key_hash("SEMP-AAAA-BBBB-CCCC-DDDD") == key_hash("semp aaaa bbbb cccc dddd")


def test_generate_key_shape():
    key = generate_key()
    parts = key.split("-")
    assert parts[0] == "SEMP"
    assert len(parts) == 5
    assert all(len(p) == 4 for p in parts[1:])
    assert key_prefix(key).startswith("SEMP-")


def test_demo_license_issued_when_approved_verified_and_device_bound(store):
    user = {
        "uid": "u1",
        "email": "A@B.com",
        "emailVerified": True,
        "access_status": "APPROVED",
        "activeDeviceId": "and-12345678",
    }
    store._data["users"] = {"u1": dict(user)}
    out = repo.ensure_demo_license(user, "and-12345678")
    assert out["plan"] == "demo"
    assert out["licenseId"]
    again = repo.ensure_demo_license(out, "and-12345678")
    assert again["licenseId"] == out["licenseId"]
    assert len(store._data["licenses"]) == 1
    lic = next(iter(store._data["licenses"].values()))
    assert lic["emailLock"] == "a@b.com"
    assert lic["deviceIdLock"] == "and-12345678"
    assert lic["status"] == "redeemed"
    assert lic["plan"] == "demo"


def test_demo_license_skipped_until_approved(store):
    user = {
        "uid": "u1",
        "email": "a@b.com",
        "emailVerified": True,
        "access_status": "PENDING",
    }
    store._data["users"] = {"u1": dict(user)}
    out = repo.ensure_demo_license(user, "and-12345678")
    assert "licenseId" not in out
    assert store._data.get("licenses", {}) == {}


def test_demo_license_skipped_without_device(store):
    user = {
        "uid": "u1",
        "email": "a@b.com",
        "emailVerified": True,
        "access_status": "APPROVED",
    }
    store._data["users"] = {"u1": dict(user)}
    out = repo.ensure_demo_license(user, None)
    assert "licenseId" not in out


def test_new_verified_approved_user_with_device_gets_demo_key(store, monkeypatch):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", True)
    u = repo.get_or_create_user(
        {
            "sub": "new-provisional",
            "email": "x@corp.com",
            "email_verified": True,
            "firebase": {"sign_in_provider": "google.com"},
        },
        device_id="and-device99",
    )
    assert u["access_status"] == "APPROVED"
    assert u["licenseId"]
    assert u["plan"] == "demo"


def test_activate_professional_requires_email_and_device_lock(store):
    store._data["users"] = {
        "dev-user": {
            "email": "dev@local", "access_status": "APPROVED", "plan": "demo",
        },
    }
    minted = repo.create_individual_license(
        email_lock="dev@local",
        device_id_lock="dev-device",
        created_by_uid="admin",
        max_analyses=40,
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )
    key = minted["key"]
    err, cfg = repo.activate_license("dev-user", "other@x.com", "dev-device", key)
    assert err == "license_email_mismatch"
    err, cfg = repo.activate_license("dev-user", "dev@local", "other-device", key)
    assert err == "license_device_mismatch"
    err, cfg = repo.activate_license("dev-user", "dev@local", "dev-device", key)
    assert err == ""
    assert cfg["plan"] == "professional"
    assert cfg["cloudBackupEnabled"] is True
    assert cfg["maxSessions"] == 40
    # Same uid, same key is idempotent.
    err2, cfg2 = repo.activate_license("dev-user", "dev@local", "dev-device", key)
    assert err2 == ""
    assert cfg2["plan"] == "professional"


def test_activate_unknown_key(store):
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED"}}
    err, cfg = repo.activate_license("dev-user", "dev@local", "dev-device", "SEMP-ZZZZ-ZZZZ-ZZZZ-ZZZZ")
    assert err == "license_not_found"
    assert cfg is None


def test_revoke_professional_drops_user_to_demo(store):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com",
        device_id_lock="and-12345678",
        created_by_uid="admin",
    )
    err, _ = repo.activate_license("u1", "a@b.com", "and-12345678", minted["key"])
    assert err == ""
    revoked = repo.revoke_license(minted["license"]["id"], "admin")
    assert revoked["status"] == "revoked"
    assert store._data["users"]["u1"]["plan"] == "demo"


@pytest.mark.asyncio
async def test_demo_cannot_create_session(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    resp = await client.post(
        "/v1/sessions",
        json={
            "specimen": "s",
            "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": "a" * 64}],
        },
    )
    assert resp.status_code == 403
    assert resp.json()["detail"] == "feature_not_licensed"


@pytest.mark.asyncio
async def test_activate_endpoint_happy_path(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {
            "email": "dev@local", "access_status": "APPROVED", "plan": "demo",
        },
    }
    minted = repo.create_individual_license(
        email_lock="dev@local",
        device_id_lock="dev-device",
        created_by_uid="admin",
    )
    resp = await client.post(
        "/v1/licenses/activate",
        json={"key": minted["key"]},
        headers={"X-Device-Id": "dev-device"},
    )
    assert resp.status_code == 200
    assert resp.json()["config"]["plan"] == "professional"


@pytest.mark.asyncio
async def test_admin_mint_and_list(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post(
        "/v1/admin/licenses",
        json={"emailLock": "pro@co.com", "deviceIdLock": "and-12345678"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["key"].startswith("SEMP-")
    assert body["license"]["emailLock"] == "pro@co.com"
    assert body["license"]["deviceIdLock"] == "and-12345678"
    listed = await client.get("/v1/admin/licenses")
    assert listed.status_code == 200
    assert listed.json()["page"]["count"] == 1


# ===================================================================== institution


def _mint_institution(max_seats=None):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
    )


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


def test_clear_seat_device_lock_lets_seat_holder_rebind(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "old-phone", minted["key"])
    # Without clearing, a new device on the same seat is rejected.
    err, _ = repo.activate_license("u1", "a@university.edu", "new-phone", minted["key"])
    assert err == "license_device_mismatch"

    assert repo.clear_seat_device_lock(license_id, "u1") is True
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


def test_downgrade_preserves_data_blocks_creation_then_reactivation_restores(store, monkeypatch):
    """Seed >25 sessions as Professional, downgrade (revoke), assert nothing is
    deleted and new cloud creation is blocked, then re-activate and confirm
    creation is restored with zero data loss."""
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
async def test_demo_after_downgrade_still_blocked_from_new_cloud_session(client, monkeypatch):
    # DEV_INSECURE_AUTH's verified_device()/current_user() resolve the caller
    # from the literal deps._DEV_USER dict, not a Firestore read — so the
    # gate under test (resolve_user_config(user)["cloudBackupEnabled"]) must
    # be driven by patching that dict's plan, exactly like the pre-existing
    # test_demo_cannot_create_session does. This simulates "the account was
    # just downgraded" (see test_downgrade_preserves_data_... above for the
    # repo-level proof that revoke never touches session data).
    from app import deps

    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    resp = await client.post(
        "/v1/sessions",
        json={
            "specimen": "s",
            "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": "a" * 64}],
        },
    )
    assert resp.status_code == 403
    assert resp.json()["detail"] == "feature_not_licensed"


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
async def test_institution_seat_patch_clear_device_lock_over_http(client, monkeypatch):
    # DEV_INSECURE_AUTH's current_user() always returns the literal
    # deps._DEV_USER dict, so the caller's email for the adminEmails
    # membership check must be set there, not just in the store.
    from app import deps

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


@pytest.mark.asyncio
async def test_institution_seat_revoke_over_http(client, monkeypatch):
    from app import deps

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


# ====================================================== rename compatibility
# The campus→institution / plan→mode rename must not strand an installed app
# or an institution IT script mid-deprecation. These pin both halves of that.

@pytest.mark.asyncio
async def test_pre_rename_campus_route_still_serves_the_same_handler(client, monkeypatch):
    """Institution IT scripts and curl one-liners hold the old path.

    /v1/campus/* is hidden from the OpenAPI schema but must keep routing, so
    an unchanged script does not start 404ing the day this deploys.
    """
    from app import deps

    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "access_status": "APPROVED", "plan": "demo"},
        "student": {"email": "a@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("student", "a@university.edu", "dev-1", minted["key"])

    legacy = await client.get(f"/v1/campus/licenses/{license_id}/seats")
    current = await client.get(f"/v1/institutions/licenses/{license_id}/seats")
    assert legacy.status_code == 200, legacy.text
    assert legacy.json() == current.json()


@pytest.mark.asyncio
async def test_admin_mint_accepts_the_pre_rename_campus_kind(client, monkeypatch):
    """Ops tooling that still sends kind="campus" mints an institution key."""
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.post(
        "/v1/admin/licenses",
        json={
            "kind": "campus",
            "domainLock": "university.edu",
            "adminEmails": ["it@university.edu"],
        },
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["license"]["kind"] == "institution"


def test_activation_resolves_a_pre_migration_license_document(store):
    """A license minted before the rename carries kind=campus and plan, not mode.

    Migration 002 rewrites these, but the dual-read window means an unmigrated
    document must still activate — otherwise every institution seat breaks
    between the code deploy and the migration run.
    """
    store._data["users"] = {
        "u1": {"email": "student@university.edu", "access_status": "APPROVED", "plan": "demo"},
    }
    key = "SEMP-AAAA-BBBB-CCCC-DDDD"
    store._data["licenses"] = {
        repo.key_hash(key): {
            "kind": "campus",              # pre-rename value
            "plan": "professional",        # pre-rename field
            "status": "active",
            "keyPrefix": "SEMP-AAAA",
            "domainLock": "university.edu",
            "adminEmails": ["it@university.edu"],
            "seatsUsed": 0,
        },
    }
    err, cfg = repo.activate_license("u1", "student@university.edu", "dev-1", key)
    assert err == ""
    assert cfg["mode"] == "licensed"
    assert cfg["plan"] == "professional"
    assert cfg["licenseKind"] == "institution"


# ============================================================ duration & grace
# A timed license stops at expiresAt + graceDays. Entitlements are UNCHANGED
# during grace — it exists so a renewal in flight does not interrupt work.

def _timed_user(days_from_now: float, grace_days: int = 14) -> dict:
    return {
        "uid": "u1",
        "mode": "licensed",
        "licenseDuration": "timed",
        "licenseGraceDays": grace_days,
        "licenseExpiresAt": datetime.now(timezone.utc) + timedelta(days=days_from_now),
    }


def test_grace_keeps_full_entitlements_after_expiry(store):
    cfg = repo.resolve_user_config(_timed_user(-2))
    assert cfg["mode"] == "licensed"
    assert cfg["inGrace"] is True
    # The point of grace: nothing is withdrawn, only flagged.
    assert cfg["cloudBackupEnabled"] is True
    assert cfg["shareEnabled"] is True


def test_before_expiry_is_licensed_and_not_in_grace(store):
    cfg = repo.resolve_user_config(_timed_user(5))
    assert (cfg["mode"], cfg["inGrace"]) == ("licensed", False)


def test_past_grace_drops_to_demo(store):
    cfg = repo.resolve_user_config(_timed_user(-20))
    assert cfg["mode"] == "demo"
    assert cfg["inGrace"] is False
    assert cfg["cloudBackupEnabled"] is False


def test_grace_boundary_is_the_last_moment_of_grace(store, monkeypatch):
    """Entitlement ends AT graceEndsAt, not a day either side of it."""
    expiry = datetime(2026, 6, 1, tzinfo=timezone.utc)
    user = {
        "uid": "u1", "mode": "licensed", "licenseDuration": "timed",
        "licenseGraceDays": 10, "licenseExpiresAt": expiry,
    }
    ends = expiry + timedelta(days=10)
    for now, expected in [
        (ends - timedelta(seconds=1), "licensed"),
        (ends, "demo"),
        (ends + timedelta(seconds=1), "demo"),
    ]:
        monkeypatch.setattr(repo, "_now", lambda now=now: now)
        assert repo.effective_mode(user) == expected, now


def test_zero_grace_is_a_hard_cliff(store):
    cfg = repo.resolve_user_config(_timed_user(-0.001, grace_days=0))
    assert cfg["mode"] == "demo"


def test_perpetual_never_expires(store):
    cfg = repo.resolve_user_config({"uid": "u1", "mode": "licensed"})
    assert cfg["mode"] == "licensed"
    assert cfg["licenseDuration"] == "perpetual"
    assert cfg["licenseExpiresAt"] is None
    assert cfg["licenseGraceEndsAt"] is None


def test_a_license_predating_grace_gets_none_retroactively(store):
    """Deploying a grace default must not reinstate already-expired accounts.

    A user document written before graceDays existed carries no such field. If
    that read as the fleet default, everyone who expired inside the window
    would silently come back to licensed on deploy.
    """
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "plan": "professional",  # pre-rename too, as such a document would be
        "licenseExpiresAt": datetime.now(timezone.utc) - timedelta(days=1),
    })
    assert cfg["mode"] == "demo"
    assert cfg["inGrace"] is False


def test_expired_institution_license_drops_the_seat_holder(store):
    """The only expiry test before this covered an individual license."""
    store._data["users"] = {
        "u1": {"email": "student@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(seconds=1),
    )
    err, cfg = repo.activate_license("u1", "student@university.edu", "dev-1", minted["key"])
    assert err == ""
    assert cfg["mode"] == "licensed"

    seat_holder = store._data["users"]["u1"]
    past = datetime.now(timezone.utc) - timedelta(days=365)
    assert repo.effective_mode({**seat_holder, "licenseExpiresAt": past}) == "demo"


def test_activating_an_expired_key_is_refused_not_silently_demoted(store):
    """It used to "succeed": the past expiry was mirrored on, the account
    resolved demo, and the caller got err="" with a demo config."""
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com",
        device_id_lock="dev-1",
        created_by_uid="admin",
        # Past the default grace window, not merely past expiry — a key inside
        # grace is still entitled and must still activate.
        expires_at=datetime.now(timezone.utc)
        - timedelta(days=settings.LICENSE_GRACE_DAYS_DEFAULT + 1),
    )
    err, cfg = repo.activate_license("u1", "a@b.com", "dev-1", minted["key"])
    assert err == "license_expired"
    assert cfg is None
    # And nothing was written onto the user.
    assert "licenseId" not in store._data["users"]["u1"]


def test_mint_records_duration_and_grace(store):
    timed = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )
    stored = store._data["licenses"][timed["license"]["id"]]
    assert stored["duration"] == "timed"
    # Stamped from the fleet default at mint, not inherited at read time.
    assert stored["graceDays"] == settings.LICENSE_GRACE_DAYS_DEFAULT

    perpetual = repo.create_individual_license(
        email_lock="c@d.com", device_id_lock="dev-2", created_by_uid="admin",
    )
    assert store._data["licenses"][perpetual["license"]["id"]]["duration"] == "perpetual"


def test_activating_inside_grace_still_works(store):
    """Grace is entitlement, not a warning state — a key can still be redeemed
    during it, and the redeemer lands in grace rather than being refused."""
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com",
        device_id_lock="dev-1",
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) - timedelta(days=1),
    )
    err, cfg = repo.activate_license("u1", "a@b.com", "dev-1", minted["key"])
    assert err == ""
    assert cfg["mode"] == "licensed"
    assert cfg["inGrace"] is True


# ====================================================== renewal (PATCH terms)
# Extending expiresAt has to reach people who already activated. The terms are
# mirrored onto each user at activation so the read path needs no Firestore
# lookup, which means editing the license alone reaches nobody.

def test_extending_an_individual_license_re_entitles_the_redeemer(store):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@b.com", "dev-1", minted["key"])

    far = datetime.now(timezone.utc) + timedelta(days=400)
    updated = repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert updated["expiresAt"] == far
    # The mirror moved with it — without the fan-out this would still be +1d.
    assert store._data["users"]["u1"]["licenseExpiresAt"] == far


def test_extending_an_institution_license_re_entitles_every_seat(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
        "u2": {"email": "b@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])

    far = datetime.now(timezone.utc) + timedelta(days=400)
    repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert store._data["users"]["u1"]["licenseExpiresAt"] == far
    assert store._data["users"]["u2"]["licenseExpiresAt"] == far


def test_extending_skips_a_revoked_seat(store):
    """A revoked seat holds no entitlement. Re-stamping its mirror would
    resurrect the member on the next resolve."""
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
        "u2": {"email": "b@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    repo.activate_license("u2", "b@university.edu", "dev-2", minted["key"])
    repo.revoke_institution_seat(license_id, "u2")

    far = datetime.now(timezone.utc) + timedelta(days=400)
    repo.update_license(license_id, {"expiresAt": far}, "admin")
    assert store._data["users"]["u1"]["licenseExpiresAt"] == far
    assert store._data["users"]["u2"]["mode"] == "demo"


def test_extending_does_not_touch_someone_on_a_different_license(store):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "mode": "demo"},
    }
    first = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    repo.activate_license("u1", "a@b.com", "dev-1", first["key"])
    # u1 moves to a second key; the first license no longer speaks for them.
    second = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
    )
    repo.activate_license("u1", "a@b.com", "dev-1", second["key"])

    far = datetime.now(timezone.utc) + timedelta(days=400)
    repo.update_license(first["license"]["id"], {"expiresAt": far}, "admin")
    # The second license is perpetual, so activating it cleared the mirror.
    # The fan-out must leave it cleared rather than stamping the first
    # license's new expiry onto someone who has moved off it.
    assert store._data["users"]["u1"].get("licenseExpiresAt") is None


def test_extending_a_perpetual_license_makes_it_timed(store):
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
    )
    assert minted["license"]["id"] in store._data["licenses"]
    far = datetime.now(timezone.utc) + timedelta(days=30)
    repo.update_license(minted["license"]["id"], {"expiresAt": far}, "admin")
    assert store._data["licenses"][minted["license"]["id"]]["duration"] == "timed"


def test_update_unknown_license_is_none(store):
    assert repo.update_license("nope", {"graceDays": 5}, "admin") is None


@pytest.mark.asyncio
async def test_admin_extend_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@b.com", "dev-1", minted["key"])

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}",
        json={"expiresAt": "2030-01-01T00:00:00Z", "graceDays": 30},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["duration"] == "timed"
    assert store._data["users"]["u1"]["licenseGraceDays"] == 30


@pytest.mark.asyncio
async def test_admin_extend_rejects_an_empty_patch(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    resp = await client.patch("/v1/admin/licenses/whatever", json={})
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_me_reports_the_license_summary(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    expiry = datetime.now(timezone.utc) - timedelta(days=1)
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER,
        "licenseExpiresAt": expiry, "licenseGraceDays": 14,
        "licenseDuration": "timed", "licenseKind": "institution",
    })
    body = (await client.get("/v1/me")).json()
    assert body["license"]["mode"] == "licensed"
    assert body["license"]["inGrace"] is True
    assert body["license"]["duration"] == "timed"
    assert body["license"]["kind"] == "institution"
    # Identity is unchanged — this is additive.
    assert body["uid"] == "dev-user"
    assert body["access_status"] == "APPROVED"
