"""Activations (ADR-033): a licence that names its app licenses only the phones
activated on it, counted against `maxDevices`.

Repo level: Activate and Deactivate against the count, an institution's pool,
the refusals Activate answers with, a licence from before the split (no
`app`) that still binds its lock, and how `revalidate_device_lock` judges a
phone on every request, including a build that does not tap Activate."""
from datetime import timedelta

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import repo_view as repo
from app.config import settings
from app.models import DeviceReg
from license_helpers import _mint_individual, _mint_institution, _signed_in
from refusals import attempt


# ------------------------------------------------------------------ helpers


def _phone(store, uid, device_id, app="semper"):
    """`device_id` registered ACTIVE to `uid` for `app`, as sign-in leaves it."""
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": app}


def _individual(store, max_devices=1, *, app="semper", uid="solo-1", email="solo@lab.org"):
    """An individual licence held by `uid`, stamped with `app` (activations)
    or, with `app=None`, from before the split (the device lock)."""
    store._data["users"] = {}
    license_id = _mint_individual(email)["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, uid, email), None)
    lic = store._data["licenses"][license_id]
    assert store._data["users"][uid]["licenseId"] == license_id
    if app:
        lic.update(app=app, maxDevices=max_devices)
    return license_id


def _institution(store, max_devices=2, members=("m1", "m2", "m3")):
    """An assigned institution licence running activations, with each of
    `members` seated (`<uid>@university.edu`)."""
    store._data["users"] = {}
    license_id = _mint_institution()["license"]["id"]
    for uid in members:
        _signed_in(store, uid, f"{uid}@university.edu")
        err, _ = attempt(repo.add_institution_member, license_id, f"{uid}@university.edu")
        assert err == ""
    store._data["licenses"][license_id].update(app="semper", maxDevices=max_devices)
    return license_id


def _floating(store, uid="f1"):
    store._data["users"] = {}
    license_id = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin", max_seats=2, seating="floating",
    )["license"]["id"]
    _signed_in(store, uid, f"{uid}@university.edu")
    assert attempt(repo.add_institution_member, license_id, f"{uid}@university.edu")[0] == ""
    store._data["licenses"][license_id]["app"] = "semper"
    return license_id


def _acts(store, license_id):
    return store._data.get(f"licenses/{license_id}/activations", {})


def _user(store, uid="solo-1"):
    return {**store._data["users"][uid], "uid": uid}


def _pem() -> str:
    key = ec.generate_private_key(ec.SECP256R1()).public_key()
    return key.public_bytes(encoding=serialization.Encoding.PEM,
                            format=serialization.PublicFormat.SubjectPublicKeyInfo).decode()


# ------------------------------------------------------- activate and count


def test_activate_within_the_count_then_the_next_phone_is_refused(store):
    """`maxDevices` is the limit, and the refusal says how full the licence is."""
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")

    err, done = attempt(repo.activate_device, "solo-1", "phone-a", "semper")
    assert err == ""
    assert (done["already"], done["devicesActive"], done["maxDevices"]) == (False, 1, 1)

    with pytest.raises(repo.Refusal) as refused:
        repo.activate_device("solo-1", "phone-b", "semper")
    assert refused.value.code == "device_limit_reached"
    assert refused.value.suffix == "1/1"
    lic = store._data["licenses"][license_id]
    assert lic["devicesActive"] == 1
    assert _acts(store, license_id)["phone-a"]["status"] == "active"
    assert "phone-b" not in _acts(store, license_id)


def test_staff_override_goes_past_the_count(store):
    """A support case the count should not block; audited as an override."""
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")
    repo.activate_device("solo-1", "phone-a", "semper")

    err, done = attempt(repo.activate_device, "solo-1", "phone-b", "semper",
                        actor=repo.ACTOR_STAFF, by_uid="staff-1", override=True)

    assert err == ""
    assert done["devicesActive"] == 2
    assert store._data["licenses"][license_id]["devicesActive"] == 2
    assert _acts(store, license_id)["phone-b"]["activatedBy"] == "staff"


def test_activating_the_same_phone_twice_is_harmless(store):
    """A double tap answers as a success and counts the phone once."""
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    repo.activate_device("solo-1", "phone-a", "semper")

    err, again = attempt(repo.activate_device, "solo-1", "phone-a", "semper")

    assert err == ""
    assert again["already"] is True
    assert again["devicesActive"] == 1
    assert store._data["licenses"][license_id]["devicesActive"] == 1


def test_deactivate_frees_the_slot(store):
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")
    repo.activate_device("solo-1", "phone-a", "semper")

    err, out = attempt(repo.deactivate_device, license_id, "phone-a",
                       actor=repo.ACTOR_SELF, caller=_user(store))

    assert err == ""
    assert out["devicesActive"] == 0
    assert _acts(store, license_id)["phone-a"]["status"] == "deactivated"
    assert attempt(repo.activate_device, "solo-1", "phone-b", "semper")[0] == ""


def test_no_cooldown_between_deactivate_and_activate(store):
    """The count is the only limit (ADR-033): five swaps in a row all work."""
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")
    current, other = "phone-a", "phone-b"
    assert attempt(repo.activate_device, "solo-1", current, "semper")[0] == ""

    for _ in range(5):
        err, _ = attempt(repo.deactivate_device, license_id, current,
                         actor=repo.ACTOR_SELF, caller=_user(store))
        assert err == ""
        err, done = attempt(repo.activate_device, "solo-1", other, "semper")
        assert err == ""
        assert done["devicesActive"] == 1
        current, other = other, current

    assert store._data["licenses"][license_id]["devicesActive"] == 1


def test_an_institution_pool_counts_across_members(store):
    """The pool is the licence's count; each seat counts its member's own."""
    license_id = _institution(store, max_devices=2)
    for uid in ("m1", "m2", "m3"):
        _phone(store, uid, f"{uid}-phone")

    assert attempt(repo.activate_device, "m1", "m1-phone", "semper")[0] == ""
    err, done = attempt(repo.activate_device, "m2", "m2-phone", "semper")
    assert err == ""
    assert done["pool"] is True

    err, _ = attempt(repo.activate_device, "m3", "m3-phone", "semper")
    assert err == "device_limit_reached"
    seats = store._data[f"licenses/{license_id}/seats"]
    assert store._data["licenses"][license_id]["devicesActive"] == 2
    assert seats["m1"]["devicesActive"] == 1
    assert seats["m2"]["devicesActive"] == 1
    assert seats["m3"].get("devicesActive", 0) == 0


# ---------------------------------------------------------------- refusals


def test_a_phone_not_registered_for_that_app_is_not_found(store):
    _individual(store)
    _phone(store, "someone-else", "theirs")
    _phone(store, "solo-1", "classroom-only", app="semperclassroom")

    assert attempt(repo.activate_device, "solo-1", "never-seen", "semper")[0] == "device_not_found"
    assert attempt(repo.activate_device, "solo-1", "theirs", "semper")[0] == "device_not_found"
    assert attempt(repo.activate_device, "solo-1", "classroom-only", "semper")[0] == "device_not_found"


def test_a_floating_licence_has_no_activations(store):
    """Its lease is its activation: check out a seat instead."""
    _floating(store)
    _phone(store, "f1", "phone-a")

    assert attempt(repo.activate_device, "f1", "phone-a", "semper")[0] == "seating_floating"


def test_a_revoked_licence_activates_nothing(store):
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    repo.revoke_license(license_id, "admin")

    assert attempt(repo.activate_device, "solo-1", "phone-a", "semper")[0] == "license_revoked"


def test_no_licence_activates_nothing(store):
    store._data["users"] = {}
    _signed_in(store, "nobody", "nobody@lab.org")
    _phone(store, "nobody", "phone-a")

    assert attempt(repo.activate_device, "nobody", "phone-a", "semper")[0] == "no_license"


# ------------------------------------------- a licence from before the split


def test_a_licence_without_an_app_binds_its_lock(store):
    """No `app`: Activate binds the empty lock, once, and that is one phone."""
    license_id = _individual(store, app=None)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")

    err, done = attempt(repo.activate_device, "solo-1", "phone-a", "semper")
    assert err == ""
    assert (done["already"], done["model"]) == (False, "lock")
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLock"] == "phone-a"

    err, again = attempt(repo.activate_device, "solo-1", "phone-a", "semper")
    assert (err, again["already"]) == ("", True)

    with pytest.raises(repo.Refusal) as refused:
        repo.activate_device("solo-1", "phone-b", "semper")
    assert refused.value.code == "device_limit_reached"
    assert refused.value.suffix == "1/1"
    assert lic["deviceIdLock"] == "phone-a"
    assert not _acts(store, license_id)


# ------------------------------------------------------ revalidate per request


def test_an_active_phone_stays_licensed(store):
    _individual(store)
    _phone(store, "solo-1", "phone-a")
    repo.activate_device("solo-1", "phone-a", "semper")

    seen = repo.revalidate_device_lock(_user(store), "phone-a", explicit=True)

    assert seen["mode"] == "licensed"


def test_a_phone_not_activated_is_demo_for_its_own_request_only(store):
    """A build that taps Activate never activates itself, and the mismatch is
    not stored: the account's other phones stay licensed."""
    license_id = _individual(store, max_devices=2)
    _phone(store, "solo-1", "phone-a")

    seen = repo.revalidate_device_lock(_user(store), "phone-a", explicit=True)

    assert seen["mode"] == "demo"
    assert store._data["users"]["solo-1"]["mode"] == "licensed"
    assert not _acts(store, license_id)
    assert store._data["licenses"][license_id].get("devicesActive", 0) == 0


def test_an_old_build_activates_its_first_phone_itself(store, monkeypatch):
    """`IMPLICIT_ACTIVATION=legacy`: a build that cannot tap Activate keeps its
    licence on the holder's first phone, and only that one."""
    monkeypatch.setattr(settings, "IMPLICIT_ACTIVATION", "legacy")
    license_id = _individual(store, max_devices=2)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")

    first = repo.revalidate_device_lock(_user(store), "phone-a")
    second = repo.revalidate_device_lock(_user(store), "phone-b")

    assert first["mode"] == "licensed"
    assert _acts(store, license_id)["phone-a"]["activatedBy"] == "legacy"
    assert second["mode"] == "demo"
    assert "phone-b" not in _acts(store, license_id)
    assert store._data["licenses"][license_id]["devicesActive"] == 1


def test_with_implicit_activation_off_nothing_activates_itself(store, monkeypatch):
    monkeypatch.setattr(settings, "IMPLICIT_ACTIVATION", "off")
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")

    seen = repo.revalidate_device_lock(_user(store), "phone-a")

    assert seen["mode"] == "demo"
    assert not _acts(store, license_id)


def test_a_phone_deactivated_lately_does_not_activate_itself_again(store, monkeypatch):
    """Otherwise deactivating an old build's phone would last only until its
    next request."""
    monkeypatch.setattr(settings, "IMPLICIT_ACTIVATION", "legacy")
    license_id = _individual(store)
    _phone(store, "solo-1", "phone-a")
    repo.revalidate_device_lock(_user(store), "phone-a")
    assert _acts(store, license_id)["phone-a"]["status"] == "active"
    repo.deactivate_device(license_id, "phone-a", actor=repo.ACTOR_SELF, caller=_user(store))

    seen = repo.revalidate_device_lock(_user(store), "phone-a")

    assert seen["mode"] == "demo"
    assert _acts(store, license_id)["phone-a"]["status"] == "deactivated"
    # Once the hold has run out, the phone may take its slot back.
    _acts(store, license_id)["phone-a"]["deactivatedAt"] -= timedelta(
        hours=settings.DEVICE_RELEASE_HOLD_HOURS, minutes=1)
    assert repo.revalidate_device_lock(_user(store), "phone-a")["mode"] == "licensed"


def test_the_shown_block_names_this_phones_state(store):
    """`/v1/config`'s `activation` block is filled from what the check read."""
    _individual(store)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")

    available: dict = {}
    repo.revalidate_device_lock(_user(store), "phone-a", explicit=True, shown=available)
    repo.activate_device("solo-1", "phone-a", "semper")
    active: dict = {}
    repo.revalidate_device_lock(_user(store), "phone-a", explicit=True, shown=active)
    full: dict = {}
    repo.revalidate_device_lock(_user(store), "phone-b", explicit=True, shown=full)

    assert (available["state"], available["devicesActive"], available["maxDevices"]) == (
        "available", 0, 1)
    assert (active["state"], active["devicesActive"], active["model"]) == ("active", 1, "activation")
    assert full["state"] == "full"


# ------------------------------------------------------- floating with an app


def test_a_floating_lease_licenses_only_the_phone_that_took_it(store):
    _floating(store)

    err, _ = attempt(repo.checkout_lease, _user(store, "f1"), "phone-a")
    assert err == ""
    user = _user(store, "f1")
    assert user["leaseDeviceId"] == "phone-a"

    assert repo.revalidate_device_lock(user, "phone-a", explicit=True)["mode"] == "licensed"
    assert repo.revalidate_device_lock(user, "phone-b", explicit=True)["mode"] == "demo"


# ------------------------------------------------------- clear and registration


def test_a_device_clear_deactivates_the_holders_phones(store):
    """A licence that runs activations has no lock to clear: the clear is a
    deactivation of every phone the holder has active."""
    license_id = _individual(store, max_devices=2)
    _phone(store, "solo-1", "phone-a")
    _phone(store, "solo-1", "phone-b")
    repo.activate_device("solo-1", "phone-a", "semper")
    repo.activate_device("solo-1", "phone-b", "semper")
    assert store._data["licenses"][license_id]["devicesActive"] == 2

    err, cleared = attempt(repo.clear_device_lock, license_id, actor=repo.ACTOR_STAFF)

    assert err == ""
    assert sorted(cleared["deactivated"]) == ["phone-a", "phone-b"]
    assert store._data["licenses"][license_id]["devicesActive"] == 0
    assert {a["status"] for a in _acts(store, license_id).values()} == {"deactivated"}


def test_registration_never_rotates_out_an_activated_phone(store, monkeypatch):
    """Past `MAX_PHONES_PER_APP` the oldest phones make room, but not one the
    licence licenses (`activated_devices`)."""
    monkeypatch.setattr(settings, "MAX_PHONES_PER_APP", 2)
    _individual(store)
    pem = _pem()
    repo.register_device("solo-1", DeviceReg(deviceId="phone-1-0001", publicKeyPem=pem), "semper")
    repo.activate_device("solo-1", "phone-1-0001", "semper")
    assert repo.activated_devices(_user(store), "semper") == {"phone-1-0001"}

    for n in (2, 3, 4):
        repo.register_device("solo-1", DeviceReg(deviceId=f"phone-{n}-0001", publicKeyPem=pem),
                             "semper")

    devices = store._data["devices"]
    assert devices["phone-1-0001"]["status"] == "ACTIVE"
    assert devices["phone-4-0001"]["status"] == "ACTIVE"
    assert devices["phone-2-0001"]["status"] != "ACTIVE"
    assert devices["phone-3-0001"]["status"] != "ACTIVE"
