"""The migration runner: chain validation, the ledger, and idempotence.

None of the operational scripts were executed or even imported by any test
before this — a syntax error in migrate_schema.py would have reached an operator
running it against production.
"""
import sys
from pathlib import Path

import fake_firestore
import pytest

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import migrate_schema  # noqa: E402
import migrations  # noqa: E402
from migrations import Migration  # noqa: E402


@pytest.fixture
def client():
    store = fake_firestore.FakeClient()
    store._data["users"] = {
        "u1": {"email": "a@x.test"},                        # pre-versioning
        "u2": {"email": "b@x.test", "schemaVersion": 1},    # already migrated
        "u3": {"email": "c@x.test", "schemaVersion": "1"},  # corrupt → treat as 0
    }
    store._data["sessions"] = {"s1": {"uid": "u1"}}
    return store


def _fake_firestore_module(monkeypatch):
    """migrate_schema uses firestore.SERVER_TIMESTAMP for ledger timestamps."""
    class _F:
        SERVER_TIMESTAMP = fake_firestore.SERVER_TIMESTAMP
    monkeypatch.setattr(migrate_schema, "firestore", _F)


def _migration(**kw):
    defaults = {
        "name": "001_test", "from_version": 0, "to_version": 1,
        "collections": ("users",), "transform": lambda doc: {},
    }
    return Migration(**{**defaults, **kw})


# --- chain validation --------------------------------------------------------

def test_shipped_chain_is_valid_and_ends_at_the_code_version():
    """Guards the exact drift the runner refuses to run under."""
    from app.firestore_repo import SCHEMA_VERSION

    chain = migrations.discover()
    assert chain, "no migrations discovered"
    assert chain[0].from_version == 0
    assert chain[-1].to_version == SCHEMA_VERSION
    for earlier, later in zip(chain, chain[1:]):
        assert later.from_version == earlier.to_version, "gap in the chain"


def test_pending_excludes_applied():
    names = {m.name for m in migrations.discover()}
    assert migrations.pending(names) == []
    assert {m.name for m in migrations.pending(set())} == names


# --- ledger ------------------------------------------------------------------

def test_dry_run_writes_nothing(client, monkeypatch):
    _fake_firestore_module(monkeypatch)
    totals = migrate_schema.run_migration(client, _migration(), apply=False)

    assert totals["changed"] == 2, "u1 and u3 need migrating"
    assert "schemaVersion" not in client._data["users"]["u1"]
    assert "_migrations" not in client._data, "dry run created a ledger entry"


def test_apply_stamps_documents_and_records_the_ledger(client, monkeypatch):
    _fake_firestore_module(monkeypatch)
    totals = migrate_schema.run_migration(client, _migration(), apply=True)

    assert totals == {"scanned": 3, "changed": 2}
    assert client._data["users"]["u1"]["schemaVersion"] == 1
    assert client._data["users"]["u3"]["schemaVersion"] == 1
    assert client._data["users"]["u2"]["schemaVersion"] == 1  # untouched, already there

    entry = client._data["_migrations"]["001_test"]
    assert entry["toVersion"] == 1
    assert entry["completedAt"] is not None
    assert entry["changed"] == 2


def test_incomplete_ledger_entry_is_retried(client, monkeypatch):
    """A crash mid-run leaves completedAt null; that migration must run again."""
    _fake_firestore_module(monkeypatch)
    client._data["_migrations"] = {
        "001_done": {"completedAt": object()},
        "002_crashed": {"completedAt": None},
    }
    assert migrate_schema.applied_migrations(client) == {"001_done"}


def test_rerunning_is_idempotent(client, monkeypatch):
    _fake_firestore_module(monkeypatch)
    migrate_schema.run_migration(client, _migration(), apply=True)
    second = migrate_schema.run_migration(client, _migration(), apply=True)

    assert second["changed"] == 0, "second pass rewrote already-migrated documents"
    assert client._data["users"]["u1"]["schemaVersion"] == 1


# --- transforms --------------------------------------------------------------

def test_transform_field_updates_are_applied_with_the_stamp(client, monkeypatch):
    _fake_firestore_module(monkeypatch)
    migrate_schema.run_migration(
        client,
        _migration(transform=lambda doc: {"tier": "standard"}),
        apply=True,
    )
    assert client._data["users"]["u1"]["tier"] == "standard"
    assert client._data["users"]["u1"]["schemaVersion"] == 1


def test_transform_returning_none_skips_the_document(client, monkeypatch):
    """None means "leave this one alone" — including its version stamp."""
    _fake_firestore_module(monkeypatch)
    totals = migrate_schema.run_migration(
        client,
        _migration(transform=lambda doc: None if doc.get("email") == "a@x.test" else {}),
        apply=True,
    )
    assert totals["changed"] == 1
    assert "schemaVersion" not in client._data["users"]["u1"]
    assert client._data["users"]["u3"]["schemaVersion"] == 1


def test_multiple_collections_are_all_walked(client, monkeypatch):
    _fake_firestore_module(monkeypatch)
    totals = migrate_schema.run_migration(
        client, _migration(collections=("users", "sessions")), apply=True,
    )
    assert totals["scanned"] == 4
    assert client._data["sessions"]["s1"]["schemaVersion"] == 1
