"""Per-user product limits: resolve defaults/overrides, config API, admin patch."""
import pytest

import fake_firestore

from app import firestore_repo as repo
from app.config import settings


@pytest.fixture
def store(monkeypatch):
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    return fake_firestore.install(monkeypatch)


def _limit_env(monkeypatch):
    monkeypatch.setattr(settings, "MAX_SESSIONS_PER_USER", 4)
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 25)
    monkeypatch.setattr(settings, "LICENSED_MAX_SESSIONS_PER_USER", 999)
    monkeypatch.setattr(settings, "MAX_FILES_PER_SESSION", 600)
    monkeypatch.setattr(settings, "MAX_FRAMES_PER_ANALYSIS", 150)
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", False)


def test_resolve_uses_demo_defaults_when_no_mode(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1"})
    assert cfg == {
        "mode": "demo",
        "plan": "demo",
        "licenseKind": "",
        "licenseDuration": "perpetual",
        "licenseExpiresAt": None,
        "licenseGraceEndsAt": None,
        "inGrace": False,
        "licenseSeating": "assigned",
        "leaseExpiresAt": None,
        "leaseHeartbeatMinutes": 30,
        "cloudBackupEnabled": False,
        "shareEnabled": False,
        "maxSessions": 25,
        "maxFilesPerSession": 600,
        "maxFrames": 150,
        "datCodecEncodingEnabled": False,
        "licensePrefix": "",
    }


def test_resolve_demo_ignores_max_sessions_override(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "maxSessions": 12})
    assert cfg["plan"] == "demo"
    assert cfg["maxSessions"] == 25
    assert cfg["cloudBackupEnabled"] is False


def test_resolve_prefers_positive_user_overrides_on_professional(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "plan": "professional",
        "maxSessions": 12,
        "maxFilesPerSession": 800,
        "maxFrames": 100,
        "datCodecEncodingEnabled": True,
    })
    assert cfg == {
        "mode": "licensed",
        "plan": "professional",
        "licenseKind": "",
        "licenseDuration": "perpetual",
        "licenseExpiresAt": None,
        "licenseGraceEndsAt": None,
        "inGrace": False,
        "licenseSeating": "assigned",
        "leaseExpiresAt": None,
        "leaseHeartbeatMinutes": 30,
        "cloudBackupEnabled": True,
        "shareEnabled": True,
        "maxSessions": 12,
        "maxFilesPerSession": 800,
        "maxFrames": 100,
        "datCodecEncodingEnabled": True,
        "licensePrefix": "",
    }


def test_expired_professional_falls_back_to_demo(store, monkeypatch):
    _limit_env(monkeypatch)
    from datetime import datetime, timedelta, timezone
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "plan": "professional",
        "licenseExpiresAt": datetime.now(timezone.utc) - timedelta(days=1),
    })
    assert cfg["plan"] == "demo"
    assert cfg["cloudBackupEnabled"] is False
    assert cfg["maxSessions"] == 25


def test_dat_codec_override_can_disable_when_fleet_default_is_on(store, monkeypatch):
    # The override must be able to go EITHER direction, not just "on" — a
    # per-account kill switch during rollout needs this, not just canarying.
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", True)
    cfg = repo.resolve_user_config({"uid": "u1", "datCodecEncodingEnabled": False})
    assert cfg["datCodecEncodingEnabled"] is False


def test_dat_codec_invalid_override_inherits_fleet_default(store, monkeypatch):
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", True)
    # A non-bool value on the doc (bad manual edit, legacy data) must not
    # silently truthy/falsy-cast — it inherits the fleet default instead.
    cfg = repo.resolve_user_config({"uid": "u1", "datCodecEncodingEnabled": "yes"})
    assert cfg["datCodecEncodingEnabled"] is True


def test_resolve_ignores_invalid_overrides(store, monkeypatch):
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({
        "uid": "u1",
        "maxSessions": 0,
        "maxFilesPerSession": "nope",
        "maxFrames": -3,
    })
    assert cfg["maxSessions"] == 25
    assert cfg["maxFilesPerSession"] == 600
    assert cfg["maxFrames"] == 150


def test_set_user_config_writes_and_clears(store, monkeypatch):
    _limit_env(monkeypatch)
    store._data["users"] = {
        "u1": {"email": "a@b.com", "access_status": "APPROVED", "plan": "professional"},
    }

    resolved = repo.set_user_config("u1", {"maxSessions": 25})
    assert resolved["maxSessions"] == 25
    assert store._data["users"]["u1"]["maxSessions"] == 25

    cleared = repo.set_user_config("u1", {"maxSessions": None})
    assert cleared["maxSessions"] == 999
    assert "maxSessions" not in store._data["users"]["u1"]


def test_set_user_config_missing_user(store):
    assert repo.set_user_config("missing", {"maxSessions": 10}) is None


def test_set_user_config_casts_bool_field_as_bool_not_int(store, monkeypatch):
    # int(True) == 1 would silently turn this into an int on the stored doc —
    # a naive single-cast implementation would pass a shallower test but store
    # the wrong type, which _bool_override's isinstance(raw, bool) check would
    # then reject on the next read (falling back to the fleet default instead
    # of the override the caller just set).
    monkeypatch.setattr(settings, "DAT_CODEC_ENCODING_ENABLED", False)
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED"}}

    resolved = repo.set_user_config("u1", {"datCodecEncodingEnabled": True})
    assert resolved["datCodecEncodingEnabled"] is True
    assert store._data["users"]["u1"]["datCodecEncodingEnabled"] is True

    cleared = repo.set_user_config("u1", {"datCodecEncodingEnabled": None})
    assert cleared["datCodecEncodingEnabled"] is False
    assert "datCodecEncodingEnabled" not in store._data["users"]["u1"]


@pytest.mark.asyncio
async def test_config_endpoint_returns_dev_professional(client):
    resp = await client.get("/v1/config")
    assert resp.status_code == 200
    body = resp.json()
    assert body["plan"] == "professional"
    assert body["cloudBackupEnabled"] is True
    assert body["shareEnabled"] is True
    assert body["maxSessions"] == settings.LICENSED_MAX_SESSIONS_PER_USER
    assert body["maxFilesPerSession"] == settings.MAX_FILES_PER_SESSION
    assert body["maxFrames"] == settings.MAX_FRAMES_PER_ANALYSIS
    assert body["datCodecEncodingEnabled"] == settings.DAT_CODEC_ENCODING_ENABLED


async def test_config_endpoint_reports_individual_license_kind(client, monkeypatch):
    from app import deps
    monkeypatch.setattr(
        deps, "_DEV_USER", {**deps._DEV_USER, "licenseKind": "individual"},
    )
    resp = await client.get("/v1/config")
    assert resp.status_code == 200
    assert resp.json()["licenseKind"] == "individual"


@pytest.mark.asyncio
async def test_admin_patch_user_config(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {"u1": {"email": "a@b.com", "access_status": "APPROVED"}}

    resp = await client.patch(
        "/v1/admin/users/u1/config",
        json={"plan": "professional", "maxSessions": 9},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["uid"] == "u1"
    assert body["config"]["plan"] == "professional"
    assert body["config"]["maxSessions"] == 9
    assert store._data["users"]["u1"]["maxSessions"] == 9
    assert store._data["users"]["u1"]["plan"] == "professional"


@pytest.mark.asyncio
async def test_admin_patch_empty_rejected(client):
    resp = await client.patch("/v1/admin/users/u1/config", json={})
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_list_sessions_quota_uses_resolved_max(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    # DEV user uid is "dev-user"; plant an override on that doc and stub
    # current_user's resolve path by putting fields on _DEV_USER via deps.
    from app import deps
    monkeypatch.setattr(
        deps,
        "_DEV_USER",
        {**deps._DEV_USER, "maxSessions": 7},
    )
    monkeypatch.setattr(repo, "list_user_sessions", lambda uid, limit=50, page_token=None: ([], None))
    monkeypatch.setattr(repo, "count_user_sessions", lambda uid: 0)

    resp = await client.get("/v1/sessions")
    assert resp.status_code == 200
    assert resp.json()["quota"] == {"used": 0, "max": 7}


def test_resolve_reads_the_pre_rename_plan_field(store, monkeypatch):
    """A user document migration 002 has not reached yet still resolves.

    Written before the rename, it carries `plan` and no `mode`. Reading it as
    demo would silently strip a paying account's entitlements.
    """
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "plan": "professional"})
    assert cfg["mode"] == "licensed"
    assert cfg["cloudBackupEnabled"] is True


def test_resolve_prefers_mode_over_a_stale_plan_mirror(store, monkeypatch):
    """`mode` wins when the two disagree — it is the field migration 002 writes."""
    _limit_env(monkeypatch)
    cfg = repo.resolve_user_config({"uid": "u1", "mode": "demo", "plan": "professional"})
    assert cfg["mode"] == "demo"
    assert cfg["plan"] == "demo"
    assert cfg["cloudBackupEnabled"] is False


def test_config_response_always_carries_both_mode_and_plan(store, monkeypatch):
    """An installed app decodes `plan` and fails closed to Demo without it.

    Dropping the mirror from this response demotes the whole fleet, so both
    keys are asserted together and must always agree.
    """
    _limit_env(monkeypatch)
    for user, mode, plan in [
        ({"uid": "u1"}, "demo", "demo"),
        ({"uid": "u1", "mode": "licensed"}, "licensed", "professional"),
    ]:
        cfg = repo.resolve_user_config(user)
        assert (cfg["mode"], cfg["plan"]) == (mode, plan)
