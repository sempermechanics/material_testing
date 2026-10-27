"""The demo cap counts per device as well as per account.

A demo account may keep `DEMO_MAX_ANALYSES` analyses in the cloud at once.
Counted per account alone, a new account on the same phone was a fresh
allowance; so for a demo account the check is
`max(account's stored, device's stored) >= maxSessions`, the device's
counting every account's sessions created from it (`repo.count_device_sessions`).
Licensed accounts keep the per-account check only.
"""
import pytest

from app import deps
import repo_view as repo
# Module level on purpose: test_config reloads app.config (see
# test_create_session_rollback).
from app.config import settings
from license_helpers import _recording_stubs

DEV_UID = "dev-user"
DEV_DEVICE = "dev-device"  # deps._DEV_DEVICE in DEV_INSECURE_AUTH mode
CAP = 2
_BODY = {"specimen": "s", "files": [{"name": "a.zip", "role": "bundle", "bytes": 10,
                                     "sha256": "a" * 64}]}


@pytest.fixture
def demo(store, monkeypatch):
    _recording_stubs(monkeypatch)
    store._data["users"] = {DEV_UID: {"email": "dev@local", "access_status": "APPROVED"}}
    store._data["sessions"] = {}
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", CAP)
    return store


def _stored(store, n, *, uid="other-account", device=DEV_DEVICE, status="COMPLETED"):
    for i in range(n):
        doc = {"uid": uid, "status": status, "fileCount": 1, "completedCount": 1}
        if device is not None:
            doc["deviceId"] = device
        store._data["sessions"][f"{uid}-{device}-{status}-{i}"] = doc


async def test_another_accounts_analyses_on_this_device_fill_the_demo_cap(demo, client):
    _stored(demo, CAP, uid="other-account")

    r = await client.post("/v1/sessions", json=_BODY)

    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    # Its own code (TD-219): the account's count has room, so the app must
    # not read this as the account cap that a re-check can clear.
    assert detail.startswith("device_quota_exceeded: ")
    # Nothing about the other account: no uid, no address, not even its count.
    assert "other-account" not in detail
    assert f"{CAP}/{CAP}" not in detail and "0/" not in detail
    assert "account" not in detail
    assert len(demo._data["sessions"]) == CAP


async def test_the_account_half_keeps_its_own_message(demo, client):
    _stored(demo, CAP, uid=DEV_UID, device="old-phone")

    r = await client.post("/v1/sessions", json=_BODY)

    assert r.status_code == 409
    assert r.json()["detail"].startswith(f"session_quota_exceeded: {CAP}/{CAP} analyses stored. ")


async def test_below_both_halves_the_session_is_created_and_names_its_device(demo, client):
    _stored(demo, CAP - 1, uid="other-account")

    r = await client.post("/v1/sessions", json=_BODY)

    assert r.status_code == 200, r.text
    created = demo._data["sessions"][r.json()["sessionId"]]
    assert (created["uid"], created["deviceId"]) == (DEV_UID, DEV_DEVICE)


async def test_failed_provisioning_stores_nothing_and_counts_toward_no_device(demo, client):
    _stored(demo, CAP, uid="other-account", status="PROVISION_FAILED")
    r = await client.post("/v1/sessions", json=_BODY)
    assert r.status_code == 200, r.text


async def test_sessions_without_a_device_id_count_toward_no_device(demo, client):
    """Old documents carry no `deviceId`; they are still held by their own
    account's cap, but cannot be charged to a phone."""
    _stored(demo, CAP, uid="other-account", device=None)
    r = await client.post("/v1/sessions", json=_BODY)
    assert r.status_code == 200, r.text


async def test_other_devices_do_not_count(demo, client):
    _stored(demo, CAP, uid="other-account", device="someone-elses-phone")
    r = await client.post("/v1/sessions", json=_BODY)
    assert r.status_code == 200, r.text


async def test_a_retry_of_an_in_flight_session_is_answered_at_the_device_cap(demo, client):
    """The idempotent retry runs before either half of the cap: the session it
    joins is already one of the device's stored analyses, so refusing it would
    strand an upload that the cap had already let in."""
    _stored(demo, CAP - 1, uid="other-account")
    demo._data["sessions"]["in-flight"] = {
        "uid": DEV_UID, "deviceId": DEV_DEVICE, "localSessionId": "L1",
        "status": "UPLOADING", "fileCount": 1, "completedCount": 0,
    }

    fresh = await client.post("/v1/sessions", json={**_BODY, "localSessionId": "L2"})
    retry = await client.post("/v1/sessions", json={**_BODY, "localSessionId": "L1"})

    assert fresh.status_code == 409, fresh.text
    assert retry.status_code == 200, retry.text
    assert retry.json()["sessionId"] == "in-flight"
    assert len(demo._data["sessions"]) == CAP


async def test_a_licensed_account_is_held_to_its_own_cap_only(demo, client, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "licensed",
                                            "plan": "professional"})
    # A cap the device's count would break, were it applied.
    monkeypatch.setattr(settings, "LICENSED_MAX_SESSIONS_PER_USER", CAP + 1)
    _stored(demo, CAP + 3, uid="other-account")

    r = await client.post("/v1/sessions", json=_BODY)

    assert r.status_code == 200, r.text


def test_count_device_sessions_counts_every_account_less_failed(demo):
    _stored(demo, 2, uid="a")
    _stored(demo, 1, uid="b")
    _stored(demo, 3, uid="c", status="PROVISION_FAILED")
    _stored(demo, 4, uid="d", device="elsewhere")
    _stored(demo, 5, uid="e", device=None)

    assert repo.count_device_sessions(DEV_DEVICE) == 3
    assert repo.count_device_sessions("elsewhere") == 4
    assert repo.count_device_sessions("") == 0
    assert repo.count_device_sessions(None) == 0


async def test_after_a_staff_release_the_next_holder_is_not_held_by_the_last(demo, client, monkeypatch):
    """TD-218 acceptance: a phone released by staff and registered by a second
    account does not count the first account's analyses toward its demo cap."""
    demo._data["users"]["old-holder"] = {
        "email": "old@lab.org", "access_status": "APPROVED", "mode": "demo",
        "activeDeviceId": DEV_DEVICE}
    _stored(demo, CAP, uid="old-holder")
    refused = await client.post("/v1/sessions", json=_BODY)
    assert refused.status_code == 409, refused.text

    released = repo.release_account_device("old-holder")
    assert repo.forget_device_sessions("old-holder", released.values()) == CAP

    created = await client.post("/v1/sessions", json=_BODY)
    assert created.status_code == 200, created.text
    assert repo.count_user_sessions("old-holder") == CAP, "still the old account's"
