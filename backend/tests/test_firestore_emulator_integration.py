"""Firestore emulator / integration tier.

Skipped unless FIRESTORE_EMULATOR_HOST is set; CI sets it (see the Tier 4 job in
.github/workflows/ci.yml), so this runs on every backend change.

This tier exists because tests/fake_firestore.py applies transactional writes
immediately, with no isolation and no retries. Every concurrency guarantee in
firestore_repo — consume_nonce being single-use, complete_file not double-
counting, bump_session_progress converging — is therefore unfalsifiable against
the fake. Those are exactly the invariants that matter under load, so they are
asserted here against a real Firestore.

Drive calls are stubbed: the emulator only covers the metadata plane.
"""
import os
import uuid
from concurrent.futures import ThreadPoolExecutor

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
    files, next_token = emulator_repo.list_session_files(sid)
    assert files and files[0]["status"] == "COMPLETED"
    assert next_token is None

    removed = emulator_repo.delete_session(sid)
    assert removed >= 1
    assert emulator_repo.get_session(sid) is None


# --------------------------------------------------------------- contention
#
# Everything below is untestable against the fake store, which has no isolation.

def _seed_session(repo_, uid, file_count):
    """A session with `file_count` PENDING files, ready to be completed."""
    sid = uuid.uuid4().hex
    user = {"uid": uid, "email": f"{uid}@example.com", "role": "user",
            "access_status": "APPROVED", "activeDeviceId": "d1"}
    device = {"deviceId": "d1", "uid": uid, "status": "ACTIVE"}
    specs = [
        FileSpec(name=f"f{i}.zip", role="bundle", bytes=10, sha256=f"{i:064x}")
        for i in range(file_count)
    ]
    body = SessionCreate(specimen="emu", localSessionId=f"local-{sid}", files=specs)
    repo_.create_session(sid, user, device, body)
    repo_.set_session_folder(sid, "session-folder")
    ids = []
    for spec in specs:
        file_id = f"{sid}_bundle_{spec.name}"
        repo_.create_file(sid, uid, file_id, spec, "https://upload.example/s")
        ids.append(file_id)
    return sid, ids


def test_uncontended_nonce_is_single_use(emulator_repo):
    """Liveness plus safety on the ordinary path: the one caller wins, and the
    nonce cannot be spent twice."""
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    nonce = emulator_repo.issue_nonce(uid, "d1")

    assert emulator_repo.consume_nonce(nonce, uid, "d1") is True
    assert emulator_repo.consume_nonce(nonce, uid, "d1") is False


def test_concurrent_nonce_consumers_never_exceed_one(emulator_repo):
    """The replay guarantee, and the security-critical half of it.

    A get-then-delete race let two callers both see a live nonce. Asserting "at
    most one" rather than "exactly one" is deliberate: under heavy contention on
    a single document, Firestore may abort every contender, and consume_nonce
    then denies them all. That is fail-closed and correct — a legitimate client
    never races itself on a nonce, it just requests a fresh challenge. Two
    winners would be the actual bug, and would never be acceptable.
    """
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    nonce = emulator_repo.issue_nonce(uid, "d1")

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(
            lambda _: emulator_repo.consume_nonce(nonce, uid, "d1"), range(8),
        ))

    assert sum(1 for r in results if r) <= 1, f"nonce consumed {sum(results)} times"
    # And whatever happened, it must not raise — losing a race is a 401, not a 500.
    assert all(isinstance(r, bool) for r in results)


def test_wrong_owner_cannot_burn_a_live_nonce(emulator_repo):
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    nonce = emulator_repo.issue_nonce(uid, "d1")

    assert emulator_repo.consume_nonce(nonce, "someone-else", "d1") is False
    assert emulator_repo.consume_nonce(nonce, uid, "other-device") is False
    assert emulator_repo.consume_nonce(nonce, uid, "d1") is True, "valid caller lost its nonce"


def _complete_then_bump(repo_, sid, uid, file_id):
    """Exactly what the route does: bump only on a FIRST completion.

    bump_session_progress is an unconditional +1 — its correctness depends on
    complete_file returning "already" for a retry. Mirroring the route is the
    only way to test that contract rather than a fiction.
    """
    outcome = repo_.complete_file(
        file_id, uid,
        FileComplete(sessionId=sid, driveFileId=f"drive-{file_id}", bytes=10, md5="d" * 32),
    )
    if outcome == "ok":
        repo_.bump_session_progress(sid)
    return outcome


def test_concurrent_completions_of_one_file_never_both_win(emulator_repo, monkeypatch):
    """The guarantee bump_session_progress rests on. If two concurrent
    completions of the same PENDING file both returned "ok", both would bump and
    the session would report COMPLETED with files still pending.

    "At most one" for the same reason as the nonce test above; the loser must
    come back as "already" or "" and never as an exception.
    """
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, (file_id,) = _seed_session(emulator_repo, uid, file_count=1)

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(
            lambda _: emulator_repo.complete_file(
                file_id, uid,
                FileComplete(sessionId=sid, driveFileId="drive-1", bytes=10, md5="d" * 32),
            ),
            range(8),
        ))

    assert outcomes.count("ok") <= 1, f"{outcomes.count('ok')} concurrent completions won"
    assert set(outcomes) <= {"ok", "already", ""}, f"unexpected outcomes {set(outcomes)}"


def test_session_counter_tracks_completions_exactly(emulator_repo, monkeypatch):
    """Four files, each completed twice concurrently, driven exactly as the
    route drives them.

    The invariant is that completedCount equals the number of "ok" outcomes —
    never more (a retry double-counting, which would flip a session to COMPLETED
    with files still pending) and never less (a lost increment, which would
    strand an upload short of done forever).
    """
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, file_ids = _seed_session(emulator_repo, uid, file_count=4)

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(
            lambda fid: _complete_then_bump(emulator_repo, sid, uid, fid),
            file_ids + file_ids,
        ))

    wins = outcomes.count("ok")
    session = emulator_repo.get_session(sid)
    assert session["completedCount"] == wins, "counter drifted from actual completions"
    assert wins <= 4, "a file was completed more than once"
    assert session["completedCount"] <= session["fileCount"]
    if wins == 4:
        assert session["status"] == "COMPLETED"


def test_serial_completions_finish_the_session(emulator_repo, monkeypatch):
    """The uncontended path clients actually take: every file completes and the
    session reaches COMPLETED. Liveness, asserted where it is guaranteed."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, file_ids = _seed_session(emulator_repo, uid, file_count=4)

    for file_id in file_ids:
        assert _complete_then_bump(emulator_repo, sid, uid, file_id) == "ok"

    session = emulator_repo.get_session(sid)
    assert session["completedCount"] == 4
    assert session["status"] == "COMPLETED"


def test_completion_is_idempotent_under_serial_retry(emulator_repo, monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, (file_id,) = _seed_session(emulator_repo, uid, file_count=1)

    assert _complete_then_bump(emulator_repo, sid, uid, file_id) == "ok"
    assert _complete_then_bump(emulator_repo, sid, uid, file_id) == "already"

    assert emulator_repo.get_session(sid)["completedCount"] == 1


def test_concurrent_bumps_lose_no_increments(emulator_repo, monkeypatch):
    """bump_session_progress is a read-modify-write. Without the transaction,
    concurrent bumps lose updates and a finished session never flips to
    COMPLETED — uploads would appear stuck at the last file forever."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, _ = _seed_session(emulator_repo, uid, file_count=6)

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda _: emulator_repo.bump_session_progress(sid), range(6)))

    session = emulator_repo.get_session(sid)
    assert session["completedCount"] == 6, "lost update: increments were dropped"
    assert session["status"] == "COMPLETED"


def test_cross_user_completion_is_refused_inside_the_transaction(emulator_repo, monkeypatch):
    """The ownership re-check lives inside the transaction, so it cannot be
    raced past by a caller that read the doc before the check."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    owner = f"emu-{uuid.uuid4().hex[:8]}"
    sid, (file_id,) = _seed_session(emulator_repo, owner, file_count=1)

    outcome = emulator_repo.complete_file(
        file_id, "a-different-uid",
        FileComplete(sessionId=sid, driveFileId="drive-1", bytes=10, md5="d" * 32),
    )

    assert outcome != "ok"
    remaining, _ = emulator_repo.list_session_files(sid)
    assert remaining[0]["status"] == "PENDING"


def test_page_token_cannot_escape_the_collection(emulator_repo):
    """Against the real client, a slash-bearing cursor addresses a *path*. The
    HTTP layer rejects these now (validation.PageToken); this pins the reason."""
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    _seed_session(emulator_repo, uid, file_count=1)

    sessions, _ = emulator_repo.list_user_sessions(uid, limit=10, page_token=None)
    assert sessions, "seeded session not listed"

    with pytest.raises(Exception):
        emulator_repo.list_user_sessions(uid, limit=10, page_token="a/b")
