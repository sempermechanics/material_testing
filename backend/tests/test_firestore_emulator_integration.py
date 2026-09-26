"""Firestore emulator / integration tier.

Skipped unless FIRESTORE_EMULATOR_HOST is set; CI sets it (see the Tier 4 job in
.github/workflows/ci.yml), so this runs on every backend change.

This tier exists because tests/fake_firestore.py applies transactional writes
immediately, with no isolation and no retries. Every concurrency guarantee in
firestore_repo — consume_nonce being single-use, complete_file not double-
counting, bump_session_progress converging — is therefore unfalsifiable against
the fake. Those are exactly the invariants that matter under load, so they are
asserted here against a real Firestore.

Drive calls are stubbed: the emulator only covers the metadata plane.
"""
import os
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest

from app import drive, firestore_repo as repo
from app.config import settings
from app.models import FileComplete, FileSpec, SessionCreate

pytestmark = pytest.mark.skipif(
    not os.environ.get("FIRESTORE_EMULATOR_HOST"),
    reason="Set FIRESTORE_EMULATOR_HOST to run the Firestore emulator integration tier",
)


@pytest.fixture
def emulator_repo(monkeypatch):
    # Force a fresh client pointed at the emulator (Client picks up the env var).
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", True)
    monkeypatch.setattr(repo, "_DB", None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    yield repo
    monkeypatch.setattr(repo, "_DB", None)


def test_create_complete_list_delete_roundtrip(emulator_repo, monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {
            "userFolderId": "user-folder",
            "sessionFolderId": "session-folder",
            "bundle": "session-folder",
            "metadata": "session-folder",
        },
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://upload.example/session")
    monkeypatch.setattr(
        drive, "get_file_meta",
        lambda *a, **k: {"size": 10, "md5": "d" * 32, "parents": ["session-folder"]},
    )
    monkeypatch.setattr(drive, "delete_file", lambda *a, **k: None)

    uid = f"emu-{uuid.uuid4().hex[:8]}"
    user = {
        "uid": uid, "email": f"{uid}@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": "d1",
    }
    device = {"deviceId": "d1", "uid": uid, "status": "ACTIVE"}
    body = SessionCreate(
        specimen="emu",
        localSessionId=f"local-{uid}",
        files=[FileSpec(name="Session.zip", role="bundle", bytes=10, sha256="e" * 64)],
    )
    sid = uuid.uuid4().hex
    emulator_repo.create_session(sid, user, device, body)
    emulator_repo.set_session_folder(sid, "session-folder")
    file_id = f"{sid}_bundle_Session.zip"
    emulator_repo.create_file(
        sid, uid, file_id, body.files[0], "https://upload.example/session",
    )
    outcome = emulator_repo.complete_file(
        file_id, uid, FileComplete(sessionId=sid, driveFileId="drive-1", bytes=10, md5="d" * 32),
    )
    assert outcome == "ok"
    emulator_repo.bump_session_progress(sid)

    sessions, _ = emulator_repo.list_user_sessions(uid, limit=10)
    assert any(s["sessionId"] == sid for s in sessions)
    files, next_token = emulator_repo.list_session_files(sid)
    assert files and files[0]["status"] == "COMPLETED"
    assert next_token is None

    removed = emulator_repo.delete_session(sid)
    assert removed >= 1
    assert emulator_repo.get_session(sid) is None


# --------------------------------------------------------------- contention
#
# Everything below is untestable against the fake store, which has no isolation.

def _seed_session(repo_, uid, file_count):
    """A session with `file_count` PENDING files, ready to be completed."""
    sid = uuid.uuid4().hex
    user = {"uid": uid, "email": f"{uid}@example.com", "role": "user",
            "access_status": "APPROVED", "activeDeviceId": "d1"}
    device = {"deviceId": "d1", "uid": uid, "status": "ACTIVE"}
    specs = [
        FileSpec(name=f"f{i}.zip", role="bundle", bytes=10, sha256=f"{i:064x}")
        for i in range(file_count)
    ]
    body = SessionCreate(specimen="emu", localSessionId=f"local-{sid}", files=specs)
    repo_.create_session(sid, user, device, body)
    repo_.set_session_folder(sid, "session-folder")
    ids = []
    for spec in specs:
        file_id = f"{sid}_bundle_{spec.name}"
        repo_.create_file(sid, uid, file_id, spec, "https://upload.example/s")
        ids.append(file_id)
    return sid, ids


def test_uncontended_nonce_is_single_use(emulator_repo):
    """Liveness plus safety on the ordinary path: the one caller wins, and the
    nonce cannot be spent twice."""
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    nonce = emulator_repo.issue_nonce(uid, "d1")

    assert emulator_repo.consume_nonce(nonce, uid, "d1") is True
    assert emulator_repo.consume_nonce(nonce, uid, "d1") is False


def test_concurrent_nonce_consumers_never_exceed_one(emulator_repo):
    """The replay guarantee, and the security-critical half of it.

    A get-then-delete race let two callers both see a live nonce. Asserting "at
    most one" rather than "exactly one" is deliberate: under heavy contention on
    a single document, Firestore may abort every contender, and consume_nonce
    then denies them all. That is fail-closed and correct — a legitimate client
    never races itself on a nonce, it just requests a fresh challenge. Two
    winners would be the actual bug, and would never be acceptable.
    """
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    nonce = emulator_repo.issue_nonce(uid, "d1")

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(
            lambda _: emulator_repo.consume_nonce(nonce, uid, "d1"), range(8),
        ))

    assert sum(1 for r in results if r) <= 1, f"nonce consumed {sum(results)} times"
    # And whatever happened, it must not raise — losing a race is a 401, not a 500.
    assert all(isinstance(r, bool) for r in results)


def test_wrong_owner_cannot_burn_a_live_nonce(emulator_repo):
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    nonce = emulator_repo.issue_nonce(uid, "d1")

    assert emulator_repo.consume_nonce(nonce, "someone-else", "d1") is False
    assert emulator_repo.consume_nonce(nonce, uid, "other-device") is False
    assert emulator_repo.consume_nonce(nonce, uid, "d1") is True, "valid caller lost its nonce"


def _complete_then_bump(repo_, sid, uid, file_id):
    """Exactly what the route does: bump only on a FIRST completion.

    bump_session_progress is an unconditional +1 — its correctness depends on
    complete_file returning "already" for a retry. Mirroring the route is the
    only way to test that contract rather than a fiction.
    """
    outcome = repo_.complete_file(
        file_id, uid,
        FileComplete(sessionId=sid, driveFileId=f"drive-{file_id}", bytes=10, md5="d" * 32),
    )
    if outcome == "ok":
        repo_.bump_session_progress(sid)
    return outcome


def test_concurrent_completions_of_one_file_never_both_win(emulator_repo, monkeypatch):
    """The guarantee bump_session_progress rests on. If two concurrent
    completions of the same PENDING file both returned "ok", both would bump and
    the session would report COMPLETED with files still pending.

    "At most one" for the same reason as the nonce test above; the loser must
    come back as "already" or "" and never as an exception.
    """
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, (file_id,) = _seed_session(emulator_repo, uid, file_count=1)

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(
            lambda _: emulator_repo.complete_file(
                file_id, uid,
                FileComplete(sessionId=sid, driveFileId="drive-1", bytes=10, md5="d" * 32),
            ),
            range(8),
        ))

    assert outcomes.count("ok") <= 1, f"{outcomes.count('ok')} concurrent completions won"
    assert set(outcomes) <= {"ok", "already", ""}, f"unexpected outcomes {set(outcomes)}"


def test_session_counter_tracks_completions_exactly(emulator_repo, monkeypatch):
    """Four files, each completed twice concurrently, driven exactly as the
    route drives them.

    The invariant is that completedCount equals the number of "ok" outcomes —
    never more (a retry double-counting, which would flip a session to COMPLETED
    with files still pending) and never less (a lost increment, which would
    strand an upload short of done forever).
    """
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, file_ids = _seed_session(emulator_repo, uid, file_count=4)

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(
            lambda fid: _complete_then_bump(emulator_repo, sid, uid, fid),
            file_ids + file_ids,
        ))

    wins = outcomes.count("ok")
    session = emulator_repo.get_session(sid)
    assert session["completedCount"] == wins, "counter drifted from actual completions"
    assert wins <= 4, "a file was completed more than once"
    assert session["completedCount"] <= session["fileCount"]
    if wins == 4:
        assert session["status"] == "COMPLETED"


def test_serial_completions_finish_the_session(emulator_repo, monkeypatch):
    """The uncontended path clients actually take: every file completes and the
    session reaches COMPLETED. Liveness, asserted where it is guaranteed."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, file_ids = _seed_session(emulator_repo, uid, file_count=4)

    for file_id in file_ids:
        assert _complete_then_bump(emulator_repo, sid, uid, file_id) == "ok"

    session = emulator_repo.get_session(sid)
    assert session["completedCount"] == 4
    assert session["status"] == "COMPLETED"


def test_completion_is_idempotent_under_serial_retry(emulator_repo, monkeypatch):
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, (file_id,) = _seed_session(emulator_repo, uid, file_count=1)

    assert _complete_then_bump(emulator_repo, sid, uid, file_id) == "ok"
    assert _complete_then_bump(emulator_repo, sid, uid, file_id) == "already"

    assert emulator_repo.get_session(sid)["completedCount"] == 1


def test_concurrent_bumps_lose_no_increments(emulator_repo, monkeypatch):
    """bump_session_progress is a read-modify-write. Without the transaction,
    concurrent bumps lose updates and a finished session never flips to
    COMPLETED — uploads would appear stuck at the last file forever."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    sid, _ = _seed_session(emulator_repo, uid, file_count=6)

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda _: emulator_repo.bump_session_progress(sid), range(6)))

    session = emulator_repo.get_session(sid)
    assert session["completedCount"] == 6, "lost update: increments were dropped"
    assert session["status"] == "COMPLETED"


def test_cross_user_completion_is_refused_inside_the_transaction(emulator_repo, monkeypatch):
    """The ownership re-check lives inside the transaction, so it cannot be
    raced past by a caller that read the doc before the check."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    owner = f"emu-{uuid.uuid4().hex[:8]}"
    sid, (file_id,) = _seed_session(emulator_repo, owner, file_count=1)

    outcome = emulator_repo.complete_file(
        file_id, "a-different-uid",
        FileComplete(sessionId=sid, driveFileId="drive-1", bytes=10, md5="d" * 32),
    )

    assert outcome != "ok"
    remaining, _ = emulator_repo.list_session_files(sid)
    assert remaining[0]["status"] == "PENDING"


def test_page_token_cannot_escape_the_collection(emulator_repo):
    """Against the real client, a slash-bearing cursor addresses a *path*. The
    HTTP layer rejects these now (validation.PageToken); this pins the reason."""
    uid = f"emu-{uuid.uuid4().hex[:8]}"
    _seed_session(emulator_repo, uid, file_count=1)

    sessions, _ = emulator_repo.list_user_sessions(uid, limit=10, page_token=None)
    assert sessions, "seeded session not listed"

    with pytest.raises(Exception):
        emulator_repo.list_user_sessions(uid, limit=10, page_token="a/b")


# --------------------------------------------------------------- licensing
# The seat count is the commercial boundary: it is what "ten seats" means on
# an invoice. Against the fake store every one of these passes vacuously —
# transactions there apply immediately with no isolation and no retries, so an
# over-claim is not merely undetected, it is unrepresentable. These are the
# only tests in the suite that can fail if the transactions are wrong.

def _emu_user(repo_, uid: str, email: str, **extra) -> dict:
    """A real user document, because claim_seat updates one inside its
    transaction and Firestore rejects an update to a document that is not
    there."""
    data = {
        "email": email,
        "emailVerified": True,
        "access_status": "APPROVED",
        "role": "user",
        **extra,
    }
    repo_.db().collection("users").document(uid).set(data)
    return {**data, "uid": uid}


def _emu_institution(repo_, *, max_seats, seating):
    minted = repo_.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="emu-admin",
        max_seats=max_seats,
        seating=seating,
    )
    return minted["license"]["id"]


def test_an_assigned_roster_never_over_admits(emulator_repo):
    """The race that made this tier necessary.

    Seat claim used to be a read followed by a WriteBatch — atomic writes, but
    no reads inside and no preconditions, so N callers all saw the same free
    count and all passed. With a cap of 5 and 16 simultaneous claimants the old
    code lands well above 5; the transaction must land exactly on it.
    """
    cap = 5
    license_id = _emu_institution(emulator_repo, max_seats=cap, seating="assigned")
    tag = uuid.uuid4().hex[:8]
    users = [
        _emu_user(emulator_repo, f"emu-{tag}-{i}", f"m{i}@university.edu")
        for i in range(16)
    ]

    def claim(user):
        return emulator_repo.claim_seat(
            license_id, user["uid"], user["email"], "",
            {"licenseId": license_id, "mode": "licensed"},
        )

    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(claim, users))

    admitted = sum(1 for err in results if err == "")
    seats = emulator_repo.list_institution_seats(license_id)
    stored = emulator_repo.get_license(license_id)

    assert admitted <= cap, f"over-admitted: {admitted} claims succeeded against a cap of {cap}"
    assert len(seats) == admitted, "seat documents disagree with the claims that succeeded"
    assert int(stored.get("seatsUsed") or 0) == admitted, "seatsUsed drifted from the roster"
    # Losing the race is a refusal, never an exception surfacing as a 500.
    assert all(isinstance(err, str) for err in results)


def test_a_floating_pool_never_hands_out_more_leases_than_it_has(emulator_repo):
    """A fifty-person lab sharing ten slots is the whole point of floating
    seating, so the roster is uncapped and the LEASE count is the boundary."""
    cap = 3
    license_id = _emu_institution(emulator_repo, max_seats=cap, seating="floating")
    tag = uuid.uuid4().hex[:8]
    users = []
    for i in range(12):
        user = _emu_user(emulator_repo, f"emu-{tag}-{i}", f"f{i}@university.edu")
        # On the roster — eligible, holding nothing.
        emulator_repo.claim_seat(
            license_id, user["uid"], user["email"], "",
            {"licenseId": license_id, "mode": "licensed"},
        )
        users.append({**user, "licenseId": license_id, "mode": "licensed"})

    # Everyone on the roster, and nobody holding a lease yet.
    assert len(emulator_repo.list_institution_seats(license_id)) == 12

    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(
            lambda u: emulator_repo.checkout_lease(u, f"dev-{u['uid']}"), users,
        ))

    granted = [cfg for err, cfg in results if err == ""]
    refused = [err for err, _ in results if err != ""]
    stored = emulator_repo.get_license(license_id)

    assert len(granted) <= cap, f"pool of {cap} handed out {len(granted)} leases"
    assert int(stored.get("leasesActive") or 0) == len(granted), "leasesActive drifted"
    assert all(err == "no_floating_seat" for err in refused), (
        f"a full pool must refuse with no_floating_seat, got {sorted(set(refused))}"
    )
    # Being between leases is the ordinary state, not a broken account.
    assert all(cfg["mode"] == "licensed" for cfg in granted)


def test_renewing_a_lease_does_not_consume_a_second_slot(emulator_repo):
    """Re-checkout IS the heartbeat, so it runs constantly. If it double-counted,
    a pool would strangle itself within one lease period."""
    license_id = _emu_institution(emulator_repo, max_seats=2, seating="floating")
    tag = uuid.uuid4().hex[:8]
    user = _emu_user(emulator_repo, f"emu-{tag}", "solo@university.edu")
    emulator_repo.claim_seat(
        license_id, user["uid"], user["email"], "",
        {"licenseId": license_id, "mode": "licensed"},
    )
    holder = {**user, "licenseId": license_id, "mode": "licensed"}

    for _ in range(5):
        err, _cfg = emulator_repo.checkout_lease(holder, "dev-1")
        assert err == ""

    assert int(emulator_repo.get_license(license_id).get("leasesActive") or 0) == 1

    # Concurrent heartbeats from the same holder must not inflate it either.
    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda _: emulator_repo.checkout_lease(holder, "dev-1"), range(6)))
    assert int(emulator_repo.get_license(license_id).get("leasesActive") or 0) == 1


def test_releasing_a_lease_frees_exactly_one_slot(emulator_repo):
    license_id = _emu_institution(emulator_repo, max_seats=1, seating="floating")
    tag = uuid.uuid4().hex[:8]
    holders = []
    for i in range(2):
        user = _emu_user(emulator_repo, f"emu-{tag}-{i}", f"r{i}@university.edu")
        emulator_repo.claim_seat(
            license_id, user["uid"], user["email"], "",
            {"licenseId": license_id, "mode": "licensed"},
        )
        holders.append({**user, "licenseId": license_id, "mode": "licensed"})

    first, second = holders
    assert emulator_repo.checkout_lease(first, "dev-a")[0] == ""
    assert emulator_repo.checkout_lease(second, "dev-b")[0] == "no_floating_seat"

    assert emulator_repo.release_lease(first)[0] == ""
    assert int(emulator_repo.get_license(license_id).get("leasesActive") or 0) == 0
    assert emulator_repo.checkout_lease(second, "dev-b")[0] == ""

    # Releasing a lease that already lapsed frees nothing and still succeeds.
    assert emulator_repo.release_lease(first)[0] == ""
    assert int(emulator_repo.get_license(license_id).get("leasesActive") or 0) == 1


def test_an_expired_lease_is_reclaimed_by_the_next_claimant(emulator_repo):
    """leasesActive drifts whenever a lease lapses without a release — a crashed
    or uninstalled client does exactly that — so the counter alone can never be
    the boundary. The sweep before each claim is what makes it true again."""
    from datetime import timedelta

    license_id = _emu_institution(emulator_repo, max_seats=1, seating="floating")
    tag = uuid.uuid4().hex[:8]
    holders = []
    for i in range(2):
        user = _emu_user(emulator_repo, f"emu-{tag}-{i}", f"x{i}@university.edu")
        emulator_repo.claim_seat(
            license_id, user["uid"], user["email"], "",
            {"licenseId": license_id, "mode": "licensed"},
        )
        holders.append({**user, "licenseId": license_id, "mode": "licensed"})
    crashed, waiting = holders

    assert emulator_repo.checkout_lease(crashed, "dev-crash")[0] == ""
    assert emulator_repo.checkout_lease(waiting, "dev-wait")[0] == "no_floating_seat"

    # Backdate the lease without releasing it — what a crashed client leaves.
    stale = emulator_repo._now() - timedelta(hours=1)
    emulator_repo._seat_ref(license_id, crashed["uid"]).update({"leaseExpiresAt": stale})
    # The counter is now wrong, which is the condition the sweep exists for.
    assert int(emulator_repo.get_license(license_id).get("leasesActive") or 0) == 1

    err, _cfg = emulator_repo.checkout_lease(waiting, "dev-wait")
    assert err == "", "the expired lease was never reclaimed"
    assert int(emulator_repo.get_license(license_id).get("leasesActive") or 0) == 1


def test_an_invite_is_redeemed_at_most_once(emulator_repo):
    """The app fires /v1/me and /v1/config back to back at launch, so a
    newcomer's very first two requests race on exactly this path. Redeeming
    twice would take two seats for one person."""
    license_id = _emu_institution(emulator_repo, max_seats=10, seating="assigned")
    tag = uuid.uuid4().hex[:8]
    address = f"newcomer-{tag}@university.edu"

    err, seat, invite = emulator_repo.add_institution_member(
        license_id, address, invited_by_uid="emu-it",
    )
    assert err == "" and seat is None and invite is not None

    uid = f"emu-{tag}"
    user = _emu_user(emulator_repo, uid, address)

    _race_entitlement(
        emulator_repo, user,
        settled=lambda: bool(emulator_repo.list_institution_seats(license_id)),
    )

    seats = emulator_repo.list_institution_seats(license_id)
    stored = emulator_repo.get_license(license_id)
    assert [s["uid"] for s in seats] == [uid]
    assert int(stored.get("seatsUsed") or 0) == 1, "one invite bought more than one seat"
    assert emulator_repo.list_institution_invites(license_id) == [], "invite was not consumed"


def test_a_revoked_invite_loses_the_race_cleanly(emulator_repo):
    """IT withdrawing an invite while the newcomer is signing in must not leave
    a seat granted against an invite that no longer exists."""
    license_id = _emu_institution(emulator_repo, max_seats=10, seating="assigned")
    tag = uuid.uuid4().hex[:8]
    address = f"racer-{tag}@university.edu"
    _err, _seat, invite = emulator_repo.add_institution_member(license_id, address)
    uid = f"emu-{tag}"
    user = _emu_user(emulator_repo, uid, address)

    emulator_repo.revoke_institution_invite(license_id, invite["id"])
    out = emulator_repo.claim_pending_invite(dict(user))

    assert out.get("licenseId") is None
    assert emulator_repo.list_institution_seats(license_id) == []
    assert int(emulator_repo.get_license(license_id).get("seatsUsed") or 0) == 0


#: How many times `_race_until` will re-run a race that granted nothing.
#: Four starved rounds in a row has never been seen; the cap is there so a
#: genuinely stuck claim fails the test instead of spinning.
_RACE_ROUNDS = 4


def _race_until(race, settled, *, what: str, rounds: int = _RACE_ROUNDS):
    """Run `race()` until `settled(result)` is true. Returns (result, round).

    `settled` sees every round's result, so a test asserts what a starved
    round is allowed to leave behind inside it and answers False to go again.
    See `_race_entitlement` for why a race that granted nothing is re-run
    rather than asserted on.
    """
    for attempt in range(1, rounds + 1):
        result = race()
        if settled(result):
            return result, attempt
    pytest.fail(f"{what} in none of {rounds} rounds — contention should not starve that long")


def _race_entitlement(repo_, user, settled, workers: int = 6, rounds: int = _RACE_ROUNDS):
    """Race `workers` simultaneous `ensure_entitlement` calls until one claim
    commits, and say how many rounds that took.

    Repeating the race is not a weaker test, because a round that grants
    nothing proves nothing. The emulator serialises contention and aborts the
    losers, so all six requests can exhaust their ten attempts (`_TX_ATTEMPTS`) and
    every one of them answer `_contended`; production reads that round the
    same way — `_drop_superseded_demo` records "six concurrent sign-ins
    starved out completely and the account landed on Demo", and the claim is
    left for a later request. With no grant, there is no grant for a loser to
    stamp a Demo key over, which is the invariant these tests are here for. So
    race again, with the same pre-race copy of the account every request would
    have held, rather than assert on a round where nothing happened.

    Each round is a real n-way race, and the assertions afterwards still cover
    the losers of every round that ran: a Demo key minted by a starved round
    survives in `licenses` until a claim commits and `_drop_superseded_demo`
    clears it, so "exactly one licence redeemed by this account" is asserted
    against everything all the rounds left behind.
    """
    def race():
        with ThreadPoolExecutor(max_workers=workers) as pool:
            list(pool.map(lambda _: repo_.ensure_entitlement(dict(user), None), range(workers)))

    _, attempt = _race_until(
        race, lambda _: settled(), rounds=rounds,
        what=f"no request of {workers} claimed the licence",
    )
    return attempt


def _emu_individual(repo_, email: str):
    return repo_.create_individual_license(
        email_lock=email, created_by_uid="emu-admin",
    )["license"]["id"]


def test_one_individual_licence_reaches_exactly_one_account(emulator_repo):
    """An individual licence names one redeemer. Two accounts signing in with
    the same address at once — the same person on a phone and a tablet, or a
    shared mailbox — must not both come away holding it.

    The emulator can starve all eight: each transaction runs out of retries
    and answers `_contended`, so a round may grant nothing. That is the
    fail-closed answer — every contender is told to retry and the licence is
    left untouched for the next request — and it is asserted as such, then the
    race is run again, as `_race_entitlement` does for the invite races. More
    than one winner fails on any round.
    """
    tag = uuid.uuid4().hex[:8]
    address = f"solo-{tag}@lab.org"
    license_id = _emu_individual(emulator_repo, address)
    users = [_emu_user(emulator_repo, f"emu-{tag}-{i}", address) for i in range(8)]
    patch = {"licenseId": license_id, "mode": "licensed"}

    def claim(u):
        return emulator_repo.claim_individual_license(license_id, u["uid"], address, dict(patch))

    def race():
        with ThreadPoolExecutor(max_workers=len(users)) as pool:
            return list(pool.map(claim, users))

    def settled(results):
        # Losing is a refusal, never an exception surfacing as a 500.
        assert all(isinstance(err, str) for err in results)
        admitted = [u for u, err in zip(users, results) if err == ""]
        assert len(admitted) <= 1, f"{len(admitted)} accounts claimed one individual licence"
        if admitted:
            return True
        # Nobody won, so nobody may be told somebody else did, and nothing
        # may be half-written.
        assert set(results) == {emulator_repo._CONTENDED}, results
        starved = emulator_repo.get_license(license_id)
        assert starved["status"] == "unused" and not starved.get("redeemedByUid"), starved
        return False

    results, _ = _race_until(
        race, settled, what=f"0 of {len(users)} accounts claimed one individual licence",
    )
    admitted = [u for u, err in zip(users, results) if err == ""]

    stored = emulator_repo.get_license(license_id)
    assert stored["redeemedByUid"] == admitted[0]["uid"]
    assert stored["status"] == "redeemed"
    losers = {err for u, err in zip(users, results) if u is not admitted[0]}
    assert losers <= {"license_already_redeemed", emulator_repo._CONTENDED}, losers
    holders = [
        u["uid"] for u in users
        if (emulator_repo.db().collection("users").document(u["uid"]).get().to_dict()
            or {}).get("licenseId") == license_id
    ]
    assert holders == [admitted[0]["uid"]], f"{len(holders)} accounts hold the licence"


def test_an_individual_invite_is_consumed_once_under_concurrency(emulator_repo):
    """The whole delivery path, raced: mint against an address, sign in, land
    licensed — and only once, however many requests arrive together.

    Every worker is handed the account as it looked before any of them ran,
    which is what six requests in flight at app launch actually see. The losers
    fall through to the Demo mint holding that stale copy; the assertion is
    that none of them stamps a Demo key over the licence a sibling just
    granted — see `_race_entitlement` for why the race may be run more than
    once before that assertion means anything.
    """
    tag = uuid.uuid4().hex[:8]
    address = f"invited-{tag}@lab.org"
    license_id = _emu_individual(emulator_repo, address)
    uid = f"emu-{tag}"
    user = _emu_user(emulator_repo, uid, address, activeDeviceId=f"dev-{tag}")

    _race_entitlement(
        emulator_repo, user,
        settled=lambda: (emulator_repo.get_license(license_id) or {}).get("redeemedByUid") == uid,
    )

    stored_user = emulator_repo.db().collection("users").document(uid).get().to_dict()
    assert stored_user["licenseId"] == license_id
    assert stored_user["mode"] == "licensed"
    stored = emulator_repo.get_license(license_id)
    assert stored["redeemedByUid"] == uid
    assert emulator_repo.list_institution_invites(license_id) == [], "invite was not consumed"
    # Strictly one record, of any mode. A loser that commits its Demo key
    # before the winner claims the real one used to leave that Demo document
    # behind — redeemed, pointed at by nobody, and indistinguishable in the
    # operator listing from a live key. `_drop_superseded_demo` clears it once
    # the claim has committed and the pointer is the claim's own.
    held = [
        doc.id for doc in emulator_repo.db().collection("licenses").stream()
        if (doc.to_dict() or {}).get("redeemedByUid") == uid
    ]
    assert held == [license_id], f"{len(held)} licences redeemed by one account"


def test_the_first_device_wins_an_unbound_lock(emulator_repo):
    """Bind-on-first-use is what ties an emailed licence to a device. Two
    devices signing in together both read an empty lock; a plain write would
    let the later one win, so the licence would follow whichever request
    Firestore happened to order second.

    The emulator aborts contended transactions, and eight binds used to be
    able to exhaust every retry with nothing committed — each then answered
    False, "someone else holds it", for a lock nobody held. A starved bind now
    re-reads and runs again, and raises DeviceLockContended rather than
    answer False while the lock is empty. So this asserts the invariant a
    device lock exists for — never two holders, and never a refusal without
    one — and tolerates only the outcome production also tolerates: a round
    in which everyone was told to try again, after which the race is run
    again against the still-empty lock."""
    tag = uuid.uuid4().hex[:8]
    address = f"binder-{tag}@lab.org"
    license_id = _emu_individual(emulator_repo, address)
    uid = f"emu-{tag}"
    user = _emu_user(emulator_repo, uid, address)
    user = emulator_repo.ensure_entitlement(dict(user), None)
    assert user["licenseId"] == license_id

    ref = emulator_repo.db().collection("licenses").document(license_id)
    devices = [f"dev-{tag}-{i}" for i in range(8)]

    def _bind(device):
        try:
            return emulator_repo.bind_device_lock(ref, device)
        except emulator_repo.DeviceLockContended:
            return None

    def race():
        with ThreadPoolExecutor(max_workers=8) as pool:
            return list(pool.map(_bind, devices))

    def settled(won):
        locked = emulator_repo.get_license(license_id)["deviceIdLock"]
        winners = [d for d, w in zip(devices, won) if w is True]
        assert len(winners) <= 1, f"{len(winners)} devices claimed the lock: {winners}"
        if winners:
            assert locked == winners[0]
            return True
        # Everyone starved. That is only acceptable if everyone was told so:
        # a False here would be a refusal with no holder behind it.
        assert locked == "", f"lock holds {locked!r} but no bind reported winning"
        assert won == [None] * len(devices), f"refused with the lock empty: {won}"
        return False

    _race_until(race, settled, what="no device bound the lock")
    locked = emulator_repo.get_license(license_id)["deviceIdLock"]
    assert locked, "no device holds the lock"
    # Every other device is now a mismatch, which is the answer a lock exists
    # to give: revalidation drops them to demo rather than re-binding.
    loser = next(d for d in devices if d != locked)
    demoted = emulator_repo.revalidate_device_lock(dict(user), loser)
    assert demoted["mode"] == "demo"
    assert emulator_repo.get_license(license_id)["deviceIdLock"] == locked


def test_an_address_finds_the_licences_it_administers(emulator_repo):
    """`array_contains` against a real Firestore, not the double.

    The fake store answers membership queries in Python, so it would happily
    serve a query the real index cannot. This is the one that proves the
    single-clause shape works unaided — and that `kind` and `status` really
    can be filtered afterwards without a composite index.
    """
    tag = uuid.uuid4().hex[:8]
    address = f"it-{tag}@lab.org"
    mine = emulator_repo.create_institution_license(
        domain_lock=f"{tag}.lab.org", admin_emails=[address, f"other-{tag}@lab.org"],
        created_by_uid="admin", max_seats=3,
    )["license"]["id"]
    revoked = emulator_repo.create_institution_license(
        domain_lock=f"{tag}.old.org", admin_emails=[address],
        created_by_uid="admin", max_seats=1,
    )["license"]["id"]
    emulator_repo.revoke_license(revoked, "admin")
    emulator_repo.create_institution_license(
        domain_lock=f"{tag}.other.org", admin_emails=[f"nobody-{tag}@lab.org"],
        created_by_uid="admin", max_seats=1,
    )

    found = [lic["id"] for lic in emulator_repo.list_licenses_administered_by(address)]

    assert found == [mine], "a revoked or foreign licence reached the listing"



def test_reconcile_matches_each_seat_to_its_own_holder(emulator_repo):
    """Holders are read with one batched `get_all`, which returns snapshots in
    no promised order. A report that paired a seat with the wrong account would
    move holders between buckets, so each seat here is in a different state."""
    license_id = _emu_institution(emulator_repo, max_seats=5, seating="assigned")
    tag = uuid.uuid4().hex[:8]
    users = [
        _emu_user(emulator_repo, f"emu-{tag}-{i}", f"r{i}-{tag}@university.edu")
        for i in range(3)
    ]
    for u in users:
        err = emulator_repo.claim_seat(
            license_id, u["uid"], u["email"], "",
            {"licenseId": license_id, "mode": "licensed"},
        )
        assert err == "", err
    holder, revoked, gone = users
    assert emulator_repo.revoke_institution_seat(license_id, revoked["uid"]) is True
    assert emulator_repo.revoke_institution_seat(license_id, gone["uid"]) is True
    emulator_repo.db().collection("users").document(gone["uid"]).delete()

    err, report = emulator_repo.reconcile_institution_seats(license_id)

    assert err == ""
    by_uid = {row["uid"]: row for row in report["seats"]}
    assert set(by_uid) == {u["uid"] for u in users}
    assert (by_uid[holder["uid"]]["bucket"], by_uid[holder["uid"]]["reason"]) == ("active", "")
    assert by_uid[holder["uid"]]["email"] == holder["email"]
    assert by_uid[gone["uid"]]["reason"] == emulator_repo.NO_ACCOUNT
    assert by_uid[revoked["uid"]]["bucket"] in {"revokedConfirmed", "revokedStillRunning"}
    assert by_uid[revoked["uid"]]["reason"] != emulator_repo.NO_ACCOUNT
    assert report["entitled"] == 1


def test_the_staff_list_pages_newest_first_through_the_real_query(emulator_repo):
    """`list_licenses` combines an equality filter, an `in` filter, a
    descending order on another field and a snapshot cursor. The store double
    only imitates that; this walks every page against Firestore itself and
    checks the licences made here come back newest first, each once, with the
    revoked one and the Demo key left out."""
    tag = uuid.uuid4().hex[:8]
    made = [
        emulator_repo.create_individual_license(
            email_lock=f"page{i}-{tag}@lab.org", created_by_uid="emu-admin",
        )["license"]["id"]
        for i in range(3)
    ]
    gone = emulator_repo.create_individual_license(
        email_lock=f"gone-{tag}@lab.org", created_by_uid="emu-admin",
    )["license"]["id"]
    emulator_repo.revoke_license(gone, "emu-admin")

    seen, token = [], None
    while True:
        rows, token = emulator_repo.list_licenses(
            limit=2, page_token=token, include_revoked=False,
        )
        seen.extend(row["id"] for row in rows)
        if not token:
            break

    assert len(seen) == len(set(seen)), "a licence was listed on two pages"
    assert [i for i in seen if i in made] == list(reversed(made))
    assert gone not in seen
    found = emulator_repo.list_licenses(q=f"page1-{tag}@lab.org")[0]
    assert [row["id"] for row in found] == [made[1]]
