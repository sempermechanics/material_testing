"""Firestore reads and writes per request, for the calls the app makes.

Firestore bills per document read, and the app calls these routes on every open
(`/v1/config`, `/v1/me`, `GET /v1/sessions`) or once per analysis (create, one
completion per file, erase). This counts what each route costs against the
store double, the way Firestore bills it: a document get is one read, a query
one read per document returned (at least one), a count one read. The budgets
below are what the routes cost today; a change that adds a read fails here, and
one that saves reads updates the number (docs/perf/request-volume.md).

Real device auth, not DEV_INSECURE_AUTH: the account lookup, the licence
re-checks and the nonce claim are part of what every call costs.
"""
import base64
import collections
import hashlib
import itertools
import json
import time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import fake_firestore
from app import audit, deps, drive, rate_limit
from app.config import settings

UID = "u1"
DEVICE = "dev-1"
SESSIONS = 20  # already in the cloud when the app lists them
FILES = 3  # a split bundle: Session.zip, Extras.zip, manifest
_SHA = "a" * 64
_MD5 = "b" * 32


class Meter:
    """Counts billable reads and writes by patching the store double's classes."""

    def __init__(self, monkeypatch):
        self.reads = 0
        self.writes = 0
        self.by_kind = collections.Counter()
        m = self

        def counted(cls, name, kind, cost):
            real = getattr(cls, name)

            def wrapper(*a, **k):
                out = real(*a, **k)
                n = cost(out)
                m.by_kind[kind] += n
                if kind.startswith("read"):
                    m.reads += n
                else:
                    m.writes += n
                return out

            monkeypatch.setattr(cls, name, wrapper)

        one = lambda _out: 1  # noqa: E731
        counted(fake_firestore._DocRef, "get", "read:get", one)
        # A query is billed per document returned, and at least one read; a
        # count aggregation is one read per 1000 index entries, so one here.
        real_stream = fake_firestore._Query.stream

        def stream(q):
            rows = list(real_stream(q))
            m.by_kind["read:query"] += max(1, len(rows))
            m.reads += max(1, len(rows))
            return iter(rows)

        monkeypatch.setattr(fake_firestore._Query, "stream", stream)
        counted(fake_firestore._Query, "count", "read:count", one)
        for w in ("set", "create", "update", "delete"):
            counted(fake_firestore._DocRef, w, f"write:{w}", one)

    def reset(self):
        self.reads = self.writes = 0
        self.by_kind.clear()


@pytest.fixture
def world(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(deps, "verify_id_token",
                        lambda _t: {"sub": UID, "email": "u1@example.com", "email_verified": True})
    for bucket in vars(rate_limit).values():
        if isinstance(bucket, rate_limit.TokenBucket):
            monkeypatch.setattr(bucket, "allow", lambda key: True)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "ensure_session_folders", lambda *a, **k: {
        "sessionFolderId": "sf", "userFolderId": "uf", "sessionsFolderId": "usf", "bundle": "sf"})
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(drive, "get_file_meta",
                        lambda _t, _id: {"size": 10, "md5": _MD5, "parents": ["sf"]})
    monkeypatch.setattr(drive, "delete_file", lambda *a, **k: None)

    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    store._data["users"] = {UID: {
        "uid": UID, "email": "u1@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": DEVICE,
    }}
    store._data["devices"] = {DEVICE: {"uid": UID, "status": "ACTIVE", "publicKeyPem": pem}}
    store._data["sessions"] = {
        f"s{i:02d}": {"uid": UID, "status": "COMPLETED", "localSessionId": f"local-{i}",
                      "fileCount": FILES, "completedCount": FILES, "driveFolderId": f"f{i}"}
        for i in range(SESSIONS)
    }
    return priv, Meter(monkeypatch)


_nonces = itertools.count()
KINDS: dict = {}  # per-call breakdown, printed with the table


def _headers(priv, method, path, body=b""):
    nonce = f"t1.{int(time.time())}.{next(_nonces):022d}"
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    sig = base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
    return {"Authorization": "Bearer ok", "X-Device-Id": DEVICE, "X-Nonce": nonce,
            "X-Signature": sig, "Content-Type": "application/json"}


async def _call(client, priv, meter, method, path, payload=None, signed=True):
    body = json.dumps(payload).encode() if payload is not None else b""
    headers = _headers(priv, method, path, body) if signed else {
        "Authorization": "Bearer ok", "X-Device-Id": DEVICE}
    meter.reset()
    r = await client.request(method, path, content=body or None, headers=headers)
    assert r.status_code == 200, f"{method} {path}: {r.status_code} {r.text}"
    KINDS[f"{method} {path.split('?')[0]}"] = dict(meter.by_kind)
    return r.json(), (meter.reads, meter.writes)


async def _measure(client, priv, meter):
    """What one app open and one analysis upload cost, route by route."""
    cost = {}
    # An account's first config call sets up its demo licence; every later one is the app-open cost.
    _, cost["GET /v1/config (first ever)"] = await _call(
        client, priv, meter, "GET", "/v1/config", signed=False)
    _, cost["GET /v1/config"] = await _call(client, priv, meter, "GET", "/v1/config", signed=False)
    _, cost["GET /v1/me"] = await _call(client, priv, meter, "GET", "/v1/me", signed=False)
    listing, cost[f"GET /v1/sessions (S={SESSIONS})"] = await _call(
        client, priv, meter, "GET", "/v1/sessions?page_size=100", signed=False)
    assert len(listing["sessions"]) == SESSIONS

    created, cost[f"POST /v1/sessions (N={FILES})"] = await _call(
        client, priv, meter, "POST", "/v1/sessions", {
            "specimen": "s", "localSessionId": "local-new",
            "files": [{"name": f"f{i}.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}
                      for i in range(FILES)],
        })
    sid = created["sessionId"]
    assert len(created["uploads"]) == FILES
    completes = []
    for up in created["uploads"]:
        _, c = await _call(client, priv, meter, "POST", f"/v1/files/{up['fileId']}/complete",
                           {"sessionId": sid, "driveFileId": f"d{up['fileId'][-6:]}",
                            "bytes": 10, "md5": _MD5})
        completes.append(c)
    cost["POST /v1/files/{id}/complete (each)"] = max(completes)
    _, cost[f"DELETE /v1/sessions/{{id}} (N={FILES})"] = await _call(
        client, priv, meter, "DELETE", f"/v1/sessions/{sid}")
    return cost


#: (reads, writes) per request, measured 2026-09-25. POST /v1/sessions was
#: (14, 11) before the inline path stopped reading back what it had just written.
BUDGET = {
    "GET /v1/config (first ever)": (4, 3),
    "GET /v1/config": (1, 0),
    "GET /v1/me": (1, 0),
    f"GET /v1/sessions (S={SESSIONS})": (SESSIONS + 2, 0),
    f"POST /v1/sessions (N={FILES})": (9, 11),
    "POST /v1/files/{id}/complete (each)": (6, 4),
    f"DELETE /v1/sessions/{{id}} (N={FILES})": (7, 5),
}


@pytest.mark.asyncio
async def test_firestore_cost_per_request_stays_in_budget(world, client, capsys):
    priv, meter = world
    cost = await _measure(client, priv, meter)
    with capsys.disabled():
        print("\n| Route | reads | writes |\n|---|--:|--:|")
        for route, (r, w) in cost.items():
            print(f"| {route} | {r} | {w} |")
        for call, kinds in KINDS.items():
            print(f"  {call}: {kinds}")
    assert cost == BUDGET


#: Reads for one POST /v1/sessions by manifest size, up to the largest manifest
#: provisioned inline (INLINE_PROVISION_MAX_FILES). Before: 8 + 2N.
CREATE_READS = {1: 7, 3: 9, 8: 14}


@pytest.mark.asyncio
@pytest.mark.parametrize("n", sorted(CREATE_READS))
async def test_create_reads_grow_once_per_file(world, client, n):
    priv, meter = world
    # The account's first call sets up its licence; measure the steady state.
    await _call(client, priv, meter, "GET", "/v1/config", signed=False)
    _, (reads, _writes) = await _call(client, priv, meter, "POST", "/v1/sessions", {
        "specimen": "s", "localSessionId": f"local-n{n}",
        "files": [{"name": f"f{i}.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}
                  for i in range(n)],
    })
    assert reads == CREATE_READS[n]
