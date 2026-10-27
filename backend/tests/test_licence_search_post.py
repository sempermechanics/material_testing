"""`POST /v1/admin/licenses/search`: the desk's search with the address in a
body, not the URL (TD-203). The GET with `q` is served for one more release
and logs `deprecated_query_search`, without the value."""
import json
import logging
from datetime import datetime, timedelta, timezone

import pytest

from app import rate_limit

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
SEARCH = "/v1/admin/licenses/search"


def _seed(store, doc_id, *, mode="licensed", status="unused", minutes=0, **extra):
    store._data.setdefault("licenses", {})[doc_id] = {
        "keyPrefix": f"SEMP-{doc_id[:4].upper()}", "kind": "individual", "mode": mode,
        "status": status, "createdAt": T0 + timedelta(minutes=minutes), **extra,
    }


def _ids(body):
    return [row["id"] for row in body["licenses"]]


@pytest.fixture
def desk(store):
    _seed(store, "live", minutes=1, emailLock="a@b.com")
    _seed(store, "gone", status="revoked", minutes=2, emailLock="a@b.com")
    _seed(store, "demo", mode="demo", status="redeemed", minutes=3, emailLock="a@b.com")
    _seed(store, "other", minutes=4, emailLock="z@b.com")
    return store


@pytest.mark.asyncio
async def test_the_post_answers_as_the_get_does(desk, client):
    posted = await client.post(SEARCH, json={"q": "A@B.com"})
    got = await client.get("/v1/admin/licenses", params={"q": "A@B.com"})
    assert posted.status_code == 200, posted.text
    assert posted.json() == got.json()
    assert _ids(posted.json()) == ["gone", "live"]
    assert set(posted.json()) == {"licenses", "demoMaxAnalyses", "page"}


@pytest.mark.asyncio
async def test_the_body_carries_the_gets_filters(desk, client):
    body = (await client.post(SEARCH, json={"q": "a@b.com", "include_demo": True})).json()
    assert _ids(body) == ["demo", "gone", "live"]
    body = (await client.post(SEARCH, json={"q": "a@b.com", "include_revoked": False})).json()
    assert _ids(body) == ["live"]


@pytest.mark.asyncio
async def test_an_empty_q_pages_the_listing(desk, client):
    first = (await client.post(SEARCH, json={"q": "", "limit": 2})).json()
    assert _ids(first) == ["other", "gone"]
    assert first["page"]["hasMore"] is True
    token = first["page"]["nextPageToken"]
    second = (await client.post(SEARCH, json={"q": "", "limit": 2, "page_token": token})).json()
    assert _ids(second) == ["live"]


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [
    {},                                      # q is required
    {"q": "x" * 321},                        # LicenceSearch's bound
    {"q": "a@b.com", "page_token": "a/b"},   # PageToken's charset
    {"q": "a@b.com", "email": "a@b.com"},    # unknown field
    {"q": "a@b.com", "include_demo": "maybe"},
])
async def test_the_body_is_validated_as_the_query_is(desk, client, body):
    r = await client.post(SEARCH, json=body)
    assert r.status_code == 422, r.text


@pytest.mark.asyncio
async def test_the_post_spends_the_admin_bucket(desk, client, monkeypatch):
    monkeypatch.setattr(rate_limit, "admin_bucket", rate_limit.TokenBucket(0.001, 1.0))
    assert (await client.post(SEARCH, json={"q": "a@b.com"})).status_code == 200
    r = await client.post(SEARCH, json={"q": "a@b.com"})
    assert r.status_code == 429
    assert r.json()["detail"] == "rate_limited"


def _warnings(caplog):
    return [json.loads(r.message) for r in caplog.records
            if r.message.startswith("{") and json.loads(r.message)["event"] == "deprecated_query_search"]


@pytest.mark.asyncio
async def test_a_get_with_q_still_works_and_warns_without_the_value(desk, client, caplog):
    with caplog.at_level(logging.WARNING, logger="semper.admin"):
        r = await client.get("/v1/admin/licenses", params={"q": "a@b.com"})
    assert r.status_code == 200
    assert _ids(r.json()) == ["gone", "live"]
    [line] = _warnings(caplog)
    assert line["uid"] == "dev-user"
    assert line["path"] == "/v1/admin/licenses"
    assert "a@b.com" not in caplog.text


@pytest.mark.asyncio
async def test_the_get_without_q_and_the_post_do_not_warn(desk, client, caplog):
    with caplog.at_level(logging.WARNING, logger="semper.admin"):
        await client.get("/v1/admin/licenses")
        await client.post(SEARCH, json={"q": "a@b.com"})
    assert _warnings(caplog) == []
