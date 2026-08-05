"""notify.access_request: enqueues off-path, retries, idempotent, never raises."""
import threading

import pytest

from app import notify
from app.config import settings


class _Response:
    def __init__(self, status_code=200, text="", headers=None):
        self.status_code = status_code
        self.text = text
        self.headers = headers or {}


@pytest.fixture(autouse=True)
def _reset_notify_state():
    notify.reset_for_tests()
    yield
    notify.reset_for_tests()


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setattr(settings, "RESEND_API_KEY", "test-key")
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@indicvision.com>")
    monkeypatch.setattr(settings, "SUPPORT_EMAIL", "support@indicvision.com")


@pytest.fixture
def sent(monkeypatch):
    calls = []
    lock = threading.Lock()

    def fake_post(url, **kwargs):
        with lock:
            calls.append((url, kwargs))
        return _Response()

    monkeypatch.setattr(notify.requests, "post", fake_post)
    return calls


def test_disabled_without_api_key(monkeypatch, sent):
    monkeypatch.setattr(settings, "RESEND_API_KEY", "")
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@indicvision.com>")

    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()

    assert sent == []


def test_sends_actionable_mail_to_support(configured, sent):
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()

    assert len(sent) == 1
    url, kwargs = sent[0]
    payload = kwargs["json"]
    assert payload["to"] == ["support@indicvision.com"]
    assert "new@example.com" in payload["subject"]
    assert "uid-1" in payload["text"]
    assert kwargs["headers"]["Idempotency-Key"] == "access-request:uid-1"
    assert kwargs["timeout"] == notify._TIMEOUT_S


def test_duplicate_enqueue_is_idempotent(configured, sent):
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()
    assert len(sent) == 1


def test_send_failure_is_swallowed(configured, monkeypatch):
    def boom(url, **kwargs):
        raise RuntimeError("resend is down")

    monkeypatch.setattr(notify.requests, "post", boom)

    notify.access_request("uid-2", "other@example.com", None, None)
    notify.flush_for_tests()


def test_rejected_send_is_swallowed(configured, monkeypatch):
    monkeypatch.setattr(
        notify.requests, "post", lambda url, **kwargs: _Response(422, "invalid from address")
    )

    notify.access_request("uid-3", "third@example.com", None, "password")
    notify.flush_for_tests()


def test_retries_on_transient_status(configured, monkeypatch):
    attempts = {"n": 0}

    def flaky(url, **kwargs):
        attempts["n"] += 1
        if attempts["n"] < 3:
            return _Response(503, "unavailable")
        return _Response(200)

    monkeypatch.setattr(notify.requests, "post", flaky)
    monkeypatch.setattr(notify.time, "sleep", lambda *_: None)

    notify.access_request("uid-4", "retry@example.com", None, None)
    notify.flush_for_tests()
    assert attempts["n"] == 3
