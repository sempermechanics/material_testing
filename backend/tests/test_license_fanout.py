"""TD-231, ADR-026: a whole-licence revoke or a terms edit records what is
left to fan out on the licence, in the licence's own write, and a Cloud Task
scheduled before that write finishes it if the call dies part-way."""
import logging
from datetime import datetime, timedelta, timezone

import pytest

import repo_view as repo
from app import tasks
from app.repo import license_admin
from license_helpers import _mint_individual, _mint_institution, _signed_in

FINISH = "/v1/tasks/license-fanout"


def _in(days):
    return datetime.now(timezone.utc) + timedelta(days=days)


@pytest.fixture
def queued(monkeypatch):
    """Every fan-out task the actions schedule: (license_id, fanout_id)."""
    seen: list[tuple[str, str]] = []
    monkeypatch.setattr(tasks, "enqueue_license_fanout",
                        lambda license_id, fanout_id: seen.append((license_id, fanout_id)) or True)
    return seen


def _roster(store):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "access_status": "APPROVED",
              "emailVerified": True, "plan": "demo"}
        for uid in ("u1", "u2")
    }
    minted = _mint_institution()
    for i, uid in enumerate(("u1", "u2")):
        repo.activate_license(uid, f"{uid}@university.edu", f"dev-{i}", minted["key"])
    return minted["license"]["id"]


def _held(store, **kw):
    store._data["users"] = {}
    minted = _mint_individual(**kw)
    _signed_in(store, "solo-1", "solo@lab.org")
    repo.ensure_entitlement({**store._data["users"]["solo-1"], "uid": "solo-1"}, "and-1")
    return minted["license"]["id"]


def _die(monkeypatch, name):
    """Make the fan-out step `name` fail, as a crash between writes would."""
    def crash(*a, **k):
        raise RuntimeError("instance killed")

    monkeypatch.setattr(license_admin, name, crash)


async def test_a_revoke_killed_before_the_holders_is_finished_by_the_task(
        store, queued, client, monkeypatch):
    license_id = _roster(store)
    with monkeypatch.context() as m:
        _die(m, "_drop_users_to_demo_if_licensed")
        with pytest.raises(RuntimeError):
            repo.revoke_license(license_id, "admin")

    lic = store._data["licenses"][license_id]
    assert lic["status"] == "revoked"
    assert lic["fanoutOps"] == ["revoke"]
    assert queued == [(license_id, lic["fanoutId"])]
    # The holders are still licensed on a revoked licence: the gap.
    assert store._data["users"]["u1"]["mode"] == "licensed"

    r = await client.post(FINISH, json={"licenseId": license_id})

    assert r.status_code == 200, r.text
    assert r.json() == {"licenseId": license_id, "finished": ["revoke"]}
    lic = store._data["licenses"][license_id]
    assert "fanoutId" not in lic and "fanoutOps" not in lic and "fanoutAt" not in lic
    assert lic["seatsUsed"] == 0
    assert store._data["users"]["u1"]["mode"] == "demo"
    assert store._data["users"]["u2"]["mode"] == "demo"
    assert all(s["status"] == "revoked" for s in repo.list_institution_seats(license_id))


async def test_an_edit_killed_before_the_holders_is_finished_by_the_task(
        store, queued, client, monkeypatch):
    license_id = _held(store, expires_at=_in(30))
    later = _in(400)
    with monkeypatch.context() as m:
        _die(m, "_refresh_license_mirrors")
        with pytest.raises(RuntimeError):
            repo.update_license(license_id, {"expiresAt": later}, "admin")

    assert sorted(store._data["licenses"][license_id]["fanoutOps"]) == ["terms"]
    assert store._data["users"]["solo-1"]["licenseExpiresAt"] < _in(31)

    r = await client.post(FINISH, json={"licenseId": license_id})

    assert r.status_code == 200 and r.json()["finished"] == ["terms"]
    assert store._data["users"]["solo-1"]["licenseExpiresAt"] == later
    assert "fanoutId" not in store._data["licenses"][license_id]


async def test_the_task_finds_nothing_after_a_call_that_finished(store, queued, client):
    license_id = _held(store, expires_at=_in(30))
    repo.update_license(license_id, {"expiresAt": _in(60)}, "admin")
    assert len(queued) == 1
    assert "fanoutId" not in store._data["licenses"][license_id]

    r = await client.post(FINISH, json={"licenseId": license_id})
    assert r.json() == {"licenseId": license_id, "finished": []}
    r = await client.post(FINISH, json={"licenseId": "no-such-licence"})
    assert r.status_code == 200 and r.json()["finished"] == []


def test_the_task_is_scheduled_before_the_licence_is_written(store, monkeypatch):
    license_id = _roster(store)
    seen = []

    def enqueue(lid, fanout_id):
        seen.append(dict(store._data["licenses"][lid]))
        return True

    monkeypatch.setattr(tasks, "enqueue_license_fanout", enqueue)
    repo.revoke_license(license_id, "admin")
    assert seen and seen[0]["status"] != "revoked" and "fanoutId" not in seen[0]


def test_an_edit_that_changes_no_holder_schedules_nothing(store, queued):
    license_id = _held(store, expires_at=_in(30))
    repo.update_license(license_id, {"notes": "renewal call booked"}, "admin")
    assert queued == []
    assert "fanoutId" not in store._data["licenses"][license_id]


def test_a_failed_enqueue_still_runs_the_fanout_inline(store, monkeypatch):
    license_id = _roster(store)
    monkeypatch.setattr(tasks, "enqueue_license_fanout", lambda *a: False)
    repo.revoke_license(license_id, "admin")
    assert store._data["users"]["u1"]["mode"] == "demo"
    assert "fanoutId" not in store._data["licenses"][license_id]


def test_ops_pending_from_an_earlier_action_are_kept(store, queued, monkeypatch):
    """A revoke while an edit's fan-out is still pending does both."""
    license_id = _held(store, expires_at=_in(30))
    with monkeypatch.context() as m:
        _die(m, "_refresh_license_mirrors")
        with pytest.raises(RuntimeError):
            repo.update_license(license_id, {"expiresAt": _in(90)}, "admin")
    with monkeypatch.context() as m:
        _die(m, "_drop_user_to_demo_if_licensed")
        with pytest.raises(RuntimeError):
            repo.revoke_license(license_id, "admin")
    assert sorted(store._data["licenses"][license_id]["fanoutOps"]) == ["revoke", "terms"]

    assert repo.finish_license_fanout(license_id) == ["revoke", "terms"]
    user = store._data["users"]["solo-1"]
    assert user["mode"] == "demo" and user["licenseExpiresAt"] > _in(89)


def test_a_fanout_overtaken_by_a_newer_edit_ends_on_the_newer_terms(store, queued, monkeypatch):
    """Edit A's holder writes land after edit B has fanned out and cleared its
    marker. A sees the marker is no longer its own and fans out again, from
    the licence as it now stands, so the holders end on B's terms."""
    license_id = _held(store, expires_at=_in(30))
    a_terms, b_terms = _in(100), _in(200)
    real = license_admin._refresh_license_mirrors
    nested = []

    def slow_refresh(uids, lid, mirror):
        if not nested:
            nested.append(True)
            repo.update_license(lid, {"expiresAt": b_terms}, "admin")  # B, whole
        real(uids, lid, mirror)

    monkeypatch.setattr(license_admin, "_refresh_license_mirrors", slow_refresh)
    repo.update_license(license_id, {"expiresAt": a_terms}, "admin")

    assert store._data["licenses"][license_id]["expiresAt"] == b_terms
    assert store._data["users"]["solo-1"]["licenseExpiresAt"] == b_terms
    assert "fanoutId" not in store._data["licenses"][license_id]


def test_a_fanout_that_keeps_being_overtaken_stops_after_three_passes(store, queued, monkeypatch):
    license_id = _held(store, expires_at=_in(30))
    passes = []
    real = license_admin._refresh_license_mirrors

    def overtaken(uids, lid, mirror):
        passes.append(1)
        store._data["licenses"][lid]["fanoutId"] = f"other-{len(passes)}"
        real(uids, lid, mirror)

    monkeypatch.setattr(license_admin, "_refresh_license_mirrors", overtaken)
    repo.update_license(license_id, {"expiresAt": _in(60)}, "admin")
    assert len(passes) == license_admin.FANOUT_PASSES
    # Still pending: the newer action's own run (or its task) clears it.
    assert store._data["licenses"][license_id]["fanoutId"].startswith("other-")


def test_leaving_floating_clears_leases_only_while_still_not_floating(store, queued, monkeypatch):
    store._data["users"] = {
        "u1": {"email": "u1@university.edu", "access_status": "APPROVED",
               "emailVerified": True, "plan": "demo"},
    }
    minted = _mint_institution(max_seats=3)
    license_id = minted["license"]["id"]
    repo.update_license(license_id, {"seating": "floating"}, "admin")
    repo.activate_license("u1", "u1@university.edu", "dev-1", minted["key"])
    seat = repo._seat_ref(license_id, "u1")
    seat.update({"leaseExpiresAt": _in(1)})
    store._data["users"]["u1"]["leaseExpiresAt"] = _in(1)

    with monkeypatch.context() as m:
        _die(m, "_clear_seat_leases")
        with pytest.raises(RuntimeError):
            repo.update_license(license_id, {"seating": "assigned"}, "admin")
    assert sorted(store._data["licenses"][license_id]["fanoutOps"]) == ["leases", "terms"]
    # Switched back before the task ran: the lease is live again, keep it.
    store._data["licenses"][license_id]["seating"] = "floating"
    repo.finish_license_fanout(license_id)
    assert "leaseExpiresAt" in seat.get().to_dict()

    with monkeypatch.context() as m:
        _die(m, "_clear_seat_leases")
        with pytest.raises(RuntimeError):
            repo.update_license(license_id, {"seating": "assigned"}, "admin")
    repo.finish_license_fanout(license_id)
    assert "leaseExpiresAt" not in seat.get().to_dict()
    assert "leaseExpiresAt" not in store._data["users"]["u1"]


def test_reconcile_reports_a_pending_fanout(store, queued, monkeypatch):
    license_id = _roster(store)
    assert repo.reconcile_institution_seats(license_id)["fanoutPending"] == []
    with monkeypatch.context() as m:
        _die(m, "_drop_users_to_demo_if_licensed")
        with pytest.raises(RuntimeError):
            repo.revoke_license(license_id, "admin")
    assert repo.reconcile_institution_seats(license_id)["fanoutPending"] == ["revoke"]


def test_the_public_licence_does_not_carry_the_marker(store, queued, monkeypatch):
    license_id = _held(store, expires_at=_in(30))
    with monkeypatch.context() as m:
        _die(m, "_refresh_license_mirrors")
        with pytest.raises(RuntimeError):
            repo.update_license(license_id, {"expiresAt": _in(90)}, "admin")
    public = repo.get_license_public(license_id)
    assert not any(k.startswith("fanout") for k in public)


# ---------------- the enqueue ----------------


class _FakeTasks:
    def __init__(self, fail=None):
        self.created = []
        self.fail = fail

    def queue_path(self, project, location, queue):
        return f"projects/{project}/locations/{location}/queues/{queue}"

    def create_task(self, parent, task):
        if self.fail:
            raise self.fail
        self.created.append((parent, task))


def _configured(monkeypatch, client):
    settings = tasks.settings
    monkeypatch.setattr(settings, "TASKS_QUEUE", "semper-provision")
    monkeypatch.setattr(settings, "TASKS_TARGET_BASE_URL", "https://run.example/")
    monkeypatch.setattr(settings, "GCP_PROJECT", "p")
    monkeypatch.setattr(tasks, "_tasks_client", lambda: client)


def test_the_enqueue_names_the_task_and_delays_it(monkeypatch):
    fake = _FakeTasks()
    _configured(monkeypatch, fake)
    before = datetime.now(timezone.utc)
    assert tasks.enqueue_license_fanout("lic-1", "abc123") is True
    [(parent, task)] = fake.created
    assert task["name"] == f"{parent}/tasks/license-fanout-abc123"
    assert task["http_request"]["url"] == "https://run.example" + tasks.LICENSE_FANOUT_PATH
    assert task["http_request"]["body"] == b'{"licenseId": "lic-1"}'
    due = task["schedule_time"]
    assert due >= before + timedelta(seconds=tasks.FANOUT_TASK_DELAY_S)


def test_the_enqueue_is_off_without_a_queue_and_false_on_failure(monkeypatch, caplog):
    monkeypatch.setattr(tasks.settings, "TASKS_QUEUE", "")
    assert tasks.enqueue_license_fanout("lic-1", "abc") is False
    _configured(monkeypatch, _FakeTasks(fail=RuntimeError("PERMISSION_DENIED")))
    with caplog.at_level(logging.ERROR, logger="semper.tasks"):
        assert tasks.enqueue_license_fanout("lic-1", "abc") is False
    assert "license_fanout_enqueue_failed" in caplog.text
