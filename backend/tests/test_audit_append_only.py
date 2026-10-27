"""TD-199 (framework 5.3): `audit_logs` is append-only in code, not by
convention alone.

* Only `repo/audit_log.add_audit_row` writes the collection, and it only adds.
* A migration may not rewrite it unless it says so (`rewrites_append_only`),
  which is the owner decision a reviewer sees; 001 did, before this guard.
* Every row is also written to Cloud Logging as one `audit_row` line, so a
  log sink can route it to a bucket with a locked retention policy, which
  no project role can edit or delete. The line is written before the
  Firestore row, so a lost row still leaves its mirror.

Clients are denied by `firestore.rules` (deny-all; `firestore-rules-tests/`).
"""
import ast
import json
import logging
import sys
import types
from pathlib import Path

import pytest

from app import audit
from app.repo import audit_log

APP = Path(__file__).resolve().parents[1] / "app"
SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import migrations  # noqa: E402

#: Calls that change or remove an existing document, by any route the repo
#: has: its own helpers, the client's methods, a batch or a transaction.
_WRITES = {
    "_set_doc", "_update_doc", "_delete_doc", "_create_doc", "_update_refs",
    "_delete_refs", "_delete_query_until_empty", "_commit",
    "set", "update", "delete", "create", "batch", "transaction",
}


def _mentions(path: Path, text: str) -> bool:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    return any(isinstance(n, ast.Constant) and n.value == text for n in ast.walk(tree))


def test_no_module_but_the_audit_repo_names_the_collection():
    named = sorted(p.relative_to(APP).as_posix() for p in APP.rglob("*.py")
                   if _mentions(p, "audit_logs"))
    assert named == ["repo/audit_log.py"]


def test_the_audit_repo_only_adds_rows():
    tree = ast.parse((APP / "repo" / "audit_log.py").read_text(encoding="utf-8"))
    calls = {
        (n.func.attr if isinstance(n.func, ast.Attribute) else getattr(n.func, "id", ""))
        for n in ast.walk(tree) if isinstance(n, ast.Call)
    }
    assert "_add_doc" in calls
    assert calls & _WRITES == set()


# ---------------- migrations ----------------


def _module(**attrs):
    module = types.ModuleType("m")
    defaults = {"from_version": 0, "to_version": 1, "transform": lambda doc: {}}
    for k, v in {**defaults, **attrs}.items():
        setattr(module, k, v)
    return module


def test_a_migration_may_not_rewrite_the_audit_log_unless_it_says_so():
    with pytest.raises(ValueError, match="append-only"):
        migrations._load(_module(collections=("users", "audit_logs")), "009_x")
    allowed = migrations._load(_module(collections=("audit_logs",),
                                       rewrites_append_only="owner decision TD-34"), "009_x")
    assert allowed.collections == ("audit_logs",)
    with pytest.raises(ValueError, match="append-only"):
        migrations._load(_module(collections=("audit_logs",), rewrites_append_only=" "), "009_x")


def test_the_shipped_chain_names_its_one_audit_rewrite():
    rewrites = [m.name for m in migrations.discover() if "audit_logs" in m.collections]
    assert rewrites == ["001_stamp_schema_version"]


# ---------------- the Cloud Logging mirror ----------------


def _lines(caplog):
    return [json.loads(r.getMessage()) for r in caplog.records if r.name == audit.TRAIL_LOGGER]


def test_every_row_is_mirrored_to_cloud_logging(store, caplog):
    with caplog.at_level(logging.INFO, logger=audit.TRAIL_LOGGER):
        audit.record("u1", device_id="dev-1", action="LICENSE_DEVICE_BIND",
                     target={"type": "seat", "id": "lic-1/u1"},
                     detail={"app": "semper"}, ip="203.0.113.9")
    [line] = _lines(caplog)
    assert line["event"] == "audit_row"
    assert line["auditRow"] == {
        "uid": "u1", "deviceId": "dev-1", "ip": "203.0.113.9",
        "action": "LICENSE_DEVICE_BIND", "outcome": "OK",
        "target": {"type": "seat", "id": "lic-1/u1"}, "detail": {"app": "semper"},
        "licenseId": "lic-1", "schemaVersion": audit_log.SCHEMA_VERSION,
    }
    # Not a top-level `action`: the `semper_auth_denied` metric counts lines
    # by `jsonPayload.action`, and would count a denial twice.
    assert "action" not in line


def test_the_mirror_survives_a_lost_firestore_row(store, caplog, monkeypatch):
    def down(row):
        raise RuntimeError("firestore unavailable")

    monkeypatch.setattr(audit_log, "add_audit_row", down)
    with caplog.at_level(logging.INFO):
        audit.record("u1", action="ADMIN_LICENSE_REVOKE", target={"type": "license", "id": "L"})
    assert [line["auditRow"]["action"] for line in _lines(caplog)] == ["ADMIN_LICENSE_REVOKE"]
    assert "audit_write_failed" in caplog.text


def test_a_value_json_cannot_hold_is_written_as_text(store, caplog):
    from datetime import datetime, timezone

    when = datetime(2026, 10, 19, tzinfo=timezone.utc)
    with caplog.at_level(logging.INFO, logger=audit.TRAIL_LOGGER):
        audit.record("u1", action="ADMIN_LICENSE_EXTEND", detail={"expiresAt": when})
    assert _lines(caplog)[0]["auditRow"]["detail"] == {"expiresAt": str(when)}


# ---------------- retention (TD-199) ----------------


def _added_rows(monkeypatch):
    """Rows `add_audit_row` adds, through a stub collection (the store double
    implements no `.add()`)."""
    from app.repo import _base

    added: list[dict] = []

    class _Collection:
        def add(self, data, *, retry=None, timeout=None):
            added.append(data)

    class _Client:
        def collection(self, name):
            assert name == "audit_logs"
            return _Collection()

    monkeypatch.setattr(_base, "_DB", _Client())
    return added


@pytest.mark.parametrize("days", [365, 30])
def test_a_new_row_carries_its_expiry(monkeypatch, days):
    """TD-199: each row is added with `expireAt` = now + AUDIT_RETENTION_DAYS,
    which the TTL policy on `audit_logs.expireAt` deletes; it is never
    rewritten to carry one."""
    from datetime import datetime, timedelta, timezone

    from app.config import settings

    monkeypatch.setattr(settings, "AUDIT_RETENTION_DAYS", days)
    added = _added_rows(monkeypatch)
    before = datetime.now(timezone.utc)
    audit.record("u1", action="ADMIN_LICENSE_EXTEND", target={"type": "license", "id": "L"})
    after = datetime.now(timezone.utc)
    [row] = added
    assert row["ts"] is audit_log._base.firestore.SERVER_TIMESTAMP
    assert before + timedelta(days=days) <= row["expireAt"] <= after + timedelta(days=days)
    assert row["expireAt"].tzinfo is not None
    assert (row["action"], row["licenseId"], row["schemaVersion"]) == (
        "ADMIN_LICENSE_EXTEND", "L", audit_log.SCHEMA_VERSION)


def test_the_default_retention_is_one_year():
    from app.config import Settings

    assert Settings.AUDIT_RETENTION_DAYS == 365
