"""POST /v1/files/{id}/complete now verifies the upload against Drive's actual
size/md5 before marking it COMPLETED, instead of trusting the client's claim."""
import fake_firestore
import pytest

from app import audit, drive

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_MD5 = "a" * 32


@pytest.fixture
def seeded(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    store._data["files"] = {
        "f1": {"uid": DEV_UID, "sizeBytes": 100, "status": "PENDING", "sessionId": "s1"},
    }
    store._data["sessions"] = {
        "s1": {
            "uid": DEV_UID, "fileCount": 1, "completedCount": 0,
            "status": "UPLOADING", "driveFolderId": "sf1",
        },
    }
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


def _meta(size=100, md5=_MD5, parents=None):
    return {"size": size, "md5": md5, "parents": parents if parents is not None else ["sf1"]}


async def test_complete_accepts_matching_drive_size(seeded, client, monkeypatch):
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: _meta())
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "s1", "driveFileId": "drive1", "bytes": 100, "md5": _MD5},
    )
    assert r.status_code == 200
    assert seeded._data["files"]["f1"]["status"] == "COMPLETED"
    assert seeded._data["sessions"]["s1"]["status"] == "COMPLETED"


async def test_complete_rejects_truncated_upload(seeded, client, monkeypatch):
    # Drive received fewer bytes than reserved → the object is corrupt.
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: _meta(size=50))
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "s1", "driveFileId": "drive1", "bytes": 100, "md5": _MD5},
    )
    assert r.status_code == 422
    assert seeded._data["files"]["f1"]["status"] == "PENDING"  # not marked complete


async def test_complete_rejects_checksum_mismatch(seeded, client, monkeypatch):
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: _meta(md5="b" * 32))
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "s1", "driveFileId": "drive1", "bytes": 100, "md5": _MD5},
    )
    assert r.status_code == 422
