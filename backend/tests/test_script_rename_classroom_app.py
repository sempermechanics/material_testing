"""ADR-030 / TD-56: the one-off rename of Material Testing to Semper Classroom
in stored records. Each `...MaterialTesting` field on users, licences and
seats is copied to its `...SemperClassroom` name (refreshed where it went
stale), a `...SemperClassroom` field with no old twin is deleted, and the old
field is left as it is. The `app` tags on devices and sessions are renamed only
with `retag=True` (`--retag`, TD-56): a backend from before the rename reads
`semperclassroom` as an unknown app."""
import copy
import sys
from pathlib import Path

import pytest
from google.api_core.exceptions import FailedPrecondition

import fake_firestore

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import rename_classroom_app as rename  # noqa: E402

OLD = "materialtesting"
NEW = "semperclassroom"
SEATS_A = "licenses/LA/seats"
DELETE = fake_firestore.DELETE_FIELD


def _run(store, apply, **kw):
    return rename.rename(store, apply, DELETE, **kw)


@pytest.fixture
def store():
    s = fake_firestore.FakeClient()
    s._data["devices"] = {
        "d-mt": {"uid": "u1", "app": OLD},
        "d-new": {"uid": "u1", "app": NEW},
        "d-semper": {"uid": "u1", "app": "semper"},
        "d-untagged": {"uid": "u2"},
    }
    s._data["sessions"] = {
        "s-mt": {"uid": "u1", "app": OLD},
        "s-semper": {"uid": "u1", "app": "semper"},
        "s-unknown": {"uid": "u1", "app": "somethingelse"},
    }
    s._data["users"] = {
        "u1": {
            "activeDeviceId": "semper-dev",  # Semper's slot: never touched
            "activeDeviceIdMaterialTesting": "d-mt",
            "deviceIdLockMaterialTesting": "d-mt",
            # The new field went stale: a backend from before the rename
            # wrote only the old one after the dual-writing backend wrote both.
            "releasedDeviceIdMaterialTesting": "current",
            "releasedDeviceIdSemperClassroom": "stale",
        },
        "u2": {"activeDeviceId": "x"},
        # Already migrated: same value under both names.
        "u3": {"activeDeviceIdMaterialTesting": "d3", "activeDeviceIdSemperClassroom": "d3"},
        # A backend from before the rename deleted the old field and left the
        # new one: the binding is gone, so the new field goes too (H1).
        "u4": {"activeDeviceId": "z", "activeDeviceIdSemperClassroom": "left-behind"},
    }
    s._data["licenses"] = {
        "LA": {"status": "active", "deviceIdLockMaterialTesting": "d-mt"},
        "LB": {"status": "active"},
        "LC": {"status": "active", "deviceIdLockSemperClassroom": "left-behind"},
    }
    s._data[SEATS_A] = {
        "u1": {"uid": "u1", "deviceIdLock": "semper-dev", "deviceIdLockMaterialTesting": "d-mt"},
        "u2": {"uid": "u2", "deviceIdLock": "y"},
    }
    s._data["audit_logs"] = {
        "a1": {"action": "DEVICE_REGISTER", "app": OLD, "activeDeviceIdMaterialTesting": "d"},
    }
    return s


def test_dry_run_counts_and_writes_nothing(store):
    before = copy.deepcopy(store._data)
    tallies = _run(store, apply=False)
    assert store._data == before

    # Tags are not this rename's (`--retag`, TD-56).
    assert set(tallies) == {"users", "licenses", "licenses/*/seats"}

    users = tallies["users"].counts
    assert (users["scanned"], users["changed"], users["copied"]) == (4, 2, 2)
    # u1's stale new field is refreshed, u4's orphaned one deleted.
    assert (users["present"], users["differ"], users["pruned"]) == (1, 1, 1)
    assert tallies["users"].differ == ["users/u1", "users/u4"]
    licences = tallies["licenses"].counts
    assert (licences["scanned"], licences["copied"], licences["pruned"]) == (3, 1, 1)
    assert tallies["licenses"].differ == ["licenses/LC"]
    assert tallies["licenses/*/seats"].counts["scanned"] == 2
    assert tallies["licenses/*/seats"].counts["copied"] == 1

    lines = rename.report(tallies, apply=False)
    assert not any("retagged" in line for line in lines)
    assert any(line.startswith("licenses/*/seats: 2 scanned") for line in lines)
    assert any("1 would be deleted (no old field)" in line for line in lines)
    assert "  differ: users/u1, users/u4" in lines


def test_tags_are_left_alone_by_default(store):
    before = copy.deepcopy({k: store._data[k] for k in ("devices", "sessions")})
    tallies = _run(store, apply=True)
    assert {k: store._data[k] for k in ("devices", "sessions")} == before
    assert "devices" not in tallies and "sessions" not in tallies


def test_retag_dry_run_counts_the_tags(store):
    before = copy.deepcopy(store._data)
    tallies = _run(store, apply=False, retag=True)
    assert store._data == before

    assert tallies["devices"].counts["scanned"] == 4
    assert tallies["devices"].counts["retagged"] == 1
    assert tallies["devices"].counts["already"] == 1
    assert tallies["devices"].counts["other"] == 2
    assert tallies["sessions"].counts["retagged"] == 1
    assert tallies["sessions"].counts["other"] == 2
    # The field pass runs as well.
    assert tallies["users"].counts["copied"] == 2

    lines = rename.report(tallies, apply=False)
    assert any(line.startswith("devices: 4 scanned, 1 would be retagged") for line in lines)


def test_retag_renames_the_tags(store):
    _run(store, apply=True, retag=True)

    assert {d: v.get("app") for d, v in store._data["devices"].items()} == {
        "d-mt": NEW, "d-new": NEW, "d-semper": "semper", "d-untagged": None,
    }
    assert {d: v.get("app") for d, v in store._data["sessions"].items()} == {
        "s-mt": NEW, "s-semper": "semper", "s-unknown": "somethingelse",
    }


def test_apply_copies_refreshes_and_prunes_fields(store):
    _run(store, apply=True)

    assert store._data["users"]["u1"] == {
        "activeDeviceId": "semper-dev",
        "activeDeviceIdMaterialTesting": "d-mt",
        "activeDeviceIdSemperClassroom": "d-mt",
        "deviceIdLockMaterialTesting": "d-mt",
        "deviceIdLockSemperClassroom": "d-mt",
        "releasedDeviceIdMaterialTesting": "current",
        "releasedDeviceIdSemperClassroom": "current",  # refreshed from the old field
    }
    assert store._data["users"]["u2"] == {"activeDeviceId": "x"}
    assert store._data["users"]["u4"] == {"activeDeviceId": "z"}, "the orphan is deleted"
    assert store._data["licenses"]["LA"] == {
        "status": "active", "deviceIdLockMaterialTesting": "d-mt",
        "deviceIdLockSemperClassroom": "d-mt",
    }
    assert store._data["licenses"]["LB"] == {"status": "active"}
    assert store._data["licenses"]["LC"] == {"status": "active"}
    assert store._data[SEATS_A]["u1"] == {
        "uid": "u1", "deviceIdLock": "semper-dev",
        "deviceIdLockMaterialTesting": "d-mt", "deviceIdLockSemperClassroom": "d-mt",
    }
    assert store._data[SEATS_A]["u2"] == {"uid": "u2", "deviceIdLock": "y"}


def test_audit_rows_are_never_touched(store):
    before = copy.deepcopy(store._data["audit_logs"])
    tallies = _run(store, apply=True, retag=True)
    assert store._data["audit_logs"] == before
    assert "audit_logs" not in tallies


@pytest.mark.parametrize("retag", [False, True])
def test_a_second_run_is_a_no_op(store, retag):
    _run(store, apply=True, retag=retag)
    after = copy.deepcopy(store._data)
    tallies = _run(store, apply=True, retag=retag)
    assert store._data == after
    assert all(t.counts["changed"] == 0 for t in tallies.values())
    assert all(not t.differ for t in tallies.values())
    assert tallies["users"].counts["copied"] == 0
    assert tallies["users"].counts["pruned"] == 0
    if retag:
        assert tallies["devices"].counts["retagged"] == 0
        assert tallies["devices"].counts["already"] == 2


def test_pages_beyond_one_batch(monkeypatch):
    store = fake_firestore.FakeClient()
    n = rename.PAGE * 2 + 17
    store._data["devices"] = {f"d{i:04d}": {"app": OLD} for i in range(n)}
    store._data["users"] = {f"u{i:04d}": {"activeDeviceIdMaterialTesting": f"d{i}"}
                            for i in range(n)}
    store._data["licenses"] = {f"L{i:04d}": {} for i in range(n)}
    store._data["licenses/L0005/seats"] = {f"s{i:04d}": {"deviceIdLockMaterialTesting": "x"}
                                           for i in range(n)}
    commits = []
    real_batch = store.batch

    def counted_batch():
        batch = real_batch()
        real_commit = batch.commit

        def commit(**kw):
            commits.append(len(batch._ops))
            return real_commit(**kw)

        batch.commit = commit
        return batch

    monkeypatch.setattr(store, "batch", counted_batch)
    tallies = _run(store, apply=True, retag=True)

    assert tallies["devices"].counts["retagged"] == n
    assert all(d["app"] == NEW for d in store._data["devices"].values())
    assert tallies["users"].counts["copied"] == n
    assert all(u["activeDeviceIdSemperClassroom"] == u["activeDeviceIdMaterialTesting"]
               for u in store._data["users"].values())
    assert tallies["licenses"].counts["scanned"] == n
    assert tallies["licenses/*/seats"].counts["copied"] == n
    assert max(commits) <= rename.PAGE
    # devices, users, seats: three full pages and one short page each.
    assert sorted(commits) == sorted([rename.PAGE, rename.PAGE, 17] * 3)


def test_a_page_whose_batch_fails_its_precondition_is_read_again(store, monkeypatch):
    real_batch = store.batch
    failed = []

    def flaky_batch():
        batch = real_batch()
        real_commit = batch.commit

        def commit(**kw):
            if not failed:
                failed.append(True)
                batch._ops.clear()
                raise FailedPrecondition("changed since read")
            return real_commit(**kw)

        batch.commit = commit
        return batch

    monkeypatch.setattr(store, "batch", flaky_batch)
    tallies = _run(store, apply=True, retag=True)
    assert failed
    assert store._data["devices"]["d-mt"]["app"] == NEW
    assert tallies["devices"].counts["scanned"] == 4  # the retried page counted once
    assert tallies["devices"].counts["retagged"] == 1


def test_report_lists_ten_documents_and_counts_the_rest():
    tally = rename.Tally()
    tally.counts.update(scanned=12, changed=12, pruned=12)
    tally.differ = [f"users/u{i:02d}" for i in range(12)]
    lines = rename.report({"users": tally}, apply=True)
    assert "12 deleted (no old field)" in lines[0] and "would be" not in lines[0]
    assert lines[1].endswith("users/u09 and 2 more")


def test_old_name_names_the_mirrored_field():
    assert rename.old_name("activeDeviceIdSemperClassroom") == "activeDeviceIdMaterialTesting"
    assert rename.old_name("activeDeviceId") is None
    assert rename.old_name("SemperClassroom") is None
    assert rename.old_name("a.bSemperClassroom") is None


def test_new_name_only_renames_suffixed_plain_fields():
    assert rename.new_name("activeDeviceIdMaterialTesting") == "activeDeviceIdSemperClassroom"
    assert rename.new_name("activeDeviceId") is None
    assert rename.new_name("MaterialTesting") is None
    assert rename.new_name("a.bMaterialTesting") is None
    assert rename.new_name("activeDeviceIdSemperClassroom") is None
