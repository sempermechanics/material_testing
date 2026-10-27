"""TD-186: the listings read only the fields they answer with, the licence
search runs its lookups at once, and the access log takes the route template
from the routing that already happened.

Projections (`select()`, `get_all(field_paths=...)`) cut the bytes a listing
pulls, not its read count. The store double honours them, so a projection
that leaves out a field a listing reads fails the listing's own tests.
"""
import json
import logging
import threading

import pytest

import fake_firestore
import repo_view as repo
from app import observability as obs
from app.repo import license_admin, reconcile, sessions


class _Recording(dict):
    """A dict that remembers every key read from it."""

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.read: set[str] = set()

    def get(self, key, default=None):
        self.read.add(key)
        return super().get(key, default)

    def __getitem__(self, key):
        self.read.add(key)
        return super().__getitem__(key)


# ---------------- the store double ----------------


def test_the_double_projects_a_query_and_a_get_all(store):
    col = store.collection("things")
    col.document("a").set({"keep": 1, "nested": {"x": 2, "y": 3}, "drop": "big"})
    [snap] = list(col.where("keep", "==", 1).select(["keep", "nested.x", "absent"]).stream())
    assert snap.to_dict() == {"keep": 1, "nested": {"x": 2}}
    # The filter still saw the whole document, and the store is untouched.
    assert store._data["things"]["a"]["drop"] == "big"
    [got] = store.get_all([col.document("a")], field_paths=["drop"])
    assert got.to_dict() == {"drop": "big"}


# ---------------- GET /v1/sessions ----------------


def test_the_session_listing_reads_only_the_fields_it_lists():
    """`LISTED_SESSION_FIELDS` is the projection; a field `_listed_session`
    reads that is not in it would come back empty from Firestore."""
    doc = _Recording({f: None for f in sessions.LISTED_SESSION_FIELDS})
    sessions._listed_session("s1", doc)
    assert doc.read <= set(sessions.LISTED_SESSION_FIELDS)
    assert set(sessions.LISTED_SESSION_FIELDS) <= doc.read


@pytest.mark.parametrize("app", [None, "semper"])
def test_the_session_listing_does_not_pull_whole_documents(store, monkeypatch, app):
    store._data["sessions"] = {
        f"s{i}": {"uid": "u1", "name": f"n{i}", "status": "SYNCED", "fileCount": 2,
                  "metrics": {"frameCount": 400}, "notes": "x" * 1000}
        for i in range(3)
    }
    seen = []
    real = sessions._listed_session
    monkeypatch.setattr(sessions, "_listed_session",
                        lambda doc_id, s: seen.append(s) or real(doc_id, s))
    page, token = repo.list_user_sessions("u1", limit=2, app=app)
    assert [p["sessionId"] for p in page] == ["s0", "s1"] and token == "s1"  # noqa: S105 - a page cursor
    assert page[0]["name"] == "n0" and page[0]["fileCount"] == 2
    assert all("notes" not in s and "metrics" not in s for s in seen)
    # The next page still positions on the token.
    page, token = repo.list_user_sessions("u1", limit=2, page_token=token, app=app)
    assert [p["sessionId"] for p in page] == ["s2"] and token is None


# ---------------- reconcile ----------------


def test_reconcile_projects_the_roster_and_the_holders(store, monkeypatch):
    lic = store.collection("licenses").document("L1")
    lic.set({"kind": "institution", "seatsUsed": 1, "maxSeats": 5})
    lic.collection("seats").document("u1").set(
        {"uid": "u1", "email": "u1@x.edu", "status": "active", "history": ["x"] * 50})
    store._data["users"] = {"u1": {
        "email": "u1@x.edu", "licenseId": "L1", "mode": "licensed",
        "consents": {"big": "x" * 1000}, "lastSeenAt": None,
    }}
    paths = []
    real = fake_firestore.FakeClient.get_all

    def spy(self, refs, transaction=None, *, field_paths=None, retry=None, timeout=None):
        paths.append(field_paths)
        return real(self, refs, transaction, field_paths=field_paths, retry=retry, timeout=timeout)

    monkeypatch.setattr(fake_firestore.FakeClient, "get_all", spy)
    report = repo.reconcile_institution_seats("L1")
    assert [tuple(p) for p in paths] == [reconcile.HOLDER_FIELDS]
    assert report["entitled"] == 1
    assert report["seats"][0]["bucket"] == "active"
    assert report["seats"][0]["email"] == "u1@x.edu"


# ---------------- the licence search ----------------


def test_the_search_runs_its_lookups_at_once(store, monkeypatch):
    """Three single-field lookups; each waits on the barrier, so a search
    that ran them one after another would break it and fail."""
    store._data["licenses"] = {
        "a": {"emailLock": "semp-abcd@x.com", "mode": "licensed", "createdAt": "1"},
    }
    barrier = threading.Barrier(3, timeout=5)
    real = license_admin._stream

    def gated(query):
        barrier.wait()
        return real(query)

    monkeypatch.setattr(license_admin, "_stream", gated)
    # An address that also reads as a key, so all three lookups run.
    rows = repo.list_licenses(q="SEMP-ABCD@x.com")[0]
    assert [r["id"] for r in rows] == ["a"]


def test_a_failed_lookup_fails_the_search(store, monkeypatch):
    def boom(query):
        raise RuntimeError("firestore down")

    monkeypatch.setattr(license_admin, "_stream", boom)
    with pytest.raises(RuntimeError):
        repo.list_licenses(q="a@b.com")


# ---------------- the access log ----------------


async def test_the_access_log_takes_the_template_from_the_router(store, client, caplog, monkeypatch):
    """The edge middleware reads the template FastAPI matched, under its
    router prefix, and does not match the path against every route again."""
    def no_rematch(path):
        raise AssertionError(f"re-matched {path}")

    monkeypatch.setattr(obs, "route_template", no_rematch)
    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/v1/institutions/licenses/SEMP-ABCD/seats")
        await client.get("/v1/me")
    entries = [json.loads(r.message) for r in caplog.records if r.name == "semper.access"]
    assert [e["routeTemplate"] for e in entries[-2:]] == [
        "/v1/institutions/licenses/{id}/seats", "/v1/me",
    ]
    assert entries[-2]["opClass"] == "institution"
    assert entries[-1]["opClass"] == "login"


async def test_an_unrouted_path_still_falls_back(client, caplog):
    with caplog.at_level(logging.INFO, logger="semper.access"):
        await client.get("/nope/abcdef0123456789")
    entry = json.loads([r.message for r in caplog.records if r.name == "semper.access"][-1])
    assert entry["routeTemplate"] == "/nope/{id}"
    assert entry["opClass"] == "other"


def test_every_served_route_reports_its_template():
    """The template the middleware reads off a matched route is the one
    `register_routes` would build for it: FastAPI's effective route context
    carries the prefixed path_format."""
    from app.main import app, served_routes

    for route in served_routes(app):
        assert obs.template_of(route) == obs._PARAM.sub("{id}", route.path_format)


def test_a_holder_with_none_of_the_projected_fields_still_exists(store):
    """Firestore answers a projected read of a document holding none of the
    fields with an empty document, not a missing one: the account is there,
    on no licence, so its seat reads `moved_on`, not `no_account`."""
    lic = store.collection("licenses").document("L1")
    lic.set({"kind": "institution", "seatsUsed": 2})
    for uid in ("u1", "u2"):
        lic.collection("seats").document(uid).set({"uid": uid, "status": "active"})
    store._data["users"] = {"u1": {"access_status": "APPROVED"}}
    report = repo.reconcile_institution_seats("L1")
    reasons = {row["uid"]: row["reason"] for row in report["seats"]}
    assert reasons == {"u1": reconcile.MOVED_ON, "u2": reconcile.NO_ACCOUNT}
    assert {row["uid"]: row["userMode"] for row in report["seats"]}["u2"] == ""
