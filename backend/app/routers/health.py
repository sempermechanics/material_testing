import logging
import time

from fastapi import APIRouter, HTTPException, Request

from .. import firestore_repo as repo
from .. import drive
from .. import observability as obs
from .. import rate_limit

log = logging.getLogger("indic")
router = APIRouter()


def _client_key(request: Request) -> str:
    """Best available caller identity for the unauthenticated health limiter.

    `request.client.host` behind API Gateway / the Cloud Run front end is the
    *proxy*, so keying on it alone puts every external caller in one bucket —
    one noisy client would then starve the load balancer's own probes. Trust the
    leftmost X-Forwarded-For entry, which the Google front end sets, and fall
    back to the socket peer when the header is absent (direct/local calls).
    """
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        first = forwarded.split(",", 1)[0].strip()
        if first:
            return first[:64]
    return request.client.host if request.client else "unknown"


@router.get("/healthz")
def healthz(request: Request):
    # Liveness only: process is up. Do not probe dependencies here — a slow
    # Firestore/Drive outage must not restart healthy instances.
    if not rate_limit.health_bucket.allow(_client_key(request)):
        raise HTTPException(429, "rate_limited")
    return {"ok": True}


@router.get("/readyz")
def readyz(request: Request):
    """Readiness: Firestore + Drive must answer within a bounded budget.

    Returns stable 503 detail codes (`firestore_unreachable`, `drive_unhealthy`,
    …) so load balancers and smoke checks can act without parsing messages.
    """
    if not rate_limit.health_bucket.allow(_client_key(request)):
        raise HTTPException(429, "rate_limited")
    started = time.perf_counter()
    try:
        repo.ping()
        drive.ping()
    except obs.DependencyError:
        raise
    except Exception as e:  # noqa: BLE001
        obs.log_event(
            log, logging.ERROR, "readyz_unexpected",
            outcome="error", errorCode="readyz_failed", dependency="unknown",
        )
        raise obs.DependencyError("readyz_failed", "unknown") from e
    latency_ms = round((time.perf_counter() - started) * 1000, 1)
    obs.log_event(
        log, logging.INFO, "readyz_ok",
        outcome="ok", latencyMs=latency_ms, dependency="firestore+drive",
    )
    return {"ok": True, "checks": {"firestore": "ok", "drive": "ok"}}
