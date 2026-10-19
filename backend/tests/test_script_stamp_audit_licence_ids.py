"""TD-246: the one-off backfill that stamps `licenseId` on audit rows written
before `audit.record` did (2026-10-18), so a licence's export reaches back
past that date. Running it is the owner's decision; `--apply` refuses
without one, and records it in the ledger."""
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

import fake_firestore

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import stamp_audit_licence_ids as backfill  # noqa: E402

T = datetime(2026, 10, 1, tzinfo=timezone.utc)


@pytest.fixture
def client(monkeypatch):
    store = fake_firestore.FakeClient()
    store._data["audit_logs"] = {
        "a1": {"action": "ADMIN_LICENSE_EXTEND", "ts": T, "schemaVersion": 2,
               "target": {"type": "license", "id": "L1"}},
        "a2": {"action": "INSTITUTION_SEAT_PATCH", "ts": T,
               "target": {"type": "seat", "id": "L1/u9"}, "detail": {"x": "y"}},
        "a3": {"action": "LICENSE_DEVICE_UNBIND", "ts": T,
               "target": {"type": "seat", "id": "L2"}},
        "a4": {"action": "SESSION_DELETE", "ts": T, "target": {"type": "session", "id": "s1"}},
        "a5": {"action": "AUTH_DENIED", "ts": T},
        "a6": {"action": "ADMIN_LICENSE_REVOKE", "ts": T, "licenseId": "L3",
               "target": {"type": "license", "id": "L3"}},
    }

    class _F:
        SERVER_TIMESTAMP = fake_firestore.SERVER_TIMESTAMP

    monkeypatch.setattr(backfill, "firestore", _F)
    return store


def _before(client):
    return {k: dict(v) for k, v in client._data["audit_logs"].items()}


def test_a_dry_run_counts_and_writes_nothing(client):
    before = _before(client)
    counts = backfill.stamp(client, apply=False, decision="", page=2)
    assert client._data["audit_logs"] == before
    assert "_migrations" not in client._data
    assert (counts["scanned"], counts["stamped"], counts["already"], counts["no_licence"]) == (6, 3, 1, 2)


def test_apply_adds_only_the_licence_id(client):
    before = _before(client)
    counts = backfill.stamp(client, apply=True, decision="owner, 2026-10-20", page=2)
    rows = client._data["audit_logs"]
    assert counts["stamped"] == 3
    assert [rows[k].get("licenseId") for k in ("a1", "a2", "a3")] == ["L1", "L1", "L2"]
    # Nothing else on any row moves: not the time, the action, the version.
    for key, row in rows.items():
        assert {k: v for k, v in row.items() if k != "licenseId"} == {
            k: v for k, v in before[key].items() if k != "licenseId"}
    assert "licenseId" not in rows["a4"] and "licenseId" not in rows["a5"]


def test_apply_records_the_decision_in_the_ledger(client):
    backfill.stamp(client, apply=True, decision="owner, 2026-10-20", page=4)
    ledger = client._data["_migrations"][backfill.LEDGER_ID]
    assert ledger["decision"] == "owner, 2026-10-20"
    assert ledger["collections"] == ["audit_logs"]
    assert (ledger["scanned"], ledger["changed"]) == (6, 3)
    assert ledger["completedAt"] is not None


def test_apply_refuses_without_a_decision(client):
    with pytest.raises(SystemExit):
        backfill.stamp(client, apply=True, decision="  ")
    assert "licenseId" not in client._data["audit_logs"]["a1"]


def test_a_rerun_stamps_nothing_more(client):
    backfill.stamp(client, apply=True, decision="owner", page=2)
    again = backfill.stamp(client, apply=True, decision="owner", page=2)
    assert (again["stamped"], again["already"]) == (0, 4)


def test_main_parses_and_runs_a_dry_run(client, monkeypatch, capsys):
    monkeypatch.setattr(backfill, "_client", lambda project: client)
    monkeypatch.setattr(sys, "argv", ["stamp_audit_licence_ids.py", "--project", "p"])
    assert backfill.main() == 0
    out = capsys.readouterr().out
    assert "would stamp 3" in out and "6 row(s) scanned" in out
