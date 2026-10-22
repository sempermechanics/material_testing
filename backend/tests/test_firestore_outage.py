"""TD-250: a Firestore outage on any route answers 503 `firestore_unreachable`
with `Retry-After`, as `/readyz` does, instead of 500 `internal_error`.

The outage reaches the edge middleware as the exception api-core raised once
the repo helper's retry gave up (`repo/_base.py`): `UNAVAILABLE` or
`DEADLINE_EXCEEDED` raw, or a `RetryError` around one. Any other failure stays
an opaque 500.
"""
import json
import logging

import fake_firestore
import pytest
from google.api_core import exceptions as gexc

import repo_view as repo
from app import audit
from app.config import settings
from app.deps import DEPENDENCY_RETRY_AFTER_SECONDS
from app.observability import DependencyError

OUTAGES = {
    "unavailable": lambda: gexc.ServiceUnavailable("firestore down"),
    "deadline": lambda: gexc.DeadlineExceeded("slow"),
    "retry_gave_up": lambda: gexc.RetryError(
        "Timeout of 10.0s exceeded", gexc.ServiceUnavailable("firestore down"),
    ),
    "retry_gave_up_on_deadline": lambda: gexc.RetryError(
        "Timeout of 10.0s exceeded", gexc.DeadlineExceeded("slow"),
    ),
}


def _failing_listing(monkeypatch, error: Exception) -> None:
    """`GET /v1/sessions` with its first Firestore read failing as `error`."""
    fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)

    def listing(*a, **k):
        raise error

    repo.patch(monkeypatch, "list_user_sessions", listing)


@pytest.mark.asyncio
@pytest.mark.parametrize("on_cloud_run", [True, False], ids=["cloud_run", "local"])
@pytest.mark.parametrize("make_error", OUTAGES.values(), ids=OUTAGES.keys())
async def test_a_firestore_outage_answers_503_with_retry_after(
    client, monkeypatch, caplog, make_error, on_cloud_run,
):
    monkeypatch.setattr(settings, "ON_CLOUD_RUN", on_cloud_run)
    _failing_listing(monkeypatch, make_error())

    with caplog.at_level(logging.INFO, logger="semper"):
        r = await client.get("/v1/sessions")

    assert r.status_code == 503
    assert r.json() == {"detail": "firestore_unreachable"}
    assert r.headers["retry-after"] == str(DEPENDENCY_RETRY_AFTER_SECONDS) == "30"
    # It still leaves through the edge: security headers and a request id.
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-request-id"]
    lines = [json.loads(rec.message) for rec in caplog.records if rec.name in ("semper", "semper.access")]
    failure = [e for e in lines if e.get("event") == "dependency_failure"]
    assert failure and failure[-1]["errorCode"] == "firestore_unreachable"
    assert failure[-1]["dependency"] == "firestore" and failure[-1]["status"] == 503
    # Not reported as an unexpected crash: that is the 500 path's line.
    assert not [e for e in lines if e.get("event") == "unhandled_exception"]
    access = [e for e in lines if e.get("event") == "http_access"]
    assert access[-1]["status"] == 503 and access[-1]["outcome"] == "server_error"


@pytest.mark.asyncio
@pytest.mark.parametrize("error", [
    ValueError("secret internals"),
    gexc.InternalServerError("boom"),
    gexc.RetryError("Timeout of 10.0s exceeded", gexc.InternalServerError("boom")),
    gexc.RetryError("Timeout of 10.0s exceeded", None),
], ids=["value_error", "internal", "retry_gave_up_on_internal", "retry_without_cause"])
async def test_anything_else_still_answers_an_opaque_500(client, monkeypatch, error):
    monkeypatch.setattr(settings, "ON_CLOUD_RUN", True)
    _failing_listing(monkeypatch, error)

    r = await client.get("/v1/sessions")

    assert r.status_code == 500
    assert r.json() == {"detail": "internal_error"}
    assert "secret" not in r.text and "retry-after" not in r.headers


@pytest.mark.asyncio
async def test_anything_else_still_raises_locally(client, monkeypatch):
    """Off Cloud Run the real failure still reaches pytest and debuggers."""
    monkeypatch.setattr(settings, "ON_CLOUD_RUN", False)
    _failing_listing(monkeypatch, ValueError("real failure"))

    with pytest.raises(ValueError, match="real failure"):
        await client.get("/v1/sessions")


@pytest.mark.asyncio
async def test_readyz_outage_carries_the_same_retry_after(client, monkeypatch):
    """`/readyz` answers its 503 through the same handler, so the hint matches."""
    def unreachable():
        raise DependencyError("firestore_unreachable", "firestore")

    repo.patch(monkeypatch, "ping", unreachable)

    r = await client.get("/readyz")

    assert r.status_code == 503
    assert r.json() == {"detail": "firestore_unreachable"}
    assert r.headers["retry-after"] == "30"
