"""The activation routes over HTTP (ADR-033), under DEV_INSECURE_AUTH.

The holder's Activate, list and Deactivate (`/v1/me/devices*`), the
`activation` block on `/v1/config`, each app's licence on `/v1/me`, staff's
`/v1/admin/licenses/{id}/devices*`, and the old device change
(`POST /v1/licenses/unbind`) on a licence that runs activations.

`current_user` returns the literal `deps._DEV_USER` in dev mode, so each test
points that dict at the account it seeds (`_dev_user_holds`)."""
import pytest

from app import deps
import repo_view as repo
from license_helpers import _mint_individual, _signed_in


PHONE = "and-phone-a1"
OTHER = "and-phone-b2"
#: The headers a build that taps Activate sends: no phone activates itself.
EXPLICIT = {"X-Device-Id": PHONE, "X-Activation": "explicit"}


def _phone(store, uid, device_id):
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": "semper"}


def _dev_user_holds(store, monkeypatch, max_devices=1):
    """The dev caller holds an individual Semper licence that runs
    activations, with PHONE registered. Staff too (`role: admin`), as the dev
    user always is, so the staff routes answer for it."""
    store._data["users"] = {}
    license_id = _mint_individual("dev@local")["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, "dev-user", "dev@local"), None)
    store._data["licenses"][license_id].update(app="semper", maxDevices=max_devices)
    _phone(store, "dev-user", PHONE)
    _as_dev_user(store, monkeypatch)
    return license_id


def _as_dev_user(store, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER",
                        {**store._data["users"]["dev-user"], "role": "admin"})


def _acts(store, license_id):
    return store._data.get(f"licenses/{license_id}/activations", {})


@pytest.mark.asyncio
async def test_activate_answers_with_the_config_the_phone_now_holds(store, client, monkeypatch):
    """So the app unlocks without a restart or a second call."""
    license_id = _dev_user_holds(store, monkeypatch)

    resp = await client.post("/v1/me/devices/activate", headers=EXPLICIT)

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["activation"]["deviceId"] == PHONE
    assert body["activation"]["devicesActive"] == 1
    assert body["config"]["activation"]["state"] == "active"
    assert _acts(store, license_id)[PHONE]["status"] == "active"


@pytest.mark.asyncio
async def test_activate_past_the_count_is_refused_with_it(store, client, monkeypatch):
    license_id = _dev_user_holds(store, monkeypatch)
    _phone(store, "dev-user", OTHER)
    assert (await client.post("/v1/me/devices/activate", headers=EXPLICIT)).status_code == 200

    resp = await client.post("/v1/me/devices/activate",
                             headers={**EXPLICIT, "X-Device-Id": OTHER})

    assert resp.status_code == 409, resp.text
    assert resp.json()["detail"] == "device_limit_reached: 1/1"
    assert OTHER not in _acts(store, license_id)


@pytest.mark.asyncio
async def test_my_devices_lists_the_phone_as_activated(store, client, monkeypatch):
    _dev_user_holds(store, monkeypatch)
    _phone(store, "dev-user", OTHER)
    assert (await client.post("/v1/me/devices/activate", headers=EXPLICIT)).status_code == 200

    resp = await client.get("/v1/me/devices", headers=EXPLICIT)

    assert resp.status_code == 200, resp.text
    body = resp.json()
    rows = {r["deviceId"]: r for r in body["devices"]}
    assert rows[PHONE]["activated"] is True
    assert rows[PHONE]["thisDevice"] is True
    assert rows[OTHER]["activated"] is False
    assert (body["devicesActive"], body["maxDevices"], body["model"]) == (1, 1, "activation")


@pytest.mark.asyncio
async def test_the_holder_deactivates_their_phone_once(store, client, monkeypatch):
    """A second Deactivate finds nothing active: 404, not a second decrement."""
    license_id = _dev_user_holds(store, monkeypatch)
    assert (await client.post("/v1/me/devices/activate", headers=EXPLICIT)).status_code == 200

    resp = await client.delete(f"/v1/me/devices/{PHONE}")

    assert resp.status_code == 200, resp.text
    assert resp.json()["devicesActive"] == 0
    assert _acts(store, license_id)[PHONE]["status"] == "deactivated"
    assert store._data["licenses"][license_id]["devicesActive"] == 0

    again = await client.delete(f"/v1/me/devices/{PHONE}")

    assert again.status_code == 404
    assert again.json()["detail"] == "activation_not_found"
    assert store._data["licenses"][license_id]["devicesActive"] == 0


@pytest.mark.asyncio
async def test_config_carries_an_activation_block(store, client, monkeypatch):
    _dev_user_holds(store, monkeypatch)

    before = await client.get("/v1/config", headers=EXPLICIT)
    assert (await client.post("/v1/me/devices/activate", headers=EXPLICIT)).status_code == 200
    after = await client.get("/v1/config", headers=EXPLICIT)
    browser = await client.get("/v1/config")

    assert before.status_code == 200, before.text
    assert before.json()["activation"]["state"] == "available"
    assert after.json()["activation"] == {"state": "active", "devicesActive": 1, "maxDevices": 1,
                                          "pool": False, "model": "activation"}
    # No phone named: the licence's count, read for the call.
    assert browser.json()["activation"]["devicesActive"] == 1


@pytest.mark.asyncio
async def test_me_names_each_apps_licence(store, client, monkeypatch):
    _dev_user_holds(store, monkeypatch)

    resp = await client.get("/v1/me")

    assert resp.status_code == 200, resp.text
    licences = resp.json()["licences"]
    assert set(licences) >= {"semper", "semperclassroom"}
    assert licences["semper"]["mode"] == "licensed"
    assert licences["semper"]["kind"] == resp.json()["license"]["kind"]


@pytest.mark.asyncio
async def test_staff_list_add_and_deactivate_a_licences_phones(store, client, monkeypatch):
    license_id = _dev_user_holds(store, monkeypatch)
    _phone(store, "dev-user", OTHER)
    assert (await client.post("/v1/me/devices/activate", headers=EXPLICIT)).status_code == 200

    # Within the count only, unless staff say otherwise.
    refused = await client.post(f"/v1/admin/licenses/{license_id}/devices/{OTHER}", json={})
    assert refused.status_code == 409
    assert refused.json()["detail"] == "device_limit_reached: 1/1"
    added = await client.post(f"/v1/admin/licenses/{license_id}/devices/{OTHER}",
                              json={"override": True})
    assert added.status_code == 200, added.text
    assert added.json()["devicesActive"] == 2

    listed = await client.get(f"/v1/admin/licenses/{license_id}/devices")
    assert listed.status_code == 200, listed.text
    assert sorted(r["deviceId"] for r in listed.json()["devices"]) == [PHONE, OTHER]
    assert listed.json()["devicesActive"] == 2

    gone = await client.delete(f"/v1/admin/licenses/{license_id}/devices/{OTHER}")
    assert gone.status_code == 200, gone.text
    assert gone.json()["devicesActive"] == 1
    assert _acts(store, license_id)[OTHER]["deactivatedBy"] == "staff"
    # Staff sign the phone out too: it may go to someone else.
    assert store._data["devices"][OTHER]["status"] != "ACTIVE"


@pytest.mark.asyncio
async def test_staff_routes_answer_404_for_what_is_not_there(store, client, monkeypatch):
    license_id = _dev_user_holds(store, monkeypatch)

    assert (await client.get("/v1/admin/licenses/no-such-licence/devices")).status_code == 404
    missing = await client.post(f"/v1/admin/licenses/{license_id}/devices/and-nowhere1", json={})
    assert (missing.status_code, missing.json()["detail"]) == (404, "device_not_found")
    gone = await client.delete(f"/v1/admin/licenses/{license_id}/devices/{PHONE}")
    assert (gone.status_code, gone.json()["detail"]) == (404, "activation_not_found")


@pytest.mark.asyncio
async def test_unbind_on_an_activation_licence_deactivates(store, client, monkeypatch):
    """The old device change still works for a build that sends it: it
    deactivates the holder's phones, and there is no next change to wait for."""
    license_id = _dev_user_holds(store, monkeypatch)
    assert (await client.post("/v1/me/devices/activate", headers=EXPLICIT)).status_code == 200

    resp = await client.post("/v1/licenses/unbind")

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["nextChangeAllowedAt"] == ""
    assert body["previousDeviceId"] == PHONE
    assert _acts(store, license_id)[PHONE]["status"] == "deactivated"
    assert store._data["licenses"][license_id]["devicesActive"] == 0
