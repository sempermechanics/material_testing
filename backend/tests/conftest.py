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
from app import audit, firestore_repo as repo, rate_limit
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
