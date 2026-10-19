"""Revoking an account, and rebinding to a new phone, must also settle the
device records. Both used to leave stale ACTIVE device docs behind."""
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import repo_view as repo
from app.models import DeviceReg
from refusals import attempt

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
def store(store, monkeypatch):
    pem = _public_pem()
    s = store
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


def test_registering_a_second_device_while_bound_is_refused(store):
    """A device switch needs the old binding released first (a lock clear or
    a staff release). The check and the write are one transaction, so two
    registrations racing from two phones cannot both leave a device ACTIVE."""
    code, _ = attempt(repo.register_device, "u1", DeviceReg(
        deviceId=DEV_B, publicKeyPem=_public_pem(),
        model="Pixel", osVersion="15", appVersion="1.0",
    ))

    assert code == "device_conflict"
    assert DEV_B not in store._data["devices"]
    assert store._data["devices"][DEV_A]["status"] == "ACTIVE"
    assert store._data["users"]["u1"]["activeDeviceId"] == DEV_A


def test_registering_a_device_another_account_holds_is_refused(store):
    store._data["users"]["u1"].pop("activeDeviceId")
    code, _ = attempt(repo.register_device, "u1", DeviceReg(
        deviceId="other-001", publicKeyPem=_public_pem(),
    ))

    assert code == "device_in_use"
    assert store._data["devices"]["other-001"]["uid"] == "u2"
    assert "activeDeviceId" not in store._data["users"]["u1"]


def test_registering_after_a_release_binds_the_new_device(store):
    store._data["users"]["u1"].pop("activeDeviceId")
    repo.register_device("u1", DeviceReg(deviceId=DEV_B, publicKeyPem=_public_pem()))

    assert store._data["devices"][DEV_B]["status"] == "ACTIVE"
    assert store._data["devices"][DEV_B]["stepUpEligible"] is False
    assert store._data["users"]["u1"]["activeDeviceId"] == DEV_B


def test_a_registration_records_whether_it_proved_a_second_factor(store):
    repo.register_device("u1", DeviceReg(deviceId=DEV_A, publicKeyPem=_public_pem()),
                         step_up_eligible=True)

    assert store._data["devices"][DEV_A]["stepUpEligible"] is True
    assert store._data["devices"][DEV_A]["app"] == "semper"


def test_reregistering_the_same_device_stays_active(store):
    """The reinstall heal path: same id re-registers, refreshing its key."""
    repo.register_device("u1", DeviceReg(
        deviceId=DEV_A, publicKeyPem=_public_pem(),
        model="Pixel", osVersion="16", appVersion="1.1",
    ))

    assert store._data["devices"][DEV_A]["status"] == "ACTIVE"
    assert store._data["devices"][DEV_A]["osVersion"] == "16"
    assert store._data["users"]["u1"]["activeDeviceId"] == DEV_A
