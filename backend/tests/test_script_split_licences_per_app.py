"""ADR-033: the one-off split of each licence from before the split into a
Semper licence and a Semper Classroom twin. Licences are seeded the old way
(app-less, one lock per app), split by the script against the same in-memory
store the repository reads, and then judged by the repository itself: each
locked phone stays licensed on its own app, with no Activate."""
import copy
import sys
from pathlib import Path

import pytest
from google.api_core.exceptions import FailedPrecondition

import fake_firestore
import repo_view as repo
from app import apps
from app.licenses import invite_id
from license_helpers import _mint_individual, _signed_in

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import split_licences_per_app as split  # noqa: E402

SEMPER = apps.SEMPER
CLASSROOM = apps.SEMPER_CLASSROOM
S_PHONE = "and-semper-1"
C_PHONE = "and-classroom-1"


def _run(store, apply=True, **kw):
    return split.split(store, apply, fake_firestore.DELETE_FIELD,
                       fake_firestore.SERVER_TIMESTAMP, **kw)


def _phone(store, uid, device_id, app):
    store._data.setdefault("devices", {})[device_id] = {
        "uid": uid, "status": "ACTIVE", "app": apps.stored_name(app)}


def _lock(doc: dict, app: str, device_id: str):
    doc.update(apps.written_fields("deviceIdLock", app, device_id))


def _user(store, uid):
    return {**store._data["users"][uid], "uid": uid}


def _lic(store, license_id):
    return store._data["licenses"][license_id]


def _acts(store, license_id):
    return store._data.get(f"licenses/{license_id}/activations", {})


def _mode(store, uid, device_id, app):
    """What `device_id` gets on `app` from a build that taps Activate."""
    out = repo.revalidate_device_lock(_user(store, uid), device_id, app, explicit=True)
    return repo.effective_mode(out, app)


def _individual(store, uid="solo-1", email="solo@lab.org", **kw):
    """An app-less individual licence held by `uid`, its Semper and Semper
    Classroom phones locked the old way."""
    store._data.setdefault("users", {})
    license_id = _mint_individual(email, **kw)["license"]["id"]
    repo.ensure_entitlement(_signed_in(store, uid, email), None)
    _phone(store, uid, S_PHONE, SEMPER)
    _phone(store, uid, C_PHONE, CLASSROOM)
    _lock(_lic(store, license_id), SEMPER, S_PHONE)
    _lock(_lic(store, license_id), CLASSROOM, C_PHONE)
    return license_id


def _institution(store, *, seating="assigned", max_seats=4):
    """An app-less institution licence at lab.org with two seated members,
    each with a phone locked per app, a disabled member, a revoked one, a
    pending invite and a held invite."""
    store._data.setdefault("users", {})
    license_id = repo.create_institution_license(
        domain_lock="lab.org", admin_emails=["it@lab.org"], created_by_uid="admin",
        max_seats=max_seats, seating=seating)["license"]["id"]
    for uid in ("m1", "m2", "m3", "m4"):
        repo.invite_institution_member(license_id, f"{uid}@lab.org", "it")
        repo.ensure_entitlement(_signed_in(store, uid, f"{uid}@lab.org"), None)
    seats = store._data[f"licenses/{license_id}/seats"]
    for uid in ("m1", "m2"):
        _phone(store, uid, f"{uid}-s", SEMPER)
        _phone(store, uid, f"{uid}-c", CLASSROOM)
        _lock(seats[uid], SEMPER, f"{uid}-s")
        _lock(seats[uid], CLASSROOM, f"{uid}-c")
    seats["m3"]["status"] = "disabled"
    _lock(seats["m3"], SEMPER, "m3-s")
    seats["m4"]["status"] = "revoked"
    _lic(store, license_id)["seatsUsed"] = 3
    repo.invite_institution_member(license_id, "later@lab.org", "it")
    repo.hold_institution_invite(license_id, "elsewhere@lab.org", "it")
    return license_id


# ------------------------------------------------------------------ individual


def test_an_individual_licence_splits_and_each_phone_stays_licensed(store):
    old = _individual(store)
    assert _mode(store, "solo-1", S_PHONE, SEMPER) == "licensed"
    assert _mode(store, "solo-1", C_PHONE, CLASSROOM) == "licensed"

    tally = _run(store)

    lic = _lic(store, old)
    twin_id = lic["splitInto"]
    twin = _lic(store, twin_id)
    assert (lic["app"], lic["devicesActive"], lic["maxDevices"]) == (SEMPER, 1, 1)
    assert "splitPending" not in lic
    # The original keeps both locks, for a rollback to a backend before the split.
    assert apps.read_field(lic, "deviceIdLock", CLASSROOM) == C_PHONE
    assert apps.canonical(twin["app"]) == CLASSROOM and twin["splitFrom"] == old
    assert (twin["devicesActive"], twin["maxDevices"]) == (1, 1)
    assert twin["keyHash"] == twin_id and twin["keyPrefix"].startswith("SEMP-")
    assert twin["deviceIdLock"] == "" and apps.read_field(twin, "deviceIdLock", CLASSROOM) == C_PHONE
    assert (twin["emailLock"], twin["redeemedByUid"], twin["status"]) == (
        lic["emailLock"], "solo-1", lic["status"])
    assert _acts(store, old)[S_PHONE]["activatedBy"] == split.ACTOR
    assert _acts(store, twin_id)[C_PHONE]["uid"] == "solo-1"

    user = _user(store, "solo-1")
    assert user["licenseId"] == old and user["licenseApp"] == SEMPER
    assert apps.read_field(user, "licenseId", CLASSROOM) == twin_id
    assert apps.read_field(user, "licensePrefix", CLASSROOM) == twin["keyPrefix"]
    assert apps.read_field(user, "mode", CLASSROOM) == user["mode"]
    # The repository now runs activations on both, and nobody tapped Activate.
    assert _mode(store, "solo-1", S_PHONE, SEMPER) == "licensed"
    assert _mode(store, "solo-1", C_PHONE, CLASSROOM) == "licensed"
    assert _mode(store, "solo-1", S_PHONE, CLASSROOM) == "demo"
    summary = repo.license_summary(user, CLASSROOM)
    assert (summary["prefix"], summary["mode"]) == (twin["keyPrefix"], "licensed")

    audit = store._data["audit_logs"][f"licence-split-{old}"]
    assert audit["action"] == "LICENSE_SPLIT" and audit["detail"]["twin"] == twin_id
    assert tally.counts["split"] == 1 and tally.counts["twins"] == 1
    assert tally.counts["activations.semper"] == 1
    assert tally.counts["activations.semperclassroom"] == 1


def test_an_unclaimed_licence_promises_its_twin_and_both_arrive_at_sign_in(store):
    store._data["users"] = {}
    old = _mint_individual("later@lab.org")["license"]["id"]

    _run(store)

    twin_id = _lic(store, old)["splitInto"]
    invite = store._data["licenseInvites"][invite_id("later@lab.org", CLASSROOM)]
    assert (invite["licenseId"], invite["app"]) == (twin_id, CLASSROOM)
    user = repo.ensure_entitlement(_signed_in(store, "later", "later@lab.org"), None)
    stored = _user(store, "later")
    assert user["licenseId"] == old
    assert apps.read_field(stored, "licenseId", CLASSROOM) == twin_id


def test_a_holder_with_a_classroom_licence_of_their_own_keeps_it(store):
    old = _individual(store)
    own = _mint_individual("solo@lab.org", app=CLASSROOM)["license"]["id"]
    assert apps.read_field(_user(store, "solo-1"), "licenseId", CLASSROOM) == own

    tally = _run(store)

    lic = _lic(store, old)
    assert lic["app"] == SEMPER and "splitInto" not in lic
    assert not any(v.get("splitFrom") for v in store._data["licenses"].values())
    assert apps.read_field(_user(store, "solo-1"), "licenseId", CLASSROOM) == own
    assert _user(store, "solo-1")["licenseApp"] == SEMPER
    assert tally.counts["conflicts"] == 1 and own in tally.conflicts[0]


# ------------------------------------------------------------------ institution


def test_an_institution_splits_its_roster_pool_and_invites(store):
    old = _institution(store)

    tally = _run(store)

    lic = _lic(store, old)
    twin_id = lic["splitInto"]
    twin = _lic(store, twin_id)
    assert (lic["devicesActive"], lic["maxDevices"]) == (2, 4)
    assert (twin["devicesActive"], twin["maxDevices"], twin["seatsUsed"]) == (2, 4, 3)
    seats = store._data[f"licenses/{twin_id}/seats"]
    assert sorted(seats) == ["m1", "m2", "m3"], "a revoked seat is not copied"
    assert seats["m3"]["status"] == "disabled"
    assert seats["m1"]["devicesActive"] == 1 and seats["m3"]["devicesActive"] == 0
    assert seats["m1"].get("deviceIdLock") is None
    assert apps.read_field(seats["m1"], "deviceIdLock", CLASSROOM) == "m1-c"
    assert store._data[f"licenses/{old}/seats"]["m1"]["devicesActive"] == 1
    # A disabled member's lock activates nothing.
    assert sorted(_acts(store, old)) == ["m1-s", "m2-s"]
    assert sorted(_acts(store, twin_id)) == ["m1-c", "m2-c"]

    for uid in ("m1", "m2"):
        assert _mode(store, uid, f"{uid}-s", SEMPER) == "licensed"
        assert _mode(store, uid, f"{uid}-c", CLASSROOM) == "licensed"
        assert apps.read_field(_user(store, uid), "licenseId", CLASSROOM) == twin_id

    invites = store._data["licenseInvites"]
    assert invites[invite_id("later@lab.org", CLASSROOM)]["licenseId"] == twin_id
    held = f"held_{twin_id}_{invite_id('elsewhere@lab.org', CLASSROOM)}"
    assert invites[held]["held"] is True and invites[held]["licenseId"] == twin_id
    assert tally.counts["invites"] == 2 and tally.counts["seats"] == 3


def test_a_member_with_a_classroom_licence_of_their_own_is_left_off_the_twin(store):
    old = _institution(store)
    own = _mint_individual("m2@lab.org", app=CLASSROOM)["license"]["id"]

    tally = _run(store)

    twin_id = _lic(store, old)["splitInto"]
    assert sorted(store._data[f"licenses/{twin_id}/seats"]) == ["m1", "m3"]
    assert apps.read_field(_user(store, "m2"), "licenseId", CLASSROOM) == own
    assert _lic(store, twin_id)["seatsUsed"] == 2
    assert "m2-c" not in _acts(store, twin_id)
    assert tally.counts["conflicts"] == 1


def test_a_floating_licence_activates_nothing_and_leaves_its_leases(store):
    old = _institution(store, seating="floating", max_seats=1)
    seat = store._data[f"licenses/{old}/seats"]["m1"]
    seat.update(leaseExpiresAt="soon", leaseDeviceId="m1-s")
    _lic(store, old)["leasesActive"] = 1

    _run(store)

    twin_id = _lic(store, old)["splitInto"]
    twin = _lic(store, twin_id)
    assert not _acts(store, old) and not _acts(store, twin_id)
    assert "maxDevices" not in twin and "maxDevices" not in _lic(store, old)
    assert twin["leasesActive"] == 0
    assert "leaseDeviceId" not in store._data[f"licenses/{twin_id}/seats"]["m1"]
    assert _lic(store, old)["leasesActive"] == 1


# ------------------------------------------------------------------ left alone


def test_demo_ci_per_app_and_revoked_licences(store):
    store._data["users"] = {}
    demo_user = repo.ensure_demo_license(
        {**_signed_in(store, "d1", "d1@lab.org"), "activeDeviceId": "dev"}, "dev")
    demo = demo_user["licenseId"]
    per_app = _mint_individual("app@lab.org", app=CLASSROOM)["license"]["id"]
    revoked = _mint_individual("gone@lab.org")["license"]["id"]
    repo.revoke_license(revoked, "admin")
    ci = _mint_individual("ci@semper.test", test_account=True)["license"]["id"]
    _lic(store, ci)["redeemedByUid"] = "ci-1"
    store._data["users"]["ci-1"] = {"email": "ci@semper.test",
                                    "testAccount": {"purpose": "ci"}}
    before = copy.deepcopy({k: _lic(store, k) for k in (demo, per_app, ci)})

    tally = _run(store)

    assert {k: _lic(store, k) for k in (demo, per_app, ci)} == before
    assert _lic(store, revoked)["app"] == SEMPER and "splitInto" not in _lic(store, revoked)
    c = tally.counts
    assert (c["demo"], c["already"], c["ci"], c["revoked"], c["split"]) == (1, 1, 1, 1, 0)


def test_only_domain_splits_that_domain_alone(store):
    qa = _individual(store, uid="qa-1", email="qc-ind1@qa.sempermechanics.com")
    other = _mint_individual("someone@lab.org")["license"]["id"]

    tally = _run(store, only_domain="QA.sempermechanics.com")

    assert _lic(store, qa)["app"] == SEMPER
    assert "app" not in _lic(store, other)
    assert tally.counts["outside"] == 1


# ------------------------------------------------------------------ the run


def test_a_dry_run_writes_nothing_and_counts_the_split(store):
    _individual(store)
    _institution(store)
    before = copy.deepcopy(store._data)

    tally = _run(store, apply=False)

    assert store._data == before
    c = tally.counts
    assert (c["split"], c["twins"], c["seats"], c["invites"]) == (2, 2, 3, 2)
    assert c["activations.semper"] == 3 and c["activations.semperclassroom"] == 3
    lines = split.report(tally, apply=False)
    assert lines[0].startswith("licenses: 2 scanned; 2 would be split")
    assert "twins would be minted: 2" in lines[1]


def test_a_second_run_is_a_no_op(store):
    _individual(store)
    _institution(store)
    _run(store)
    after = copy.deepcopy(store._data)

    tally = _run(store)

    assert store._data == after
    assert tally.counts["already"] == 2 and tally.counts["split"] == 0


def test_a_run_that_stops_part_way_finishes_the_same_twin(store, monkeypatch):
    old = _individual(store)
    real = split.Split._stamp
    monkeypatch.setattr(split.Split, "_stamp",
                        lambda *a: (_ for _ in ()).throw(RuntimeError("died")))
    with pytest.raises(RuntimeError):
        _run(store)
    pending = _lic(store, old)["splitPending"]["id"]
    assert apps.read_field(_user(store, "solo-1"), "licenseId", CLASSROOM) == pending
    monkeypatch.setattr(split.Split, "_stamp", real)

    tally = _run(store)

    assert _lic(store, old)["splitInto"] == pending
    assert "splitPending" not in _lic(store, old)
    assert sum(1 for v in store._data["licenses"].values() if v.get("splitFrom")) == 1
    assert tally.counts["resume"] == 1 and tally.counts["twins"] == 0


def test_a_licence_that_changed_since_it_was_read_is_redone(store, monkeypatch):
    old = _individual(store)
    real = split._update
    failed = []

    def flaky(st, snap, patch):
        if "app" in patch and not failed:
            failed.append(True)
            raise FailedPrecondition("changed since read")
        return real(st, snap, patch)

    monkeypatch.setattr(split, "_update", flaky)

    tally = _run(store)

    assert failed and _lic(store, old)["app"] == SEMPER
    assert tally.counts["resume"] == 1
    assert sum(1 for v in store._data["licenses"].values() if v.get("splitFrom")) == 1


def test_pages_beyond_one_read(store, monkeypatch):
    monkeypatch.setattr(split, "PAGE", 2)
    store._data["users"] = {}
    ids = [_mint_individual(f"p{i}@lab.org")["license"]["id"] for i in range(5)]

    tally = _run(store)

    assert tally.counts["split"] == 5
    assert all(_lic(store, i)["app"] == SEMPER for i in ids)


def test_report_lists_twenty_conflicts_and_counts_the_rest():
    tally = split.Tally()
    tally.counts["conflicts"] = 23
    tally.conflicts = [f"users/u{i:02d}: why" for i in range(23)]
    lines = split.report(tally, apply=True)
    assert "would be" not in lines[0]
    assert lines[-1] == "  and 3 more" and lines[-2] == "  users/u19: why"
