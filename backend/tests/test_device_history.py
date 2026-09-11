"""Device-move history for one licence — Gap C admin read of audit logs."""
from datetime import datetime, timezone

import fake_firestore
import pytest

from app import audit, deps, firestore_repo as repo
from app.config import settings


@pytest.fixture
def store(monkeypatch):
    s = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": "admin-1", "email": "admin@sempermechanics.com",
                    "email_verified": True},
    )
    monkeypatch.setattr(
        repo, "get_or_create_user",
        lambda claims, device_id=None: {
            "uid": "admin-1", "email": "admin@sempermechanics.com",
            "role": "admin", "access_status": "APPROVED",
        },
    )
    s._data["users"] = {
        "admin-1": {
            "email": "admin@sempermechanics.com", "role": "admin",
            "access_status": "APPROVED",
        },
    }
    return s


def _add_audit(store, *, action, target_id, detail=None, ts=None, uid="u1"):
    store._data.setdefault("audit_logs", {})
    doc_id = f"a{len(store._data['audit_logs'])}"
    store._data["audit_logs"][doc_id] = {
        "ts": ts or datetime(2026, 9, 1, 12, 0, tzinfo=timezone.utc),
        "uid": uid,
        "action": action,
        "outcome": "OK",
        "target": {"type": "license", "id": target_id},
        "detail": detail or {},
    }
    return doc_id


@pytest.mark.asyncio
async def test_device_history_lists_bind_and_clear_for_one_licence(store, client):
    license_id = "lic-hist-1"
    store._data["licenses"] = {
        license_id: {"kind": "individual", "status": "active", "keyPrefix": "SEMP-HIST"},
        "other": {"kind": "individual", "status": "active", "keyPrefix": "SEMP-OTHR"},
    }
    _add_audit(
        store, action="LICENSE_DEVICE_BIND", target_id=license_id,
        detail={"deviceId": "phone-a"},
        ts=datetime(2026, 9, 2, tzinfo=timezone.utc),
    )
    _add_audit(
        store, action="ADMIN_DEVICE_LOCK_CLEAR", target_id=license_id,
        detail={"previousDeviceId": "phone-a"},
        ts=datetime(2026, 9, 3, tzinfo=timezone.utc),
    )
    _add_audit(
        store, action="LICENSE_DEVICE_BIND", target_id="other",
        detail={"deviceId": "elsewhere"},
    )

    r = await client.get(
        f"/v1/admin/licenses/{license_id}/device-history",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 200, r.text
    events = r.json()["events"]
    assert [e["action"] for e in events] == [
        "ADMIN_DEVICE_LOCK_CLEAR",
        "LICENSE_DEVICE_BIND",
    ]
    assert all(e["target"]["id"] == license_id for e in events)


@pytest.mark.asyncio
async def test_device_history_includes_seat_clears_under_the_licence(store, client):
    license_id = "lic-inst"
    store._data["licenses"] = {
        license_id: {"kind": "institution", "status": "active", "keyPrefix": "SEMP-INST"},
    }
    store._data.setdefault("audit_logs", {})["seat1"] = {
        "ts": datetime(2026, 9, 4, tzinfo=timezone.utc),
        "uid": "it-1",
        "action": "INSTITUTION_SEAT_PATCH",
        "outcome": "OK",
        "target": {"type": "seat", "id": f"{license_id}/student-1"},
        "detail": {"clearDeviceLock": True, "previousDeviceId": "dev-old"},
    }
    store._data["audit_logs"]["seat2"] = {
        "ts": datetime(2026, 9, 5, tzinfo=timezone.utc),
        "uid": "it-1",
        "action": "INSTITUTION_SEAT_PATCH",
        "outcome": "OK",
        "target": {"type": "seat", "id": f"{license_id}/student-1"},
        "detail": {"clearDeviceLock": False, "enabled": False},
    }

    r = await client.get(
        f"/v1/admin/licenses/{license_id}/device-history",
        headers={"Authorization": "Bearer ok"},
    )
    assert r.status_code == 200, r.text
    events = r.json()["events"]
    assert len(events) == 1
    assert events[0]["detail"]["previousDeviceId"] == "dev-old"


def test_list_license_device_history_filters_non_device_seat_patches(store):
    license_id = "lic-x"
    store._data.setdefault("audit_logs", {})["p"] = {
        "ts": datetime(2026, 9, 1, tzinfo=timezone.utc),
        "uid": "it",
        "action": "INSTITUTION_SEAT_PATCH",
        "outcome": "OK",
        "target": {"type": "seat", "id": f"{license_id}/u"},
        "detail": {"enabled": True},
    }
    assert audit.list_license_device_history(license_id) == []
