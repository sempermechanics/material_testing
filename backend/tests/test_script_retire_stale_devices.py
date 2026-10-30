"""scripts/retire_stale_devices.py: ACTIVE device records no account binds.

Since ADR-033 any ACTIVE record of an account signs, so a record an older path
left ACTIVE must be retired first. Kept: the phone the account names for the
record's app, and every phone of a test account. Retired: the rest, as
SUPERSEDED. Dry-run by default; a second `--apply` finds nothing.

Driven through `main()` against the in-memory store double, with
`firestore.Client` swapped for one that hands back the double.
"""
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import retire_stale_devices as rsd  # noqa: E402


@pytest.fixture
def run(store, monkeypatch):
    monkeypatch.setattr(rsd, "firestore", SimpleNamespace(
        Client=lambda project=None: store, SERVER_TIMESTAMP="ts"))

    def _run(*argv):
        monkeypatch.setattr(sys, "argv", ["retire_stale_devices.py", "--project", "p", *argv])
        return rsd.main()

    return _run


@pytest.fixture
def world(store):
    store._data["users"] = {
        "u1": {"activeDeviceId": "bound-1", "activeDeviceIdMaterialTesting": "class-1"},
        "t1": {"activeDeviceId": "test-2", "testAccount": {"purpose": "qc"}},
    }
    store._data["devices"] = {
        "bound-1": {"uid": "u1", "status": "ACTIVE", "app": "semper"},
        "stale-1": {"uid": "u1", "status": "ACTIVE", "app": "semper"},
        "class-1": {"uid": "u1", "status": "ACTIVE", "app": "materialtesting"},
        "test-1": {"uid": "t1", "status": "ACTIVE"},
        "test-2": {"uid": "t1", "status": "ACTIVE"},
        "orphan-1": {"uid": "gone", "status": "ACTIVE"},
        "old-1": {"uid": "u1", "status": "SUPERSEDED"},
    }
    return store


def test_dry_run_lists_and_writes_nothing(world, run, capsys):
    assert run() == 0
    out = capsys.readouterr().out
    assert "would retire 2" in out
    assert "orphan-1" in out and "stale-1" in out
    assert world._data["devices"]["stale-1"]["status"] == "ACTIVE"


def test_apply_retires_only_the_stale_records(world, run, capsys):
    assert run("--apply") == 0
    devices = world._data["devices"]
    assert devices["stale-1"]["status"] == devices["orphan-1"]["status"] == "SUPERSEDED"
    assert devices["stale-1"]["retiredBy"] == rsd.RETIRED_BY
    for kept in ("bound-1", "class-1", "test-1", "test-2"):
        assert devices[kept]["status"] == "ACTIVE", kept
    assert "retiredBy" not in devices["old-1"]


def test_a_second_apply_finds_nothing(world, run, capsys):
    run("--apply")
    capsys.readouterr()
    run("--apply")
    assert "retired 0" in capsys.readouterr().out
