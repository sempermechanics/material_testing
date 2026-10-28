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
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@sempermechanics.com>")
    monkeypatch.setattr(settings, "SUPPORT_EMAIL", "support@sempermechanics.com")


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
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@sempermechanics.com>")

    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()

    assert sent == []


def test_sends_actionable_mail_to_support(configured, sent):
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()

    assert len(sent) == 1
    url, kwargs = sent[0]
    payload = kwargs["json"]
    assert payload["to"] == ["support@sempermechanics.com"]
    assert "new@example.com" in payload["subject"]
    assert "uid-1" in payload["text"]
    assert payload["subject"] == "semper access request — new@example.com"
    assert payload["text"].startswith("A new semper account is waiting for approval.")
    # The queue's one name on both desks (readiness AE3-117).
    assert "Settings → Access requests in the app" in payload["text"]
    assert "operator console → Access requests" in payload["text"]
    assert "Semper" not in payload["subject"] + payload["text"]
    assert kwargs["headers"]["Idempotency-Key"] == "access-request:uid-1"
    assert kwargs["timeout"] == notify._TIMEOUT_S


def test_duplicate_enqueue_is_idempotent(configured, sent):
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()
    notify.access_request("uid-1", "new@example.com", "New User", "google.com")
    notify.flush_for_tests()
    assert len(sent) == 1


def test_send_failure_is_swallowed(configured, monkeypatch):
    """A dead mail provider must not break sign-in, and must not kill the worker
    thread — every later notification would be silently lost."""
    attempts = {"n": 0}

    def boom(url, **kwargs):
        attempts["n"] += 1
        raise RuntimeError("resend is down")

    monkeypatch.setattr(notify.requests, "post", boom)

    notify.access_request("uid-2", "other@example.com", None, None)
    notify.flush_for_tests()

    assert attempts["n"] >= 1, "no send was attempted at all"
    # The worker survived: a subsequent notification is still processed.
    sent_after = []
    monkeypatch.setattr(
        notify.requests, "post",
        lambda url, **kwargs: sent_after.append(kwargs) or _Response(200, "{}"),
    )
    notify.access_request("uid-2b", "later@example.com", None, None)
    notify.flush_for_tests()
    assert len(sent_after) == 1, "worker died on the failing job"


def test_rejected_send_is_swallowed(configured, monkeypatch):
    """A 4xx is permanent — it must be given up on, not retried forever."""
    attempts = {"n": 0}

    def reject(url, **kwargs):
        attempts["n"] += 1
        return _Response(422, "invalid from address")

    monkeypatch.setattr(notify.requests, "post", reject)

    notify.access_request("uid-3", "third@example.com", None, "password")
    notify.flush_for_tests()

    assert attempts["n"] == 1, f"a permanent 422 was retried {attempts['n']} times"


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


# ---------------- a new device on the account (TD-295) ----------------
@pytest.fixture
def security_mail(configured, monkeypatch):
    monkeypatch.setattr(settings, "NOTIFY_SECURITY_FROM", "Semper security <security@sempermechanics.com>")


def test_a_new_device_mails_its_holder(security_mail, sent, caplog):
    caplog.set_level("DEBUG")
    notify.device_registered("uid-1", "holder@uni.edu", "phone-1", "semper", model="Pixel 8")
    notify.flush_for_tests()

    assert len(sent) == 1
    _, kwargs = sent[0]
    payload = kwargs["json"]
    assert payload["to"] == ["holder@uni.edu"]
    assert payload["from"] == "Semper security <security@sempermechanics.com>"
    assert "Pixel 8" in payload["text"]
    # One word for it to the holder, the app's: phone (readiness AE3-101); the
    # product name lower-case (ADDENDUM-4); the id keeps the app's "Device ID".
    assert payload["subject"] == "New phone on your semper account"
    assert payload["text"].startswith("A new phone was signed in to your semper account.")
    assert "Phone: Pixel 8\n" in payload["text"] and "Device ID: phone-1\n" in payload["text"]
    assert "so the phone can be removed" in payload["text"]
    assert "device" not in payload["text"].replace("Device ID", "").lower()
    assert "Semper" not in payload["subject"] + payload["text"]
    assert kwargs["headers"]["Idempotency-Key"] == "device-registered:uid-1:phone-1"
    # TD-242: no address in the logs.
    assert "holder@uni.edu" not in caplog.text


def test_the_same_device_mails_once_and_another_device_again(security_mail, sent):
    notify.device_registered("uid-1", "holder@uni.edu", "phone-1", "semper")
    notify.device_registered("uid-1", "holder@uni.edu", "phone-1", "semper")
    notify.flush_for_tests()
    notify.device_registered("uid-1", "holder@uni.edu", "phone-2", "semperclassroom")
    notify.flush_for_tests()

    assert [kw["headers"]["Idempotency-Key"] for _, kw in sent] == [
        "device-registered:uid-1:phone-1", "device-registered:uid-1:phone-2"]
    assert sent[1][1]["json"]["subject"] == "New phone on your semper Classroom account"


def test_the_device_mail_names_the_old_short_name_as_semper_classroom(security_mail, sent):
    """A value stored before ADR-030 names the app by its name today."""
    notify.device_registered("uid-1", "holder@uni.edu", "phone-3", "materialtesting")
    notify.flush_for_tests()

    assert sent[0][1]["json"]["subject"] == "New phone on your semper Classroom account"


def test_the_device_mail_has_its_own_dedup_from_the_access_request(security_mail, sent):
    notify.access_request("uid-1", "holder@uni.edu", None, None)
    notify.device_registered("uid-1", "holder@uni.edu", "phone-1", "semper")
    notify.flush_for_tests()

    assert sorted(kw["headers"]["Idempotency-Key"] for _, kw in sent) == [
        "access-request:uid-1", "device-registered:uid-1:phone-1"]


def test_no_device_mail_without_its_sender_or_an_address(configured, sent, monkeypatch):
    monkeypatch.setattr(settings, "NOTIFY_SECURITY_FROM", "")
    notify.device_registered("uid-1", "holder@uni.edu", "phone-1", "semper")
    monkeypatch.setattr(settings, "NOTIFY_SECURITY_FROM", "security@sempermechanics.com")
    notify.device_registered("uid-1", "", "phone-1", "semper")
    notify.flush_for_tests()

    assert sent == []
