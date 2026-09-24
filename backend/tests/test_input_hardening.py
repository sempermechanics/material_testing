"""Phase 1 hardening: cursor-token path escapes, limiter keying and eviction,
and the ordering of the download audit write against its rate-limit check."""
import time

import pytest
from fastapi import Request

from app import audit, drive, main, rate_limit
from app.drive import _escape_q_value
from app.rate_limit import TokenBucket

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode


@pytest.fixture
def store(store, monkeypatch):
    s = store
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    return s


# --- cursor tokens must not address a Firestore path -------------------------
#
# page_token is fed to CollectionReference.document(), which accepts a
# slash-separated path. Unvalidated, "a/b" raised inside the client and the
# generic handler turned it into an opaque 500; "a/b/c" resolved into a
# different subcollection entirely.

@pytest.mark.parametrize("bad", ["a/b", "a/b/c", "../users/other", "x\\y"])
async def test_session_page_token_rejects_path_separators(store, client, bad):
    r = await client.get("/v1/sessions", params={"page_token": bad})
    assert r.status_code == 422, f"{bad!r} was not rejected"


@pytest.mark.parametrize("bad", ["a/b", "sessions/s1"])
async def test_admin_page_token_rejects_path_separators(store, client, bad):
    r = await client.get("/v1/admin/users", params={"page_token": bad})
    assert r.status_code == 422


async def test_empty_page_token_still_means_first_page(store, client):
    """The guard must not break the default: "" is a valid "start at the top"."""
    r = await client.get("/v1/sessions", params={"page_token": ""})
    assert r.status_code == 200
    assert r.json()["page"]["nextPageToken"] is None


async def test_admin_status_filter_is_an_allowlist(store, client):
    """An arbitrary string used to reach Firestore's where() on every call."""
    assert (await client.get("/v1/admin/users", params={"status": "x" * 5000})).status_code == 422
    assert (await client.get("/v1/admin/users", params={"status": "PENDING"})).status_code == 200
    assert (await client.get("/v1/admin/users", params={"status": ""})).status_code == 200


# --- limiter keying and eviction ---------------------------------------------

def _request(headers: dict, peer: str = "10.0.0.1") -> Request:
    scope = {
        "type": "http",
        "method": "GET",
        "path": "/healthz",
        "headers": [(k.lower().encode(), v.encode()) for k, v in headers.items()],
        "client": (peer, 1234),
    }
    return Request(scope)


def test_client_key_prefers_forwarded_for_over_proxy_peer():
    """Behind the gateway, request.client.host is the proxy — keying on it puts
    every external caller in one bucket, so one client starves the LB probes."""
    key = main._client_key(_request({"x-forwarded-for": "203.0.113.9, 70.41.3.18"}))
    assert key == "203.0.113.9"


def test_client_key_falls_back_to_peer_without_the_header():
    assert main._client_key(_request({}, peer="192.0.2.7")) == "192.0.2.7"


def test_client_key_is_length_bounded():
    """The header is attacker-controlled; an unbounded value becomes a dict key."""
    assert len(main._client_key(_request({"x-forwarded-for": "a" * 10_000}))) <= 64


def test_bucket_evicts_fully_refilled_keys(monkeypatch):
    """Both dicts previously grew for the lifetime of the instance."""
    bucket = TokenBucket(rate_per_sec=10.0, burst=10.0)  # idle TTL = 1s
    for i in range(500):
        assert bucket.allow(f"key-{i}")
    assert len(bucket._updated) == 500

    # Jump past the idle TTL so every key has refilled to full.
    now = time.monotonic()
    monkeypatch.setattr(rate_limit.time, "monotonic", lambda: now + 60.0)
    assert bucket.allow("fresh")
    assert len(bucket._updated) == 1, "stale keys were not pruned"
    assert len(bucket._tokens) == 1


def test_eviction_does_not_hand_back_budget_early():
    """Pruning is only safe for keys that have refilled — a throttled key must
    stay throttled until its tokens have genuinely regenerated."""
    bucket = TokenBucket(rate_per_sec=1.0, burst=2.0)
    assert bucket.allow("u") and bucket.allow("u")
    assert not bucket.allow("u")          # exhausted
    for i in range(200):                  # churn other keys to trigger a prune
        bucket.allow(f"other-{i}")
    assert not bucket.allow("u"), "prune reset a throttled key's budget"


# --- download: limiter must run before the audit write -----------------------

async def test_rate_limited_download_writes_no_audit_row(store, client, monkeypatch):
    """audit.record is a Firestore .add(); limiting after it charged a write per
    rejected request and logged a FILE_DOWNLOAD that never happened."""
    store._data["files"] = {"f1": {"uid": DEV_UID, "driveFileId": "d1", "name": "a.zip"}}
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    recorded = []
    monkeypatch.setattr(audit, "record", lambda *a, **k: recorded.append(k))
    monkeypatch.setattr(rate_limit.download_bucket, "allow", lambda key: False)

    r = await client.get("/v1/files/f1/content")
    assert r.status_code == 429
    assert recorded == [], "audit row written for a rejected download"


# --- Drive query escaping ----------------------------------------------------

def test_q_escape_handles_trailing_backslash():
    """Escaping only the quote left a trailing backslash free to consume the
    closing quote, letting a crafted name alter the query."""
    assert _escape_q_value("x\\") == "x\\\\"
    assert _escape_q_value("o'brien") == "o\\'brien"
    assert _escape_q_value("x\\' or '1'='1") == "x\\\\\\' or \\'1\\'=\\'1"
