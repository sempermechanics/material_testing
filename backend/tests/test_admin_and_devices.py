"""Admin approve/revoke, list_users limit clamp, and device registration conflicts."""
import pytest

from app import audit, firestore_repo as repo
from app import deps
from key_helpers import _ec_pem


@pytest.fixture
def store(store, monkeypatch):
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


# ---------------- admin approve / revoke ----------------
async def test_admin_approve_and_revoke(store, client):
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "PENDING", "role": "user"},
    }

    r = await client.post("/v1/admin/users/u1/approve")
    assert r.status_code == 200
    assert r.json() == {"uid": "u1", "access_status": "APPROVED"}
    assert store._data["users"]["u1"]["access_status"] == "APPROVED"

    r = await client.post("/v1/admin/users/u1/revoke")
    assert r.status_code == 200
    assert r.json() == {"uid": "u1", "access_status": "SUSPENDED"}
    assert store._data["users"]["u1"]["access_status"] == "SUSPENDED"


async def test_admin_approve_missing_user_404(store, client):
    r = await client.post("/v1/admin/users/missing/approve")
    assert r.status_code == 404


async def test_admin_list_users_clamps_limit(store, client, monkeypatch):
    store._data["users"] = {
        f"u{i}": {"email": f"u{i}@t.com", "access_status": "APPROVED", "role": "user"}
        for i in range(5)
    }
    seen = {}

    def spy(status="", limit=50, page_token=None):
        seen["limit"] = limit
        seen["page_token"] = page_token
        users = [{"uid": uid, "email": u.get("email"), "displayName": None,
                  "role": u.get("role"), "access_status": u.get("access_status"),
                  "activeDeviceId": None}
                 for uid, u in list(store._data["users"].items())[:limit]]
        return users, None

    monkeypatch.setattr(repo, "list_users", spy)

    r = await client.get("/v1/admin/users?limit=9999")
    assert r.status_code == 200
    assert seen["limit"] == 200

    r = await client.get("/v1/admin/users?limit=0")
    assert r.status_code == 200
    assert seen["limit"] == 1

    r = await client.get("/v1/admin/users?limit=3")
    assert r.status_code == 200
    assert seen["limit"] == 3
    assert len(r.json()["users"]) == 3
    assert r.json()["page"]["hasMore"] is False


async def test_admin_list_users_cursor(store, client):
    store._data["users"] = {
        f"u{i:02d}": {"email": f"u{i}@t.com", "access_status": "APPROVED", "role": "user"}
        for i in range(5)
    }
    first = await client.get("/v1/admin/users?limit=2")
    assert first.status_code == 200
    body = first.json()
    assert len(body["users"]) == 2
    assert body["page"]["hasMore"] is True
    token = body["page"]["nextPageToken"]
    second = await client.get(f"/v1/admin/users?limit=2&page_token={token}")
    assert second.status_code == 200
    assert len(second.json()["users"]) == 2
    assert {u["uid"] for u in first.json()["users"]}.isdisjoint(
        {u["uid"] for u in second.json()["users"]}
    )


# ---------------- register_device conflicts ----------------
async def test_register_device_conflict_when_account_bound(store, client, monkeypatch):
    # DEV user already has activeDeviceId="dev-device"; registering a different id conflicts.
    monkeypatch.setattr(
        deps, "_DEV_USER",
        {**deps._DEV_USER, "activeDeviceId": "already-bound-device"},
    )
    r = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-newdevice", "publicKeyPem": _ec_pem()},
    )
    assert r.status_code == 409
    assert r.json()["detail"] == "device_conflict"


async def test_register_device_in_use_by_other_account(store, client, monkeypatch):
    monkeypatch.setattr(
        deps, "_DEV_USER",
        {**deps._DEV_USER, "activeDeviceId": None},
    )
    store._data["devices"] = {
        "and-shared1": {"uid": "other-user", "status": "ACTIVE", "publicKeyPem": "x"},
    }
    r = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-shared1", "publicKeyPem": _ec_pem()},
    )
    assert r.status_code == 409
    assert r.json()["detail"] == "device_in_use"


async def test_register_device_heals_same_device(store, client, monkeypatch):
    pem = _ec_pem()
    monkeypatch.setattr(
        deps, "_DEV_USER",
        {**deps._DEV_USER, "activeDeviceId": "and-mine12"},
    )
    store._data["users"] = {
        "dev-user": {"email": "dev@local", "access_status": "APPROVED", "activeDeviceId": "and-mine12"},
    }
    store._data["devices"] = {
        "and-mine12": {"uid": "dev-user", "status": "ACTIVE", "publicKeyPem": "old"},
    }
    r = await client.post(
        "/v1/devices/register",
        json={"deviceId": "and-mine12", "publicKeyPem": pem},
    )
    assert r.status_code == 201
    body = r.json()
    assert body["deviceId"] == "and-mine12"
    assert body["healed"] is True
    assert store._data["devices"]["and-mine12"]["publicKeyPem"] == pem
