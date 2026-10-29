"""Schema-driven fuzzing of the local app (readiness TE3-007, Schemathesis
approved by the Admin on 2026-10-28).

Every operation in the app's OpenAPI document is called with requests
Schemathesis builds from it, valid and invalid, against the in-process ASGI
app on the store double, signed in as the dev caller (`DEV_INSECURE_AUTH`,
conftest: an approved Semper admin), so the routes are reached past auth.

The property is "no unhandled failure": a 500 is a defect, and any other 5xx
must be a deliberate answer that carries a `detail` code (a Drive call failing
is 502 `drive_*`, for instance). Every outbound call is refused here: Drive is
an offline session, Cloud Tasks are off, and a socket connect raises, so a
route that reaches Google answers as it would with Google down.

Not fuzzed: `/v1/tasks/*` (Cloud Tasks OIDC only, no user path to them).

Fast and repeatable: a bounded number of examples per operation, generated
with `derandomize` (the same examples on every run), no example database.
"""
from __future__ import annotations

import json
import os
import socket
import tempfile

import pytest
import requests
import schemathesis
from hypothesis import HealthCheck, settings as hypothesis_settings
from hypothesis.configuration import set_hypothesis_home_dir

import fake_firestore
from app import audit, deps, drive, google_auth, notify
from app.config import settings
from app.main import app

# Hypothesis keeps caches under ./.hypothesis by default (created on first
# use, after collection); nothing of this run belongs in the checkout.
set_hypothesis_home_dir(os.path.join(tempfile.gettempdir(), "semper-backend-hypothesis"))

#: Examples per operation. ~70 operations, so a few hundred requests in all.
MAX_EXAMPLES = 8

schema = schemathesis.openapi.from_asgi("/openapi.json", app).exclude(path_regex=r"^/v1/tasks/")


class _OfflineSession:
    """Stands in for `drive.http()`: every Drive call fails as with no network."""

    def request(self, *_a, **_k):
        raise requests.ConnectionError("offline: no Drive in the schema fuzz")

    get = post = put = patch = delete = request


@pytest.fixture
def offline(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    # The dev caller's own profile, as `get_or_create_user` would have it; the
    # dev bypass hands the routes the profile without storing it.
    store._data["users"] = {deps._DEV_USER["uid"]: {
        k: v for k, v in deps._DEV_USER.items() if k != "uid"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(notify, "device_registered", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "http", lambda: _OfflineSession())
    monkeypatch.setattr(google_auth, "drive_access_token", lambda: "tok")
    monkeypatch.setattr(settings, "TASKS_QUEUE", "")

    def no_network(*_a, **_k):
        raise OSError("offline: no network in the schema fuzz")

    monkeypatch.setattr(socket.socket, "connect", no_network)
    return store


def _no_unhandled_failure(response) -> None:
    status = response.status_code
    assert status != 500, f"500 from {response.request.method} {response.request.url}: {response.text[:500]}"
    if status > 500:
        body = json.loads(response.text or "{}")
        assert isinstance(body, dict) and body.get("detail"), \
            f"{status} with no detail code: {response.text[:500]}"


@schema.parametrize()
@hypothesis_settings(
    max_examples=MAX_EXAMPLES,
    derandomize=True,
    database=None,
    deadline=None,
    suppress_health_check=list(HealthCheck),
)
def test_no_request_the_schema_allows_or_refuses_is_an_unhandled_failure(case, offline):
    response = case.call()
    _no_unhandled_failure(response)
