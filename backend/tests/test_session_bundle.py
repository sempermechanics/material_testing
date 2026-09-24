"""`GET /v1/sessions/{sid}/bundle` — one analysis out through a browser.

The single-file route is device-attested, which is the right tier for a phone
restoring itself and the wrong one for somebody at a desk whose phone is what
went missing. These tests pin the three things that make the substitute safe:
it answers to the caller's own analyses only, it needs the step-up tier, and
it never buffers what it sends.
"""
import io
import json
import zipfile
from datetime import datetime, timezone

import pytest

import fake_firestore

from app import deps, drive, firestore_repo as repo, statuses

SID = "sess-1"


class _FakeDownload:
    def __init__(self, payload: bytes, chunk: int = 4):
        self._payload, self._chunk = payload, chunk

    def iter_chunks(self, chunk_size: int = 0):
        for i in range(0, len(self._payload), self._chunk):
            yield self._payload[i:i + self._chunk]


@pytest.fixture
def stored(monkeypatch):
    """One licensed account owning one uploaded analysis."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["sessions"] = {SID: {
        "uid": "dev-user", "specimen": "coupon-7", "status": statuses.SESSION_COMPLETED,
        "createdAt": datetime(2026, 3, 4, 5, 6, 7, tzinfo=timezone.utc),
    }}
    store._data["files"] = {
        f"{SID}_bundle_Session.zip": {
            "sessionId": SID, "uid": "dev-user", "role": "bundle", "name": "Session.zip",
            "sizeBytes": 9, "sha256": "aa", "status": statuses.FILE_COMPLETED,
            "driveFileId": "drive-bundle",
        },
        f"{SID}_extras_Extras.zip": {
            "sessionId": SID, "uid": "dev-user", "role": "extras", "name": "Extras.zip",
            "sizeBytes": 7, "sha256": "bb", "status": statuses.FILE_COMPLETED,
            "driveFileId": "drive-extras",
        },
        f"{SID}_raw_pending.png": {
            "sessionId": SID, "uid": "dev-user", "role": "raw", "name": "pending.png",
            "sizeBytes": 3, "status": statuses.FILE_PENDING,
        },
    }
    payloads = {"drive-bundle": b"SESSIONZP", "drive-extras": b"EXTRASZ"}
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "open_download",
                        lambda token, fid, **kw: _FakeDownload(payloads[fid]))
    return store


async def _bundle(client, sid=SID):
    resp = await client.get(f"/v1/sessions/{sid}/bundle")
    return resp, (zipfile.ZipFile(io.BytesIO(resp.content))
                  if resp.status_code == 200 else None)


# ---------------------------------------------------------------- the archive

@pytest.mark.asyncio
async def test_the_whole_analysis_comes_back_as_one_zip(client, stored):
    resp, zf = await _bundle(client)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/zip"
    assert resp.headers["content-disposition"] == \
        f'attachment; filename="semper-analysis-{SID}.zip"'
    assert resp.headers["cache-control"] == "no-store"
    assert zf.namelist() == ["manifest.json", "bundle/Session.zip", "extras/Extras.zip"]
    assert zf.read("bundle/Session.zip") == b"SESSIONZP"
    assert zf.read("extras/Extras.zip") == b"EXTRASZ"
    # A zip whose central directory parses is a zip that finished.
    assert zf.testzip() is None


@pytest.mark.asyncio
async def test_the_manifest_is_the_inventory(client, stored):
    _, zf = await _bundle(client)
    manifest = json.loads(zf.read("manifest.json"))
    assert manifest["sessionId"] == SID
    assert manifest["specimen"] == "coupon-7"
    assert manifest["fileCount"] == 2
    assert [f["entry"] for f in manifest["files"]] == \
        ["bundle/Session.zip", "extras/Extras.zip"]
    assert [f["sha256"] for f in manifest["files"]] == ["aa", "bb"]


@pytest.mark.asyncio
async def test_a_file_still_uploading_is_not_in_the_archive(client, stored):
    """The archive is of what was stored, not of what was promised."""
    _, zf = await _bundle(client)
    assert not any(n.startswith("raw/") for n in zf.namelist())


@pytest.mark.asyncio
async def test_nothing_is_buffered(client, stored, monkeypatch):
    """The zip is produced as the Drive bytes arrive, not after.

    Asserted by counting what the sink is asked to hold: if the response were
    assembled first, one drain would carry the whole archive.
    """
    from app.routers import sessions as route

    sizes = []
    original = route._ZipSink.drain

    def counting_drain(self):
        out = original(self)
        if out:
            sizes.append(len(out))
        return out

    monkeypatch.setattr(route._ZipSink, "drain", counting_drain)
    resp, _ = await _bundle(client)
    assert resp.status_code == 200
    # Four-byte Drive chunks, so a streamed archive drains many small pieces.
    assert len(sizes) > 4
    assert max(sizes) < len(resp.content)


@pytest.mark.asyncio
async def test_a_hostile_filename_cannot_escape_the_archive(client, stored):
    stored._data["files"][f"{SID}_raw_evil"] = {
        "sessionId": SID, "uid": "dev-user", "role": "../../etc",
        "name": "../../../passwd", "sizeBytes": 9,
        "status": statuses.FILE_COMPLETED, "driveFileId": "drive-bundle",
    }
    _, zf = await _bundle(client)
    for name in zf.namelist():
        assert not name.startswith("/")
        assert ".." not in name


# ------------------------------------------------------------------ refusals

@pytest.mark.asyncio
async def test_another_accounts_analysis_is_not_found(client, stored):
    stored._data["sessions"][SID]["uid"] = "somebody-else"
    resp, _ = await _bundle(client)
    assert resp.status_code == 404
    assert resp.json()["detail"] == "session_not_found"


@pytest.mark.asyncio
async def test_an_analysis_that_does_not_exist(client, stored):
    resp, _ = await _bundle(client, sid="no-such-session")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_demo_cannot_pull_a_bundle(client, stored, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo",
                                            "plan": "demo"})
    resp, _ = await _bundle(client)
    assert resp.status_code == 403
    assert resp.json()["detail"].startswith("feature_not_licensed: ")


@pytest.mark.asyncio
async def test_an_analysis_with_nothing_uploaded_yet(client, stored):
    for f in stored._data["files"].values():
        f["status"] = statuses.FILE_PENDING
    resp, _ = await _bundle(client)
    assert resp.status_code == 409
    assert resp.json()["detail"] == "file_not_uploaded"


@pytest.mark.asyncio
async def test_a_drive_failure_before_the_first_byte_is_a_status_code(
    client, stored, monkeypatch,
):
    """Everything that can be reported as a code is resolved up front."""
    def boom():
        raise RuntimeError("no token")

    monkeypatch.setattr(drive, "access_token", boom)
    resp, _ = await _bundle(client)
    assert resp.status_code == 502
    assert resp.json()["detail"] == "drive_download_failed"


@pytest.mark.asyncio
async def test_the_download_is_recorded(client, stored, monkeypatch):
    from app import audit

    rows = []
    monkeypatch.setattr(audit, "record",
                        lambda uid=None, device_id=None, **kw: rows.append(kw))
    await _bundle(client)
    assert [r["action"] for r in rows] == ["SESSION_BUNDLE_DOWNLOAD"]
    assert rows[0]["target"] == {"type": "session", "id": SID}
    assert rows[0]["detail"]["fileCount"] == 2


# ------------------------------------------------------------- the repo split

def test_the_artifact_read_carries_a_drive_id_and_the_export_read_does_not(stored):
    """Two projections of one query, kept apart on purpose.

    `list_session_files_all` feeds `GET /v1/me/export`, where a Drive file id
    is a handle to bytes the caller is not being handed. Adding a field to the
    artifact read must never widen that.
    """
    artifacts = repo.list_session_artifacts(SID)
    assert [a["driveFileId"] for a in artifacts] == ["drive-bundle", "drive-extras"]
    exported = repo.list_session_files_all(SID)
    assert len(exported) == 3
    assert all("driveFileId" not in f for f in exported)


def _drive_http_error(code):
    import requests

    resp = requests.Response()
    resp.status_code = code
    return requests.HTTPError(f"{code}", response=resp)


@pytest.mark.asyncio
async def test_a_file_deleted_in_drive_downloads_as_404_not_502(client, stored, monkeypatch):
    """The app gives up on a 404 and says the backup is gone; a 502 made it
    retry the restore forever."""
    def gone(token, fid, **kw):
        raise _drive_http_error(404)

    monkeypatch.setattr(drive, "open_download", gone)
    resp = await client.get(f"/v1/files/{SID}_bundle_Session.zip/content")
    assert resp.status_code == 404
    assert resp.json()["detail"] == "drive_file_gone"
