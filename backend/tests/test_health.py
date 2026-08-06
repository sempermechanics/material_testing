import pytest

from app import drive, firestore_repo as repo
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

    monkeypatch.setattr(repo, "ping", unreachable)
    monkeypatch.setattr(drive, "ping", unreachable)

    resp = await client.get("/healthz")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_readyz_reports_which_dependency_failed(client, monkeypatch):
    """Readiness does the opposite, with a stable code the deploy smoke gate
    and load balancer can act on without parsing prose."""
    monkeypatch.setattr(repo, "ping", lambda: None)
    monkeypatch.setattr(
        drive, "ping",
        lambda *a, **k: (_ for _ in ()).throw(DependencyError("drive_unhealthy", "drive")),
    )

    resp = await client.get("/readyz")
    assert resp.status_code == 503
    assert resp.json()["detail"] == "drive_unhealthy"


@pytest.mark.asyncio
async def test_readyz_ok_reports_both_checks(client, monkeypatch):
    monkeypatch.setattr(repo, "ping", lambda: None)
    monkeypatch.setattr(drive, "ping", lambda *a, **k: None)

    resp = await client.get("/readyz")
    assert resp.status_code == 200
    assert resp.json()["checks"] == {"firestore": "ok", "drive": "ok"}
