"""Institution IT adds only its licence's own domain, and its add / import
replies say nothing about another licence (readiness TE3-102, ADDENDUM-2).

Before: IT of any institution could seat any approved Demo account of any
domain without its consent, and the add answered 409 `member_already_licensed`
for an address another licence holds or promises, so one guessed address at a
time told IT who was licensed or invited elsewhere.

Now, at IT's tier (`/v1/institutions/...`):
- an address off the licence's `domainLock` is refused, 422
  `member_off_domain`, before anything about the address is read;
- an address on the domain that another licence holds or promises gets the
  answer an unknown address gets: a pending invite on this roster. It is held,
  never claimed, so it cannot move the person off the licence they have.

Semper staff (`/v1/admin/...`) keep the explicit refusal and may seat an
external collaborator off the domain.
"""
import time

import pytest

import fake_firestore
import repo_view as repo
from app import deps, licenses
from app.config import settings
from refusals import attempt_add

IT_EMAIL = "it@uni-a.edu"


def _mint(domain: str, admin: str, **kw) -> str:
    return repo.create_institution_license(
        domain_lock=domain, admin_emails=[admin], created_by_uid="staff", **kw,
    )["license"]["id"]


def _account(store, uid: str, email: str, **extra) -> None:
    store._data.setdefault("users", {})[uid] = {
        "email": email, "access_status": "APPROVED", "emailVerified": True, "plan": "demo", **extra,
    }


def _as(monkeypatch, uid: str, email: str) -> None:
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": uid, "email": email, "email_verified": True,
                    "firebase": {"sign_in_second_factor": "totp"},
                    "auth_time": time.time() - 60},
    )
    profile = {"uid": uid, "email": email, "role": "user",
               "access_status": "APPROVED", "emailVerified": True}
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: dict(profile))


@pytest.fixture
def it(monkeypatch, audited):
    """IT for licence A (uni-a.edu). Licence C is another institution's licence
    on the same domain; licence B is on uni-b.edu."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    _as(monkeypatch, "it-a", IT_EMAIL)
    store._data["users"] = {}
    store.a = _mint("uni-a.edu", IT_EMAIL)
    store.b = _mint("uni-b.edu", "it@uni-b.edu")
    store.c = _mint("uni-a.edu", "it-c@uni-a.edu")
    store.bearer = {"Authorization": "Bearer ok"}
    store.base = f"/v1/institutions/licenses/{store.a}"
    store.audited = audited
    return store


async def _add(client, it, email, base=None):
    return await client.post(f"{base or it.base}/seats", json={"email": email},
                             headers=it.bearer)


def _without_address(reply: dict) -> dict:
    """An add reply with the parts that only echo the address taken out."""
    invite = dict(reply.get("invite") or {})
    for key in ("id", "email", "createdAt"):
        invite.pop(key, None)
    return {**reply, "invite": invite}


def _seat_and_invite_counts(store) -> tuple[int, int]:
    seats = sum(len(v) for k, v in store._data.items() if k.endswith("/seats"))
    return seats, len(store._data.get("licenseInvites", {}))


# ======================================================== domain lock (IT)


@pytest.mark.asyncio
async def test_it_cannot_seat_an_off_domain_demo_account(it, client):
    _account(it, "carol", "carol@gmail.com")
    before = dict(it._data["users"]["carol"])

    r = await _add(client, it, "carol@gmail.com")

    assert (r.status_code, r.json()) == (422, {"detail": "member_off_domain"})
    assert it._data["users"]["carol"] == before
    assert _seat_and_invite_counts(it) == (0, 0)
    assert it.audited == []


@pytest.mark.asyncio
async def test_it_cannot_invite_an_off_domain_address(it, client):
    r = await _add(client, it, "nobody@nowhere.org")
    assert (r.status_code, r.json()) == (422, {"detail": "member_off_domain"})
    assert _seat_and_invite_counts(it) == (0, 0)


@pytest.mark.asyncio
async def test_a_look_alike_domain_is_off_domain(it, client):
    for address in ("x@sub.uni-a.edu", "x@uni-a.edu.evil.org", "x@xuni-a.edu"):
        r = await _add(client, it, address)
        assert (r.status_code, r.json()) == (422, {"detail": "member_off_domain"}), address


@pytest.mark.asyncio
async def test_the_off_domain_refusal_reads_nothing_about_the_address(it, client):
    """B's seated, invited and unknown addresses are refused alike at A."""
    _account(it, "ana", "ana@uni-b.edu")
    attempt_add(repo.add_institution_member, it.b, "ana@uni-b.edu")
    attempt_add(repo.add_institution_member, it.b, "pending@uni-b.edu")

    replies = [await _add(client, it, a)
               for a in ("ana@uni-b.edu", "pending@uni-b.edu", "nobody@uni-b.edu")]

    assert {(r.status_code, r.text) for r in replies} == {(422, '{"detail":"member_off_domain"}')}
    assert it._data[f"licenses/{it.b}/seats"]["ana"]["status"] == "active"


@pytest.mark.asyncio
async def test_the_import_refuses_off_domain_rows_and_adds_the_rest(it, client):
    _account(it, "ana", "ana@uni-a.edu")
    _account(it, "carol", "carol@gmail.com")
    r = await client.post(f"{it.base}/seats/import", headers=it.bearer,
                          json={"csv": "ana@uni-a.edu\ncarol@gmail.com\nnew@uni-a.edu\n"})
    assert r.status_code == 200, r.text
    assert [(x["outcome"], x["error"]) for x in r.json()["results"]] == [
        ("seated", ""), ("refused", "member_off_domain"), ("invited", ""),
    ]
    assert "licenseId" not in it._data["users"]["carol"]


@pytest.mark.asyncio
async def test_own_domain_adds_still_seat_and_invite(it, client):
    _account(it, "ana", "ana@uni-a.edu")

    seated = await _add(client, it, "ANA@Uni-A.edu")
    invited = await _add(client, it, "new@uni-a.edu")

    assert seated.status_code == 200 and seated.json()["seat"]["uid"] == "ana"
    assert invited.status_code == 200 and invited.json()["invite"]["email"] == "new@uni-a.edu"
    assert it._data["users"]["ana"]["licenseId"] == it.a


# ============================================ no oracle on another licence


def _other_licence_holds_seated_and_invited(it):
    """Licence C, on A's own domain: `seated@` holds a seat, `invited@` an invite."""
    _account(it, "seated", "seated@uni-a.edu")
    assert attempt_add(repo.add_institution_member, it.c, "seated@uni-a.edu")[1]
    assert attempt_add(repo.add_institution_member, it.c, "invited@uni-a.edu")[2]


@pytest.mark.asyncio
async def test_it_cannot_tell_another_licences_seated_invited_or_unknown(it, client):
    _other_licence_holds_seated_and_invited(it)
    addresses = ("seated@uni-a.edu", "invited@uni-a.edu", "unknown@uni-a.edu")

    replies = [await _add(client, it, a) for a in addresses]

    assert [r.status_code for r in replies] == [200, 200, 200]
    bodies = [r.json() for r in replies]
    assert len({str(_without_address(b)) for b in bodies}) == 1
    for address, body in zip(addresses, bodies):
        assert body["seat"] is None
        assert body["invite"]["email"] == address
        assert body["invite"]["id"] == licenses.invite_id(address)
        assert body["invite"]["licenseId"] == it.a
    # The audit rows IT can read back are alike too.
    assert [(a["action"], a["target"]["type"]) for a in it.audited] == \
        [("INSTITUTION_INVITE_ADD", "invite")] * 3


@pytest.mark.asyncio
async def test_the_roster_lists_them_alike(it, client):
    _other_licence_holds_seated_and_invited(it)
    addresses = ("seated@uni-a.edu", "invited@uni-a.edu", "unknown@uni-a.edu")
    for a in addresses:
        await _add(client, it, a)

    listing = (await client.get(f"{it.base}/seats", headers=it.bearer)).json()

    assert listing["seats"] == []
    assert sorted(i["email"] for i in listing["invites"]) == sorted(addresses)
    assert {i["id"] for i in listing["invites"]} == {licenses.invite_id(a) for a in addresses}
    assert {tuple(sorted(i)) for i in listing["invites"]} == \
        {("createdAt", "email", "id", "invitedByUid", "licenseId")}
    summary = (await client.get(f"{it.base}/summary", headers=it.bearer)).json()
    assert summary["counts"]["invited"] == 3


@pytest.mark.asyncio
async def test_the_import_answers_them_alike(it, client):
    _other_licence_holds_seated_and_invited(it)
    r = await client.post(f"{it.base}/seats/import", headers=it.bearer,
                          json={"csv": "seated@uni-a.edu\ninvited@uni-a.edu\nunknown@uni-a.edu\n"})
    assert [(x["outcome"], x["error"]) for x in r.json()["results"]] == [("invited", "")] * 3
    # Importing again: all three are on the roster now, alike.
    again = await client.post(f"{it.base}/seats/import", headers=it.bearer,
                              json={"csv": "seated@uni-a.edu\ninvited@uni-a.edu\nunknown@uni-a.edu\n"})
    assert [x["outcome"] for x in again.json()["results"]] == ["exists"] * 3


@pytest.mark.asyncio
async def test_the_other_licence_is_left_as_it_was(it, client):
    _other_licence_holds_seated_and_invited(it)
    for a in ("seated@uni-a.edu", "invited@uni-a.edu"):
        await _add(client, it, a)

    assert it._data[f"licenses/{it.c}/seats"]["seated"]["status"] == "active"
    assert it._data["users"]["seated"]["licenseId"] == it.c
    promise = it._data["licenseInvites"][licenses.invite_id("invited@uni-a.edu")]
    assert promise["licenseId"] == it.c
    # The person still holds, and is still promised, C alone.
    assert repo.licence_held_by("seated@uni-a.edu") == it.c
    assert repo.licence_held_by("invited@uni-a.edu") == it.c
    # C's own IT still re-adds them as before.
    assert attempt_add(repo.add_institution_member, it.c, "seated@uni-a.edu")[0] == ""
    assert attempt_add(repo.add_institution_member, it.c, "invited@uni-a.edu")[0] == ""


def test_a_held_invite_is_never_claimed(it):
    """The invited person signs in: C's promise is the one they take."""
    attempt_add(repo.add_institution_member, it.c, "invited@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "invited@uni-a.edu", institution_it=True)
    _account(it, "late", "invited@uni-a.edu")

    user = repo.claim_pending_invite({**it._data["users"]["late"], "uid": "late"})

    assert user["licenseId"] == it.c
    assert f"licenses/{it.a}/seats" not in it._data or it._data[f"licenses/{it.a}/seats"] == {}


@pytest.mark.asyncio
async def test_withdrawing_a_held_invite_leaves_the_other_promise(it, client):
    _other_licence_holds_seated_and_invited(it)
    invite = (await _add(client, it, "invited@uni-a.edu")).json()["invite"]

    r = await client.delete(f"{it.base}/invites/{invite['id']}", headers=it.bearer)

    assert r.status_code == 200 and r.json()["revoked"] is True
    assert (await client.get(f"{it.base}/seats", headers=it.bearer)).json()["invites"] == []
    assert it._data["licenseInvites"][invite["id"]]["licenseId"] == it.c
    # Withdrawing it twice is the 404 any unknown invite gets.
    again = await client.delete(f"{it.base}/invites/{invite['id']}", headers=it.bearer)
    assert again.status_code == 404


@pytest.mark.asyncio
async def test_it_cannot_withdraw_the_other_licences_invite(it, client):
    _other_licence_holds_seated_and_invited(it)
    key = licenses.invite_id("invited@uni-a.edu")
    r = await client.delete(f"{it.base}/invites/{key}", headers=it.bearer)
    assert r.status_code == 404
    assert it._data["licenseInvites"][key]["licenseId"] == it.c


@pytest.mark.asyncio
async def test_once_free_the_address_is_added_for_real(it, client):
    """C lets them go; A's next add seats or invites them for real and drops
    the held entries."""
    _other_licence_holds_seated_and_invited(it)
    for a in ("seated@uni-a.edu", "invited@uni-a.edu"):
        await _add(client, it, a)
    repo.revoke_license(it.c, "staff")

    seated = (await _add(client, it, "seated@uni-a.edu")).json()
    invited = (await _add(client, it, "invited@uni-a.edu")).json()

    assert seated["seat"]["uid"] == "seated" and seated["invite"] is None
    assert invited["invite"]["id"] == licenses.invite_id("invited@uni-a.edu")
    # The invite is now the claimable one, and nothing held is left.
    assert it._data["licenseInvites"][invited["invite"]["id"]]["licenseId"] == it.a
    assert [k for k in it._data["licenseInvites"] if k.startswith("held_")] == []
    listing = (await client.get(f"{it.base}/seats", headers=it.bearer)).json()
    assert [s["uid"] for s in listing["seats"]] == ["seated"]
    assert [i["email"] for i in listing["invites"]] == ["invited@uni-a.edu"]


@pytest.mark.asyncio
async def test_revoking_the_licence_drops_its_held_invites(it, client):
    _other_licence_holds_seated_and_invited(it)
    await _add(client, it, "seated@uni-a.edu")
    repo.revoke_license(it.a, "staff")
    assert [d for d in it._data["licenseInvites"].values() if d.get("licenseId") == it.a] == []


# =================================================== Semper staff (unchanged)


@pytest.fixture
def staff(monkeypatch, audited):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "staff@semper.test"})
    store._data["users"] = {}
    store.a = _mint("uni-a.edu", IT_EMAIL)
    store.c = _mint("uni-a.edu", "it-c@uni-a.edu")
    return store


@pytest.mark.asyncio
async def test_staff_may_seat_an_external_collaborator(staff, client):
    _account(staff, "carol", "carol@gmail.com")
    r = await client.post(f"/v1/admin/licenses/{staff.a}/seats", json={"email": "carol@gmail.com"})
    assert r.status_code == 200, r.text
    assert r.json()["seat"]["uid"] == "carol"


@pytest.mark.asyncio
async def test_staff_are_still_told_the_address_is_licensed(staff, client):
    _account(staff, "seated", "seated@uni-a.edu")
    attempt_add(repo.add_institution_member, staff.c, "seated@uni-a.edu")
    r = await client.post(f"/v1/admin/licenses/{staff.a}/seats", json={"email": "seated@uni-a.edu"})
    assert (r.status_code, r.json()) == (409, {"detail": "member_already_licensed"})
