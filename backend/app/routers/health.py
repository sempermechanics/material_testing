import contextvars
import logging
import time
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as ProbeTimeout

from fastapi import APIRouter, Request

from .. import repo
from .. import drive, errors
from .. import observability as obs
from .. import rate_limit

log = logging.getLogger("semper")
router = APIRouter()

#: Each probe's own deadline: the Firestore read (`repo.ping`, one attempt, no
#: retry) and Drive's one GET. They run in series.
PROBE_TIMEOUT_S = repo.PING_TIMEOUT_S
#: The whole check. The uptime check gives up at 10 s
#: (ops/monitoring/uptime/readyz.json) and the deploy smoke at 15 s, so a slow
#: but up dependency answers 503 with its code before either times out
#: (readiness TE3-010). Leaves room for a Drive token refresh after both probes.
READYZ_BUDGET_S = 8.0
#: Where the probes run, so the request can stop waiting at the budget. Two
#: workers: a probe stuck past the budget ends at its own deadline, and the
#: per-IP limit above bounds how many queue behind it.
_probes = ThreadPoolExecutor(max_workers=2, thread_name_prefix="readyz")
#: The answer for a probe still running at the budget, by the step it reached.
_OVER_BUDGET = {
    "queued": (errors.READYZ_FAILED, "unknown"),
    "firestore": (errors.FIRESTORE_UNREACHABLE, "firestore"),
    "drive": (errors.DRIVE_UNREACHABLE, "drive"),
}


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
    rate_limit.enforce(rate_limit.health_bucket, _client_key(request))
    return {"ok": True}


def _probe(stage: list[str]) -> None:
    """Firestore, then Drive, as before; `stage` says which one is running."""
    stage[0] = "firestore"
    repo.ping()
    stage[0] = "drive"
    drive.ping()


@router.get("/readyz")
def readyz(request: Request):
    """Readiness: Firestore + Drive must answer within a bounded budget.

    Returns stable 503 detail codes (`firestore_unreachable`, `drive_unhealthy`,
    …) so load balancers and smoke checks can act without parsing messages.
    """
    rate_limit.enforce(rate_limit.health_bucket, _client_key(request))
    started = time.perf_counter()
    stage = ["queued"]
    # The request's log context (request id) goes with the probes.
    running = _probes.submit(contextvars.copy_context().run, _probe, stage)
    try:
        running.result(timeout=READYZ_BUDGET_S)
    except ProbeTimeout as e:
        code, dependency = _OVER_BUDGET[stage[0]]
        obs.log_event(
            log, logging.ERROR, "readyz_over_budget",
            outcome="error", errorCode=code, dependency=dependency,
        )
        raise obs.DependencyError(code, dependency) from e
    except obs.DependencyError:
        raise
    except Exception as e:  # noqa: BLE001
        obs.log_event(
            log, logging.ERROR, "readyz_unexpected",
            outcome="error", errorCode=errors.READYZ_FAILED, dependency="unknown",
        )
        raise obs.DependencyError(errors.READYZ_FAILED, "unknown") from e
    latency_ms = round((time.perf_counter() - started) * 1000, 1)
    obs.log_event(
        log, logging.INFO, "readyz_ok",
        outcome="ok", latencyMs=latency_ms, dependency="firestore+drive",
    )
    return {"ok": True, "checks": {"firestore": "ok", "drive": "ok"}}
