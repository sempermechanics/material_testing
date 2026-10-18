"""Bulk roster import, roster CSV export, the seat summary and the licence's
own audit log (`routers/roster.py`, `roster_io.py`, `audit.page_license_audit`).

Mostly over HTTP at institution IT's tier, where the scoping matters: IT for
their own licence only, staff for any, everyone else refused. The staff tier
runs on the dev bypass (`deps._DEV_USER` is an admin).
"""
import json
import logging
import time
from datetime import datetime, timedelta, timezone

import pytest
from google.api_core import exceptions as google_exceptions

import fake_firestore
import repo_view as repo
from app import audit, deps, errors, roster_io
from app.config import settings
from app.routers import roster
from refusals import attempt_add

IT_EMAIL = "it@uni-a.edu"
NOW = datetime.now(timezone.utc)


def _mint(domain: str, admin: str, **kw) -> str:
    return repo.create_institution_license(
        domain_lock=domain, admin_emails=[admin], created_by_uid="staff", **kw,
    )["license"]["id"]


def _account(store, uid: str, email: str, **extra) -> None:
    store._data.setdefault("users", {})[uid] = {
        "email": email, "access_status": "APPROVED", "plan": "demo", **extra,
    }


def _as(monkeypatch, uid: str, email: str, *, role: str = "user") -> None:
    """Sign every request in as this verified, second-factor account."""
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": uid, "email": email, "email_verified": True,
                    "firebase": {"sign_in_second_factor": "totp"},
                    "auth_time": time.time() - 60},
    )
    profile = {"uid": uid, "email": email, "role": role,
               "access_status": "APPROVED", "emailVerified": True}
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: dict(profile))


@pytest.fixture
def it(monkeypatch, audited):
    """IT for licence A (`store.a`); licence B (`store.b`) belongs to another
    institution. Requests carry `store.bearer`."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    _as(monkeypatch, "it-a", IT_EMAIL)
    store._data["users"] = {}
    store.a = _mint("uni-a.edu", IT_EMAIL)
    store.b = _mint("uni-b.edu", "it@uni-b.edu")
    store.bearer = {"Authorization": "Bearer ok"}
    store.base = f"/v1/institutions/licenses/{store.a}"
    store.audited = audited
    return store


@pytest.fixture
def staff(monkeypatch, audited):
    """The dev caller: Semper staff, named by no licence."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "staff@semper.test"})
    store._data["users"] = {}
    store.a = _mint("uni-a.edu", IT_EMAIL)
    store.audited = audited
    return store


async def _import(client, it, text, base=None):
    return await client.post(f"{base or it.base}/seats/import", json={"csv": text},
                             headers=getattr(it, "bearer", {}))


# ===================================================================== import


@pytest.mark.asyncio
async def test_each_row_is_answered_and_added_through_the_single_add(it, client):
    _account(it, "ana", "ana@uni-a.edu")
    _account(it, "held", "held@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "held@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "promised@uni-a.edu")
    it.audited.clear()
    text = (
        "Name,Email\r\n"
        "Ana,ana@uni-a.edu\r\n"
        "\r\n"
        "New,new@uni-a.edu\r\n"
        "Again,ANA@uni-a.edu\r\n"
        "Bad,not-an-address\r\n"
        "Nobody,\r\n"
        "Held,held@uni-a.edu\r\n"
        "Promised,promised@uni-a.edu\r\n"
    )
    r = await _import(client, it, text)
    assert r.status_code == 200, r.text
    body = r.json()
    assert [(x["row"], x["email"], x["outcome"], x["error"]) for x in body["results"]] == [
        (2, "ana@uni-a.edu", "seated", ""),
        (4, "new@uni-a.edu", "invited", ""),
        (5, "ana@uni-a.edu", "duplicate", "row 2"),
        (6, "not-an-address", "invalid", "invalid_email"),
        (7, "", "invalid", "invalid_email"),
        (8, "held@uni-a.edu", "exists", ""),
        (9, "promised@uni-a.edu", "exists", ""),
    ]
    assert body["rows"] == 7
    assert body["counts"] == {"seated": 1, "invited": 1, "exists": 2, "duplicate": 1,
                              "invalid": 2, "refused": 0, "unprocessed": 0}
    assert it._data[f"licenses/{it.a}/seats"]["ana"]["status"] == "active"
    # One audit row per add, as the single add writes, then the summary.
    assert [a["action"] for a in it.audited] == [
        "INSTITUTION_SEAT_ADD", "INSTITUTION_INVITE_ADD", "INSTITUTION_ROSTER_IMPORT",
    ]
    summary = it.audited[-1]
    assert summary["uid"] == "it-a"
    assert summary["target"] == {"type": "license", "id": it.a}
    assert summary["detail"] == {"rows": 7, **body["counts"]}


@pytest.mark.asyncio
async def test_importing_the_same_file_again_changes_nothing(it, client):
    _account(it, "ana", "ana@uni-a.edu")
    text = "ana@uni-a.edu\nnew@uni-a.edu\n"
    first = await _import(client, it, text)
    assert [x["outcome"] for x in first.json()["results"]] == ["seated", "invited"]
    used = it._data["licenses"][it.a]["seatsUsed"]
    it.audited.clear()

    again = await _import(client, it, text)
    assert [x["outcome"] for x in again.json()["results"]] == ["exists", "exists"]
    assert it._data["licenses"][it.a]["seatsUsed"] == used
    assert [a["action"] for a in it.audited] == ["INSTITUTION_ROSTER_IMPORT"]


@pytest.mark.asyncio
async def test_a_held_seat_is_left_held_and_a_removed_one_is_added_back(it, client):
    for uid in ("held", "gone"):
        _account(it, uid, f"{uid}@uni-a.edu")
        attempt_add(repo.add_institution_member, it.a, f"{uid}@uni-a.edu")
    repo.set_seat_enabled(it.a, "held", False)
    repo.revoke_institution_seat(it.a, "gone")

    r = await _import(client, it, "email\nheld@uni-a.edu\ngone@uni-a.edu\n")
    assert [x["outcome"] for x in r.json()["results"]] == ["exists", "seated"]
    seats = it._data[f"licenses/{it.a}/seats"]
    assert seats["held"]["status"] == "disabled"
    assert seats["gone"]["status"] == "active"


@pytest.mark.asyncio
async def test_a_refused_row_is_reported_and_the_others_still_land(it, client):
    full = _mint("uni-a.edu", IT_EMAIL, max_seats=1)
    for uid in ("one", "two"):
        _account(it, uid, f"{uid}@uni-a.edu")
    # Holds a seat at another institution: one licence per person.
    _account(it, "elsewhere", "x@uni-b.edu")
    attempt_add(repo.add_institution_member, it.b, "x@uni-b.edu")

    r = await _import(client, it, "one@uni-a.edu\ntwo@uni-a.edu\nx@uni-b.edu\n",
                      base=f"/v1/institutions/licenses/{full}")
    assert r.status_code == 200, r.text
    assert [(x["outcome"], x["error"]) for x in r.json()["results"]] == [
        ("seated", ""),
        ("refused", "license_seats_exhausted"),
        ("refused", "member_already_licensed"),
    ]
    assert list(it._data[f"licenses/{full}/seats"]) == ["one"]
    assert r.json()["counts"]["refused"] == 2


@pytest.mark.asyncio
async def test_rows_past_the_time_budget_are_unprocessed(it, client, monkeypatch):
    monkeypatch.setattr(roster, "IMPORT_BUDGET_S", -1.0)
    attempt_add(repo.add_institution_member, it.a, "promised@uni-a.edu")
    r = await _import(client, it, "promised@uni-a.edu\nlater@uni-a.edu\n")
    assert [(x["outcome"], x["error"]) for x in r.json()["results"]] == [
        ("exists", ""), ("unprocessed", "roster_row_unprocessed"),
    ]


@pytest.mark.asyncio
async def test_a_backend_fault_on_one_row_is_that_rows_alone(it, client, monkeypatch, caplog):
    real = repo.add_institution_member

    def flaky(license_id, email, **kw):
        if email.startswith("flaky"):
            raise google_exceptions.ServiceUnavailable("firestore down")
        return real(license_id, email, **kw)

    repo.patch(monkeypatch, "add_institution_member", flaky)
    with caplog.at_level(logging.WARNING, logger="roster"):
        r = await _import(client, it, "flaky@uni-a.edu\nfine@uni-a.edu\n")
    assert [x["outcome"] for x in r.json()["results"]] == ["unprocessed", "invited"]
    line = json.loads(next(rec.getMessage() for rec in caplog.records if rec.name == "roster"))
    assert line["event"] == "roster_import_row_failed"
    assert line["errorCode"] == "ServiceUnavailable"


@pytest.mark.asyncio
async def test_five_hundred_rows_are_taken_and_five_hundred_and_one_refused(
        it, client, monkeypatch):
    added = []
    repo.patch(monkeypatch, "add_institution_member",
               lambda license_id, email, **kw: added.append(email) or (None, {"id": email}))
    rows = [f"m{i}@uni-a.edu" for i in range(roster_io.MAX_IMPORT_ROWS)]

    r = await _import(client, it, "email\n" + "\n".join(rows) + "\n")
    assert r.status_code == 200, r.text
    assert r.json()["counts"]["invited"] == 500
    added.clear()

    over = await _import(client, it, "\n".join(rows + ["one-more@uni-a.edu"]))
    assert over.status_code == 413
    assert over.json()["detail"] == "roster_import_too_many_rows"
    assert added == [], "nothing is added from a file that is refused"


@pytest.mark.asyncio
@pytest.mark.parametrize("text,code", [
    ('email\n"unterminated@uni-a.edu\n', "roster_import_malformed"),
    ('"a@uni-a.edu"x\n', "roster_import_malformed"),
    ("\n\n  \n", "roster_import_empty"),
    ("email\n", "roster_import_empty"),
    ("name,mail\nAna,Ana\n", "roster_import_no_email_column"),
])
async def test_a_file_that_cannot_be_read_is_refused_whole(it, client, text, code):
    r = await _import(client, it, text)
    assert r.status_code == 400, r.text
    assert r.json()["detail"] == code
    assert f"licenses/{it.a}/seats" not in it._data
    assert [a["action"] for a in it.audited] == []


@pytest.mark.asyncio
async def test_the_import_body_is_held_to_its_shape(it, client):
    r = await client.post(f"{it.base}/seats/import", json={"csv": ""}, headers=it.bearer)
    assert r.status_code == 422
    r = await client.post(f"{it.base}/seats/import", json={"csv": "a@b.c", "x": 1},
                          headers=it.bearer)
    assert r.status_code == 422


# ===================================================================== scoping


ROUTES = [
    ("POST", "/seats/import", {"csv": "a@uni-b.edu"}),
    ("GET", "/seats/export", None),
    ("GET", "/summary", None),
    ("GET", "/audit", None),
    ("GET", "/audit?format=csv", None),
]


@pytest.mark.asyncio
@pytest.mark.parametrize("method,path,body", ROUTES)
async def test_it_reaches_its_own_licence_only(it, client, method, path, body):
    foreign = await client.request(method, f"/v1/institutions/licenses/{it.b}{path}",
                                   json=body, headers=it.bearer)
    assert foreign.status_code == 404, foreign.text
    assert foreign.json()["detail"] == "license_not_found"
    assert f"licenses/{it.b}/seats" not in it._data
    own = await client.request(method, f"{it.base}{path}", json=body, headers=it.bearer)
    assert own.status_code == 200, own.text


@pytest.mark.asyncio
@pytest.mark.parametrize("method,path,body", ROUTES)
async def test_an_account_that_administers_nothing_is_refused(it, client, monkeypatch,
                                                              method, path, body):
    _as(monkeypatch, "member", "member@uni-a.edu")
    it_route = await client.request(method, f"{it.base}{path}", json=body, headers=it.bearer)
    assert it_route.status_code == 404
    staff_route = await client.request(method, f"/v1/admin/licenses/{it.a}{path}",
                                       json=body, headers=it.bearer)
    assert staff_route.status_code == 403
    assert staff_route.json()["detail"] == "not_admin"


@pytest.mark.asyncio
async def test_staff_reach_any_institution_licence(staff, client):
    _account(staff, "ana", "ana@uni-a.edu")
    base = f"/v1/admin/licenses/{staff.a}"
    r = await client.post(f"{base}/seats/import", json={"csv": "ana@uni-a.edu"})
    assert r.status_code == 200, r.text
    assert r.json()["results"][0]["outcome"] == "seated"
    assert [a["action"] for a in staff.audited] == ["ADMIN_SEAT_ADD", "ADMIN_ROSTER_IMPORT"]
    for path in ("/seats/export", "/summary", "/audit"):
        assert (await client.get(f"{base}{path}")).status_code == 200, path
    # IT's own routes still refuse staff, who are on no licence's adminEmails.
    assert (await client.get(f"/v1/institutions/licenses/{staff.a}/summary")).status_code == 404


# ===================================================================== export


@pytest.mark.asyncio
async def test_the_roster_exports_as_a_csv_that_imports_back(it, client):
    _account(it, "ana", "ana@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "ana@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "=cmd@uni-a.edu")
    it.audited.clear()

    r = await client.get(f"{it.base}/seats/export", headers=it.bearer)
    assert r.status_code == 200
    assert r.headers["content-type"] == "text/csv; charset=utf-8"
    assert r.headers["content-disposition"] == f'attachment; filename="roster-{it.a}.csv"'
    assert r.headers["cache-control"] == "no-store"
    lines = r.text.splitlines()
    assert lines[0].startswith("email,status,uid,deviceIdLock,")
    assert lines[1].startswith("ana@uni-a.edu,active,ana,")
    # An address that reads as a formula is defused for the spreadsheet.
    assert lines[2].startswith("'=cmd@uni-a.edu,invited,,")
    assert [a["action"] for a in it.audited] == ["INSTITUTION_ROSTER_EXPORT"]

    # The file as it was downloaded imports back: the guard is undone (TD-267).
    again = await _import(client, it, r.text)
    assert [(x["email"], x["outcome"]) for x in again.json()["results"]] == [
        ("ana@uni-a.edu", "exists"), ("=cmd@uni-a.edu", "exists")]


@pytest.mark.asyncio
async def test_addresses_that_start_with_a_sign_survive_an_export_and_import(it, client):
    """TD-267: `-a@` and `+b@` are valid local parts, which the export quotes
    as `'-a@` and `'+b@`. Re-importing the file used to answer `invalid`."""
    _account(it, "dash", "-a@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "-a@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "+b@uni-a.edu")

    r = await client.get(f"{it.base}/seats/export", headers=it.bearer)
    assert r.status_code == 200
    cells = sorted(line.split(",", 1)[0] for line in r.text.splitlines()[1:])
    assert cells == ["'+b@uni-a.edu", "'-a@uni-a.edu"]

    again = await _import(client, it, r.text)
    assert again.status_code == 200, again.text
    assert sorted((x["email"], x["outcome"]) for x in again.json()["results"]) == [
        ("+b@uni-a.edu", "exists"), ("-a@uni-a.edu", "exists")]
    assert again.json()["counts"]["exists"] == 2


def test_a_download_is_named_from_the_licence_id_alone():
    assert roster._attachment("roster", 'a"b c;d') == 'attachment; filename="roster-a_b_c_d.csv"'


# ===================================================================== summary


@pytest.mark.asyncio
async def test_the_seat_summary_counts_what_is_stored(it, client):
    it._data["licenses"][it.a]["expiresAt"] = NOW + timedelta(days=10)
    recently, long_ago = NOW - timedelta(days=1), NOW - timedelta(days=30)
    for uid, seen in (("fresh", recently), ("stale", long_ago), ("held", recently),
                      ("gone", long_ago), ("lagging", recently)):
        _account(it, uid, f"{uid}@uni-a.edu", lastSeenAt=seen)
        attempt_add(repo.add_institution_member, it.a, f"{uid}@uni-a.edu")
    attempt_add(repo.add_institution_member, it.a, "invited@uni-a.edu")
    repo.set_seat_enabled(it.a, "held", False)
    repo.revoke_institution_seat(it.a, "gone")
    repo.revoke_institution_seat(it.a, "lagging")
    # Revoked, demoted, and not back since: the device has not heard yet.
    it._data[f"licenses/{it.a}/seats"]["lagging"]["revokedAt"] = NOW
    it._data["users"]["lagging"]["lastSeenAt"] = NOW - timedelta(hours=2)

    r = await client.get(f"{it.base}/summary", headers=it.bearer)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["licenseId"] == it.a
    assert body["seating"] == "assigned"
    assert "leasesActive" not in body
    assert body["counts"] == {
        "assigned": 3, "onHold": 1, "activeLast7Days": 1, "invited": 1,
        "revocationPending": 2, "revocationPendingNoCheckIn": 2, "removed": 2,
        "expiringWithin30Days": 3,
    }


def test_the_summary_of_a_floating_open_ended_licence():
    report = {"maxSeats": 5, "seats": [
        {"bucket": "active", "status": "active", "lastSeenAt": NOW},
        {"bucket": "revokedStillRunning", "reason": "still_licensed"},
        {"bucket": "revokedConfirmed", "reason": "moved_on"},
    ]}
    out = roster_io.seat_summary({"seating": "floating", "leasesActive": 2, "seatsUsed": 1},
                                 report, [], NOW)
    assert out["leasesActive"] == 2
    assert out["expiresAt"] is None
    assert out["counts"]["expiringWithin30Days"] == 0
    assert out["counts"]["revocationPending"] == 1
    assert out["counts"]["revocationPendingNoCheckIn"] == 0
    assert out["counts"]["removed"] == 2
    far = roster_io.seat_summary({"expiresAt": NOW + timedelta(days=90)}, report, [], NOW)
    assert far["counts"]["expiringWithin30Days"] == 0


# ===================================================================== audit log


def _event(store, doc_id, license_id, ts, action="INSTITUTION_SEAT_ADD", **extra):
    store._data.setdefault("audit_logs", {})[doc_id] = {
        "ts": ts, "uid": "it-a", "action": action, "outcome": "OK",
        "ip": "203.0.113.9", "deviceId": "dev-1",
        "target": {"type": "seat", "id": f"{license_id}/u"}, "detail": {"n": doc_id},
        "licenseId": license_id, **extra,
    }


@pytest.fixture
def logged(it):
    day = datetime(2026, 10, 1, 12, tzinfo=timezone.utc)
    for i in range(5):
        _event(it, f"a{i}", it.a, day + timedelta(days=i))
    _event(it, "b0", it.b, day + timedelta(days=2))
    _event(it, "b1", it.b, day + timedelta(days=9))
    return it


@pytest.mark.asyncio
async def test_the_audit_log_pages_newest_first_and_holds_one_licence(logged, client):
    first = (await client.get(f"{logged.base}/audit?page_size=2", headers=logged.bearer)).json()
    assert [e["id"] for e in first["events"]] == ["a4", "a3"]
    assert first["page"] == {"size": 2, "count": 2, "nextPageToken": "a3", "hasMore": True}
    event = first["events"][0]
    assert event["ts"] == "2026-10-05T12:00:00Z"
    assert "ip" not in event and "deviceId" not in event

    ids, token = [], first["page"]["nextPageToken"]
    while token:
        page = (await client.get(f"{logged.base}/audit?page_size=2&page_token={token}",
                                 headers=logged.bearer)).json()
        ids += [e["id"] for e in page["events"]]
        token = page["page"]["nextPageToken"]
    assert ids == ["a2", "a1", "a0"]


@pytest.mark.asyncio
async def test_another_licences_page_token_restarts_the_log(logged, client):
    page = (await client.get(f"{logged.base}/audit?page_token=b1", headers=logged.bearer)).json()
    assert [e["id"] for e in page["events"]] == ["a4", "a3", "a2", "a1", "a0"]
    page = (await client.get(f"{logged.base}/audit?page_token=nothing", headers=logged.bearer)).json()
    assert len(page["events"]) == 5


@pytest.mark.asyncio
async def test_the_audit_log_filters_by_date(logged, client):
    async def ids(query):
        r = await client.get(f"{logged.base}/audit?{query}", headers=logged.bearer)
        assert r.status_code == 200, r.text
        return [e["id"] for e in r.json()["events"]]

    assert await ids("since=2026-10-03") == ["a4", "a3", "a2"]
    assert await ids("until=2026-10-02") == ["a1", "a0"], "a bare end date includes that day"
    assert await ids("since=2026-10-02&until=2026-10-03") == ["a2", "a1"]
    assert await ids("since=2026-10-02T13:00:00Z&until=2026-10-04T12:00:00") == ["a2"]


@pytest.mark.asyncio
@pytest.mark.parametrize("query", ["since=yesterday", "until=2026-13-01",
                                   "since=2026-10-05&until=2026-10-01"])
async def test_a_range_that_is_not_one_is_refused(logged, client, query):
    r = await client.get(f"{logged.base}/audit?{query}", headers=logged.bearer)
    assert r.status_code == 400
    assert r.json()["detail"] == "invalid_audit_range"


@pytest.mark.asyncio
async def test_the_audit_log_downloads_as_csv(logged, client):
    logged.audited.clear()
    r = await client.get(f"{logged.base}/audit?format=csv&page_size=3&since=2026-10-01",
                         headers=logged.bearer)
    assert r.status_code == 200
    assert r.headers["content-type"] == "text/csv; charset=utf-8"
    assert r.headers["content-disposition"] == f'attachment; filename="audit-{logged.a}.csv"'
    assert r.headers["x-next-page-token"] == "a2"
    lines = r.text.splitlines()
    assert lines[0] == "ts,action,outcome,actorUid,targetType,targetId,detail"
    assert lines[1] == f'2026-10-05T12:00:00Z,INSTITUTION_SEAT_ADD,OK,it-a,seat,{logged.a}/u,"{{""n"":""a4""}}"'
    assert len(lines) == 4
    assert [a["action"] for a in logged.audited] == ["INSTITUTION_AUDIT_EXPORT"]
    assert logged.audited[0]["detail"] == {"count": 3, "since": "2026-10-01", "until": ""}

    last = await client.get(f"{logged.base}/audit?format=csv&page_token=a2", headers=logged.bearer)
    assert "x-next-page-token" not in last.headers


# ===================================================================== audit.record


def test_licence_events_are_stamped_with_their_licence(monkeypatch):
    rows = []
    monkeypatch.setattr(audit, "_add_doc", lambda col, data: rows.append(data))
    monkeypatch.setattr(audit, "db", lambda: type("Db", (), {"collection": lambda s, n: n})())
    audit.record("u", action="INSTITUTION_SEAT_ADD", target={"type": "seat", "id": "L1/u9"})
    audit.record("u", action="LICENSE_DEVICE_UNBIND", target={"type": "seat", "id": "L1"})
    audit.record("u", action="ADMIN_LICENSE_REVOKE", target={"type": "license", "id": "L2"})
    audit.record("u", action="SESSION_CREATE", target={"type": "session", "id": "s1"})
    audit.record("u", action="LICENSE_DEVICE_BIND", target={"type": "license", "id": None})
    assert [r.get("licenseId") for r in rows] == ["L1", "L1", "L2", None, None]


def test_a_lost_audit_write_is_one_structured_line(monkeypatch, caplog):
    def refuse(col, data):
        raise google_exceptions.DeadlineExceeded("slow")

    monkeypatch.setattr(audit, "_add_doc", refuse)
    monkeypatch.setattr(audit, "db", lambda: type("Db", (), {"collection": lambda s, n: n})())
    with caplog.at_level(logging.ERROR, logger="audit"):
        audit.record("u", action="INSTITUTION_SEAT_ADD", target={"type": "seat", "id": "L1/u"})
    (rec,) = [r for r in caplog.records if r.name == "audit"]
    line = json.loads(rec.getMessage())
    assert line["event"] == "audit_write_failed"
    assert line["action"] == "INSTITUTION_SEAT_ADD"
    assert line["errorCode"] == "DeadlineExceeded"
    assert "slow" not in rec.getMessage()


# ===================================================================== roster_io


def test_an_import_without_a_header_reads_the_first_column():
    rows = roster_io.parse_import('﻿a@x.edu,Ana\n"b@x.edu",Ben\n\n c@x.edu \n')
    assert [(r.row, r.email) for r in rows] == [(1, "a@x.edu"), (2, "b@x.edu"), (4, "c@x.edu")]


def test_an_import_header_may_put_email_anywhere():
    rows = roster_io.parse_import("Name,EMAIL,Dept\nAna,a@x.edu,Eng\nBen\n")
    assert [(r.row, r.email) for r in rows] == [(2, "a@x.edu"), (3, "")]


@pytest.mark.parametrize("lead", ["=", "+", "-", "@", "\t", "\r"])
def test_an_imported_cell_drops_the_guard_the_export_added(lead):
    assert roster_io.undefused(roster_io._cell(f"{lead}x@y.edu")) == f"{lead}x@y.edu".strip()


def test_only_one_guard_quote_is_dropped_and_only_before_a_formula_lead():
    assert roster_io.undefused("''-a@x.edu") == "''-a@x.edu"
    assert roster_io.undefused("'a@x.edu") == "'a@x.edu"
    assert roster_io.undefused("'") == "'"
    assert roster_io.undefused("  '=a@x.edu ") == "=a@x.edu"
    rows = roster_io.parse_import("email\n'-a@x.edu\n'+b@x.edu\n'=c@x.edu\n'@d\n")
    assert [r.email for r in rows] == ["-a@x.edu", "+b@x.edu", "=c@x.edu", "@d"]


def test_csv_cells_are_written_for_a_spreadsheet():
    assert roster_io._cell(None) == ""
    assert roster_io._cell(datetime(2026, 1, 2, tzinfo=timezone.utc)) == "2026-01-02T00:00:00Z"
    for lead in ("=", "+", "-", "@", "\t"):
        assert roster_io._cell(f"{lead}x") == f"'{lead}x"
    assert roster_io._cell(3) == "3"
    assert roster_io.iso(None) == ""
    assert roster_io.iso("2026-01-01") == "2026-01-01"


def test_audit_range_reads_dates_and_instants():
    start, end = roster_io.audit_range("2026-10-01", "2026-10-01")
    assert (start, end) == (datetime(2026, 10, 1, tzinfo=timezone.utc),
                            datetime(2026, 10, 2, tzinfo=timezone.utc))
    start, _ = roster_io.audit_range("2026-10-01T05:30:00+05:30", "")
    assert start == datetime(2026, 10, 1, tzinfo=timezone.utc)
    assert roster_io.audit_range("", "") == (None, None)
    with pytest.raises(roster_io.RosterRefused) as refused:
        roster_io.audit_range("2026-10-02", "2026-10-01T12:00:00Z")
    assert refused.value.code == errors.INVALID_AUDIT_RANGE


def test_a_refusal_is_also_one_structured_log_line(monkeypatch, caplog):
    monkeypatch.setattr(audit, "_add_doc", lambda col, data: None)
    monkeypatch.setattr(audit, "db", lambda: type("Db", (), {"collection": lambda s, n: n})())
    with caplog.at_level(logging.WARNING, logger="audit"):
        audit.record(action="AUTH_DENIED", outcome="DENIED", detail={"stage": "id_token"})
        audit.record("u", action="INSTITUTION_SEAT_ADD", target={"type": "seat", "id": "L1/u"})
    (rec,) = [r for r in caplog.records if r.name == "audit"]
    line = json.loads(rec.getMessage())
    assert (line["event"], line["action"], line["stage"]) == ("audit_denied", "AUTH_DENIED", "id_token")
