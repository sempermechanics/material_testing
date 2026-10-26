import pytest

from app import drive
import repo_view as repo
from app.observability import DependencyError


@pytest.mark.asyncio
async def test_healthz(client):
    resp = await client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json() == {"ok": True}


@pytest.mark.asyncio
async def test_healthz_stays_up_when_dependencies_are_down(client, monkeypatch):
    """Liveness must not probe dependencies. If it did, a Drive or Firestore
    outage would fail the liveness check and Cloud Run would restart healthy
    instances in a loop, turning a partial outage into a total one."""
    def unreachable(*a, **k):
        raise DependencyError("firestore_unreachable", "firestore")

    repo.patch(monkeypatch, "ping", unreachable)
    monkeypatch.setattr(drive, "ping", unreachable)

    resp = await client.get("/healthz")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_readyz_reports_which_dependency_failed(client, monkeypatch):
    """Readiness does the opposite, with a stable code the deploy smoke gate
    and load balancer can act on without parsing prose."""
    repo.patch(monkeypatch, "ping", lambda: None)
    monkeypatch.setattr(
        drive, "ping",
        lambda *a, **k: (_ for _ in ()).throw(DependencyError("drive_unhealthy", "drive")),
    )

    resp = await client.get("/readyz")
    assert resp.status_code == 503
    assert resp.json()["detail"] == "drive_unhealthy"


@pytest.mark.asyncio
async def test_readyz_ok_reports_both_checks(client, monkeypatch):
    repo.patch(monkeypatch, "ping", lambda: None)
    monkeypatch.setattr(drive, "ping", lambda *a, **k: None)

    resp = await client.get("/readyz")
    assert resp.status_code == 200
    assert resp.json()["checks"] == {"firestore": "ok", "drive": "ok"}


# ------------------------------------------------ time budget (TE3-010)
# The uptime check gives up at 10 s (ops/monitoring/uptime/readyz.json) and the
# deploy smoke at 15 s. A slow but up dependency must come back as a 503 with
# its code inside that, not as the checker's own timeout.

def _uptime_timeout_s() -> float | None:
    import json
    import pathlib

    spec = pathlib.Path(__file__).resolve().parents[2] / "ops" / "monitoring" / "uptime" / "readyz.json"
    if not spec.is_file():
        return None
    return float(json.loads(spec.read_text(encoding="utf-8"))["timeout"].rstrip("s"))


def test_the_readyz_budget_ends_inside_the_uptime_check():
    from app.routers import health

    timeout = _uptime_timeout_s()
    if timeout is None:
        pytest.skip("ops/ not present (backend-only checkout)")
    assert health.READYZ_BUDGET_S < timeout
    # Each probe's own deadline fits the budget twice: they run in series.
    assert 2 * health.PROBE_TIMEOUT_S < health.READYZ_BUDGET_S


@pytest.mark.asyncio
@pytest.mark.parametrize("slow,code", [("firestore", "firestore_unreachable"),
                                       ("drive", "drive_unreachable")])
async def test_a_slow_dependency_is_a_503_inside_the_budget(client, monkeypatch, slow, code):
    import threading
    import time

    from app.routers import health

    release = threading.Event()

    def hang(*_a, **_k):
        release.wait(5)

    monkeypatch.setattr(health, "READYZ_BUDGET_S", 0.3, raising=False)
    repo.patch(monkeypatch, "ping", hang if slow == "firestore" else (lambda: None))
    monkeypatch.setattr(drive, "ping", hang if slow == "drive" else (lambda *a, **k: None))
    started = time.monotonic()
    try:
        resp = await client.get("/readyz")
    finally:
        release.set()
    assert time.monotonic() - started < 2.0
    assert resp.status_code == 503, resp.text
    assert resp.json()["detail"] == code
    assert resp.headers["retry-after"] == "30"


def test_the_probes_use_their_own_short_deadlines(store, monkeypatch):
    """Firestore: one attempt of PROBE_TIMEOUT_S, no retry window behind it
    (the read helpers allow 10 s retried, about 20 s). Drive: the same
    deadline on its one GET."""
    import fake_firestore
    from google.api_core.exceptions import ServiceUnavailable

    from app.repo import _base
    from app.routers import health

    fake_firestore.CALLS.clear()
    repo.ping()
    [(method, timeout, retry, _caller)] = fake_firestore.CALLS
    assert (method, timeout) == ("get", health.PROBE_TIMEOUT_S)
    assert not retry._predicate(ServiceUnavailable("down"))
    assert _base._GET_TIMEOUT_S > health.PROBE_TIMEOUT_S

    import inspect

    assert inspect.signature(drive.ping).parameters["timeout_s"].default == health.PROBE_TIMEOUT_S
    seen = []
    monkeypatch.setattr(drive, "ping", lambda *a, **k: seen.append((a, k)))
    repo.patch(monkeypatch, "ping", lambda: None)
    health._probe(["queued"])
    assert seen == [((), {})]  # the default deadline, not a longer one
