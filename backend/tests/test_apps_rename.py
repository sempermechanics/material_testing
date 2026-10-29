"""Material Testing became Semper Classroom (ADR-030), and the stored data did not move.

Devices, sessions and audit rows still say `materialtesting`, and every
account not yet migrated holds its Classroom phone and locks in the
`*MaterialTesting` fields. A backend from before the rename can serve beside
this one during a deploy, or again after a rollback, so the old names stay the
stored truth while TD-330 is open. These pin that the backend reads both short
names as the one app, still stores the old one (`apps.stored_name`), reads only
the old field (`apps.read_field`), writes both fields, and still serves the
builds that send the old application id, bar registration (410 `app_retired`).
"""
import pytest
from fastapi import HTTPException
from starlette.requests import Request

import fake_firestore
import repo_view as repo
from app import apps, audit, deps, drive, statuses
from key_helpers import _ec_pem
from app.models import DeviceReg
from license_helpers import _mint_individual, _signed_in
from tests import test_device_auth as device_auth

NEW_ID = "com.sempermechanics.semperclassroom"
OLD_ID = "com.sempermechanics.materialtesting"
OLDEST_ID = "com.indicvision.semper.materialtesting"
SC = apps.SEMPER_CLASSROOM
OLD = apps.LEGACY_MATERIAL_TESTING

keypair = device_auth.keypair


# ------------------------------------------------------------------ apps.py


def test_canonical_reads_the_old_short_name_as_the_new_one():
    assert apps.canonical(OLD) == SC
    assert apps.canonical(" MaterialTesting ") == SC
    assert apps.canonical(SC) == SC
    assert apps.canonical(apps.SEMPER) == apps.SEMPER
    assert apps.canonical("") == apps.SEMPER
    assert apps.canonical(None) == apps.SEMPER
    # Unknown stays unknown, so it is never read as an app.
    assert apps.canonical("other") == "other"
    assert apps.canonical("other") not in apps.ALL


def test_same_app_compares_through_canonical():
    assert apps.same_app(OLD, SC)
    assert apps.same_app(None, apps.SEMPER)
    assert not apps.same_app(OLD, apps.SEMPER)
    assert not apps.same_app("other", SC)


def test_from_name_takes_both_names_and_refuses_the_rest():
    assert apps.from_name(SC) == SC
    assert apps.from_name(OLD) == SC
    assert apps.from_name("semper") == apps.SEMPER
    assert apps.from_name("") == apps.SEMPER
    assert apps.from_name("other") is None


def test_the_headers_and_their_token_claims():
    for app_id in (NEW_ID, OLD_ID, OLDEST_ID):
        assert apps.from_header(app_id) == SC
    assert apps.token_claim(NEW_ID) == SC
    # A build from before the rename pins the old name (`EntitlementPins.kt`).
    assert apps.token_claim(OLD_ID) == OLD
    assert apps.token_claim(f" {OLDEST_ID} ") == OLD
    assert apps.token_claim("com.sempermechanics.semper") == apps.SEMPER
    assert apps.token_claim("") == apps.SEMPER
    assert apps.token_claim("com.example.other") is None
    assert apps.is_retired(OLD_ID) and not apps.is_retired(NEW_ID)


def test_field_names_for_each_app():
    assert apps.field("deviceIdLock", SC) == "deviceIdLockSemperClassroom"
    assert apps.field("deviceIdLock", OLD) == "deviceIdLockSemperClassroom"
    assert apps.legacy_field("deviceIdLock", SC) == "deviceIdLockMaterialTesting"
    assert apps.legacy_field("deviceIdLock", apps.SEMPER) is None


def test_stored_name_keeps_the_old_short_name_for_semper_classroom():
    # A backend from before the rename reads `semperclassroom` as an unknown
    # app, so the tag every backend reads is the one stored (TD-330).
    assert apps.stored_name(SC) == OLD
    assert apps.stored_name(OLD) == OLD
    assert apps.stored_name(" SemperClassroom ") == OLD
    assert apps.stored_name(apps.SEMPER) == apps.SEMPER
    assert apps.stored_name("") == apps.SEMPER
    # Unknown stays unknown, never stored as an app it is not.
    assert apps.stored_name("other") == "other"
    # What is stored reads back as the app of today.
    for app in apps.ALL:
        assert apps.canonical(apps.stored_name(app)) == app
        assert apps.same_app(apps.stored_name(app), app)


def test_read_field_reads_the_old_field_only():
    assert apps.read_field({"activeDeviceIdMaterialTesting": "old"}, "activeDeviceId", SC) == "old"
    # The new field alone is not read: a backend from before the rename that
    # cleared the old field left the new one behind, stale (H1).
    assert apps.read_field({"activeDeviceIdSemperClassroom": "new"}, "activeDeviceId", SC) is None
    assert apps.read_field({"activeDeviceIdSemperClassroom": "new"}, "activeDeviceId", SC,
                           "none") == "none"
    # Every backend writes the old field, one from before the rename too, so
    # a new field it did not write is stale: the old one wins (TD-330).
    both = {"activeDeviceIdMaterialTesting": "old", "activeDeviceIdSemperClassroom": "stale"}
    assert apps.read_field(both, "activeDeviceId", SC) == "old"
    assert apps.read_field(both, "activeDeviceId", OLD) == "old"
    # An old field written empty is the answer, not a reason to read the new.
    cleared = {"deviceIdLockMaterialTesting": "", "deviceIdLockSemperClassroom": "stale"}
    assert apps.read_field(cleared, "deviceIdLock", SC) == ""
    assert apps.read_field({}, "activeDeviceId", SC, "none") == "none"
    assert apps.read_field(None, "activeDeviceId", SC) is None
    assert apps.read_field({"activeDeviceIdMaterialTesting": "x"}, "activeDeviceId", apps.SEMPER) is None


def test_written_fields_mirror_the_old_name():
    assert apps.written_fields("deviceIdLock", SC, "d1") == {
        "deviceIdLockSemperClassroom": "d1", "deviceIdLockMaterialTesting": "d1"}
    assert apps.written_fields("deviceIdLock", apps.SEMPER, "d1") == {"deviceIdLock": "d1"}
    sentinel = fake_firestore.DELETE_FIELD
    assert set(apps.written_fields("activeDeviceId", SC, sentinel).values()) == {sentinel}


def test_spread_names_every_app_with_the_old_mirror():
    assert apps.spread("releasedDeviceId", {OLD: "x", apps.SEMPER: "y"}) == {
        "releasedDeviceId": "y",
        "releasedDeviceIdSemperClassroom": "x",
        "releasedDeviceIdMaterialTesting": "x",
    }
    assert apps.spread("previousDeviceId", {}) == {
        "previousDeviceId": "", "previousDeviceIdSemperClassroom": "",
        "previousDeviceIdMaterialTesting": ""}


# ------------------------------------------------- an account not yet migrated


def _legacy_licensed(store, uid="solo-1", email="solo@lab.org"):
    """An individual licence bound before the rename: the Classroom phone and
    lock only in the `*MaterialTesting` fields."""
    store._data["users"] = {}
    license_id = _mint_individual(email)["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, uid, email), None)
    repo.revalidate_device_lock(user, "and-semper")
    store._data["licenses"][license_id]["deviceIdLockMaterialTesting"] = "and-classroom"
    store._data["users"][uid]["activeDeviceIdMaterialTesting"] = "and-classroom"
    store._data.setdefault("devices", {})["and-classroom"] = {
        "uid": uid, "status": statuses.DEVICE_ACTIVE, "app": OLD}
    return license_id


def test_a_legacy_lock_still_licenses_its_phone_and_only_it(store):
    _legacy_licensed(store)
    user = store._data["users"]["solo-1"]
    assert repo.revalidate_device_lock(user, "and-classroom", SC)["mode"] == "licensed"
    assert repo.revalidate_device_lock(user, "and-classroom", OLD)["mode"] == "licensed"
    assert repo.revalidate_device_lock(user, "and-other-phone", SC)["mode"] == "demo"


def test_an_empty_lock_binds_under_both_names(store):
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    user["activeDeviceIdMaterialTesting"] = "and-classroom"

    assert repo.revalidate_device_lock(user, "and-classroom", SC)["mode"] == "licensed"

    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLockSemperClassroom"] == "and-classroom"
    assert lic["deviceIdLockMaterialTesting"] == "and-classroom"


def test_a_self_clear_of_a_legacy_lock_clears_both_names(store):
    license_id = _legacy_licensed(store)

    cleared = repo.clear_device_lock(license_id, "solo-1", actor=repo.ACTOR_SELF, app=SC)

    assert cleared["previousDeviceIdSemperClassroom"] == "and-classroom"
    assert cleared["previousDeviceIdMaterialTesting"] == "and-classroom"
    assert cleared["releasedDeviceIdSemperClassroom"] == "and-classroom"
    lic = store._data["licenses"][license_id]
    assert lic["deviceIdLockSemperClassroom"] == "" and lic["deviceIdLockMaterialTesting"] == ""
    assert "deviceChangedAtSemperClassroom" in lic and "deviceChangedAtMaterialTesting" in lic
    assert lic["deviceIdLock"] == "and-semper"
    user = store._data["users"]["solo-1"]
    assert not {"activeDeviceIdSemperClassroom", "activeDeviceIdMaterialTesting"} & set(user)
    assert user["releasedDeviceIdSemperClassroom"] == "and-classroom"
    assert user["releasedDeviceIdMaterialTesting"] == "and-classroom"
    # The cooldown is read back through the new field and the old alike.
    again = pytest.raises(repo.Refusal, repo.clear_device_lock, license_id, "solo-1",
                          actor=repo.ACTOR_SELF, app=OLD)
    assert again.value.code == "device_change_too_soon"


def _as(store, monkeypatch, uid):
    monkeypatch.setattr(deps, "_DEV_USER", dict(store._data["users"][uid]))


@pytest.mark.parametrize(("app_id", "status"), [(NEW_ID, 201), (OLDEST_ID, 410), ("", 201)])
async def test_registration_beside_a_legacy_binding(client, monkeypatch, audited, app_id, status):
    """A Classroom phone held in the legacy field does not keep another
    phone out (several per app, ADR-033); the retired id still may not
    register (410)."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    _legacy_licensed(store, "dev-user", "dev@local")
    _as(store, monkeypatch, "dev-user")
    headers = {"X-App-Id": app_id} if app_id else {}

    r = await client.post("/v1/devices/register", headers=headers,
                          json={"deviceId": "and-newphone", "publicKeyPem": _ec_pem()})

    assert r.status_code == status, r.text


async def test_the_new_app_heals_its_legacy_phone_and_writes_both_fields(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    _legacy_licensed(store, "dev-user", "dev@local")
    _as(store, monkeypatch, "dev-user")

    r = await client.post("/v1/devices/register", headers={"X-App-Id": NEW_ID},
                          json={"deviceId": "and-classroom", "publicKeyPem": _ec_pem()})

    assert r.status_code == 201, r.text
    assert r.json()["healed"] is True
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceIdSemperClassroom"] == "and-classroom"
    assert user["activeDeviceIdMaterialTesting"] == "and-classroom"
    assert "activeDeviceId" not in user, "Semper's binding is its own"
    # The record keeps the name every backend reads (TD-330).
    assert store._data["devices"]["and-classroom"]["app"] == OLD


async def test_a_stale_new_field_does_not_hold_the_account(client, monkeypatch, audited):
    """H1: a backend from before the rename cleared the old field and left the
    new one, so the account holds only `activeDeviceIdSemperClassroom: B`. The
    old field is the binding, and it is empty: phone C registers."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {}
    _signed_in(store, "dev-user", "dev@local")
    store._data["users"]["dev-user"]["activeDeviceIdSemperClassroom"] = "and-phone-b"
    store._data.setdefault("devices", {})["and-phone-b"] = {
        "uid": "dev-user", "status": statuses.DEVICE_ACTIVE, "app": OLD}
    _as(store, monkeypatch, "dev-user")

    r = await client.post("/v1/devices/register", headers={"X-App-Id": NEW_ID},
                          json={"deviceId": "and-phone-c", "publicKeyPem": _ec_pem()})

    assert r.status_code == 201, r.text
    user = store._data["users"]["dev-user"]
    assert user["activeDeviceIdMaterialTesting"] == "and-phone-c"
    assert user["activeDeviceIdSemperClassroom"] == "and-phone-c", "the stale field is overwritten"
    assert store._data["devices"]["and-phone-c"]["app"] == OLD


async def test_the_retired_id_may_not_register_but_its_other_calls_work(client, monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    _legacy_licensed(store, "dev-user", "dev@local")
    _as(store, monkeypatch, "dev-user")
    store._data["sessions"] = {"s-old": {"uid": "dev-user", "app": OLD,
                                         "status": statuses.SESSION_COMPLETED}}
    old = {"X-App-Id": OLD_ID}

    r = await client.post("/v1/devices/register", headers=old,
                          json={"deviceId": "and-classroom", "publicKeyPem": _ec_pem()})

    assert r.status_code == 410, r.text
    assert r.json()["detail"] == "app_retired"
    assert "publicKeyPem" not in store._data["devices"]["and-classroom"], "nothing written"
    denied, = [row for row in audited if row["action"] == "DEVICE_REGISTER"]
    assert denied["detail"] == {"reason": "app_retired", "app": SC}
    assert (await client.get("/v1/config", headers=old)).status_code == 200
    listed = await client.get("/v1/sessions", headers=old)
    assert listed.status_code == 200
    assert [s["sessionId"] for s in listed.json()["sessions"]] == ["s-old"]


# ------------------------------------------------- a device tagged the old way


def _request(app_id: str) -> Request:
    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    request = Request({"type": "http", "method": "GET", "path": "/v1/me/export",
                       "headers": [], "query_string": b""}, receive)
    request.state.app = apps.from_header(app_id)
    return request


@pytest.fixture
def legacy_device(monkeypatch, keypair):
    """`verified_device` with the bypass off, and an account whose Classroom
    phone is bound and tagged the way Material Testing left it."""
    priv, pem = keypair
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps.repo, "get_device", lambda did: {
        "deviceId": did, "uid": "u1", "status": "ACTIVE", "publicKeyPem": pem, "app": OLD})
    monkeypatch.setattr(deps.repo, "consume_nonce", lambda *a: True)
    monkeypatch.setattr(deps.repo, "test_account_of", lambda user: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return priv


async def _verify(priv, app_id: str):
    return await deps.verified_device(
        request=_request(app_id),
        user={"uid": "u1", "access_status": "APPROVED",
              "activeDeviceIdMaterialTesting": "and-classroom"},
        x_device_id="and-classroom", x_nonce="n1",
        x_signature=device_auth._sign(priv, "n1", "GET", "/v1/me/export", b""),
    )


@pytest.mark.parametrize("app_id", [NEW_ID, OLD_ID, OLDEST_ID])
async def test_a_materialtesting_device_signs_for_semper_classroom(legacy_device, app_id):
    ctx = await _verify(legacy_device, app_id)
    assert ctx.device["deviceId"] == "and-classroom"


@pytest.mark.parametrize("app_id", [NEW_ID, OLD_ID, OLDEST_ID])
async def test_a_device_this_backend_registers_signs_for_either_id(store, monkeypatch, keypair,
                                                                    app_id):
    """Registered by this backend for Semper Classroom, the device is stored as
    `materialtesting` and its binding in both fields; it signs for the new id
    and for the ids from before the rename."""
    priv, pem = keypair
    store._data["users"] = {"u1": {"uid": "u1", "access_status": "APPROVED"}}
    repo.register_device("u1", DeviceReg(deviceId="and-classroom", publicKeyPem=pem), SC)
    assert store._data["devices"]["and-classroom"]["app"] == OLD
    monkeypatch.setattr(deps.settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(deps.repo, "consume_nonce", lambda *a: True)
    monkeypatch.setattr(deps.repo, "test_account_of", lambda user: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)

    ctx = await deps.verified_device(
        request=_request(app_id), user=dict(store._data["users"]["u1"]),
        x_device_id="and-classroom", x_nonce="n1",
        x_signature=device_auth._sign(priv, "n1", "GET", "/v1/me/export", b""),
    )

    assert ctx.device["deviceId"] == "and-classroom"


async def test_a_materialtesting_device_does_not_sign_for_semper(legacy_device):
    with pytest.raises(HTTPException) as e:
        await _verify(legacy_device, "com.sempermechanics.semper")
    # The record names another app, so its key does not sign for Semper.
    assert e.value.status_code == 401
    assert e.value.detail == "bad_signature"


# ------------------------------------------------- sessions tagged the old way


@pytest.fixture
def old_sessions(store, monkeypatch):
    store._data["users"] = {"dev-user": {"email": "dev@test", "access_status": "APPROVED"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    base = {"uid": "dev-user", "status": statuses.SESSION_COMPLETED, "localSessionId": "",
            "fileCount": 1, "completedCount": 1, "driveFolderId": None}
    store._data["sessions"] = {
        "a-semper": {**base, "app": apps.SEMPER},
        "b-old": {**base, "app": OLD},
        "c-new": {**base, "app": SC},
    }
    return store


@pytest.mark.parametrize("headers", [{"X-App-Id": NEW_ID}, {"X-App-Id": OLD_ID}])
async def test_sessions_tagged_materialtesting_list_for_semper_classroom(old_sessions, client, headers):
    r = await client.get("/v1/sessions", headers=headers)
    assert r.status_code == 200
    assert {s["sessionId"]: s["app"] for s in r.json()["sessions"]} == {"b-old": SC, "c-new": SC}


async def test_sessions_tagged_materialtesting_restore_for_semper_classroom(old_sessions, client):
    for headers in ({"X-App-Id": NEW_ID}, {"X-App-Id": OLD_ID}):
        assert (await client.get("/v1/sessions/b-old/files", headers=headers)).status_code == 200
    assert (await client.get("/v1/sessions/b-old/files")).status_code == 404, "not Semper's"


def test_an_old_tag_reads_as_the_new_app(old_sessions):
    assert repo.session_app({"app": OLD}) == SC
    page, _ = repo.list_user_sessions("dev-user", app=OLD)
    assert [s["sessionId"] for s in page] == ["b-old", "c-new"]


# ------------------------------------------------- unknown stored values


def test_a_test_account_phone_with_an_unknown_app_releases_without_a_key_error(store):
    store._data["users"] = {"t-1": {"email": "t@x", "access_status": "APPROVED",
                                    "activeDeviceId": "and-phone-1"}}
    store._data["devices"] = {"and-odd": {"uid": "t-1", "status": "ACTIVE", "app": "other"}}

    repo.release_test_device("t-1", "and-odd")

    assert store._data["devices"]["and-odd"]["status"] == statuses.DEVICE_REVOKED
    assert store._data["users"]["t-1"]["activeDeviceId"] == "and-phone-1"


def test_the_listing_names_an_old_tagged_test_phone_by_the_new_app(store):
    store._data["devices"] = {
        "and-old": {"uid": "t-1", "status": "ACTIVE", "app": OLD},
        "and-odd": {"uid": "t-1", "status": "ACTIVE", "app": "other"},
    }
    found = {d["deviceId"]: d["app"] for d in repo.active_test_devices("t-1")}
    assert found == {"and-old": SC, "and-odd": "other"}
