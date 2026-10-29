"""GET /v1/config's release gate and kill switches (A6.1 / A6.4), the
`X-App-Version` log field, and the structured licence events the log-based
metrics count (A7.3): activation, checkout, devlock_drop."""
import json
import logging

import pytest

import fake_firestore
import repo_view as repo
from app import config, deps, observability as obs, tasks
from app.config import settings
from app.licenses import key_hash
from license_helpers import _mint_individual, _signed_in


def _events(caplog, event: str) -> list[dict]:
    out = []
    for rec in caplog.records:
        if rec.message.startswith("{"):
            payload = json.loads(rec.message)
            if payload.get("event") == event:
                out.append(payload)
    return out


# ---------------- release gate and kill switches ----------------
async def test_config_publishes_the_release_gate_and_kill_switches(store, client, monkeypatch):
    monkeypatch.setattr(settings, "MIN_SUPPORTED_VERSION_CODE", 30)
    monkeypatch.setattr(settings, "RECOMMENDED_VERSION_CODE", 35)
    monkeypatch.setattr(settings, "KILL_SWITCHES", ["share", "upload"])
    body = (await client.get("/v1/config")).json()
    assert body["minSupportedVersionCode"] == 30
    assert body["recommendedVersionCode"] == 35
    assert body["killSwitches"] == ["share", "upload"]


async def test_the_defaults_gate_nothing(store, client):
    body = (await client.get("/v1/config")).json()
    assert (body["minSupportedVersionCode"], body["recommendedVersionCode"]) == (0, 0)
    assert body["killSwitches"] == []
    assert "entitlement" not in body


async def test_each_app_gets_its_own_release_gate(store, client, monkeypatch):
    """Semper and Semper Classroom number their builds separately, so a
    minimum set for one must not retire the other's builds."""
    monkeypatch.setattr(settings, "MIN_SUPPORTED_VERSION_CODE", 1040)
    monkeypatch.setattr(settings, "RECOMMENDED_VERSION_CODE", 1045)
    monkeypatch.setattr(settings, "MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM", 1003)
    monkeypatch.setattr(settings, "RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM", 0)
    semper = (await client.get("/v1/config", headers={"X-App-Id": "com.sempermechanics.semper"})).json()
    assert (semper["minSupportedVersionCode"], semper["recommendedVersionCode"]) == (1040, 1045)
    unnamed = (await client.get("/v1/config")).json()
    assert unnamed["minSupportedVersionCode"] == 1040
    mt = (await client.get(
        "/v1/config", headers={"X-App-Id": "com.sempermechanics.semperclassroom"})).json()
    assert (mt["minSupportedVersionCode"], mt["recommendedVersionCode"]) == (1003, 0)
    # A Semper Classroom build is the same app, numbered by the same counter.
    old = (await client.get(
        "/v1/config", headers={"X-App-Id": "com.sempermechanics.materialtesting"})).json()
    assert (old["minSupportedVersionCode"], old["recommendedVersionCode"]) == (1003, 0)


async def test_an_old_build_is_still_served(store, client, monkeypatch):
    """Nothing is refused on the version: the app gates itself."""
    monkeypatch.setattr(settings, "MIN_SUPPORTED_VERSION_CODE", 40)
    r = await client.get("/v1/config", headers={"X-App-Version": "12"})
    assert r.status_code == 200
    assert r.json()["minSupportedVersionCode"] == 40


@pytest.mark.parametrize(("raw", "expected"), [
    ("", []),
    ("share", ["share"]),
    ("upload, backup", ["backup", "upload"]),
    ("RESTORE share;share  backup", ["backup", "share", "restore"]),
])
def test_kill_switches_parse_to_the_known_names_in_order(raw, expected):
    assert config.parse_kill_switches(raw) == expected


def test_an_unknown_kill_switch_is_dropped_with_a_warning(caplog):
    with caplog.at_level(logging.WARNING, logger="semper"):
        assert config.parse_kill_switches("share,analytics bogus") == ["share"]
    assert "ignoring unknown value(s) ['analytics', 'bogus']" in caplog.text


def test_the_settings_read_their_env_vars(monkeypatch):
    monkeypatch.setenv("MIN_SUPPORTED_VERSION_CODE", "31")
    monkeypatch.setenv("RECOMMENDED_VERSION_CODE", "36")
    monkeypatch.setenv("MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM", "21")
    monkeypatch.setenv("RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM", "22")
    # The old names lose to the new ones when both are set (ADR-030).
    monkeypatch.setenv("MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING", "11")
    monkeypatch.setenv("RECOMMENDED_VERSION_CODE_MATERIAL_TESTING", "12")
    monkeypatch.setenv("KILL_SWITCHES", "restore nope")
    monkeypatch.setenv("CHECK_REVOKED_ON_STEP_UP", "true")
    monkeypatch.setenv("ENTITLEMENT_OFFLINE_GRACE_DAYS", "7")
    monkeypatch.setenv("ENTITLEMENT_KID", "ent-9")
    import importlib

    fresh = importlib.reload(config).Settings()
    try:
        assert fresh.MIN_SUPPORTED_VERSION_CODE == 31
        assert fresh.RECOMMENDED_VERSION_CODE == 36
        assert fresh.release_gate("semper") == (31, 36)
        assert fresh.release_gate("semperclassroom") == (21, 22)
        assert fresh.release_gate("materialtesting") == (21, 22)
        assert fresh.KILL_SWITCHES == ["restore"]
        assert fresh.CHECK_REVOKED_ON_STEP_UP is True
        assert fresh.ENTITLEMENT_OFFLINE_GRACE_DAYS == 7
        assert fresh.ENTITLEMENT_KID == "ent-9"
        assert fresh.ENTITLEMENT_SIGNING_KEY == ""
    finally:
        for name in ("MIN_SUPPORTED_VERSION_CODE", "RECOMMENDED_VERSION_CODE",
                     "MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM",
                     "RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM",
                     "MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING",
                     "RECOMMENDED_VERSION_CODE_MATERIAL_TESTING", "KILL_SWITCHES",
                     "CHECK_REVOKED_ON_STEP_UP", "ENTITLEMENT_OFFLINE_GRACE_DAYS", "ENTITLEMENT_KID"):
            monkeypatch.delenv(name)
        importlib.reload(config)


@pytest.mark.parametrize(("env", "gate"), [
    # Deployed before the rename: only the old pair is set.
    ({"MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING": "41",
      "RECOMMENDED_VERSION_CODE_MATERIAL_TESTING": "42"}, (41, 42)),
    # The new name set but empty, as an unset repo variable renders: the old one.
    ({"MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM": "",
      "MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING": "43"}, (43, 0)),
    ({}, (0, 0)),
])
def test_the_classroom_gate_falls_back_to_the_material_testing_names(monkeypatch, env, gate):
    for name in ("MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM",
                 "RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM",
                 "MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING",
                 "RECOMMENDED_VERSION_CODE_MATERIAL_TESTING"):
        monkeypatch.delenv(name, raising=False)
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    import importlib

    try:
        assert importlib.reload(config).Settings().release_gate("semperclassroom") == gate
    finally:
        monkeypatch.undo()
        importlib.reload(config)


# ---------------- X-App-Version ----------------
@pytest.mark.parametrize(("raw", "expected"), [
    ("35", 35), (" 35 ", 35), ("0", 0), ("2147483647", 2147483647),
    (None, None), ("", None), ("-1", None), ("1.2", None), ("abc", None),
    ("2147483648", None), ("99999999999", None), ("٣٥", None),
])
def test_the_app_version_header_parses_to_a_version_code(raw, expected):
    assert obs.parse_app_version(raw) == expected


async def test_the_access_log_carries_the_app_version(client, caplog):
    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/healthz", headers={"X-App-Version": "35"})
        await client.get("/healthz", headers={"X-App-Version": "not-a-number"})
    first, second = _events(caplog, "http_access")[-2:]
    assert first["appVersion"] == 35
    assert "appVersion" not in second


def test_events_outside_a_request_carry_no_client_fields():
    payload = obs.event_payload("startup")
    assert "appId" not in payload and "appVersion" not in payload


# ---------------- licence events ----------------
@pytest.fixture
def licensing(store, audited, monkeypatch):
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED", "emailVerified": True}}
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    return store


async def test_a_redeemed_key_logs_an_activation(licensing, client, caplog):
    minted = repo.create_individual_license(email_lock="dev@local", created_by_uid="admin")
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post("/v1/licenses/activate", json={"key": minted["key"]}, headers={
            "X-Device-Id": "dev-device", "X-App-Id": "com.sempermechanics.semper",
            "X-App-Version": "35"})
    assert r.status_code == 200, r.text
    [event] = _events(caplog, "activation")
    assert event["outcome"] == "ok"
    assert event["licenseId"] == minted["license"]["id"] == key_hash(minted["key"])
    assert event["appId"] == "com.sempermechanics.semper"
    assert event["appVersion"] == 35
    # Passed by the handler, not read from the request context (TD-204).
    assert event["uid"] == "dev-user"
    assert "dev@local" not in caplog.text


async def test_a_refused_key_logs_its_refusal(licensing, client, caplog):
    minted = repo.create_individual_license(email_lock="else@lab.org", created_by_uid="admin")
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post("/v1/licenses/activate", json={"key": minted["key"]},
                              headers={"X-Device-Id": "dev-device"})
    assert r.status_code == 403
    [event] = _events(caplog, "activation")
    assert (event["outcome"], event["errorCode"]) == ("refused", "license_email_mismatch")
    assert event["licenseId"] == minted["license"]["id"]
    assert event["appId"] == "none"
    assert event["uid"] == "dev-user"
    assert "else@lab.org" not in caplog.text


async def test_a_key_nobody_minted_logs_no_licence(licensing, client, caplog):
    minted = repo.create_individual_license(email_lock="dev@local", created_by_uid="admin")
    licensing._data["licenses"].clear()
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post("/v1/licenses/activate", json={"key": minted["key"]},
                              headers={"X-Device-Id": "dev-device"})
    assert r.status_code == 404
    [event] = _events(caplog, "activation")
    assert event["errorCode"] == "license_not_found"
    assert "licenseId" not in event


async def test_a_checkout_logs_its_refusal(licensing, client, caplog):
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post("/v1/licenses/checkout", headers={"X-Device-Id": "dev-device"})
    assert r.status_code == 404
    [event] = _events(caplog, "checkout")
    assert (event["outcome"], event["errorCode"]) == ("refused", "no_license")
    assert event["uid"] == "dev-user"


async def test_a_checkout_logs_the_licence_it_leased(client, monkeypatch, caplog):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "dev@university.edu", "access_status": "APPROVED", "emailVerified": True, "mode": "demo"},
    }
    minted = repo.create_institution_license(
        domain_lock="university.edu", admin_emails=["it@university.edu"],
        created_by_uid="admin", max_seats=2, seating="floating",
    )
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "dev@university.edu")
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER, **store._data["users"]["dev-user"], "uid": "dev-user",
    })
    with caplog.at_level(logging.INFO, logger="semper.license"):
        r = await client.post("/v1/licenses/checkout", headers={
            "X-Device-Id": "dev-device", "X-App-Id": "com.sempermechanics.semperclassroom"})
    assert r.status_code == 200, r.text
    [event] = _events(caplog, "checkout")
    assert (event["outcome"], event["licenseId"]) == ("ok", license_id)
    assert event["uid"] == "dev-user"
    assert event["appId"] == "com.sempermechanics.semperclassroom"


def _bound(store) -> str:
    store._data["users"] = {}
    license_id = _mint_individual()["license"]["id"]
    user = repo.ensure_entitlement(_signed_in(store, "solo-1", "solo@lab.org"), None)
    repo.revalidate_device_lock(user, "phone-1")
    return license_id


def test_a_device_mismatch_logs_a_devlock_drop(store, caplog):
    license_id = _bound(store)
    with caplog.at_level(logging.INFO, logger="semper.firestore"):
        user = repo.revalidate_device_lock(store._data["users"]["solo-1"], "phone-2")
    assert user["mode"] == "demo"
    [event] = _events(caplog, "devlock_drop")
    assert (event["licenseId"], event["stage"], event["outcome"]) == (license_id, "mismatch", "demo")
    # Logged before `current_user` binds the uid, so it is passed in (TD-204).
    assert event["uid"] == "solo-1"
    assert "solo@lab.org" not in caplog.text


def test_a_revoked_licence_logs_a_devlock_drop(store, caplog):
    license_id = _bound(store)
    store._data["licenses"][license_id]["status"] = "revoked"
    with caplog.at_level(logging.INFO, logger="semper.firestore"):
        user = repo.revalidate_device_lock(store._data["users"]["solo-1"], "phone-1")
    assert user["mode"] == "demo"
    assert store._data["users"]["solo-1"]["mode"] == "demo"
    [event] = _events(caplog, "devlock_drop")
    assert (event["licenseId"], event["stage"]) == (license_id, "revoked")
    assert event["uid"] == "solo-1"


def test_the_matching_device_logs_nothing(store, caplog):
    _bound(store)
    with caplog.at_level(logging.INFO, logger="semper.firestore"):
        repo.revalidate_device_lock(store._data["users"]["solo-1"], "phone-1")
    assert _events(caplog, "devlock_drop") == []


# ---------------- no clear-text email ----------------
def test_short_hash_is_stable_and_hides_the_address():
    tag = obs.short_hash("Someone@Example.com ")
    assert tag == obs.short_hash("someone@example.com")
    assert len(tag) == 12 and "@" not in tag
    assert obs.short_hash("") == "" and obs.short_hash(None) == ""


def test_a_task_token_from_the_wrong_principal_logs_no_address(monkeypatch, caplog):
    from fastapi import HTTPException
    from google.oauth2 import id_token as ga_id_token

    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "TASKS_INVOKER_SA", "tasks@proj.iam.gserviceaccount.com")
    monkeypatch.setattr(tasks, "_oidc_transport", lambda: None)
    monkeypatch.setattr(ga_id_token, "verify_oauth2_token",
                        lambda *a, **k: {"email": "intruder@example.com", "email_verified": True})
    with pytest.raises(HTTPException):
        tasks.tasks_caller("Bearer x")
    assert "intruder@example.com" not in caplog.text
    assert obs.short_hash("intruder@example.com") in caplog.text


def test_the_offline_grace_and_the_licence_grace_default_to_14_days(monkeypatch):
    """Two different 14s (readiness TE4-103). ENTITLEMENT_OFFLINE_GRACE_DAYS is
    a signed token's offline lifetime (`entitlement_token.py`, ADR-023
    decision 2); LICENSE_GRACE_DAYS_DEFAULT is how long a licence keeps working
    past its expiry. The app's EntitlementClockTest.PRODUCTION_GRACE_DAYS
    mirrors the first: change the two together."""
    import importlib

    monkeypatch.delenv("ENTITLEMENT_OFFLINE_GRACE_DAYS", raising=False)
    monkeypatch.delenv("LICENSE_GRACE_DAYS_DEFAULT", raising=False)
    try:
        fresh = importlib.reload(config).Settings()
        assert fresh.ENTITLEMENT_OFFLINE_GRACE_DAYS == 14
        assert fresh.LICENSE_GRACE_DAYS_DEFAULT == 14
    finally:
        importlib.reload(config)
