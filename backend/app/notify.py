"""Outbound notification mail — off the request path, with retry and idempotency.

Cloud Run only guarantees CPU while a request is in flight, so the send runs on a
daemon worker that is kicked before the response returns. The request path only
enqueues; Resend failures never alter the caller's HTTP status.

Two kinds, each with its own sender and de-duplication key: an access request
to support (`access_request`, once per uid, from `NOTIFY_FROM`), and a new
device on an account to its holder (`device_registered`, once per uid and
device, from `NOTIFY_SECURITY_FROM`; TD-295). No address is ever logged
(TD-242).
"""
from __future__ import annotations

import logging
import queue
import threading
import time
from dataclasses import dataclass

import requests

from . import apps, backoff
from . import observability as obs
from .config import settings

log = logging.getLogger("notify")

_RESEND_URL = "https://api.resend.com/emails"
_TIMEOUT_S = 5
_MAX_ATTEMPTS = 4
_RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})

# Dedup key → last successful send epoch. Prevents duplicate mails if
# get_or_create_user races or a worker retries after a partial success. The
# key is the mail's idempotency key, so each kind de-duplicates on its own.
_sent_lock = threading.Lock()
_sent_keys: dict[str, float] = {}
_SENT_TTL_S = 7 * 24 * 3600

_work: queue.Queue[_Job | None] = queue.Queue()
_worker_started = False
_worker_lock = threading.Lock()


@dataclass(frozen=True)
class _Job:
    """One mail, built when it is enqueued. `idempotency_key` is also the
    de-duplication key."""
    sender: str
    to: str
    subject: str
    text: str
    idempotency_key: str


def _enabled(sender: str | None = None) -> bool:
    return bool(settings.RESEND_API_KEY and (sender if sender is not None else settings.NOTIFY_FROM))


def _prune_sent(now: float) -> None:
    stale = [key for key, ts in _sent_keys.items() if now - ts > _SENT_TTL_S]
    for key in stale:
        del _sent_keys[key]


def _already_sent(key: str) -> bool:
    now = time.monotonic()
    with _sent_lock:
        _prune_sent(now)
        return key in _sent_keys


def _mark_sent(key: str) -> None:
    with _sent_lock:
        _sent_keys[key] = time.monotonic()


def _ensure_worker() -> None:
    global _worker_started
    with _worker_lock:
        if _worker_started:
            return
        thread = threading.Thread(target=_worker_loop, name="notify-resend", daemon=True)
        thread.start()
        _worker_started = True


def _worker_loop() -> None:
    while True:
        job = _work.get()
        if job is None:
            return
        try:
            _deliver(job)
        except Exception:  # noqa: BLE001 - worker must never die on one bad job
            obs.report_exception(log, error_code="notify_worker_crash", dependency="resend")
        finally:
            _work.task_done()


def _retry_delay(attempt: int, response: requests.Response | None) -> float:
    raw = response.headers.get("Retry-After") if response is not None else None
    return backoff.retry_delay(attempt, raw, floor=0.5)


def _deliver(job: _Job) -> None:
    if _already_sent(job.idempotency_key):
        obs.log_event(
            log, logging.INFO, "notify_skip_duplicate",
            outcome="ok", dependency="resend", errorCode="already_sent",
        )
        return
    if not _enabled(job.sender):
        obs.log_event(
            log, logging.INFO, "notify_disabled",
            outcome="ok", dependency="resend", errorCode="notify_disabled",
        )
        return

    payload = {
        "from": job.sender,
        "to": [job.to],
        "subject": job.subject,
        "text": job.text,
        "headers": {"Idempotency-Key": job.idempotency_key},
    }

    for attempt in range(_MAX_ATTEMPTS):
        try:
            response = requests.post(
                _RESEND_URL,
                headers={
                    "Authorization": f"Bearer {settings.RESEND_API_KEY}",
                    "Idempotency-Key": job.idempotency_key,
                },
                json=payload,
                timeout=_TIMEOUT_S,
            )
        except requests.RequestException:
            obs.log_event(
                log, logging.WARNING, "notify_send_failed",
                outcome="error", dependency="resend", errorCode="notify_transport",
                attempt=attempt + 1, maxAttempts=_MAX_ATTEMPTS,
            )
            if attempt == _MAX_ATTEMPTS - 1:
                return
            time.sleep(_retry_delay(attempt, None))
            continue

        if response.status_code < 400:
            _mark_sent(job.idempotency_key)
            obs.log_event(
                log, logging.INFO, "notify_sent",
                outcome="ok", dependency="resend",
                attempt=attempt + 1, httpStatus=response.status_code,
            )
            return

        retryable = response.status_code in _RETRY_STATUSES
        obs.log_event(
            log, logging.WARNING, "notify_send_rejected",
            outcome="error", dependency="resend", errorCode="notify_rejected",
            attempt=attempt + 1, maxAttempts=_MAX_ATTEMPTS,
            httpStatus=response.status_code,
        )
        if not retryable or attempt == _MAX_ATTEMPTS - 1:
            return
        time.sleep(_retry_delay(attempt, response))


def _enqueue(job: _Job) -> None:
    """Hand `job` to the worker unless its kind is off or it already went.
    Swallows enqueue failures: a mail must never fail the request."""
    if not _enabled(job.sender):
        obs.log_event(
            log, logging.INFO, "notify_disabled",
            outcome="ok", dependency="resend", errorCode="notify_disabled",
        )
        return
    if _already_sent(job.idempotency_key):
        return
    try:
        _ensure_worker()
        _work.put_nowait(job)
    except Exception:  # noqa: BLE001 - notification must not fail the request
        obs.log_event(
            log, logging.WARNING, "notify_enqueue_failed",
            outcome="error", dependency="resend", errorCode="notify_enqueue",
        )


def access_request(uid: str, email, display_name, provider) -> None:
    """Enqueue a support mail that a new account is PENDING.

    Called once per account from get_or_create_user. Returns immediately; the
    worker handles transport. Swallows enqueue failures so sign-in still yields
    a clean 403 when mail is down.
    """
    text = (
        "A new Semper account is waiting for approval.\n\n"
        f"Account: {email or '(no email)'}\n"
        f"Name: {display_name or '(none)'}\n"
        f"Sign-in: {provider or '(unknown)'}\n"
        f"User id: {uid}\n\n"
        "To approve, open Settings -> Access requests in the app, or call:\n"
        f"  POST /v1/admin/users/{uid}/approve\n"
    )
    _enqueue(_Job(
        sender=settings.NOTIFY_FROM,
        to=settings.SUPPORT_EMAIL,
        subject=f"Semper access request — {email or uid}",
        text=text,
        idempotency_key=f"access-request:{uid}",
    ))


#: How the device mail names the app the device registered for.
_APP_NAMES = {apps.SEMPER: "Semper", apps.MATERIAL_TESTING: "Material Testing"}


def device_registered(uid: str, address: str, device_id: str, app: str, *,
                      model: str = "") -> None:
    """Enqueue a mail to an account's holder that a new device registered.

    `address` is the account's verified address (`repo.verified_address`);
    without one nothing is sent. Once per (uid, device): a key heal of the
    same device is not a new device, and the route does not call this for
    it. Off while `NOTIFY_SECURITY_FROM` is unset (TD-295).
    """
    if not address:
        return
    name = _APP_NAMES.get(app, "Semper")
    text = (
        f"A new device was registered to your {name} account.\n\n"
        f"Device: {model or '(model not reported)'}\n"
        f"Device id: {device_id}\n"
        f"When: {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}\n\n"
        "If this was you, there is nothing to do.\n\n"
        "If it was not, someone may have signed in as you: change your password "
        "or secure your Google account, then contact "
        f"{settings.SUPPORT_EMAIL} so the device can be removed.\n"
    )
    _enqueue(_Job(
        sender=settings.NOTIFY_SECURITY_FROM,
        to=address,
        subject=f"New device on your {name} account",
        text=text,
        idempotency_key=f"device-registered:{uid}:{device_id}",
    ))


def flush_for_tests(timeout_s: float = 2.0) -> None:
    """Block until the queue drains (tests only)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if _work.unfinished_tasks == 0:
            return
        time.sleep(0.01)


def reset_for_tests() -> None:
    """Clear idempotency memory between tests."""
    with _sent_lock:
        _sent_keys.clear()
