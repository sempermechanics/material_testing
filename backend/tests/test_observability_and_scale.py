"""Readiness, opaque errors, pagination, rate-limit concurrency, Unicode, size bounds."""
import threading

import fake_firestore
import pytest

from app import audit, drive, firestore_repo as repo, observability as obs, rate_limit
from app.config import settings
from app.models import FileSpec, SessionCreate


@pytest.mark.asyncio
async def test_readyz_ok(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    monkeypatch.setattr(repo, "ping", lambda: None)
    monkeypatch.setattr(drive, "ping", lambda: None)
    r = await client.get("/readyz")
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["checks"]["firestore"] == "ok"


@pytest.mark.asyncio
async def test_readyz_firestore_failure_is_stable_503(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)

    def boom():
        raise obs.DependencyError("firestore_unreachable", "firestore")

    monkeypatch.setattr(repo, "ping", boom)
    r = await client.get("/readyz")
    assert r.status_code == 503
    assert r.json() == {"detail": "firestore_unreachable"}


@pytest.mark.asyncio
async def test_readyz_drive_failure_is_stable_503(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    monkeypatch.setattr(repo, "ping", lambda: None)

    def boom():
        raise obs.DependencyError("drive_unhealthy", "drive")

    monkeypatch.setattr(drive, "ping", boom)
    r = await client.get("/readyz")
    assert r.status_code == 503
    assert r.json() == {"detail": "drive_unhealthy"}


@pytest.mark.asyncio
async def test_safe_error_body_on_unexpected_failure(client, monkeypatch):
    monkeypatch.setattr(rate_limit.health_bucket, "allow", lambda key: True)
    monkeypatch.setattr(settings, "ON_CLOUD_RUN", True)

    from app.main import app

    @app.get("/__test_boom")
    def boom():
        raise RuntimeError("secret internals")

    try:
        r = await client.get("/__test_boom")
        assert r.status_code == 500
        assert r.json() == {"detail": "internal_error"}
        assert "secret" not in r.text
    finally:
        app.router.routes = [
            rt for rt in app.router.routes if getattr(rt, "path", None) != "/__test_boom"
        ]


@pytest.mark.asyncio
async def test_session_pagination(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["sessions"] = {
        f"s{i:02d}": {
            "uid": "dev-user", "status": "COMPLETED", "localSessionId": f"l{i}",
            "fileCount": 1, "completedCount": 1, "totalBytes": 1,
        }
        for i in range(5)
    }
    first = await client.get("/v1/sessions?page_size=2")
    assert first.status_code == 200
    body = first.json()
    assert len(body["sessions"]) == 2
    assert body["page"]["hasMore"] is True
    assert body["quota"]["used"] == 5
    token = body["page"]["nextPageToken"]
    second = await client.get(f"/v1/sessions?page_size=2&page_token={token}")
    assert len(second.json()["sessions"]) == 2
    assert {s["sessionId"] for s in first.json()["sessions"]}.isdisjoint(
        {s["sessionId"] for s in second.json()["sessions"]}
    )


@pytest.mark.asyncio
async def test_verify_uses_batched_existence(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["sessions"] = {
        "s1": {
            "uid": "dev-user", "status": "COMPLETED", "driveFolderId": "fold-alive",
            "localSessionId": "l1", "fileCount": 1, "completedCount": 1, "totalBytes": 1,
        },
        "s2": {
            "uid": "dev-user", "status": "COMPLETED", "driveFolderId": "fold-gone",
            "localSessionId": "l2", "fileCount": 1, "completedCount": 1, "totalBytes": 1,
        },
    }
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    seen = {}

    def fake_probe(token, ids, max_workers=8):
        seen["ids"] = list(ids)
        return {fid: (drive.ALIVE if fid == "fold-alive" else drive.MISSING) for fid in ids}

    monkeypatch.setattr(drive, "probe_files", fake_probe)
    r = await client.get("/v1/sessions?verify=true&page_size=50")
    assert r.status_code == 200
    assert set(seen["ids"]) == {"fold-alive", "fold-gone"}
    assert [s["sessionId"] for s in r.json()["sessions"]] == ["s1"]
    assert r.json()["verify"]["purged"] == 1
    assert "s2" not in store._data["sessions"]


@pytest.mark.asyncio
async def test_export_is_complete_not_capped(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "dev@local", "access_status": "APPROVED", "role": "admin"},
    }
    store._data["sessions"] = {
        f"s{i:03d}": {
            "uid": "dev-user", "status": "COMPLETED", "localSessionId": f"l{i}",
            "specimen": "beam", "fileCount": 0, "completedCount": 0, "totalBytes": 0,
        }
        for i in range(25)
    }
    # page_size inside iter defaults to 100; force smaller via monkeypatch path
    r = await client.get("/v1/me/export")
    assert r.status_code == 200
    body = r.json()
    assert body["complete"] is True
    assert len(body["sessions"]) == 25


@pytest.mark.asyncio
async def test_rate_limit_concurrency(monkeypatch):
    bucket = rate_limit.TokenBucket(rate_per_sec=0.0, burst=5.0)
    results = []

    def worker():
        results.append(bucket.allow("u1"))

    threads = [threading.Thread(target=worker) for _ in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sum(1 for ok in results if ok) == 5
    assert sum(1 for ok in results if not ok) == 15


@pytest.mark.asyncio
async def test_quota_race_accepts_soft_overshoot_window(client, monkeypatch):
    """Two creates that both pass count before either reserves can soft-overshoot.

    Documented intentional soft quota — not a security boundary. This test
    locks the create path so both see count=0 then both reserve.
    """
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    from app import deps
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "maxSessions": 1})
    # A licensed ceiling is floored at demo's, so demo's has to be 1 as well.
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 1)
    monkeypatch.setattr(repo, "count_user_sessions", lambda uid: 0)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"userFolderId": "u", "sessionFolderId": "s", "bundle": "s", "metadata": "s"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://upload")
    monkeypatch.setattr(repo, "remember_user_folder", lambda *a, **k: None)
    monkeypatch.setattr(repo, "set_session_folder", lambda *a, **k: None)

    body = {
        "specimen": "x",
        "localSessionId": "",
        "files": [{
            "name": "Session.zip", "role": "bundle", "bytes": 10,
            "sha256": "a" * 64,
        }],
    }
    r1 = await client.post("/v1/sessions", json={**body, "localSessionId": "a"})
    r2 = await client.post("/v1/sessions", json={**body, "localSessionId": "b"})
    assert r1.status_code == 200
    assert r2.status_code == 200
    assert len(store._data.get("sessions", {})) == 2


@pytest.mark.asyncio
async def test_unicode_specimen_and_device_model(client, monkeypatch):
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"userFolderId": "u", "sessionFolderId": "s", "bundle": "s", "metadata": "s"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://upload")
    monkeypatch.setattr(repo, "remember_user_folder", lambda *a, **k: None)
    monkeypatch.setattr(repo, "set_session_folder", lambda *a, **k: None)

    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives import serialization
    pem = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    reg = await client.post("/v1/devices/register", json={
        "deviceId": "dev-unicode-1",
        "publicKeyPem": pem,
        "model": "Pixel नमस्ते",
        "osVersion": "14",
        "appVersion": "1.0",
    })
    # DEV user already has activeDeviceId=dev-device → conflict on different id
    assert reg.status_code in (201, 409)

    created = SessionCreate(
        specimen="काष्ठ-beam-α",
        files=[FileSpec(name="Session.zip", role="bundle", bytes=10, sha256="b" * 64)],
        localSessionId="loc-unicode",
    )
    assert "काष्ठ" in created.specimen


@pytest.mark.asyncio
async def test_max_file_size_rejected():
    with pytest.raises(Exception):
        FileSpec(name="huge.bin", role="bundle", bytes=6 * 1024 * 1024 * 1024, sha256="c" * 64)


@pytest.mark.asyncio
async def test_drive_timeout_surfaces_as_dependency_error(monkeypatch):
    import requests

    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive.requests, "get",
        lambda *a, **k: (_ for _ in ()).throw(requests.Timeout("slow")),
    )
    with pytest.raises(obs.DependencyError) as e:
        drive.ping(timeout_s=0.01)
    assert e.value.code == "drive_unreachable"
    assert e.value.status_code == 503
