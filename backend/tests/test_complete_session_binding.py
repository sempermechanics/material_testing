"""A completion advances the counter of the file's OWN session.

`FileComplete.sessionId` is client-supplied and was previously passed straight
to `bump_session_progress`, which updates `sessions/{sid}` with no ownership
check. That let a caller advance — and prematurely COMPLETE — a session
belonging to someone else. The file doc already records its own `sessionId`,
so the client's value is never needed.
"""
import fake_firestore
import pytest

from app import audit, drive

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
OTHER_UID = "victim-user"


@pytest.fixture
def seeded(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    store._data["files"] = {
        "f1": {"uid": DEV_UID, "sizeBytes": 100, "status": "PENDING", "sessionId": "mine"},
    }
    store._data["sessions"] = {
        "mine": {"uid": DEV_UID, "fileCount": 2, "completedCount": 0, "status": "UPLOADING"},
        # Another user's session, one file short of done.
        "theirs": {"uid": OTHER_UID, "fileCount": 5, "completedCount": 4, "status": "UPLOADING"},
    }
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: {"size": 100, "md5": "m"})
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return store


async def test_completion_cannot_advance_another_users_session(seeded, client):
    """The attack: complete my own file, but name the victim's session."""
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "theirs", "driveFileId": "drive1", "bytes": 100, "md5": "m"},
    )
    assert r.status_code == 200

    victim = seeded._data["sessions"]["theirs"]
    assert victim["completedCount"] == 4, "victim's counter was advanced"
    assert victim["status"] == "UPLOADING", "victim's session was flipped to COMPLETED"


async def test_completion_advances_the_files_own_session(seeded, client):
    """Even with a wrong sessionId in the body, the right session advances."""
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "theirs", "driveFileId": "drive1", "bytes": 100, "md5": "m"},
    )
    assert r.status_code == 200
    assert seeded._data["sessions"]["mine"]["completedCount"] == 1


async def test_completion_still_completes_a_finished_session(seeded, client):
    """The normal path is unaffected: last file in → session COMPLETED."""
    seeded._data["sessions"]["mine"]["completedCount"] = 1  # 1 of 2 already done
    r = await client.post(
        "/v1/files/f1/complete",
        json={"sessionId": "mine", "driveFileId": "drive1", "bytes": 100, "md5": "m"},
    )
    assert r.status_code == 200
    assert seeded._data["sessions"]["mine"]["status"] == "COMPLETED"
