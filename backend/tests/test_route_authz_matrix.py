"""Every route's auth tier, asserted as a table, plus cross-user negative tests.

Two different jobs:

1. `test_every_route_has_the_expected_auth_tier` is a structural guard. Adding a
   route without an auth dependency is the classic silent regression — the first
   batch of endpoints gets it right and a later addition quietly does not. This
   fails on any route not listed below, so a new one cannot ship without someone
   writing down what it should be.

2. The rest exercise the boundaries against real requests. Note that
   conftest.py sets DEV_INSECURE_AUTH=1 and deps._DEV_USER is role="admin", so
   every *other* HTTP test in the suite runs as an authenticated admin with auth
   bypassed. These tests turn that off.
"""
import base64
import hashlib

import fake_firestore
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import audit, deps, drive, firestore_repo as repo
from app.config import settings
from app.deps import admin_user, current_user, device_or_legacy_reader, verified_device
from app.main import app
from app.tasks import tasks_caller

NONE, USER, ADMIN, DEVICE, DEVICE_ADMIN = "none", "user", "admin", "device", "device+admin"
# Not a user tier: authenticated by the OIDC token Cloud Tasks attaches, and
# reachable by nothing else — no ID token or device signature will open it.
TASK = "cloud-task"
# Temporary tier: device-attested when the caller attests or the flag is set,
# but an unattested ID-token read is accepted meanwhile (the /uploads migration
# window). Recorded explicitly so the compatibility gap is visible in the table
# rather than masquerading as a plain USER or DEVICE route. Delete with the flag.
DEVICE_MIGRATING = "device-migrating"

# (method, path) -> required tier. Keep in sync deliberately, not automatically:
# the point is that a human decides.
EXPECTED = {
    ("GET", "/healthz"): NONE,                                  # liveness probe
    ("GET", "/readyz"): NONE,                                   # readiness probe
    ("GET", "/v1/me"): USER,
    ("GET", "/v1/config"): USER,
    ("GET", "/v1/me/export"): DEVICE,
    ("DELETE", "/v1/me"): DEVICE,
    ("POST", "/v1/devices/register"): USER,                     # bootstrap: no device yet
    ("POST", "/v1/challenge"): USER,                            # bootstrap: mints the nonce
    ("GET", "/v1/sessions"): USER,
    ("POST", "/v1/sessions"): DEVICE,
    ("DELETE", "/v1/sessions/{sid}"): DEVICE,
    ("GET", "/v1/sessions/{sid}/uploads"): DEVICE_MIGRATING,    # returns Drive upload URIs
    ("GET", "/v1/sessions/{sid}/files"): USER,
    ("GET", "/v1/files/{file_id}/content"): DEVICE,
    ("POST", "/v1/files/{file_id}/complete"): DEVICE,
    ("GET", "/v1/admin/users"): ADMIN,                          # read-only: no device needed
    ("POST", "/v1/admin/users/{uid}/approve"): DEVICE_ADMIN,
    ("POST", "/v1/admin/users/{uid}/revoke"): DEVICE_ADMIN,
    ("PATCH", "/v1/admin/users/{uid}/config"): DEVICE_ADMIN,
    ("POST", "/v1/tasks/provision-session"): TASK,
}


def _flatten(dependant):
    for sub in dependant.dependencies:
        if sub.call is not None:
            yield sub.call
        yield from _flatten(sub)


def _tier(route) -> str:
    calls = set(_flatten(route.dependant))
    if tasks_caller in calls:
        return TASK
    # The migration wrapper calls verified_device directly (not via Depends), so
    # it never appears in the flattened deps — detect the wrapper itself.
    if device_or_legacy_reader in calls:
        return DEVICE_MIGRATING
    has_device = verified_device in calls
    has_admin = admin_user in calls
    if has_device and has_admin:
        return DEVICE_ADMIN
    if has_device:
        return DEVICE
    if has_admin:
        return ADMIN
    if current_user in calls:
        return USER
    return NONE


def _iter_api_routes(routes):
    """Walk `app.routes`, including FastAPI `_IncludedRouter` wrappers.

    `include_router` no longer flattens child APIRoutes onto `app.routes`;
    they live on `original_router.routes`. The auth-tier table still needs
    every user-facing path.
    """
    for route in routes:
        nested = getattr(route, "original_router", None)
        if nested is not None:
            yield from _iter_api_routes(nested.routes)
            continue
        yield route


def _actual_routes():
    out = {}
    for route in _iter_api_routes(app.routes):
        if not hasattr(route, "dependant") or not getattr(route, "methods", None):
            continue
        for method in route.methods:
            if method in ("HEAD", "OPTIONS"):
                continue
            out[(method, route.path)] = _tier(route)
    return out


def test_every_route_has_the_expected_auth_tier():
    actual = _actual_routes()

    undeclared = set(actual) - set(EXPECTED)
    assert not undeclared, (
        f"route(s) added without an auth decision: {sorted(undeclared)}. "
        "Add them to EXPECTED with the tier they should require."
    )
    removed = set(EXPECTED) - set(actual)
    assert not removed, f"EXPECTED lists route(s) that no longer exist: {sorted(removed)}"

    wrong = {k: (EXPECTED[k], actual[k]) for k in EXPECTED if EXPECTED[k] != actual[k]}
    assert not wrong, f"auth tier changed (expected, actual): {wrong}"


def test_only_health_probes_are_unauthenticated():
    """Stated separately so the intent survives an edit to the table above.

    The Cloud Tasks callback is deliberately not in this set: it has no *user*
    dependency, but it is authenticated — see test_task_route_rejects_users.
    """
    unauth = {k for k, v in _actual_routes().items() if v == NONE}
    assert unauth == {("GET", "/healthz"), ("GET", "/readyz")}


# --------------------------------------------------------------- live requests

VICTIM = "victim-uid"
ATTACKER = "attacker-uid"


def _sign(priv, nonce, method, path, body: bytes) -> str:
    msg = (nonce + method + path).encode() + hashlib.sha256(body).digest()
    return base64.b64encode(priv.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


@pytest.fixture
def attacker(monkeypatch):
    """A fully legitimate, approved, device-attested user — who owns nothing.

    Everything they are denied below is denied on ownership, not on credentials.
    """
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(repo, "consume_nonce", lambda *a, **k: True)

    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    profile = {
        "uid": ATTACKER, "email": "attacker@example.com", "role": "user",
        "access_status": "APPROVED", "activeDeviceId": "atk-device",
    }
    monkeypatch.setattr(deps, "verify_id_token", lambda _t: {"sub": ATTACKER})
    monkeypatch.setattr(repo, "get_or_create_user", lambda claims: dict(profile))
    store._data["devices"] = {"atk-device": {
        "uid": ATTACKER, "status": "ACTIVE", "publicKeyPem": pem,
    }}

    # The victim's data, which the attacker will try to reach.
    store._data["sessions"] = {"s-victim": {
        "uid": VICTIM, "status": "UPLOADING", "localSessionId": "lv",
        "driveFolderId": "victim-folder", "fileCount": 1, "completedCount": 0,
    }}
    store._data["files"] = {"f-victim": {
        "uid": VICTIM, "sessionId": "s-victim", "driveFileId": "victim-drive-file",
        "name": "secret.zip", "sizeBytes": 10, "status": "COMPLETED",
    }}

    def signed(method, path, body=b""):
        nonce = "n-attacker"
        return {
            "Authorization": "Bearer ok",
            "X-Device-Id": "atk-device",
            "X-Nonce": nonce,
            "X-Signature": _sign(priv, nonce, method, path, body),
            # Without this the body never parses and the route 422s before it
            # ever reaches the ownership check we are trying to exercise.
            "Content-Type": "application/json",
        }

    store.signed = signed
    store.bearer = {"Authorization": "Bearer ok"}
    return store


@pytest.mark.parametrize("method,path,detail", [
    ("GET", "/v1/sessions/s-victim/uploads", "session_not_found"),
    ("DELETE", "/v1/sessions/s-victim", "session_not_found"),
    ("GET", "/v1/files/f-victim/content", "file_not_found"),
])
async def test_attested_user_cannot_reach_another_users_data(attacker, client, method, path, detail):
    """Valid token, valid device, valid signature — and still denied. Before
    this, only the /files listing had a cross-user test."""
    r = await client.request(method, path, headers=attacker.signed(method, path))
    assert r.status_code == 404, r.text
    assert r.json()["detail"] == detail
    # And nothing was destroyed on the way to being denied.
    assert "s-victim" in attacker._data["sessions"]
    assert "f-victim" in attacker._data["files"]


async def test_attested_user_cannot_complete_another_users_file(attacker, client, monkeypatch):
    monkeypatch.setattr(drive, "get_file_meta", lambda t, fid: {
        "size": 10, "md5": "b" * 32, "parents": ["victim-folder"],
    })
    path = "/v1/files/f-victim/complete"
    body = b'{"sessionId":"s-victim","driveFileId":"d","bytes":10,"md5":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'
    r = await client.post(path, headers=attacker.signed("POST", path, body), content=body)
    assert r.status_code == 404
    assert attacker._data["files"]["f-victim"]["status"] == "COMPLETED"
    assert attacker._data["sessions"]["s-victim"]["completedCount"] == 0


@pytest.mark.parametrize("method,path", [
    ("GET", "/v1/admin/users"),
    ("POST", "/v1/admin/users/victim-uid/approve"),
    ("POST", "/v1/admin/users/victim-uid/revoke"),
    ("PATCH", "/v1/admin/users/victim-uid/config"),
])
async def test_non_admin_is_refused_every_admin_route(attacker, client, method, path):
    attacker._data["users"] = {VICTIM: {"email": "v@e.com", "access_status": "PENDING"}}
    body = b'{"maxSessions":9999}' if method == "PATCH" else b""
    r = await client.request(method, path, headers=attacker.signed(method, path, body), content=body)
    assert r.status_code == 403, r.text
    assert r.json()["detail"] == "not_admin"
    assert attacker._data["users"][VICTIM]["access_status"] == "PENDING"


@pytest.mark.parametrize("method,path", [
    ("GET", "/v1/me/export"),
    ("DELETE", "/v1/me"),
    ("POST", "/v1/sessions"),
    ("GET", "/v1/files/f-victim/content"),
])
async def test_device_routes_reject_a_bare_id_token(attacker, client, method, path):
    """A stolen ID token, with no device key, must not reach these at all.

    /uploads is intentionally absent: it is behind the migration wrapper and is
    covered by the DEVICE_MIGRATING tests below, where a bare token is accepted
    while REQUIRE_ATTESTED_UPLOADS is off and rejected once it is on.
    """
    r = await client.request(method, path, headers=attacker.bearer)
    assert r.status_code in (400, 401), f"{method} {path} -> {r.status_code}"
    assert attacker._data["files"], "account data was touched without attestation"


# ---------------------------------------------------- /uploads migration window
#
# The resume list carries Drive capability URLs and should be device-attested,
# but testers on an older build read it with an ID token only. These pin the
# temporary compromise so the flip point is a deliberate change, not a surprise.

def _owned_session(attacker):
    """A session the attacker actually owns, so a lenient read reaches 200
    (the cross-user fixture session is owned by the victim and 404s first)."""
    attacker._data["sessions"]["s-mine"] = {
        "uid": ATTACKER, "status": "UPLOADING", "localSessionId": "lm",
        "driveFolderId": "mine-folder", "fileCount": 0, "completedCount": 0,
    }


async def test_uploads_lenient_accepts_bare_token_and_logs(attacker, client, monkeypatch, caplog):
    """Flag off + no device headers: the old fleet's bare-token read succeeds,
    and every such call emits one observable legacy_unattested_uploads event."""
    monkeypatch.setattr(settings, "REQUIRE_ATTESTED_UPLOADS", False)
    _owned_session(attacker)
    with caplog.at_level("WARNING", logger="indic.auth"):
        r = await client.get("/v1/sessions/s-mine/uploads", headers=attacker.bearer)
    assert r.status_code == 200, r.text
    assert r.json()["sessionId"] == "s-mine"
    assert "legacy_unattested_uploads" in caplog.text


async def test_uploads_lenient_still_enforces_strict_path_when_headers_present(attacker, client, monkeypatch):
    """Flag off but the caller presents device headers: it cannot be downgraded
    to the lenient path — a bad signature is still rejected."""
    monkeypatch.setattr(settings, "REQUIRE_ATTESTED_UPLOADS", False)
    _owned_session(attacker)
    headers = {
        "Authorization": "Bearer ok",
        "X-Device-Id": "atk-device",
        "X-Nonce": "n-attacker",
        "X-Signature": "not-a-valid-signature",
    }
    r = await client.get("/v1/sessions/s-mine/uploads", headers=headers)
    assert r.status_code == 401, r.text


async def test_uploads_strict_when_flag_set_rejects_bare_token(attacker, client, monkeypatch):
    """Flag on: the endpoint is fully device-attested — a bare token is refused."""
    monkeypatch.setattr(settings, "REQUIRE_ATTESTED_UPLOADS", True)
    _owned_session(attacker)
    r = await client.get("/v1/sessions/s-mine/uploads", headers=attacker.bearer)
    assert r.status_code in (400, 401), r.text


async def test_uploads_strict_when_flag_set_accepts_attested_read(attacker, client, monkeypatch):
    """Flag on: a properly device-signed read still works."""
    monkeypatch.setattr(settings, "REQUIRE_ATTESTED_UPLOADS", True)
    _owned_session(attacker)
    path = "/v1/sessions/s-mine/uploads"
    r = await client.get(path, headers=attacker.signed("GET", path))
    assert r.status_code == 200, r.text
    assert r.json()["sessionId"] == "s-mine"


async def test_download_requires_attestation_not_just_ownership(attacker, client):
    """The route previously had only a reflection-based test that never issued
    a request, so nothing proved the dependency was actually enforced."""
    attacker._data["files"]["f-mine"] = {
        "uid": ATTACKER, "sessionId": "s", "driveFileId": "d", "name": "a.zip",
    }
    unsigned = await client.get("/v1/files/f-mine/content", headers=attacker.bearer)
    assert unsigned.status_code in (400, 401)
