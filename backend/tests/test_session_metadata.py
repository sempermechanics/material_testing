"""PUT /v1/sessions/{sid}/metadata replaces a backed-up session's metadata.json
(ADR-013, TD-150): a bending deflection correction set after the backup must
reach the cloud copy, because the restore reads it back from that file."""
import hashlib
import json

import fake_firestore
import pytest
import requests

from app import audit, drive

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_FILE = "s1_metadata_metadata.json"


@pytest.fixture
def seeded(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    store._data["sessions"] = {
        "s1": {"uid": DEV_UID, "status": "COMPLETED", "localSessionId": "loc-1", "driveFolderId": "sf1"},
    }
    store._data["files"] = {
        _FILE: {
            "uid": DEV_UID, "sessionId": "s1", "role": "metadata", "name": "metadata.json",
            "sizeBytes": 10, "sha256": "0" * 64, "status": "COMPLETED",
            "driveFileId": "drive-meta", "driveMd5": "a" * 32,
        },
    }
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


def _metadata(**over):
    body = {
        "schema": "indic.session.metadata/6",
        "localSessionId": "loc-1",
        "test": {"type": "flexural", "geometry": {"deflectionScale": 1.05, "deflectionBiasMm": -0.12}},
    }
    body.update(over)
    return body


def _writes_to(monkeypatch, sent: list, size_delta: int = 0):
    def fake(token, drive_file_id, data, mime="application/json"):
        sent.append((drive_file_id, data))
        return {"size": len(data) + size_delta, "md5": "b" * 32}

    monkeypatch.setattr(drive, "replace_content", fake)


async def test_the_metadata_is_written_over_the_same_drive_object(seeded, client, monkeypatch):
    sent = []
    _writes_to(monkeypatch, sent)

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 200
    (drive_id, data), = sent
    assert drive_id == "drive-meta"
    assert json.loads(data)["test"]["geometry"]["deflectionScale"] == 1.05
    # The restore checks the metadata's size against the file doc; the bundle
    # manifest lists it too. Both must follow the new bytes.
    doc = seeded._data["files"][_FILE]
    assert doc["sizeBytes"] == len(data) == r.json()["sizeBytes"]
    assert doc["sha256"] == hashlib.sha256(data).hexdigest()
    assert doc["driveMd5"] == "b" * 32
    assert doc["status"] == "COMPLETED"


async def test_an_unfinished_upload_is_not_touched(seeded, client, monkeypatch):
    sent = []
    _writes_to(monkeypatch, sent)
    seeded._data["sessions"]["s1"]["status"] = "UPLOADING"

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 409
    assert r.json()["detail"] == "session_not_complete"
    assert sent == []


async def test_someone_elses_session_reads_as_absent(seeded, client, monkeypatch):
    sent = []
    _writes_to(monkeypatch, sent)
    seeded._data["sessions"]["s1"]["uid"] = "other"

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 404
    assert r.json()["detail"] == "session_not_found"
    assert sent == []


@pytest.mark.parametrize(
    "body",
    [
        _metadata(localSessionId="loc-2"),  # another analysis's metadata
        _metadata(schema="something/1"),
        {"localSessionId": "loc-1"},  # no schema
    ],
)
async def test_a_body_that_is_not_this_sessions_metadata_is_refused(seeded, client, monkeypatch, body):
    sent = []
    _writes_to(monkeypatch, sent)

    r = await client.put("/v1/sessions/s1/metadata", json=body)

    assert r.status_code == 422
    assert r.json()["detail"] == "metadata_invalid"
    assert sent == []
    assert seeded._data["files"][_FILE]["sizeBytes"] == 10


async def test_an_oversized_body_is_refused(seeded, client, monkeypatch):
    sent = []
    _writes_to(monkeypatch, sent)

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata(frames=["x" * 1000] * 300))

    assert r.status_code == 413
    assert r.json()["detail"] == "metadata_too_large"
    assert sent == []


async def test_a_session_without_a_metadata_file_is_404(seeded, client, monkeypatch):
    sent = []
    _writes_to(monkeypatch, sent)
    del seeded._data["files"][_FILE]

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 404
    assert r.json()["detail"] == "metadata_not_found"


def _drive_error(code):
    resp = requests.Response()
    resp.status_code = code
    return requests.HTTPError(response=resp)


async def test_a_metadata_object_gone_from_drive_is_409_not_a_retryable_5xx(seeded, client, monkeypatch):
    def gone(*a, **k):
        raise _drive_error(404)

    monkeypatch.setattr(drive, "replace_content", gone)

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 409
    assert r.json()["detail"] == "drive_file_gone"
    assert seeded._data["files"][_FILE]["sizeBytes"] == 10


async def test_a_drive_outage_is_502_and_leaves_the_file_doc(seeded, client, monkeypatch):
    def down(*a, **k):
        raise _drive_error(503)

    monkeypatch.setattr(drive, "replace_content", down)

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 502
    assert r.json()["detail"] == "drive_write_failed"
    assert seeded._data["files"][_FILE]["sha256"] == "0" * 64


async def test_a_short_write_is_not_recorded(seeded, client, monkeypatch):
    sent = []
    _writes_to(monkeypatch, sent, size_delta=-1)

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata())

    assert r.status_code == 502
    assert seeded._data["files"][_FILE]["sizeBytes"] == 10
