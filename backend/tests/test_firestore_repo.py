"""Security-critical repo logic: nonce single-use/TTL, admin+approval policy,
and the complete_file idempotency + bump-once invariant. All against an
in-memory Firestore double — no live backend, no auth bypass."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import firestore_repo as repo
from app.models import FileComplete


@pytest.fixture
def store(monkeypatch):
    # Keep outbound mail out of the unit tests regardless of env.
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    return fake_firestore.install(monkeypatch)


def _claims(sub="u1", email="a@b.com", verified=True, provider="google.com"):
    return {
        "sub": sub,
        "email": email,
        "email_verified": verified,
        "name": "Test",
        "firebase": {"sign_in_provider": provider},
    }


# ---------------- nonce ----------------
def test_nonce_round_trip_consumes_once(store):
    nonce = repo.issue_nonce("u1", "d1")
    assert repo.consume_nonce(nonce, "u1", "d1") is True
    # Single-use: the same nonce cannot be replayed.
    assert repo.consume_nonce(nonce, "u1", "d1") is False


def test_nonce_rejects_wrong_uid_or_device(store):
    nonce = repo.issue_nonce("u1", "d1")
    assert repo.consume_nonce(nonce, "someone-else", "d1") is False
    # Invalid callers must not burn the challenge — owner can still consume it.
    assert nonce in store._data["challenges"]
    assert repo.consume_nonce(nonce, "u1", "d1") is True


def test_nonce_wrong_device_rejected(store):
    nonce = repo.issue_nonce("u1", "d1")
    assert repo.consume_nonce(nonce, "u1", "d2") is False
    assert nonce in store._data["challenges"]


def test_nonce_expired_rejected(store):
    nonce = repo.issue_nonce("u1", "d1")
    # Force the stored challenge to be in the past.
    store._data["challenges"][nonce]["expireAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert repo.consume_nonce(nonce, "u1", "d1") is False
    # Expired docs are left for TTL reclaim, not deleted by a failed consume.
    assert nonce in store._data["challenges"]


def test_nonce_unknown_rejected(store):
    assert repo.consume_nonce("never-issued", "u1", "d1") is False


# ---------------- admin / approval ----------------
def test_admin_email_requires_verified(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", {"boss@corp.com"})
    assert repo._is_admin_email(_claims(email="boss@corp.com", verified=True)) is True
    # Unverified email must never grant admin, even if it matches.
    assert repo._is_admin_email(_claims(email="boss@corp.com", verified=False)) is False
    assert repo._is_admin_email(_claims(email="nobody@corp.com", verified=True)) is False


def test_auto_approved_policy(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "corp.com")
    assert repo._auto_approved(_claims(email="x@corp.com", verified=True)) is True
    assert repo._auto_approved(_claims(email="x@corp.com", verified=False)) is False
    assert repo._auto_approved(_claims(email="x@other.com", verified=True)) is False


def test_new_user_pending_by_default(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    u = repo.get_or_create_user(_claims(sub="new1", email="x@nowhere.com"))
    assert u["access_status"] == "PENDING"
    assert u["role"] == "user"


def test_new_admin_user_approved(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", {"boss@corp.com"})
    u = repo.get_or_create_user(_claims(sub="admin1", email="boss@corp.com"))
    assert u["access_status"] == "APPROVED"
    assert u["role"] == "admin"


def test_pending_user_promoted_on_qualifying_signin(monkeypatch, store):
    monkeypatch.setattr(repo.settings, "ADMIN_EMAILS", set())
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE", False)
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "")
    repo.get_or_create_user(_claims(sub="u2", email="x@corp.com"))  # PENDING
    # Domain is now auto-approved; the next sign-in flips them to APPROVED.
    monkeypatch.setattr(repo.settings, "AUTO_APPROVE_HD", "corp.com")
    u = repo.get_or_create_user(_claims(sub="u2", email="x@corp.com"))
    assert u["access_status"] == "APPROVED"


# ---------------- complete_file idempotency ----------------
def _seed_pending_file(store, file_id="f1", uid="u1", size=100):
    store._data.setdefault("files", {})[file_id] = {
        "uid": uid, "sizeBytes": size, "status": "PENDING", "sessionId": "s1",
    }


_MD5 = "a" * 32


def test_complete_file_first_call_ok(store):
    _seed_pending_file(store)
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("f1", "u1", body) == "ok"
    assert store._data["files"]["f1"]["status"] == "COMPLETED"


def test_complete_file_retry_is_idempotent(store):
    _seed_pending_file(store)
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("f1", "u1", body) == "ok"
    # A retried completion must return "already" and NOT bump again.
    assert repo.complete_file("f1", "u1", body) == "already"


def test_complete_file_rejects_wrong_uid(store):
    _seed_pending_file(store, uid="u1")
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("f1", "someone-else", body) == ""


def test_complete_file_rejects_size_mismatch(store):
    _seed_pending_file(store, size=100)
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=999, md5=_MD5)
    assert repo.complete_file("f1", "u1", body) == ""


def test_complete_file_missing_returns_empty(store):
    body = FileComplete(sessionId="s1", driveFileId="drive1", bytes=100, md5=_MD5)
    assert repo.complete_file("nope", "u1", body) == ""


# ---------------- bump_session_progress ----------------
def test_bump_advances_and_completes(store):
    store._data.setdefault("sessions", {})["s1"] = {
        "uid": "u1", "fileCount": 2, "completedCount": 0, "status": "UPLOADING",
    }
    repo.bump_session_progress("s1")
    assert store._data["sessions"]["s1"]["completedCount"] == 1
    assert store._data["sessions"]["s1"]["status"] == "UPLOADING"
    repo.bump_session_progress("s1")
    assert store._data["sessions"]["s1"]["completedCount"] == 2
    assert store._data["sessions"]["s1"]["status"] == "COMPLETED"


# ---------------- unbounded erasure ----------------
def test_account_erasure_deletes_more_than_old_2000_record_cap(store):
    count = 2005
    store._data["users"] = {"u1": {"email": "erase@example.com"}}
    store._data["sessions"] = {
        f"s{i}": {"uid": "u1"} for i in range(count)
    }
    store._data["files"] = {
        f"f{i}": {"uid": "u1", "sessionId": f"s{i}"} for i in range(count)
    }
    store._data["devices"] = {
        f"d{i}": {"uid": "u1"} for i in range(count)
    }
    store._data["audit_logs"] = {
        "a1": {"uid": "u1", "action": "SESSION_CREATE"}
    }

    result = repo.delete_all_user_data("u1")

    assert result == {"sessions": count, "files": count, "devices": count}
    assert store._data["sessions"] == {}
    assert store._data["files"] == {}
    assert store._data["devices"] == {}
    assert store._data["users"] == {}
    # Explicit policy: append-only security audit facts survive account erasure.
    assert "a1" in store._data["audit_logs"]


def test_session_erasure_deletes_more_than_old_2000_file_cap(store):
    count = 2005
    store._data["sessions"] = {"s1": {"uid": "u1"}}
    store._data["files"] = {
        f"f{i}": {"uid": "u1", "sessionId": "s1"} for i in range(count)
    }

    assert repo.delete_session("s1") == count
    assert store._data["files"] == {}
    assert store._data["sessions"] == {}


def test_new_documents_include_schema_version(store):
    monkey_claims = _claims(sub="versioned")
    repo.get_or_create_user(monkey_claims)
    assert store._data["users"]["versioned"]["schemaVersion"] == repo.SCHEMA_VERSION
    nonce = repo.issue_nonce("versioned", "device-123")
    assert store._data["challenges"][nonce]["schemaVersion"] == repo.SCHEMA_VERSION
