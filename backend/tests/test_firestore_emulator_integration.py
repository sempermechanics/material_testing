"""Optional Firestore emulator / integration tier.

Skipped unless FIRESTORE_EMULATOR_HOST is set (and usually GOOGLE_CLOUD_PROJECT).
Exercises create → complete → list/download/delete against a real emulator.
Drive calls are stubbed — the emulator only covers the metadata plane.
"""
import os
import uuid

import pytest

from app import drive, firestore_repo as repo
from app.config import settings
from app.models import FileComplete, FileSpec, SessionCreate

pytestmark = pytest.mark.skipif(
    not os.environ.get("FIRESTORE_EMULATOR_HOST"),
    reason="Set FIRESTORE_EMULATOR_HOST to run the Firestore emulator integration tier",
)


@pytest.fixture
def emulator_repo(monkeypatch):
    # Force a fresh client pointed at the emulator (Client picks up the env var).
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", True)
    monkeypatch.setattr(repo, "_DB", None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    yield repo
    monkeypatch.setattr(repo, "_DB", None)


def test_create_complete_list_delete_roundtrip(emulator_repo, monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {
            "userFolderId": "user-folder",
            "sessionFolderId": "session-folder",
            "bundle": "session-folder",
            "metadata": "session-folder",
        },
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://upload.example/session")
    monkeypatch.setattr(
        drive, "get_file_meta",
        lambda *a, **k: {"size": 10, "md5": "d" * 32, "parents": ["session-folder"]},
    )
    monkeypatch.setattr(drive, "delete_file", lambda *a, **k: None)

    uid = f"emu-{uuid.uuid4().hex[:8]}"
    user = {
        "uid": uid, "email": f"{uid}@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": "d1",
    }
    device = {"deviceId": "d1", "uid": uid, "status": "ACTIVE"}
    body = SessionCreate(
        specimen="emu",
        localSessionId=f"local-{uid}",
        files=[FileSpec(name="Session.zip", role="bundle", bytes=10, sha256="e" * 64)],
    )
    sid = uuid.uuid4().hex
    emulator_repo.create_session(sid, user, device, body)
    emulator_repo.set_session_folder(sid, "session-folder")
    file_id = f"{sid}_bundle_Session.zip"
    emulator_repo.create_file(
        sid, uid, file_id, body.files[0], "https://upload.example/session",
    )
    outcome = emulator_repo.complete_file(
        file_id, uid, FileComplete(sessionId=sid, driveFileId="drive-1", bytes=10, md5="d" * 32),
    )
    assert outcome == "ok"
    emulator_repo.bump_session_progress(sid)

    sessions, _ = emulator_repo.list_user_sessions(uid, limit=10)
    assert any(s["sessionId"] == sid for s in sessions)
    files = emulator_repo.list_session_files(sid)
    assert files and files[0]["status"] == "COMPLETED"

    removed = emulator_repo.delete_session(sid)
    assert removed >= 1
    assert emulator_repo.get_session(sid) is None
