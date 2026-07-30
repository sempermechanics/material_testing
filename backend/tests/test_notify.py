"""notify.access_request: sends when configured, stays silent otherwise, never raises."""
import pytest

from app import notify
from app.config import settings


class _Response:
    def __init__(self, status_code=200, text=""):
        self.status_code = status_code
        self.text = text


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setattr(settings, "RESEND_API_KEY", "test-key")
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@indicvision.com>")
    monkeypatch.setattr(settings, "SUPPORT_EMAIL", "support@indicvision.com")


@pytest.fixture
def sent(monkeypatch):
    calls = []

    def fake_post(url, **kwargs):
        calls.append((url, kwargs))
        return _Response()

    monkeypatch.setattr(notify.requests, "post", fake_post)
    return calls


def test_disabled_without_api_key(monkeypatch, sent):
    monkeypatch.setattr(settings, "RESEND_API_KEY", "")
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@indicvision.com>")

    notify.access_request("uid-1", "new@example.com", "New User", "google.com")

    assert sent == []


def test_sends_actionable_mail_to_support(configured, sent):
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")

    assert len(sent) == 1
    url, kwargs = sent[0]
    payload = kwargs["json"]
    assert payload["to"] == ["support@indicvision.com"]
    assert "new@example.com" in payload["subject"]
    # The uid is what makes the mail actionable — it is the admin approve key.
    assert "uid-1" in payload["text"]
    assert "new@example.com" in payload["text"]
    assert kwargs["timeout"] == notify._TIMEOUT_S


def test_send_failure_is_swallowed(configured, monkeypatch):
    def boom(url, **kwargs):
        raise RuntimeError("resend is down")

    monkeypatch.setattr(notify.requests, "post", boom)

    # Must return normally: the caller is mid-sign-in and owes the user a 403,
    # not a 500.
    notify.access_request("uid-2", "other@example.com", None, None)


def test_rejected_send_is_swallowed(configured, monkeypatch):
    monkeypatch.setattr(
        notify.requests, "post", lambda url, **kwargs: _Response(422, "invalid from address")
    )

    notify.access_request("uid-3", "third@example.com", None, "password")
