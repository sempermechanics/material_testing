"""PII-safe structured operational logging and API error types."""
from __future__ import annotations

import json
import logging
from contextvars import ContextVar
from datetime import datetime, timezone
from typing import Any

_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)
_uid: ContextVar[str | None] = ContextVar("uid", default=None)
_device_id: ContextVar[str | None] = ContextVar("device_id", default=None)

_ALLOWED = frozenset({
    "requestId", "uid", "deviceId", "event", "outcome", "errorCode",
    "dependency", "latencyMs", "method", "path", "status", "attempt",
    "maxAttempts", "httpStatus", "count", "stage",
})


class DependencyError(RuntimeError):
    """Known upstream failure with a stable, non-sensitive API code."""

    def __init__(self, code: str, dependency: str, status_code: int = 503):
        super().__init__(code)
        self.code = code
        self.dependency = dependency
        self.status_code = status_code


def bind_request(request_id: str):
    return _request_id.set(request_id)


def bind_uid(uid: str | None) -> None:
    _uid.set(uid)


def bind_device(device_id: str | None) -> None:
    _device_id.set(device_id)


def reset_request(token) -> None:
    _request_id.reset(token)
    _uid.set(None)
    _device_id.set(None)


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _safe_value(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float)):
        return value
    text = str(value)
    # Identifiers used here are validated opaque IDs. Bound length prevents a
    # malformed dependency response from creating an oversized log entry.
    return text[:256]


def event_payload(event: str, **fields: Any) -> dict:
    payload = {
        "timestamp": utc_timestamp(),
        "event": event,
        "requestId": _request_id.get(),
        "uid": _uid.get(),
        "deviceId": _device_id.get(),
    }
    payload.update({key: _safe_value(value) for key, value in fields.items() if key in _ALLOWED})
    return {key: value for key, value in payload.items() if value is not None}


def log_event(logger: logging.Logger, level: int, event: str, **fields: Any) -> None:
    logger.log(level, json.dumps(event_payload(event, **fields), separators=(",", ":")))


def report_exception(
    logger: logging.Logger,
    *,
    error_code: str,
    dependency: str | None = None,
) -> None:
    """Emit a sanitized event Cloud Error Reporting can ingest without a trace.

    The explicit ReportedErrorEvent type enables grouping while deliberately
    omitting exception messages and stack traces from Cloud Logging.
    """
    payload = event_payload(
        "unhandled_exception",
        outcome="error",
        errorCode=error_code,
        dependency=dependency,
    )
    payload.update({
        "@type": "type.googleapis.com/google.devtools.clouderrorreporting.v1beta1.ReportedErrorEvent",
        "serviceContext": {"service": "indic-api"},
        "message": error_code,
    })
    logger.error(json.dumps(payload, separators=(",", ":")))
