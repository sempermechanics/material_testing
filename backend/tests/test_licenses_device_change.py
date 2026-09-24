"""Moving a licence or seat to a new device: the holder's own change and the admin unbind."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from app.config import settings
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)


# ======================================================= changing device (D)
# One primitive, three actors. Since an individual licence binds on first use,
# emptying the lock *is* the device change: whoever signs in next takes it.
# The holder's own change is the one that carries a cooldown, because a second
# factor proves who is asking and not how often.


def _bound_individual(store, device_id="old-phone"):
    """An individual licence held by solo-1 and bound to `device_id`."""
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, device_id)
    assert store._data["licenses"][license_id]["deviceIdLock"] == device_id
    return license_id


def _bound_seat(store, device_id="old-phone"):
    """An institution seat held by u1 and bound to `device_id`."""
    store._data["users"] = {
        "u1": {"uid": "u1", "email": "a@university.edu",
               "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "a@university.edu", device_id, minted["key"])
    return license_id


def test_staff_clear_an_individual_lock_and_the_next_device_binds(store):
    license_id = _bound_individual(store)

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["scope"] == "license"
    assert cleared["previousDeviceId"] == "old-phone"
    rebound = repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert rebound["mode"] == "licensed"
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_it_clears_a_seat_and_the_next_device_binds(store):
    license_id = _bound_seat(store)

    err, cleared = repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_IT)

    assert err == ""
    assert cleared["scope"] == "seat"
    assert cleared["previousDeviceId"] == "old-phone"
    rebound = repo.revalidate_device_lock(store._data["users"]["u1"], "new-phone")
    assert rebound["mode"] == "licensed"
    seats = store._data[f"licenses/{license_id}/seats"]
    assert seats["u1"]["deviceIdLock"] == "new-phone"


def test_the_holder_changes_their_own_device(store):
    license_id = _bound_individual(store)

    err, cleared = repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)

    assert err == ""
    assert cleared["previousDeviceId"] == "old-phone"
    # Only the self-service path stamps the clock the cooldown is read from.
    assert store._data["licenses"][license_id]["deviceChangedAt"]
    assert cleared["nextChangeAllowedAt"]
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_a_second_self_service_change_inside_the_cooldown_is_refused(store):
    """The licence-sharing vector: one person re-binding daily passes a single
    licence round a lab. A second factor cannot see that; the cooldown can."""
    license_id = _bound_individual(store)
    assert repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)[0] == ""
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")

    err, cleared = repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)

    assert err == "device_change_too_soon"
    assert cleared is None
    assert store._data["licenses"][license_id]["deviceIdLock"] == "new-phone"


def test_the_cooldown_lapses(store):
    license_id = _bound_individual(store)
    store._data["licenses"][license_id]["deviceChangedAt"] = (
        datetime.now(timezone.utc)
        - timedelta(days=settings.SELF_DEVICE_CHANGE_COOLDOWN_DAYS + 1)
    )

    assert repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)[0] == ""


def test_a_support_clear_ignores_the_cooldown(store):
    """A lost phone does not wait 30 days. Staff and IT neither read nor write
    the stamp, so a support request always works however recently the holder
    changed device themselves."""
    license_id = _bound_individual(store)
    repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF)
    stamped = store._data["licenses"][license_id]["deviceChangedAt"]
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")

    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert cleared["previousDeviceId"] == "new-phone"
    assert store._data["licenses"][license_id]["deviceChangedAt"] == stamped


def test_clearing_a_lock_is_not_revoking(store):
    """The lock goes empty and nothing else moves: the seat still counts
    against maxSeats, the holder keeps their entitlement, and no data is
    touched."""
    license_id = _bound_seat(store)
    assert store._data["licenses"][license_id]["seatsUsed"] == 1

    repo.clear_device_lock(license_id, "u1", actor=repo.ACTOR_STAFF)

    assert store._data["licenses"][license_id]["seatsUsed"] == 1
    assert store._data["licenses"][license_id]["status"] == "active"
    seat = store._data[f"licenses/{license_id}/seats"]["u1"]
    assert (seat["status"], seat["deviceIdLock"]) == ("active", "")
    assert store._data["users"]["u1"]["plan"] == "professional"


def test_a_revoked_licence_is_not_re_entitled_by_clearing_its_lock(store):
    """Clearing restores a mode the mismatch took away — it must not restore
    one a revoke took away. Support clearing a lock on a dead licence would
    otherwise hand the holder their entitlement back."""
    license_id = _bound_individual(store)
    repo.revoke_license(license_id, "admin")
    assert store._data["users"]["solo-1"]["mode"] == "demo"

    err, _ = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert store._data["users"]["solo-1"]["mode"] == "demo"


def test_a_device_change_is_audited_at_both_ends(store, audited):
    """The record has to name the device given up and the one that took its
    place; neither half is the change on its own."""
    license_id = _bound_individual(store)
    err, cleared = repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)
    assert (err, cleared["previousDeviceId"]) == ("", "old-phone")
    repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")

    bound = [r for r in audited if r["action"] == "LICENSE_DEVICE_BIND"]
    assert [r["deviceId"] for r in bound] == ["old-phone", "new-phone"]
    assert bound[-1]["target"] == {"type": "license", "id": license_id}


def test_restore_on_a_new_device_waits_for_the_lock_to_move(store):
    """The ordering Gap D's runbook turns on.

    `/v1/files/{id}/content` is device-attested, and `verified_device`
    re-validates the lock before the route reads `cloud_backup_enabled`. So on
    a device the licence is not bound to, restore is refused by the licence
    check rather than half-served; once the lock has moved and the new device
    has bound, the same call is entitled.
    """
    license_id = _bound_individual(store)

    # Before: the new phone is not the bound device, so the account resolves
    # to demo for this request and cloud restore is not licensed.
    demoted = repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert demoted["mode"] == "demo"
    assert repo.cloud_backup_enabled(demoted) is False

    repo.clear_device_lock(license_id, actor=repo.ACTOR_STAFF)

    # After: the first authed request from the new phone binds the lock, and
    # the restore that follows it is entitled.
    rebound = repo.revalidate_device_lock(store._data["users"]["solo-1"], "new-phone")
    assert rebound["mode"] == "licensed"
    assert repo.cloud_backup_enabled(rebound) is True


def test_clearing_a_lock_on_a_licence_that_does_not_exist(store):
    store._data["users"] = {}
    assert repo.clear_device_lock("no-such-licence")[0] == "license_not_found"


def test_clearing_a_seat_that_does_not_exist(store):
    license_id = _mint_institution()["license"]["id"]
    assert repo.clear_device_lock(license_id, "nobody")[0] == "seat_not_found"
    # An institution licence holds no lock of its own — there is no seat to
    # name, so this is a caller error rather than a licence-wide clear.
    assert repo.clear_device_lock(license_id)[0] == "seat_not_found"


def _dev_user_holds(store, monkeypatch, email="dev@local"):
    """Sign the DEV_INSECURE_AUTH caller in against an individual licence.

    `current_user` returns the literal `deps._DEV_USER` in dev mode rather
    than reading the store, so a route that acts on the caller's own licence
    sees a licenceless account unless that dict is the one carrying it.
    """
    license_id = _mint_individual(email)["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "dev-user", email), None)
    repo.revalidate_device_lock(user, "old-phone")
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))
    return license_id


@pytest.mark.asyncio
async def test_the_holder_changes_device_over_http(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _dev_user_holds(store, monkeypatch)

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["deviceIdLock"] == ""
    assert body["previousDeviceId"] == "old-phone"
    assert body["nextChangeAllowedAt"]
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    assert [r["action"] for r in audited if r["action"] == "LICENSE_DEVICE_UNBIND"]


@pytest.mark.asyncio
async def test_a_holder_with_no_licence_has_no_device_to_change(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {"dev-user": {"uid": "dev-user", "email": "dev@local"}}
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"]["dev-user"]))

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 404
    assert resp.json()["detail"] == "no_license"


@pytest.mark.asyncio
async def test_the_holder_cannot_change_device_twice_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _dev_user_holds(store, monkeypatch)
    assert (await client.post("/v1/licenses/unbind")).status_code == 200

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 429
    assert resp.json()["detail"] == "device_change_too_soon"


@pytest.mark.asyncio
async def test_staff_clear_an_individual_lock_over_http(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "old-phone")

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}", json={"clearDeviceLock": True},
    )

    assert resp.status_code == 200, resp.text
    assert resp.json()["deviceIdLock"] == ""
    assert store._data["licenses"][license_id]["deviceIdLock"] == ""
    cleared = [r for r in audited if r["action"] == "ADMIN_DEVICE_LOCK_CLEAR"]
    assert cleared and cleared[0]["detail"]["previousDeviceId"] == "old-phone"


@pytest.mark.asyncio
async def test_staff_clear_a_seat_lock_over_http(client, monkeypatch, audited):
    """The gap this route closes: the operator console used to call the
    institution-tier route, which 404s for staff who are not in that licence's
    adminEmails — every licence Semper did not itself administer."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    license_id = _bound_seat(store)

    resp = await client.patch(f"/v1/admin/licenses/{license_id}/seats/u1/device")

    assert resp.status_code == 200, resp.text
    assert resp.json() == {
        "licenseId": license_id, "uid": "u1",
        "deviceIdLock": "", "previousDeviceId": "old-phone",
    }
    assert store._data[f"licenses/{license_id}/seats"]["u1"]["deviceIdLock"] == ""
    assert [r for r in audited if r["action"] == "ADMIN_DEVICE_LOCK_CLEAR"]
