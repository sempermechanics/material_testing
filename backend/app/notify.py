"""Outbound notification mail — off the request path, with retry and idempotency.

Cloud Run only guarantees CPU while a request is in flight, so the send runs on a
daemon worker that is kicked before the response returns. The request path only
enqueues; Resend failures never alter the caller's HTTP status.
"""
from __future__ import annotations

import logging
import queue
import threading
import time
from dataclasses import dataclass

import requests

from . import backoff
from . import observability as obs
from .config import settings

log = logging.getLogger("notify")

_RESEND_URL = "https://api.resend.com/emails"
_TIMEOUT_S = 5
_MAX_ATTEMPTS = 4
_RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})

# uid → last successful enqueue/send epoch. Prevents duplicate mails if
# get_or_create_user races or a worker retries after a partial success.
_sent_lock = threading.Lock()
_sent_uids: dict[str, float] = {}
_SENT_TTL_S = 7 * 24 * 3600

_work: queue.Queue[_Job | None] = queue.Queue()
_worker_started = False
_worker_lock = threading.Lock()


@dataclass(frozen=True)
class _Job:
    uid: str
    email: str | None
    display_name: str | None
    provider: str | None
    idempotency_key: str


def _enabled() -> bool:
    return bool(settings.RESEND_API_KEY and settings.NOTIFY_FROM)


def _prune_sent(now: float) -> None:
    stale = [uid for uid, ts in _sent_uids.items() if now - ts > _SENT_TTL_S]
    for uid in stale:
        del _sent_uids[uid]


def _already_sent(uid: str) -> bool:
    now = time.monotonic()
    with _sent_lock:
        _prune_sent(now)
        return uid in _sent_uids


def _mark_sent(uid: str) -> None:
    with _sent_lock:
        _sent_uids[uid] = time.monotonic()


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
    if _already_sent(job.uid):
        obs.log_event(
            log, logging.INFO, "notify_skip_duplicate",
            outcome="ok", dependency="resend", errorCode="already_sent",
        )
        return
    if not _enabled():
        obs.log_event(
            log, logging.INFO, "notify_disabled",
            outcome="ok", dependency="resend", errorCode="notify_disabled",
        )
        return

    body = (
        "A new Semper account is waiting for approval.\n\n"
        f"Account: {job.email or '(no email)'}\n"
        f"Name: {job.display_name or '(none)'}\n"
        f"Sign-in: {job.provider or '(unknown)'}\n"
        f"User id: {job.uid}\n\n"
        "To approve, open Settings -> Access requests in the app, or call:\n"
        f"  POST /v1/admin/users/{job.uid}/approve\n"
    )
    payload = {
        "from": settings.NOTIFY_FROM,
        "to": [settings.SUPPORT_EMAIL],
        "subject": f"Semper access request — {job.email or job.uid}",
        "text": body,
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
            _mark_sent(job.uid)
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


def access_request(uid: str, email, display_name, provider) -> None:
    """Enqueue a support mail that a new account is PENDING.

    Called once per account from get_or_create_user. Returns immediately; the
    worker handles transport. Swallows enqueue failures so sign-in still yields
    a clean 403 when mail is down.
    """
    if not _enabled():
        obs.log_event(
            log, logging.INFO, "notify_disabled",
            outcome="ok", dependency="resend", errorCode="notify_disabled",
        )
        return
    if _already_sent(uid):
        return
    try:
        _ensure_worker()
        _work.put_nowait(_Job(
            uid=uid,
            email=email,
            display_name=display_name,
            provider=provider,
            idempotency_key=f"access-request:{uid}",
        ))
    except Exception:  # noqa: BLE001 - notification must not fail the request
        obs.log_event(
            log, logging.WARNING, "notify_enqueue_failed",
            outcome="error", dependency="resend", errorCode="notify_enqueue",
        )


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
        _sent_uids.clear()
