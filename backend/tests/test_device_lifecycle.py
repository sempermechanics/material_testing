"""Revoking an account, and rebinding to a new phone, must also settle the
device records. Both used to leave stale ACTIVE device docs behind."""
import fake_firestore
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import firestore_repo as repo
from app.models import DeviceReg

DEV_A = "dev-a-0001"
DEV_B = "dev-b-0002"


def _public_pem() -> str:
    """DeviceReg parses and type-checks the key, so it must be a genuine P-256."""
    key = ec.generate_private_key(ec.SECP256R1()).public_key()
    return key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()


@pytest.fixture
def store(monkeypatch):
    pem = _public_pem()
    s = fake_firestore.install(monkeypatch)
    s._data["users"] = {
        "u1": {"email": "u1@x.test", "access_status": "APPROVED", "activeDeviceId": DEV_A},
    }
    s._data["devices"] = {
        DEV_A: {"uid": "u1", "status": "ACTIVE", "publicKeyPem": pem},
        "dev-old-9": {"uid": "u1", "status": "SUPERSEDED", "publicKeyPem": pem},
        "other-001": {"uid": "u2", "status": "ACTIVE", "publicKeyPem": pem},
    }
    return s


def test_suspending_revokes_the_accounts_devices(store):
    assert repo.set_user_status("u1", "SUSPENDED") is True

    assert store._data["users"]["u1"]["access_status"] == "SUSPENDED"
    assert store._data["devices"][DEV_A]["status"] == "REVOKED"
    assert "revokedAt" in store._data["devices"][DEV_A]
    # The binding itself is released, so the id is reusable after a rebind.
    assert "activeDeviceId" not in store._data["users"]["u1"]


def test_suspending_does_not_touch_another_users_device(store):
    repo.set_user_status("u1", "SUSPENDED")
    assert store._data["devices"]["other-001"]["status"] == "ACTIVE"


def test_approving_leaves_devices_alone(store):
    """Approval is not a device event — it must not resurrect or revoke one."""
    assert repo.set_user_status("u1", "APPROVED") is True
    assert store._data["devices"][DEV_A]["status"] == "ACTIVE"
    assert store._data["users"]["u1"]["activeDeviceId"] == DEV_A


def test_unknown_user_is_reported_not_written(store):
    assert repo.set_user_status("nobody", "SUSPENDED") is False
    assert "nobody" not in store._data["users"]


def test_rebinding_supersedes_the_previous_device(store):
    """A device switch left the old doc ACTIVE forever, so its id stayed held
    against other accounts by the device_in_use check."""
    repo.register_device("u1", DeviceReg(
        deviceId=DEV_B, publicKeyPem=_public_pem(),
        model="Pixel", osVersion="15", appVersion="1.0",
    ))

    assert store._data["devices"][DEV_A]["status"] == "SUPERSEDED"
    assert store._data["devices"][DEV_B]["status"] == "ACTIVE"
    assert store._data["users"]["u1"]["activeDeviceId"] == DEV_B


def test_reregistering_the_same_device_stays_active(store):
    """The reinstall heal path: same id re-registers, refreshing its key."""
    repo.register_device("u1", DeviceReg(
        deviceId=DEV_A, publicKeyPem=_public_pem(),
        model="Pixel", osVersion="16", appVersion="1.1",
    ))

    assert store._data["devices"][DEV_A]["status"] == "ACTIVE"
    assert store._data["devices"][DEV_A]["osVersion"] == "16"
    assert store._data["users"]["u1"]["activeDeviceId"] == DEV_A
