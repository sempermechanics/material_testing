"""Floating and assigned seating: leases, roster members, and assigned-mode behaviour."""
from datetime import datetime, timedelta, timezone

import pytest

import fake_firestore

from app import deps, firestore_repo as repo
from app.config import settings
from license_helpers import (  # noqa: F401
    _mint_individual,
    _mint_institution,
    _recording_stubs,
    _signed_in,
)


# ================================================================== floating
# A floating license separates the roster from the count: every member may use
# it, but only maxSeats hold a live lease at once. A member between leases is
# demo — that is the ordinary state for most of the roster, not a failure.

def _mint_floating(max_seats=2):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
        seating="floating",
    )


def _roster(store, *uids):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "access_status": "APPROVED", "mode": "demo"}
        for uid in uids
    }


def test_a_roster_member_without_a_lease_is_demo(store):
    _roster(store, "u1")
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    err, seat, _invite = repo.add_institution_member(license_id, "u1@university.edu")
    assert err == ""
    assert seat["uid"] == "u1"

    # On the roster, entitled to *take* a seat — but holding none yet.
    cfg = repo.resolve_user_config(store._data["users"]["u1"])
    assert cfg["mode"] == "demo"
    assert cfg["licenseSeating"] == "floating"


def test_checkout_makes_a_member_licensed_and_release_gives_it_back(store):
    _roster(store, "u1")
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")

    err, cfg = repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    assert err == ""
    assert cfg["mode"] == "licensed"
    assert store._data["licenses"][license_id]["leasesActive"] == 1

    err, cfg = repo.release_lease({**store._data["users"]["u1"], "uid": "u1"})
    assert err == ""
    assert cfg["mode"] == "demo"
    assert store._data["licenses"][license_id]["leasesActive"] == 0


def test_a_lapsed_lease_is_demo_without_anything_having_released_it(store):
    """The mirror is compared to now, so expiry needs no sweep to take effect.

    That is what keeps effective_mode a pure function of the user document.
    """
    _roster(store, "u1")
    minted = _mint_floating()
    repo.add_institution_member(minted["license"]["id"], "u1@university.edu")
    lapsed = {
        **store._data["users"]["u1"],
        "leaseExpiresAt": datetime.now(timezone.utc) - timedelta(minutes=1),
    }
    assert repo.effective_mode(lapsed) == "demo"


def test_a_full_pool_refuses_the_next_claimant(store):
    _roster(store, "u1", "u2", "u3")
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    for uid in ("u1", "u2", "u3"):
        repo.add_institution_member(license_id, f"{uid}@university.edu")

    for uid in ("u1", "u2"):
        err, _ = repo.checkout_lease({**store._data["users"][uid], "uid": uid}, f"dev-{uid}")
        assert err == "", uid
    err, cfg = repo.checkout_lease({**store._data["users"]["u3"], "uid": "u3"}, "dev-u3")
    assert err == "no_floating_seat"
    assert cfg is None
    # Being refused a seat is not being thrown off the roster.
    assert store._data["licenses"][license_id]["seatsUsed"] == 3


def test_releasing_frees_exactly_one_slot(store):
    _roster(store, "u1", "u2", "u3")
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    for uid in ("u1", "u2", "u3"):
        repo.add_institution_member(license_id, f"{uid}@university.edu")
    for uid in ("u1", "u2"):
        repo.checkout_lease({**store._data["users"][uid], "uid": uid}, f"dev-{uid}")

    repo.release_lease({**store._data["users"]["u1"], "uid": "u1"})
    err, _ = repo.checkout_lease({**store._data["users"]["u3"], "uid": "u3"}, "dev-u3")
    assert err == ""
    assert store._data["licenses"][license_id]["leasesActive"] == 2


def test_re_checkout_extends_rather_than_taking_a_second_slot(store):
    """Re-checkout is the heartbeat. Counting it twice would drain the pool
    with one user."""
    _roster(store, "u1")
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")

    err, first = repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    assert err == ""
    err, second = repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    assert err == ""
    assert store._data["licenses"][license_id]["leasesActive"] == 1
    assert second["leaseExpiresAt"] >= first["leaseExpiresAt"]


def test_an_expired_lease_is_swept_and_its_slot_reclaimed(store):
    """leasesActive drifts upward whenever an app dies mid-lease, so the
    counter alone cannot say whether the pool is full."""
    _roster(store, "u1", "u2")
    minted = _mint_floating(max_seats=1)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.add_institution_member(license_id, "u2@university.edu")
    repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")

    # u1 vanishes without releasing; the lease lapses.
    seats = store._data[f"licenses/{license_id}/seats"]
    seats["u1"]["leaseExpiresAt"] = datetime.now(timezone.utc) - timedelta(hours=1)

    err, _ = repo.checkout_lease({**store._data["users"]["u2"], "uid": "u2"}, "dev-2")
    assert err == "", "the abandoned slot must be reclaimable"
    assert store._data["licenses"][license_id]["leasesActive"] == 1


def test_checkout_refuses_someone_not_on_the_roster(store):
    _roster(store, "u1")
    minted = _mint_floating()
    store._data["users"]["u1"]["licenseId"] = minted["license"]["id"]
    store._data["users"]["u1"]["licenseSeating"] = "floating"
    err, _ = repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    assert err == "not_eligible"


def test_checkout_on_an_assigned_license_is_refused(store):
    """Nothing to check out — an assigned seat is always entitled. Accepting
    it would let a client invent a lease field on a license that has none."""
    _roster(store, "u1")
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "u1@university.edu", "dev-1", minted["key"])
    err, _ = repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    assert err == "seating_not_floating"
    assert license_id  # minted, and untouched by the refusal


def test_revoking_a_seat_frees_its_live_lease(store):
    _roster(store, "u1")
    minted = _mint_floating(max_seats=1)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    assert store._data["licenses"][license_id]["leasesActive"] == 1

    assert repo.revoke_institution_seat(license_id, "u1") is True
    assert store._data["licenses"][license_id]["leasesActive"] == 0
    assert store._data["licenses"][license_id]["seatsUsed"] == 0


def test_release_is_idempotent(store):
    _roster(store, "u1")
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")
    repo.release_lease({**store._data["users"]["u1"], "uid": "u1"})
    err, _ = repo.release_lease({**store._data["users"]["u1"], "uid": "u1"})
    assert err == ""
    assert store._data["licenses"][license_id]["leasesActive"] == 0, "must not go negative"


# ---------------------------------------------------- adding roster members

def test_adding_a_member_who_never_signed_in_creates_an_invite(store):
    """IT works from a list of addresses and cannot make people sign up on
    cue, so an unknown address is a promise rather than a rejection."""
    store._data["users"] = {}
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    err, seat, invite = repo.add_institution_member(
        license_id, "Nobody@University.edu", invited_by_uid="it-admin",
    )
    assert err == ""
    assert seat is None
    assert invite["email"] == "nobody@university.edu"  # normalised
    assert invite["licenseId"] == license_id
    assert invite["invitedByUid"] == "it-admin"
    # A promise is not a seat: nothing is consumed until someone claims it.
    assert store._data["licenses"][license_id].get("seatsUsed", 0) == 0


def test_an_invite_is_redeemed_at_first_sign_in(store):
    """The whole point of the invite: the newcomer lands licensed, not demo
    then upgraded."""
    store._data["users"] = {}
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "newcomer@university.edu")

    user = {"uid": "new-1", "email": "newcomer@university.edu",
            "access_status": "APPROVED", "emailVerified": True}
    store._data["users"]["new-1"] = dict(user)
    out = repo.ensure_entitlement(user, None)

    assert out["licenseId"] == license_id
    assert out["licenseKind"] == "institution"
    assert store._data[f"licenses/{license_id}/seats"]["new-1"]["status"] == "active"
    assert store._data["licenses"][license_id]["seatsUsed"] == 1
    # Consumed, so a second sign-in cannot take a second seat.
    assert store._data.get("licenseInvites", {}) == {}


def test_an_unverified_address_never_redeems_an_invite(store):
    """The address is the entire claim to the seat, so an unproven one takes
    nothing — otherwise anyone who can type it gets the seat meant for them."""
    store._data["users"] = {}
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "newcomer@university.edu")

    user = {"uid": "imposter", "email": "newcomer@university.edu",
            "access_status": "APPROVED", "emailVerified": False}
    store._data["users"]["imposter"] = dict(user)
    out = repo.claim_pending_invite(user)

    assert out.get("licenseId") is None
    assert store._data.get(f"licenses/{license_id}/seats", {}) == {}
    # Still waiting for the real owner of the address.
    assert len(store._data["licenseInvites"]) == 1


def test_an_invite_to_a_second_licence_is_refused(store):
    """An invite is a promise of a licence, so the one-licence rule counts it."""
    store._data["users"] = {}
    first = _mint_floating()["license"]["id"]
    second = _mint_floating()["license"]["id"]
    repo.add_institution_member(first, "shared@university.edu")
    err, seat, invite = repo.add_institution_member(second, "shared@university.edu")
    assert err == "member_already_licensed"
    assert seat is None and invite is None


def test_re_inviting_to_the_same_licence_is_a_no_op(store):
    """IT pasting the same list twice must not be an error."""
    store._data["users"] = {}
    license_id = _mint_floating()["license"]["id"]
    repo.add_institution_member(license_id, "twice@university.edu")
    err, _seat, invite = repo.add_institution_member(license_id, "twice@university.edu")
    assert err == ""
    assert invite["licenseId"] == license_id
    assert len(store._data["licenseInvites"]) == 1


def test_a_starved_invite_claim_mints_no_demo_key(store, monkeypatch):
    """TD-33: a claim that lost every attempt to contention, with nobody
    winning, used to fall through to the Demo mint. The Demo `licenseId` then
    short-circuited every later claim, so the invite stayed pending for good.
    The request is now served unlicensed (Demo limits) and the next one claims.
    """
    store._data["users"] = {}
    license_id = _mint_floating()["license"]["id"]
    repo.add_institution_member(license_id, "busy@university.edu")
    user = {"uid": "busy-1", "email": "busy@university.edu",
            "access_status": "APPROVED", "emailVerified": True}
    store._data["users"]["busy-1"] = dict(user)
    demo_before = {k for k, v in store._data["licenses"].items() if v.get("mode") == "demo"}

    real_claim = repo.claim_seat
    monkeypatch.setattr(repo, "claim_seat", lambda *a, **k: repo._CONTENDED)
    out = repo.ensure_entitlement(dict(user), "dev-1")

    assert not out.get("licenseId")
    assert repo.resolve_user_config(out)["mode"] == "demo"
    assert {k for k, v in store._data["licenses"].items() if v.get("mode") == "demo"} == demo_before
    assert len(repo.list_institution_invites(license_id)) == 1, "the invite must stay pending"

    monkeypatch.setattr(repo, "claim_seat", real_claim)
    out = repo.ensure_entitlement(dict(store._data["users"]["busy-1"], uid="busy-1"), "dev-1")
    assert out["licenseId"] == license_id
    assert out["mode"] == "licensed"
    assert repo.list_institution_invites(license_id) == []


def test_a_non_transient_claim_failure_still_mints_demo(store, monkeypatch):
    """Seats exhausted is not going to clear on the next request, so the
    account gets its Demo key rather than re-running the claim every time."""
    store._data["users"] = {}
    license_id = _mint_floating()["license"]["id"]
    repo.add_institution_member(license_id, "full@university.edu")
    user = {"uid": "full-1", "email": "full@university.edu",
            "access_status": "APPROVED", "emailVerified": True}
    store._data["users"]["full-1"] = dict(user)
    monkeypatch.setattr(repo, "claim_seat", lambda *a, **k: "license_seats_exhausted")
    out = repo.ensure_entitlement(dict(user), "dev-1")
    assert out["licenseId"] and out["licenseId"] != license_id
    assert out["mode"] == "demo"


def test_a_revoked_invite_is_never_redeemed(store):
    store._data["users"] = {}
    license_id = _mint_floating()["license"]["id"]
    _err, _seat, invite = repo.add_institution_member(license_id, "gone@university.edu")

    assert repo.revoke_institution_invite(license_id, invite["id"]) is True
    assert repo.revoke_institution_invite(license_id, invite["id"]) is False

    user = {"uid": "gone-1", "email": "gone@university.edu",
            "access_status": "APPROVED", "emailVerified": True}
    store._data["users"]["gone-1"] = dict(user)
    out = repo.ensure_entitlement(user, "dev-1")
    # Falls through to the Demo key everyone gets.
    assert out["mode"] == "demo"
    assert store._data.get(f"licenses/{license_id}/seats", {}) == {}


def test_one_institution_cannot_revoke_anothers_invite(store):
    store._data["users"] = {}
    mine = _mint_floating()["license"]["id"]
    theirs = _mint_floating()["license"]["id"]
    _err, _seat, invite = repo.add_institution_member(theirs, "theirs@university.edu")
    assert repo.revoke_institution_invite(mine, invite["id"]) is False
    assert len(store._data["licenseInvites"]) == 1


def test_an_invite_whose_licence_was_revoked_is_dropped(store):
    store._data["users"] = {}
    license_id = _mint_floating()["license"]["id"]
    repo.add_institution_member(license_id, "orphan@university.edu")
    store._data["licenses"][license_id]["status"] = "revoked"

    user = {"uid": "orphan-1", "email": "orphan@university.edu",
            "access_status": "APPROVED", "emailVerified": True}
    store._data["users"]["orphan-1"] = dict(user)
    out = repo.claim_pending_invite(user)

    assert out.get("licenseId") is None
    assert store._data["licenseInvites"] == {}


def test_adding_a_member_consumes_no_floating_slot(store):
    _roster(store, "u1", "u2")
    minted = _mint_floating(max_seats=1)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.add_institution_member(license_id, "u2@university.edu")
    assert store._data["licenses"][license_id]["leasesActive"] == 0
    assert store._data["licenses"][license_id]["seatsUsed"] == 2


def test_adding_a_member_twice_is_idempotent(store):
    _roster(store, "u1")
    minted = _mint_floating()
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    err, _seat, _invite = repo.add_institution_member(license_id, "u1@university.edu")
    assert err == ""
    assert store._data["licenses"][license_id]["seatsUsed"] == 1


def test_re_adding_a_member_does_not_wipe_their_device_lock(store):
    """IT adds by email and passes no device; the member's existing lock must
    survive, or their next request would look like a device mismatch."""
    _roster(store, "u1")
    minted = _mint_institution()
    license_id = minted["license"]["id"]
    repo.activate_license("u1", "u1@university.edu", "dev-1", minted["key"])
    repo.add_institution_member(license_id, "u1@university.edu")
    seats = store._data[f"licenses/{license_id}/seats"]
    assert seats["u1"]["deviceIdLock"] == "dev-1"


# ------------------------------------------------ assigned stays unchanged

def test_an_assigned_license_needs_no_lease(store):
    """Every shipped institution license is assigned, and none of them may
    start requiring a checkout."""
    _roster(store, "u1")
    minted = _mint_institution()
    err, cfg = repo.activate_license("u1", "u1@university.edu", "dev-1", minted["key"])
    assert err == ""
    assert cfg["mode"] == "licensed"
    assert cfg["licenseSeating"] == "assigned"
    assert cfg["leaseExpiresAt"] is None


def test_a_license_predating_seating_reads_as_assigned(store):
    """No migration ships with this: a document with no `seating` already
    means permanently held seats, which is exactly what assigned is."""
    cfg = repo.resolve_user_config({"uid": "u1", "mode": "licensed"})
    assert cfg["mode"] == "licensed"
    assert cfg["licenseSeating"] == "assigned"


@pytest.mark.asyncio
async def test_checkout_and_release_over_http(client, monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "mode": "demo", "plan": "demo"})
    store._data["users"] = {
        "dev-user": {"email": "dev@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_floating(max_seats=1)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "dev@university.edu")
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER, **store._data["users"]["dev-user"], "uid": "dev-user",
    })

    resp = await client.post("/v1/licenses/checkout", headers={"X-Device-Id": "dev-device"})
    assert resp.status_code == 200, resp.text
    body = resp.json()["config"]
    assert body["mode"] == "licensed"
    assert body["licenseSeating"] == "floating"
    assert body["leaseHeartbeatMinutes"] == settings.LICENSE_LEASE_HEARTBEAT_MINUTES

    resp = await client.post("/v1/licenses/release")
    assert resp.status_code == 200, resp.text
    assert resp.json()["config"]["mode"] == "demo"


@pytest.mark.asyncio
async def test_checkout_on_a_full_pool_is_409_over_http(client, monkeypatch):
    """A full pool is not an account problem — the member stays eligible and
    the app offers to retry, so this must read as a conflict, not a denial."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "dev@university.edu", "access_status": "APPROVED", "mode": "demo"},
        "other": {"email": "other@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_floating(max_seats=1)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "dev@university.edu")
    repo.add_institution_member(license_id, "other@university.edu")
    repo.checkout_lease({**store._data["users"]["other"], "uid": "other"}, "dev-2")
    monkeypatch.setattr(deps, "_DEV_USER", {
        **deps._DEV_USER, **store._data["users"]["dev-user"], "uid": "dev-user",
    })

    resp = await client.post("/v1/licenses/checkout", headers={"X-Device-Id": "dev-device"})
    assert resp.status_code == 409
    assert resp.json()["detail"] == "no_floating_seat"


@pytest.mark.asyncio
async def test_add_member_over_http(client, monkeypatch):
    from app import deps as _deps

    monkeypatch.setattr(_deps, "_DEV_USER", {**_deps._DEV_USER, "email": "it@university.edu"})
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    store._data["users"] = {
        "dev-user": {"email": "it@university.edu", "access_status": "APPROVED", "mode": "demo"},
        "student": {"email": "s@university.edu", "access_status": "APPROVED", "mode": "demo"},
    }
    minted = _mint_floating()
    license_id = minted["license"]["id"]

    resp = await client.post(
        f"/v1/institutions/licenses/{license_id}/seats",
        json={"email": "S@University.edu"},  # case and spacing are normalised
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["seat"]["uid"] == "student"

    # An address with no account is a promise, not a rejection.
    missing = await client.post(
        f"/v1/institutions/licenses/{license_id}/seats",
        json={"email": "nobody@university.edu"},
    )
    assert missing.status_code == 200, missing.text
    assert missing.json()["seat"] is None
    assert missing.json()["invite"]["email"] == "nobody@university.edu"

    listed = await client.get(f"/v1/institutions/licenses/{license_id}/seats")
    assert listed.status_code == 200, listed.text
    assert [i["email"] for i in listed.json()["invites"]] == ["nobody@university.edu"]

    invite_id = listed.json()["invites"][0]["id"]
    dropped = await client.delete(
        f"/v1/institutions/licenses/{license_id}/invites/{invite_id}"
    )
    assert dropped.status_code == 200, dropped.text
    again = await client.get(f"/v1/institutions/licenses/{license_id}/seats")
    assert again.json()["invites"] == []


def test_floating_requires_max_seats_at_mint():
    """An uncapped pool is an assigned license with extra steps — nobody would
    ever be refused a lease."""
    from app.models import AdminLicenseCreate

    with pytest.raises(ValueError):
        AdminLicenseCreate(
            kind="institution", domainLock="university.edu",
            adminEmails=["it@university.edu"], seating="floating",
        )
    with pytest.raises(ValueError):
        AdminLicenseCreate(
            kind="individual", emailLock="a@b.com", deviceIdLock="device-0001",
            seating="floating",
        )


def test_the_seat_listing_shows_who_holds_a_lease(store):
    """The roster view is what institution IT uses to see current usage, so
    "eligible" and "using a seat right now" have to be distinguishable."""
    _roster(store, "u1", "u2")
    minted = _mint_floating(max_seats=2)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.add_institution_member(license_id, "u2@university.edu")
    repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")

    seats = {s["uid"]: s for s in repo.list_institution_seats(license_id)}
    assert seats["u1"]["leaseExpiresAt"] is not None
    assert seats["u1"]["lastHeartbeatAt"] is not None
    assert seats["u2"]["leaseExpiresAt"] is None, "eligible, but holding nothing"


def test_the_license_summary_reports_pool_usage(store):
    """The console shows `leasesActive/maxSeats in use` beside the roster size;
    both numbers have to reach it."""
    _roster(store, "u1")
    minted = _mint_floating(max_seats=3)
    license_id = minted["license"]["id"]
    repo.add_institution_member(license_id, "u1@university.edu")
    repo.checkout_lease({**store._data["users"]["u1"], "uid": "u1"}, "dev-1")

    summary = repo.institution_license_summary(license_id)
    assert summary["seating"] == "floating"
    assert summary["maxSeats"] == 3
    assert summary["leasesActive"] == 1
    assert summary["seatsUsed"] == 1
