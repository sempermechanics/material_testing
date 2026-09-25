"""create_session reserves the session doc first and rolls back on a staging error.

The session doc is written BEFORE any Drive folder or file doc, so a failure part
way through staging can never leave file docs (or a Drive subtree) with no parent
session — which would be invisible to the quota and never reclaimed. On such a
failure the reserved session (and any file docs already written) are rolled back
so nothing lingers against the user's quota.
"""
import pytest

from app import audit, drive
# Module level on purpose: test_config reloads app.config, and an import taken
# after that is a different `settings` object from the one the repo reads.
from app.config import settings

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_SHA = "a" * 64


def _file(name: str) -> dict:
    return {"name": name, "role": "bundle", "bytes": 10, "sha256": _SHA}


@pytest.fixture
def store(store, monkeypatch):
    store._data["users"] = {DEV_UID: {"email": "dev@test", "access_status": "APPROVED"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"},
    )
    return store


async def test_staging_failure_leaves_no_orphans(store, monkeypatch, client):
    """init_resumable raises on the 2nd of 3 files → no session/file docs remain."""
    calls = {"n": 0}

    def flaky_init(*a, **k):
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("drive blew up mid-stage")
        return "https://drive/resumable"

    monkeypatch.setattr(drive, "init_resumable", flaky_init)

    # The staging error surfaces (the ASGI test transport re-raises it); what
    # matters is that the except-block rollback ran before it propagated.
    with pytest.raises(RuntimeError):
        await client.post(
            "/v1/sessions",
            json={"specimen": "s", "files": [_file("a"), _file("b"), _file("c")]},
        )
    assert store._data.get("sessions", {}) == {}, "reserved session left orphaned"
    assert store._data.get("files", {}) == {}, "file docs left with no parent session"


async def test_success_reserves_session_before_files(store, monkeypatch, client):
    """The happy path still creates exactly one session with its files."""
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")

    r = await client.post(
        "/v1/sessions",
        json={"specimen": "s", "files": [_file("a"), _file("b")]},
    )
    assert r.status_code == 200
    assert len(store._data["sessions"]) == 1
    sid = next(iter(store._data["sessions"]))
    # The reserved doc was created, then its Drive folder recorded.
    assert store._data["sessions"][sid]["driveFolderId"] == "sf"
    assert len(store._data["files"]) == 2


async def test_duplicate_local_session_id_is_idempotent(store, monkeypatch, client):
    """Two creates with the same localSessionId return one session."""
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    body = {
        "specimen": "s",
        "localSessionId": "local-abc",
        "files": [_file("a")],
    }
    r1 = await client.post("/v1/sessions", json=body)
    r2 = await client.post("/v1/sessions", json=body)
    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r1.json()["sessionId"] == r2.json()["sessionId"]
    assert len(store._data["sessions"]) == 1


async def test_too_many_files_returns_413(store, monkeypatch, client):
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    from app import deps, rate_limit
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda uid: True)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "maxFilesPerSession": 2})

    r = await client.post(
        "/v1/sessions",
        json={"specimen": "s", "files": [_file("a"), _file("b"), _file("c")]},
    )
    assert r.status_code == 413
    assert r.json()["detail"] == "too_many_files"
    assert store._data.get("sessions", {}) == {}


async def test_session_quota_exceeded_returns_409(store, monkeypatch, client):
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    from app import deps, rate_limit
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda uid: True)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "maxSessions": 1})
    # A licensed ceiling is floored at demo's, so demo's has to be 1 as well.
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 1)
    # One existing analysis already fills the quota.
    store._data["sessions"] = {
        "existing": {"uid": DEV_UID, "status": "COMPLETED", "fileCount": 1, "completedCount": 1},
    }

    r = await client.post(
        "/v1/sessions",
        json={"specimen": "s", "files": [_file("a")]},
    )
    assert r.status_code == 409
    assert "session_quota_exceeded" in r.json()["detail"]
    assert len(store._data["sessions"]) == 1
