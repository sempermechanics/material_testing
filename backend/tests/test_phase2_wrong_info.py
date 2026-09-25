"""Answers that named the wrong cause, or described state that was not true.

Each section pins one fix: the lease sweep counted a reclaimed seat twice
(TD-100), a lost claim race was reported as a full licence (TD-101), a quota
refusal told a lapsed licence holder to delete analyses (TD-103), Extend could
shorten or end a licence and still say "extended" (TD-104), and a claim wrote
the licence terms it read before its transaction (TD-105).

The fake store has no transaction isolation, so a race is staged by hand: the
loser's stale read is taken first, the winner commits, then the loser's
transaction body runs against what the winner left.
"""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from app.config import settings
from app.repo import leases
from app.repo.claims import _institution_member_patch
from license_helpers import _mint_institution, _signed_in


def _now():
    return datetime.now(timezone.utc)


def _mint_floating(max_seats=2):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
        seating="floating",
    )


def _roster(store, license_id, *uids):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "access_status": "APPROVED", "mode": "demo"}
        for uid in uids
    }
    for uid in uids:
        err, _seat, _invite = repo.add_institution_member(license_id, f"{uid}@university.edu")
        assert err == ""


class _FrozenSeatQuery:
    """A licence reference whose seat query answers with a list read earlier —
    the view a sweeper has when its query ran before another one committed."""

    def __init__(self, ref, docs):
        self._ref, self._docs = ref, docs

    def __getattr__(self, name):
        return getattr(self._ref, name)

    def collection(self, _name):
        return self

    def where(self, *_args):
        return self

    def limit(self, _n):
        return self

    def stream(self):
        return iter(self._docs)


def _checkout(store, uid):
    return repo.checkout_lease({**store._data["users"][uid], "uid": uid}, f"dev-{uid}")


# ============================================================ TD-100 sweep
# The sweep's query runs outside a transaction, so two checkouts arriving
# together both find the same expired seat. Only one of them may count it.

def test_two_sweeps_over_the_same_expired_seat_reclaim_it_once(store):
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    _roster(store, license_id, "u1", "u2", "u3", "u4")
    assert _checkout(store, "u1")[0] == ""
    assert _checkout(store, "u2")[0] == ""
    lic = store._data["licenses"][license_id]
    assert lic["leasesActive"] == 2

    # u1's app died; its lease lapsed without a release.
    seats = store._data[f"licenses/{license_id}/seats"]
    seats["u1"]["leaseExpiresAt"] = _now() - timedelta(hours=1)
    lic_ref = repo.db().collection("licenses").document(license_id)
    now = _now()
    # The second checkout's sweep ran its query before the first committed.
    stale = list(lic_ref.collection("seats").where("leaseExpiresAt", "<=", now).stream())
    assert [d.id for d in stale] == ["u1"]

    assert leases._sweep_expired_leases(lic_ref, now) == 1
    assert leases._sweep_expired_leases(_FrozenSeatQuery(lic_ref, stale), now) == 0
    # One seat came back, not two. The batch this replaced left 0 here.
    assert lic["leasesActive"] == 1

    # So the pool still stops at maxSeats: one more fits, the next does not.
    assert _checkout(store, "u3")[0] == ""
    assert _checkout(store, "u4")[0] == "no_floating_seat"
    assert lic["leasesActive"] == 2


def test_a_lease_renewed_after_the_sweep_query_is_left_alone(store):
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    _roster(store, license_id, "u1")
    assert _checkout(store, "u1")[0] == ""
    seats = store._data[f"licenses/{license_id}/seats"]
    seats["u1"]["leaseExpiresAt"] = _now() - timedelta(minutes=1)
    lic_ref = repo.db().collection("licenses").document(license_id)
    now = _now()
    stale = list(lic_ref.collection("seats").where("leaseExpiresAt", "<=", now).stream())

    # The holder's heartbeat lands between that query and the reclaim.
    seats["u1"]["leaseExpiresAt"] = _now() + timedelta(hours=1)
    assert leases._reclaim_expired_lease(lic_ref, stale[0].reference, now) is False
    assert seats["u1"]["leaseExpiresAt"] > now
    assert store._data["licenses"][license_id]["leasesActive"] == 1


def test_the_sweep_never_pushes_the_counter_below_zero(store):
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    _roster(store, license_id, "u1")
    assert _checkout(store, "u1")[0] == ""
    store._data["licenses"][license_id]["leasesActive"] = 0  # already drifted low
    store._data[f"licenses/{license_id}/seats"]["u1"]["leaseExpiresAt"] = (
        _now() - timedelta(hours=1)
    )
    lic_ref = repo.db().collection("licenses").document(license_id)

    assert leases._sweep_expired_leases(lic_ref, _now()) == 1
    assert store._data["licenses"][license_id]["leasesActive"] == 0
    assert "leaseExpiresAt" not in store._data[f"licenses/{license_id}/seats"]["u1"]


def test_a_reclaim_that_loses_every_attempt_counts_nothing(store, monkeypatch):
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    _roster(store, license_id, "u1")
    assert _checkout(store, "u1")[0] == ""
    store._data[f"licenses/{license_id}/seats"]["u1"]["leaseExpiresAt"] = (
        _now() - timedelta(hours=1)
    )
    lic_ref = repo.db().collection("licenses").document(license_id)
    monkeypatch.setattr(leases, "_run_tx", lambda body, on_contended: on_contended())

    assert leases._sweep_expired_leases(lic_ref, _now()) == 0
    assert store._data["licenses"][license_id]["leasesActive"] == 1


# ================================================== TD-101 lost claim race
# Losing the race is not a full licence. IT was told "no seats left" when a
# retry would have succeeded.

def test_a_lost_race_to_add_a_member_is_claim_contended(store, monkeypatch):
    minted = _mint_institution(max_seats=5)
    license_id = minted["license"]["id"]
    store._data["users"] = {}
    _signed_in(store, "u1", "u1@university.edu")
    monkeypatch.setattr(repo, "claim_seat", lambda *a, **k: repo._CONTENDED)

    err, seat, invite = repo.add_institution_member(license_id, "u1@university.edu")
    assert (err, seat, invite) == ("claim_contended", None, None)


def test_a_lost_race_to_activate_is_claim_contended(store, monkeypatch):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution(max_seats=5)
    monkeypatch.setattr(repo, "claim_seat", lambda *a, **k: repo._CONTENDED)

    err, cfg = repo.activate_license("u1", "a@university.edu", "dev-1", minted["key"])
    assert (err, cfg) == ("claim_contended", None)


@pytest.mark.asyncio
async def test_a_lost_race_is_503_over_http_on_both_routes(client, monkeypatch):
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "access_status": "APPROVED", "mode": "demo"},
        "student": {"email": "s@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution(max_seats=5)
    license_id = minted["license"]["id"]
    monkeypatch.setattr(repo, "claim_seat", lambda *a, **k: repo._CONTENDED)

    added = await client.post(
        f"/v1/institutions/licenses/{license_id}/seats", json={"email": "s@university.edu"},
    )
    assert added.status_code == 503, added.text
    assert added.json()["detail"] == "claim_contended"

    activated = await client.post(
        "/v1/licenses/activate", json={"key": minted["key"]},
        headers={"X-Device-Id": "dev-device"},
    )
    assert activated.status_code == 503, activated.text
    assert activated.json()["detail"] == "claim_contended"


# ============================================ TD-103 quota names the cause

def _licensed(**extra):
    return {"mode": "licensed", "licenseKind": "institution", **extra}


def test_inactive_licence_reason():
    past = _now() - timedelta(days=30)
    assert repo.inactive_licence_reason({"mode": "demo"}) == ""
    assert repo.inactive_licence_reason(_licensed()) == ""
    assert repo.inactive_licence_reason(
        _licensed(licenseExpiresAt=past, licenseGraceDays=0)) == repo.INACTIVE_LICENCE_ENDED
    # In grace is still licensed — nothing to explain.
    assert repo.inactive_licence_reason(
        _licensed(licenseExpiresAt=past, licenseGraceDays=60)) == ""
    assert repo.inactive_licence_reason(
        _licensed(licenseSeating="floating")) == repo.INACTIVE_NO_SEAT
    assert repo.inactive_licence_reason(_licensed(
        licenseSeating="floating", leaseExpiresAt=_now() + timedelta(hours=1))) == ""


_SHA = "a" * 64


@pytest.mark.parametrize(("extra", "says"), [
    ({"licenseExpiresAt": _now() - timedelta(days=30), "licenseGraceDays": 0},
     "Your licence has ended"),
    ({"licenseSeating": "floating"}, "No shared seat is free"),
    ({"mode": "demo", "plan": "demo"}, "Delete an older analysis"),
])
@pytest.mark.asyncio
async def test_the_quota_refusal_says_why_the_cap_dropped(client, monkeypatch, extra, says):
    from app import audit, rate_limit

    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(rate_limit.session_bucket, "allow", lambda uid: True)
    monkeypatch.setattr(settings, "DEMO_MAX_ANALYSES", 2)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, **extra})
    store._data["users"] = {"dev-user": {"email": "dev@test", "access_status": "APPROVED"}}
    store._data["sessions"] = {
        f"s{i}": {"uid": "dev-user", "status": "COMPLETED", "fileCount": 1, "completedCount": 1}
        for i in range(3)
    }

    r = await client.post("/v1/sessions", json={
        "specimen": "s", "files": [{"name": "a", "role": "bundle", "bytes": 10, "sha256": _SHA}],
    })
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    # The code the app branches on is unchanged; only the sentence moved.
    assert detail.startswith("session_quota_exceeded: 3/2 analyses stored. ")
    assert says in detail


# ================================================ TD-104 Extend only extends

def _timed(store, days=30):
    minted = repo.create_individual_license(
        email_lock="a@b.com", device_id_lock="dev-1", created_by_uid="admin",
        expires_at=_now() + timedelta(days=days),
    )
    return minted["license"]["id"]


@pytest.mark.parametrize(("delta", "code"), [
    (timedelta(days=-1), "expiry_in_past"),
    (timedelta(days=10), "expiry_before_current"),
])
def test_extend_refuses_a_date_that_would_end_or_shorten(store, delta, code):
    license_id = _timed(store, days=30)
    before = dict(store._data["licenses"][license_id])
    with pytest.raises(repo.LicenseTermsRejected) as raised:
        repo.update_license(license_id, {"expiresAt": _now() + delta}, "admin")
    assert raised.value.code == code
    assert store._data["licenses"][license_id] == before


def test_extend_to_a_later_date_still_works(store):
    license_id = _timed(store, days=30)
    later = _now() + timedelta(days=400)
    assert repo.update_license(license_id, {"expiresAt": later}, "admin")["expiresAt"] == later


@pytest.mark.asyncio
async def test_a_refused_extend_is_422_and_touches_nothing(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    license_id = _timed(store, days=30)
    before = dict(store._data["licenses"][license_id])

    # Sent alongside a device change: the rejected date must not leave the
    # lock cleared behind a 422.
    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}",
        json={"expiresAt": "2020-01-01T00:00:00Z", "clearDeviceLock": True},
    )
    assert resp.status_code == 422, resp.text
    assert resp.json()["detail"] == "expiry_in_past"
    assert store._data["licenses"][license_id] == before

    perpetual = repo.create_individual_license(
        email_lock="p@b.com", device_id_lock="dev-2", created_by_uid="admin",
    )["license"]["id"]
    resp = await client.patch(
        f"/v1/admin/licenses/{perpetual}", json={"expiresAt": "2099-01-01T00:00:00Z"},
    )
    assert resp.status_code == 422, resp.text
    assert resp.json()["detail"] == "license_perpetual"


@pytest.mark.asyncio
async def test_a_refusal_that_only_update_license_sees_is_still_422(client, monkeypatch):
    """The route checks first; `update_license` checks again against what it
    reads, for an edit landing in between. That second answer is a 422 too."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    license_id = _timed(store, days=30)
    # Blind the route's own check: it reads through `get_license`, which
    # `update_license` does not use.
    monkeypatch.setattr(repo, "get_license", lambda _license_id: None)

    resp = await client.patch(
        f"/v1/admin/licenses/{license_id}", json={"expiresAt": "2020-01-01T00:00:00Z"},
    )
    assert resp.status_code == 422, resp.text
    assert resp.json()["detail"] == "expiry_in_past"


# ============================================ TD-105 claim writes live terms

def test_a_seat_claim_writes_the_terms_read_in_its_transaction(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_institution(max_seats=5)
    license_id = minted["license"]["id"]
    stale = repo.get_license(license_id)
    patch = _institution_member_patch(license_id, stale)

    # Renewed between the caller's read and the claim.
    later = _now() + timedelta(days=400)
    repo.update_license(license_id, {"maxAnalyses": 900}, "admin")
    store._data["licenses"][license_id]["expiresAt"] = later

    assert repo.claim_seat(license_id, "u1", "a@university.edu", "", patch) == ""
    user = store._data["users"]["u1"]
    assert user["licenseMaxAnalyses"] == 900
    assert user["licenseExpiresAt"] == later
    # The caller merges this dict into its answer, so it must match too.
    assert patch["licenseMaxAnalyses"] == 900


def test_an_individual_claim_writes_the_terms_read_in_its_transaction(store):
    store._data["users"] = {}
    minted = repo.create_individual_license(
        email_lock="solo@lab.org", created_by_uid="admin",
        expires_at=_now() + timedelta(days=5),
    )
    license_id = minted["license"]["id"]
    _signed_in(store, "solo-1", "solo@lab.org")
    patch = repo._individual_member_patch(license_id, repo.get_license(license_id))

    later = _now() + timedelta(days=400)
    repo.update_license(license_id, {"expiresAt": later}, "admin")

    assert repo.claim_individual_license(license_id, "solo-1", "solo@lab.org", patch) == ""
    assert store._data["users"]["solo-1"]["licenseExpiresAt"] == later


def test_adding_a_member_during_a_renewal_gets_the_renewed_terms(store, monkeypatch):
    """End to end through `add_institution_member`, which reads the licence
    before it claims: the renewal lands in that gap, and its fan-out has
    already run past the member who is not seated yet."""
    minted = _mint_institution(max_seats=5)
    license_id = minted["license"]["id"]
    store._data["users"] = {}
    _signed_in(store, "u1", "u1@university.edu")
    real_claim = repo.claim_seat
    later = _now() + timedelta(days=400)

    def renewed_first(*a, **k):
        store._data["licenses"][license_id]["expiresAt"] = later
        store._data["licenses"][license_id]["graceDays"] = 21
        return real_claim(*a, **k)

    monkeypatch.setattr(repo, "claim_seat", renewed_first)
    err, seat, _invite = repo.add_institution_member(license_id, "u1@university.edu")
    assert err == "" and seat["uid"] == "u1"
    user = store._data["users"]["u1"]
    assert user["licenseExpiresAt"] == later
    assert user["licenseGraceDays"] == 21


def test_a_claim_patch_without_terms_gets_none_added(store):
    """Only a patch that carries the mirror is refreshed; a caller writing
    something else through the claim is not asking for one."""
    minted = _mint_institution(max_seats=5)
    license_id = minted["license"]["id"]
    store._data["users"] = {}
    _signed_in(store, "u1", "u1@university.edu")

    assert repo.claim_seat(license_id, "u1", "u1@university.edu", "", {"mode": "licensed"}) == ""
    assert "licenseExpiresAt" not in store._data["users"]["u1"]
