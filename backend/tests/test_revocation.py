"""Revocation (A3.4 / A5.5): the browser step-up can ask Firebase whether the
token was revoked, a staff suspension revokes the user's refresh tokens, and
an account erase deletes the Firebase user. The Admin-SDK calls are
best-effort until the runtime account holds Firebase Auth admin."""
import time

import pytest
from firebase_admin import auth as fb_auth

import fake_firestore
import repo_view as repo
from app import deps, drive, google_auth
from app.config import settings


# ---------------- google_auth ----------------
def test_verify_id_token_passes_check_revoked_through(monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "verify_id_token",
                        lambda token, check_revoked=False: seen.append((token, check_revoked)) or {})
    google_auth.verify_id_token("t1")
    google_auth.verify_id_token("t2", check_revoked=True)
    assert seen == [("t1", False), ("t2", True)]


def test_revoke_sessions_is_true_when_firebase_accepts(monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)
    assert google_auth.revoke_sessions("u1") is True
    assert seen == ["u1"]


def test_revoke_sessions_failure_is_logged_and_false(monkeypatch, caplog):
    def denied(uid):
        raise PermissionError("caller lacks firebaseauth.users.update")

    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", denied)
    assert google_auth.revoke_sessions("u1") is False
    assert "revoke_refresh_tokens failed uid=u1: PermissionError" in caplog.text


def test_delete_auth_user_counts_an_absent_user_as_deleted(monkeypatch):
    def gone(uid):
        raise fb_auth.UserNotFoundError("no user record")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", gone)
    assert google_auth.delete_auth_user("u1") is True


def test_delete_auth_user_failure_is_logged_and_false(monkeypatch, caplog):
    def denied(uid):
        raise PermissionError("caller lacks firebaseauth.users.delete")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", denied)
    assert google_auth.delete_auth_user("u1") is False
    assert "delete_user failed uid=u1: PermissionError" in caplog.text


def test_delete_auth_user_is_true_when_firebase_accepts(monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "delete_user", seen.append)
    assert google_auth.delete_auth_user("u1") is True
    assert seen == ["u1"]


def test_the_setting_is_off_by_default():
    assert settings.CHECK_REVOKED_ON_STEP_UP is False


# ---------------- the browser step-up ----------------
@pytest.fixture
def stepping_admin(monkeypatch, audited):
    """A staff admin in a browser with a fresh second factor, and a record of
    every verify_id_token call (token, check_revoked)."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    calls: list[tuple[str, bool]] = []
    outcome: dict = {"revoked_check": None}

    def verify(token, *, check_revoked=False):
        calls.append((token, check_revoked))
        if check_revoked and outcome["revoked_check"] is not None:
            raise outcome["revoked_check"]
        return {"sub": "admin-1", "email": "admin@sempermechanics.com", "email_verified": True,
                "firebase": {"sign_in_second_factor": "phone"}, "auth_time": time.time() - 60}

    monkeypatch.setattr(deps, "verify_id_token", verify)
    repo.patch(monkeypatch, "get_or_create_user", lambda c, device_id=None: {
        "uid": "admin-1", "email": "admin@sempermechanics.com",
        "role": "admin", "access_status": "APPROVED",
    })
    store._data["users"] = {
        "admin-1": {"email": "admin@sempermechanics.com", "role": "admin", "access_status": "APPROVED"},
        "target": {"email": "t@e.com", "role": "user", "access_status": "PENDING"},
    }
    return {"store": store, "calls": calls, "outcome": outcome, "audited": audited}


async def test_off_the_step_up_does_not_ask_about_revocation(stepping_admin, client, monkeypatch):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", False)
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 200, r.text
    assert stepping_admin["calls"] == [("tok", False)]


async def test_on_a_live_token_passes_after_one_revocation_check(stepping_admin, client, monkeypatch):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    r = await client.post("/v1/admin/users/target/approve",
                          headers={"X-Forwarded-Authorization": "Bearer fwd",
                                   "Authorization": "Bearer gateway-sa"})
    assert r.status_code == 200, r.text
    # The client's own token, the one behind the gateway, is the one checked.
    assert stepping_admin["calls"] == [("fwd", False), ("fwd", True)]
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "APPROVED"


@pytest.mark.parametrize("error", [
    fb_auth.RevokedIdTokenError("The Firebase ID token has been revoked."),
    fb_auth.UserDisabledError("The user record is disabled."),
])
async def test_on_a_revoked_or_disabled_user_is_refused(stepping_admin, client, monkeypatch, error):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    stepping_admin["outcome"]["revoked_check"] = error
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "PENDING"
    denied = [a for a in stepping_admin["audited"] if a.get("action") == "AUTH_DENIED"]
    assert denied == [{"uid": "admin-1", "deviceId": None, "action": "AUTH_DENIED",
                       "outcome": "DENIED", "detail": {"stage": "revoked"}}]


async def test_on_a_check_that_cannot_answer_fails_closed(stepping_admin, client, monkeypatch, caplog):
    """No Firebase Auth admin grant (or Auth down): refused, and logged as
    the check failing rather than as a revoked user."""
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    stepping_admin["outcome"]["revoked_check"] = PermissionError("403 caller lacks permission")
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 401
    assert r.json()["detail"] == "invalid_token"
    assert stepping_admin["store"]._data["users"]["target"]["access_status"] == "PENDING"
    assert [a["detail"] for a in stepping_admin["audited"] if a.get("action") == "AUTH_DENIED"] == [
        {"stage": "revocation_check"}]
    assert '"event":"revocation_check_failed"' in caplog.text
    assert '"errorCode":"PermissionError"' in caplog.text


async def test_on_the_device_signed_path_is_not_asked(stepping_admin, client, monkeypatch):
    """A phone's signed request is held to its device record, not to this.
    It fails here on the device (not registered), with no revocation call."""
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    r = await client.post("/v1/admin/users/target/approve", headers={
        "Authorization": "Bearer tok", "X-Device-Id": "adev", "X-Nonce": "n" * 16,
        "X-Signature": "c2ln",
    })
    assert r.status_code == 409
    assert all(not checked for _, checked in stepping_admin["calls"])


async def test_on_a_missing_second_factor_is_refused_before_any_revocation_call(
    stepping_admin, client, monkeypatch,
):
    monkeypatch.setattr(settings, "CHECK_REVOKED_ON_STEP_UP", True)
    monkeypatch.setattr(deps, "verify_id_token", lambda token, *, check_revoked=False: (
        stepping_admin["calls"].append((token, check_revoked)) or {
            "sub": "admin-1", "email": "admin@sempermechanics.com"}))
    r = await client.post("/v1/admin/users/target/approve", headers={"Authorization": "Bearer tok"})
    assert r.status_code == 403
    assert stepping_admin["calls"] == [("tok", False)]


# ---------------- staff suspension ----------------
@pytest.fixture
def admin_store(store, audited):
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED", "role": "user"}}
    return store


async def test_revoking_a_user_revokes_their_sessions(admin_store, audited, client, monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)
    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert seen == ["u1"]
    row = next(a for a in audited if a["action"] == "ADMIN_REVOKE")
    assert row["detail"] == {"sessionsRevoked": True}
    assert row["target"] == {"type": "user", "id": "u1"}


async def test_a_failed_session_revoke_still_suspends(admin_store, audited, client, monkeypatch):
    def denied(uid):
        raise PermissionError("no firebaseauth.admin")

    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", denied)
    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert r.json() == {"uid": "u1", "access_status": "SUSPENDED"}
    assert admin_store._data["users"]["u1"]["access_status"] == "SUSPENDED"
    assert next(a for a in audited if a["action"] == "ADMIN_REVOKE")["detail"] == {
        "sessionsRevoked": False}


async def test_revoking_a_missing_user_revokes_nothing(admin_store, client, monkeypatch):
    seen = []
    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", seen.append)
    assert (await client.post("/v1/admin/users/nobody/revoke")).status_code == 404
    assert seen == []


# ---------------- account erase ----------------
@pytest.fixture
def erasable(store, audited, monkeypatch):
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED",
                                         "driveFolderId": "uf"}}
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "driveFolderId": "uf"})
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "delete_file", lambda token, fid: None)
    return store


async def test_erasing_the_account_deletes_the_firebase_user_last(erasable, audited, client, monkeypatch):
    order = []
    monkeypatch.setattr(google_auth.fb_auth, "delete_user", lambda uid: order.append(("auth", uid)))
    real = repo.delete_all_user_data
    repo.patch(monkeypatch, "delete_all_user_data",
               lambda uid: order.append(("data", uid)) or real(uid))
    r = await client.delete("/v1/me")
    assert r.status_code == 200, r.text
    assert order == [("data", "dev-user"), ("auth", "dev-user")]
    assert r.json()["authUserDeleted"] is True
    row = next(a for a in audited if a["action"] == "ACCOUNT_DELETE")
    assert row["detail"]["authUserDeleted"] is True


async def test_a_failed_auth_delete_still_erases_and_says_so(erasable, audited, client, monkeypatch):
    def denied(uid):
        raise PermissionError("no firebaseauth.admin")

    monkeypatch.setattr(google_auth.fb_auth, "delete_user", denied)
    r = await client.delete("/v1/me")
    assert r.status_code == 200
    assert r.json()["authUserDeleted"] is False
    assert "dev-user" not in erasable._data.get("users", {})
    assert next(a for a in audited if a["action"] == "ACCOUNT_DELETE")["detail"][
        "authUserDeleted"] is False

