"""A Drive outage on an erase or an inline session create is a deliberate 502,
not an unhandled 500 (found by the schema fuzz, readiness TE3-007).

Both paths already stop safely: the erase aborts before any record goes, and
the inline create rolls the session back. What they answered was the opaque
500 `internal_error` of an unhandled exception, which also files an Error
Reporting event for an outage of a dependency. The other Drive routes answer
502 with a code (`routers/_shared.drive_failure`); these now do too. A failure
that is not Drive's (anything but a `requests` error) is still unhandled.
"""
import pytest
import requests

from app import audit, drive, rate_limit, tasks
from app.deps import _DEV_USER

_SHA = "a" * 64


def _offline(*_a, **_k):
    raise requests.ConnectionError("drive unreachable")


@pytest.fixture
def seeded(store, monkeypatch):
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED"}}
    store._data["sessions"] = {"s-mine": {"uid": "dev-user", "driveFolderId": "f-mine"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    return store


@pytest.mark.asyncio
async def test_an_erase_with_drive_down_is_502_and_erases_nothing(seeded, client, monkeypatch):
    monkeypatch.setitem(_DEV_USER, "driveFolderId", "dev-user-folder")
    monkeypatch.setattr(drive, "delete_file", _offline)
    before = {k: dict(v) for k, v in seeded._data.items()}

    r = await client.delete("/v1/me")

    assert (r.status_code, r.json()) == (502, {"detail": "drive_write_failed"})
    assert seeded._data == before


@pytest.mark.asyncio
async def test_an_inline_create_with_drive_down_is_502_and_rolls_back(seeded, client, monkeypatch):
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: False)
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda uid: True)
    monkeypatch.setattr(drive, "ensure_session_folders", _offline)
    monkeypatch.setattr(drive, "delete_file", lambda *a, **k: None)

    r = await client.post("/v1/sessions", json={
        "specimen": "s", "files": [{"name": "a.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}],
    })

    assert (r.status_code, r.json()) == (502, {"detail": "drive_write_failed"})
    assert set(seeded._data["sessions"]) == {"s-mine"}
    assert seeded._data.get("files", {}) == {}
