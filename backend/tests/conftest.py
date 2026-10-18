import os
import sys

# Make test-only helpers (e.g. fake_firestore) importable by bare name.
sys.path.insert(0, os.path.dirname(__file__))

os.environ.setdefault("DEV_INSECURE_AUTH", "1")
os.environ.setdefault("GOOGLE_CLOUD_PROJECT", "test-project")
os.environ.setdefault("SERVICE_ACCOUNT_EMAIL", "test@test.iam.gserviceaccount.com")
os.environ.setdefault("SHARED_DRIVE_ID", "test-drive-id")

import pytest
from httpx import ASGITransport, AsyncClient

import fake_firestore
from app import audit, rate_limit
import repo_view as repo
from app.main import app


@pytest.fixture
def store(monkeypatch):
    """The in-memory Firestore double, with the new-account mail muted.

    A module that needs more (seeded users, a muted audit log, Drive stubs)
    overrides this with a fixture of the same name that takes `store` itself.
    """
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    return fake_firestore.install(monkeypatch)


@pytest.fixture
def audited(monkeypatch):
    """Every audit.record call, as a dict.

    `audit.record` writes through `.add()`, which the store double does not
    implement — and it swallows its own failures, so the row would simply not
    appear. Capturing the call is the suite's convention.
    """
    rows: list[dict] = []

    def _record(uid=None, device_id=None, **kw):
        rows.append({"uid": uid, "deviceId": device_id, **kw})

    monkeypatch.setattr(audit, "record", _record)
    return rows


@pytest.fixture(autouse=True)
def firestore_calls_carry_a_deadline():
    """Fails a test whose app code reached the store double without a timeout.

    Every Firestore round trip goes through a `repo/_base.py` helper that sets
    `timeout` and `retry` (TD-197); a raw `.get()` / `.stream()` / write would
    run on the client's 60 to 300 s defaults, past the gateway's 60 s.
    """
    fake_firestore.CALLS.clear()
    fake_firestore.UNTIMED.clear()
    yield
    untimed = list(fake_firestore.UNTIMED)
    assert not untimed, f"Firestore calls without a timeout (method, module, line): {untimed}"


@pytest.fixture(autouse=True)
def firebase_user_admin_is_offline(monkeypatch):
    """The Admin-SDK user calls (`google_auth.revoke_sessions`,
    `delete_auth_user`) never reach the network from a test: they succeed
    as no-ops unless a test replaces them to fail."""
    from app import google_auth

    monkeypatch.setattr(google_auth.fb_auth, "revoke_refresh_tokens", lambda uid, app=None: None)
    monkeypatch.setattr(google_auth.fb_auth, "delete_user", lambda uid, app=None: None)


@pytest.fixture(autouse=True)
def reset_rate_limits():
    """Token buckets are process-global singletons shared by every test.

    Without this, requests made by one test spend another test's budget and a
    suite that passes alone starts returning 429 depending on file order — the
    tight buckets (session_verify: burst 2) are exhausted in three calls.
    """
    buckets = [v for v in vars(rate_limit).values() if isinstance(v, rate_limit.TokenBucket)]
    for bucket in buckets:
        bucket._tokens.clear()
        bucket._updated.clear()
    yield


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
