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


async def test_complete_rejects_omitted_md5_when_drive_has_one(seeded, client, monkeypatch):
    # Client must supply md5 whenever Drive reports one — omitting it used to
    # skip the check entirely (corrupt-but-right-sized uploads would pass).
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: _meta())
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "s1", "driveFileId": "drive1", "bytes": 100},
    )
    assert r.status_code == 422
    assert r.json()["detail"] == "checksum_mismatch"
    assert seeded._data["files"]["f1"]["status"] == "PENDING"


def _drive_http_error(code):
    import requests

    resp = requests.Response()
    resp.status_code = code
    return requests.HTTPError(f"{code}", response=resp)


async def test_complete_when_the_upload_is_not_in_drive_is_400_not_502(
    seeded, client, monkeypatch,
):
    """A 5xx made the app retry this forever. 400 drives the app's existing
    stale-session branch: delete the session and rebuild it."""
    def gone(t, fid):
        raise _drive_http_error(404)

    monkeypatch.setattr(drive, "get_file_meta", gone)
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "s1", "driveFileId": "drive1", "bytes": 100, "md5": _MD5},
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "drive_file_gone"
    assert seeded._data["files"]["f1"]["status"] == "PENDING"


async def test_complete_on_a_drive_outage_is_still_502(seeded, client, monkeypatch):
    def outage(t, fid):
        raise _drive_http_error(503)

    monkeypatch.setattr(drive, "get_file_meta", outage)
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "s1", "driveFileId": "drive1", "bytes": 100, "md5": _MD5},
    )
    assert r.status_code == 502
    assert r.json()["detail"] == "drive_meta_failed"
