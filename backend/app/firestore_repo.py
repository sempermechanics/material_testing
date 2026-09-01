"""Server-side Firestore access. Clients never touch Firestore directly."""
import logging
import secrets
from datetime import datetime, timedelta, timezone

from google.api_core.exceptions import Aborted, AlreadyExists, NotFound
from google.cloud import firestore

from . import errors, notify, statuses
from .config import settings
from .models import DeviceReg, FileComplete, FileSpec, SessionCreate

log = logging.getLogger("indic.firestore")
_DB = None

# Every server-owned document carries this integer. Migrations must be
# idempotent and advance documents only after an export/restore checkpoint.
SCHEMA_VERSION = 1

# Transaction retries on the hot single-document paths (nonce consumption, file
# completion). The client default is 5; contention there is expected rather than
# exceptional, and every lost race costs a legitimate caller a round trip.
_TX_ATTEMPTS = 10

# Firestore caps a write batch at 500 operations.
_BATCH_LIMIT = 400

# Soft cap on ordinary response manifests. Erasure deliberately does not use
# this cap; it loops in bounded batches until the relevant query is empty.
_LIST_SOFT_LIMIT = 2000

# get_or_create_user's "last seen" write is throttled to this granularity — a
# proxied restore makes dozens of authenticated requests (one per download
# window) in quick succession, so an unconditional write here was dozens of
# Firestore writes to record a timestamp nobody reads at finer resolution.
_LAST_SEEN_THROTTLE = timedelta(hours=1)


def _lost_to_contention(exc: BaseException) -> bool:
    """True when a transaction failed only because it kept losing the race.

    The client retries an ABORTED transaction five times and then raises
    ValueError("Failed to commit transaction in 5 attempts") chained from the
    last Aborted. Two callers hammering one hot document — the same nonce
    replayed, the same file completed twice — is an expected condition on these
    paths, not a server fault, so it must resolve to the normal deny/idempotent
    answer instead of a 500.
    """
    if isinstance(exc, Aborted):
        return True
    return isinstance(exc, ValueError) and isinstance(exc.__cause__, Aborted)


def db() -> firestore.Client:
    """Process-wide Firestore singleton.

    Cloud Run concurrency (see deploy flags) shares this client across requests
    in one instance. The google-cloud-firestore sync client is thread-safe for
    ordinary reads/writes; do not create per-request clients.
    """
    global _DB
    if _DB is None:
        _DB = firestore.Client(project=settings.GCP_PROJECT or None)
    return _DB


def ping() -> None:
    """Cheap Firestore reachability probe for readiness."""
    from .observability import DependencyError

    try:
        # A missing document is still a successful round-trip.
        # Doc ids matching __.*__ are reserved by Firestore and raise locally
        # (misreported as unreachable); use a plain probe id.
        db().collection("users").document("readyz_ping").get()
    except Exception as e:  # noqa: BLE001
        log.exception("firestore ping failed: %s", e)
        raise DependencyError(errors.FIRESTORE_UNREACHABLE, "firestore") from e


def _now():
    return datetime.now(timezone.utc)


# ---------------- users ----------------
def _is_admin_email(claims: dict) -> bool:
    # Never grant admin off an unverified email — an email/password user could
    # otherwise claim an admin address without proving they own it.
    return bool(claims.get("email_verified")) and \
        claims.get("email", "").lower() in settings.ADMIN_EMAILS


def _auto_approved(claims: dict) -> bool:
    """Admins, blanket AUTO_APPROVE, or a *verified* email in AUTO_APPROVE_HD.

    Auto-approval always requires a verified email. Email-link and Google/
    Microsoft sign-ins are verified by construction; a new email/password user
    is not verified until they confirm, so they land PENDING until then.
    """
    if _is_admin_email(claims):
        return True
    if not claims.get("email_verified"):
        return False
    if settings.AUTO_APPROVE:
        return True
    hd = settings.AUTO_APPROVE_HD
    if not hd:
        return False
    return claims.get("email", "").lower().endswith("@" + hd)


class DeviceInUseError(Exception):
    """This device is already bound to a different email."""


def _emails_conflict(left, right) -> bool:
    """True unless both sides name the same address.

    Fail closed. This gates adopting a device-bound account, so a caller that
    presents no address at all is a conflict, not a match: the old fail-open
    form ("either side blank — no conflict") let any sign-in without an email
    claim an account by device id alone. Two address-less identities still
    match, which is the only case that form got right.
    """
    a = (left or "").strip().lower()
    b = (right or "").strip().lower()
    return a != b


def _load_user(uid: str):
    snap = db().collection("users").document(uid).get()
    if not snap.exists:
        return None
    return {**snap.to_dict(), "uid": uid}


def _link_auth_uid(canonical_uid: str, firebase_sub: str) -> None:
    db().collection("auth_links").document(firebase_sub).set({"uid": canonical_uid})
    if firebase_sub == canonical_uid:
        return
    ref = db().collection("users").document(canonical_uid)
    snap = ref.get()
    if not snap.exists:
        return
    linked = list((snap.to_dict() or {}).get("linkedAuthUids") or [])
    if firebase_sub not in linked:
        linked.append(firebase_sub)
        ref.update({"linkedAuthUids": linked})


def _user_for_device(device_id: str | None):
    if not device_id:
        return None
    dev = get_device(device_id)
    if dev and dev.get("status") == statuses.DEVICE_ACTIVE:
        found = _load_user(dev["uid"])
        if found:
            return found
    query = (
        db().collection("users")
        .where("claimedDeviceId", "==", device_id)
        .limit(1)
    )
    for snap in query.stream():
        return {**snap.to_dict(), "uid": snap.id}
    return None


def _touch_existing(cur: dict, claims: dict, device_id: str | None) -> dict:
    uid = cur["uid"]
    ref = db().collection("users").document(uid)
    verified = bool(claims.get("email_verified"))
    provider = (claims.get("firebase") or {}).get("sign_in_provider")
    # Only the fields that actually changed — a write with nothing new to say
    # is exactly what the throttle below is trying to avoid.
    changed: dict = {}
    if provider and cur.get("signInProvider") != provider:
        changed["signInProvider"] = provider
    if cur.get("emailVerified") != verified:
        changed["emailVerified"] = verified
    if cur.get("schemaVersion") != SCHEMA_VERSION:
        changed["schemaVersion"] = SCHEMA_VERSION
    # Keep admin role in sync with ADMIN_EMAILS for pre-existing users.
    if _is_admin_email(claims) and cur.get("role") != "admin":
        changed["role"] = "admin"
    # A previously-PENDING user who has since verified a domain email (or been
    # made admin) is auto-approved on this sign-in.
    if cur.get("access_status") == statuses.ACCESS_PENDING and _auto_approved(claims):
        changed["access_status"] = "APPROVED"
    if device_id and not cur.get("claimedDeviceId"):
        changed["claimedDeviceId"] = device_id

    last_seen = cur.get("lastSeenAt")
    stale = last_seen is None or (datetime.now(timezone.utc) - last_seen) >= _LAST_SEEN_THROTTLE
    # This used to write lastSeenAt on every authenticated request — but a
    # single restore is now dozens of requests (one challenge+content pair
    # per adaptive download window, see DriveTransfer.nextWindowBytes), so
    # that was dozens of writes to say the same thing. "Last seen" only
    # needs hour granularity; skip the write when nothing else changed and
    # the timestamp is still fresh.
    if not changed and not stale:
        return {**cur, "uid": uid}

    patch = {**changed, "lastSeenAt": firestore.SERVER_TIMESTAMP}
    ref.update(patch)
    return {**cur, **patch, "uid": uid}


def get_or_create_user(claims: dict, device_id: str | None = None) -> dict:
    uid = claims["sub"]
    ref = db().collection("users").document(uid)
    snap = ref.get()
    if snap.exists:
        return _touch_existing({**snap.to_dict(), "uid": uid}, claims, device_id)

    link = db().collection("auth_links").document(uid).get()
    if link.exists:
        canonical = (link.to_dict() or {}).get("uid")
        existing = _load_user(canonical) if canonical else None
        if existing:
            return _touch_existing(existing, claims, device_id)

    bound = _user_for_device(device_id)
    if bound:
        # Adopting a bound account hands over its sessions and entitlement, so
        # the address has to be proven rather than asserted: any provider can
        # mint a token carrying an address it never checked.
        unproven = bool(bound.get("email")) and not claims.get("email_verified")
        if unproven or _emails_conflict(bound.get("email"), claims.get("email")):
            raise DeviceInUseError()
        _link_auth_uid(bound["uid"], uid)
        return _touch_existing(bound, claims, device_id)

    verified = bool(claims.get("email_verified"))
    provider = (claims.get("firebase") or {}).get("sign_in_provider")
    data = {
        "email": claims.get("email"),
        "emailVerified": verified,
        "signInProvider": provider,
        "displayName": claims.get("name"),
        "role": "admin" if _is_admin_email(claims) else "user",
        "access_status": (statuses.ACCESS_APPROVED if _auto_approved(claims)
                          else statuses.ACCESS_PENDING),
        "activeDeviceId": None,
        "claimedDeviceId": device_id,
        "linkedAuthUids": [],
        "createdAt": firestore.SERVER_TIMESTAMP,
        "lastSeenAt": firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    try:
        # create(), not set(): two first-ever requests from one account race
        # here (the app fires /v1/me and /v1/config back to back on launch),
        # and an unconditional set let the loser overwrite the winner — resetting
        # an already-approved profile to PENDING and mailing support twice.
        ref.create(data)
    except AlreadyExists:
        existing = _load_user(uid)
        if existing:
            return _touch_existing(existing, claims, device_id)
        raise
    _link_auth_uid(uid, uid)
    # Only ever reached once per account — every later sign-in takes the
    # snap.exists / auth_links branch above — so support gets exactly one mail per user.
    created = {**data, "uid": uid}
    if data["access_status"] == statuses.ACCESS_PENDING:
        notify.access_request(uid, data["email"], data["displayName"], provider)
    return created


def list_users(
    status: str = "",
    limit: int = 50,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Cursor-paginated user list. Returns (page, next_page_token_or_None)."""
    col = db().collection("users")
    query = col.where("access_status", "==", status) if status else col
    query = query.order_by("__name__").limit(limit + 1)
    if page_token:
        cursor = col.document(page_token).get()
        if cursor.exists:
            query = query.start_after(cursor)
    out = []
    docs = list(query.stream())
    next_token = None
    if len(docs) > limit:
        docs = docs[:limit]
        next_token = docs[-1].id
    for d in docs:
        u = d.to_dict()
        out.append({
            "uid": d.id,
            "email": u.get("email"),
            "displayName": u.get("displayName"),
            "role": u.get("role"),
            "access_status": u.get("access_status"),
            "activeDeviceId": u.get("activeDeviceId"),
        })
    return out, next_token


def set_user_status(uid: str, status: str) -> bool:
    """Set access_status, revoking the account's devices when suspending.

    Revoking access used to leave devices/{id}.status == "ACTIVE". Nothing broke
    — current_user rejects a non-APPROVED account before any device check — but
    the binding survived the revocation, so re-approving silently restored the
    old device's authority and `device_in_use` still held the id against another
    account. Revocation should mean the same thing at both layers.
    """
    ref = db().collection("users").document(uid)
    if not ref.get().exists:
        return False
    batch = db().batch()
    batch.update(ref, {"access_status": status, "updatedAt": firestore.SERVER_TIMESTAMP})
    if status != "APPROVED":
        batch.update(ref, {"activeDeviceId": firestore.DELETE_FIELD})
        for dev in db().collection("devices").where("uid", "==", uid).stream():
            batch.update(dev.reference, {
                "status": statuses.DEVICE_REVOKED,
                "revokedAt": firestore.SERVER_TIMESTAMP,
            })
    batch.commit()
    return True


def _positive_int_override(user: dict, key: str):
    """Optional positive int on the user doc; invalid/missing → None (inherit default)."""
    raw = user.get(key)
    if raw is None:
        return None
    try:
        n = int(raw)
    except (TypeError, ValueError):
        return None
    return n if n > 0 else None


def _bool_override(user: dict, key: str):
    """Optional bool on the user doc; anything else (missing/wrong type) → None
    (inherit the fleet default). Mirrors [_positive_int_override]'s "invalid
    input inherits rather than errors" contract."""
    raw = user.get(key)
    return raw if isinstance(raw, bool) else None


def resolve_user_config(user: dict) -> dict:
    """Product limits for this account: per-user override, else fleet env default.

    Missing fields are not written at user creation so changing the env default
    updates everyone who has not been individually overridden.
    """
    dat_codec_override = _bool_override(user, "datCodecEncodingEnabled")
    return {
        "maxSessions": (
            _positive_int_override(user, "maxSessions") or settings.MAX_SESSIONS_PER_USER
        ),
        "maxFilesPerSession": (
            _positive_int_override(user, "maxFilesPerSession")
            or settings.MAX_FILES_PER_SESSION
        ),
        "maxFrames": (
            _positive_int_override(user, "maxFrames") or settings.MAX_FRAMES_PER_ANALYSIS
        ),
        "datCodecEncodingEnabled": (
            dat_codec_override
            if dat_codec_override is not None
            else settings.DAT_CODEC_ENCODING_ENABLED
        ),
    }


#: Per-user config override fields and how to cast an incoming patch value for
#: each — int(True) == 1 would silently turn a bool override into an int, so a
#: single int() cast for every field (as before datCodecEncodingEnabled) is
#: wrong here; each field casts to its own resolve_user_config type.
_CONFIG_CASTERS = {
    "maxSessions": int,
    "maxFilesPerSession": int,
    "maxFrames": int,
    "datCodecEncodingEnabled": bool,
}


def set_user_config(uid: str, patch: dict) -> dict | None:
    """Persist per-user limit overrides. Returns resolved config, or None if missing."""
    ref = db().collection("users").document(uid)
    snap = ref.get()
    if not snap.exists:
        return None
    allowed = tuple(_CONFIG_CASTERS)
    update = {
        k: _CONFIG_CASTERS[k](patch[k]) for k in allowed if k in patch and patch[k] is not None
    }
    # Explicit null clears an override so the user re-inherits the fleet default.
    deletes = {k: firestore.DELETE_FIELD for k in allowed if k in patch and patch[k] is None}
    if update or deletes:
        ref.update({**update, **deletes, "updatedAt": firestore.SERVER_TIMESTAMP})
    user = {**(snap.to_dict() or {}), **update, "uid": uid}
    for k in deletes:
        user.pop(k, None)
    return resolve_user_config(user)


# ---------------- devices ----------------
def get_device(device_id: str):
    snap = db().collection("devices").document(device_id).get()
    return {**snap.to_dict(), "deviceId": device_id} if snap.exists else None


def user_has_active_device(uid: str) -> bool:
    u = db().collection("users").document(uid).get()
    return bool(u.exists and u.to_dict().get("activeDeviceId"))


def register_device(uid: str, body: DeviceReg) -> dict:
    dev = {
        "uid": uid,
        "publicKeyPem": body.publicKeyPem,
        "status": statuses.DEVICE_ACTIVE,
        "model": body.model,
        "osVersion": body.osVersion,
        "appVersion": body.appVersion,
        "registeredAt": firestore.SERVER_TIMESTAMP,
        "lastAssertionAt": firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    user_ref = db().collection("users").document(uid)
    # Only one device may be active per account (activeDeviceId is the single
    # binding). Superseded devices used to keep status ACTIVE forever, so an
    # account accumulated stale ACTIVE docs that still held their device ids
    # against other accounts via the device_in_use check.
    previous = user_ref.get().to_dict().get("activeDeviceId") if user_ref.get().exists else None
    batch = db().batch()
    if previous and previous != body.deviceId:
        batch.update(db().collection("devices").document(previous), {
            "status": "SUPERSEDED",
            "revokedAt": firestore.SERVER_TIMESTAMP,
        })
    batch.set(db().collection("devices").document(body.deviceId), dev)
    batch.update(user_ref, {"activeDeviceId": body.deviceId})
    batch.commit()
    return {**dev, "deviceId": body.deviceId}


# ---------------- challenge / nonce ----------------
def issue_nonce(uid: str, device_id: str) -> str:
    nonce = secrets.token_urlsafe(32)
    db().collection("challenges").document(nonce).set(
        {
            "uid": uid,
            "deviceId": device_id,
            "expireAt": _now() + timedelta(seconds=120),
            "schemaVersion": SCHEMA_VERSION,
        }
    )
    return nonce


def consume_nonce(nonce: str, uid: str, device_id: str) -> bool:
    """Atomically claim a challenge. Delete only when uid/device/expiry match.

    A plain get→delete race let two concurrent callers both read a live nonce;
    wrapping in a transaction means only one commit wins. Invalid callers must
    not delete — otherwise a wrong-uid probe would burn a valid challenge.
    """
    ref = db().collection("challenges").document(nonce)
    # More than the default five attempts: this document is the hottest in the
    # service and losing the race means denying a legitimate caller.
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _consume(tx):
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return False
        d = snap.to_dict()
        if d.get("uid") != uid or d.get("deviceId") != device_id:
            return False
        exp = d.get("expireAt")
        if not (exp and exp > _now()):
            return False
        tx.delete(ref)
        return True

    try:
        return _consume(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Concurrent consumption of one nonce is by definition a replay, and we
        # could not commit — so deny. Fail closed: claiming the nonce here would
        # be the one outcome that breaks single-use. A legitimate client never
        # races itself on a nonce; it just fetches a fresh challenge.
        return False


# ---------------- sessions / files ----------------
def delete_session(sid: str) -> int:
    """Hard-delete an analysis' metadata: every file doc, then the session doc.

    GDPR erasure — records are removed, not flagged. Returns the file count.
    Firestore batches cap at 500 writes, so this chunks.
    """
    file_count = _delete_query_until_empty(
        db().collection("files").where("sessionId", "==", sid)
    )
    db().collection("sessions").document(sid).delete()
    return file_count


def remember_user_folder(uid: str, folder_id: str) -> None:
    """Persist the user's Drive subtree id so erasure never has to guess by name."""
    db().collection("users").document(uid).update({"driveFolderId": folder_id})


def get_user(uid: str):
    snap = db().collection("users").document(uid).get()
    return {**snap.to_dict(), "uid": uid} if snap.exists else None


def list_user_devices(uid: str) -> list:
    out = []
    for d in db().collection("devices").where("uid", "==", uid).stream():
        v = d.to_dict()
        out.append({
            "deviceId": d.id,
            "status": v.get("status"),
            "model": v.get("model"),
            "osVersion": v.get("osVersion"),
            "appVersion": v.get("appVersion"),
            "registeredAt": str(v.get("registeredAt")),
        })
    return out


def delete_all_user_data(uid: str) -> dict:
    """Erase every Firestore record belonging to a user (GDPR account deletion).

    Sessions + their file docs, the device registrations, and the user profile
    itself. Audit records are intentionally kept: they hold no analysis content,
    only the fact that actions (including this erasure) occurred.
    """
    # File docs carry the uid, so the whole account is one query rather than one
    # per session. Deleting session by session meant a query and a batch commit
    # each — sequential round-trips that made erasing a busy account crawl.
    files = _delete_query_until_empty(
        db().collection("files").where("uid", "==", uid)
    )
    sessions = _delete_query_until_empty(
        db().collection("sessions").where("uid", "==", uid)
    )
    devices = _delete_query_until_empty(
        db().collection("devices").where("uid", "==", uid)
    )
    db().collection("users").document(uid).delete()
    return {"sessions": sessions, "files": files, "devices": devices}


def _delete_query_until_empty(query) -> int:
    """Delete a query result in bounded batches, repeating until empty."""
    deleted = 0
    while True:
        docs = list(query.limit(_BATCH_LIMIT).stream())
        if not docs:
            return deleted
        _delete_refs([doc.reference for doc in docs])
        deleted += len(docs)


def _delete_refs(refs: list) -> None:
    """Deletes every reference, chunked to Firestore's per-batch write cap."""
    for start in range(0, len(refs), _BATCH_LIMIT):
        batch = db().batch()
        for ref in refs[start:start + _BATCH_LIMIT]:
            batch.delete(ref)
        batch.commit()


def get_session(sid: str):
    snap = db().collection("sessions").document(sid).get()
    return {**snap.to_dict(), "sessionId": sid} if snap.exists else None


def get_file(file_id: str):
    snap = db().collection("files").document(file_id).get()
    return {**snap.to_dict(), "fileId": file_id} if snap.exists else None


def _page_session_files(sid: str, limit: int, page_token: str | None):
    """One cursor-paged slice of a session's files. Returns (docs, next_token).

    Both public listings below used to take a flat `.limit(_LIST_SOFT_LIMIT)`
    with no cursor and no signal, so a session larger than the cap was silently
    truncated — a restore manifest would simply be missing files, and the resume
    list would stop offering them.
    """
    col = db().collection("files")
    q = col.where("sessionId", "==", sid).order_by("__name__").limit(limit + 1)
    if page_token:
        cursor = col.document(page_token).get()
        if cursor.exists:
            q = q.start_after(cursor)
    docs = list(q.stream())
    next_token = None
    if len(docs) > limit:
        docs = docs[:limit]
        next_token = docs[-1].id
    return docs, next_token


def list_pending_uploads(
    sid: str,
    limit: int = _LIST_SOFT_LIMIT,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Files in a session that still need bytes, with their resumable URIs.

    Lets an interrupted upload resume the SAME session instead of creating a
    duplicate (which would also burn the per-user analysis quota). Files already
    COMPLETED have their uploadUrl cleared, so they're naturally excluded.

    Returns (page, next_page_token_or_None). A file that has no uploadUrl yet
    because provisioning has not reached it is also excluded — the session's
    PROVISIONING status is what tells the client to wait.
    """
    docs, next_token = _page_session_files(sid, limit, page_token)
    out = []
    for d in docs:
        f = d.to_dict()
        url = f.get("uploadUrl")
        if f.get("status") == statuses.FILE_COMPLETED or not url:
            continue
        out.append({
            "fileId": d.id,
            "uploadUrl": url,
            "chunkSize": 32 * 1024 * 1024,  # keep in sync with create_session
            "name": f.get("name"),
            "role": f.get("role"),
            "sizeBytes": f.get("sizeBytes", 0),
        })
    return out, next_token


def list_session_files(
    sid: str,
    limit: int = _LIST_SOFT_LIMIT,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Every file in an analysis — the manifest the app restores from.

    Returns (page, next_page_token_or_None).
    """
    docs, next_token = _page_session_files(sid, limit, page_token)
    out = []
    for d in docs:
        f = d.to_dict()
        out.append({
            "fileId": d.id,
            "name": f.get("name"),
            "role": f.get("role"),
            "sizeBytes": f.get("sizeBytes", 0),
            "sha256": f.get("sha256"),
            "status": f.get("status"),
        })
    return out, next_token


# ---------------- async provisioning ----------------
def set_session_status(sid: str, status: str, error_code: str | None = None) -> None:
    patch = {"status": status, "updatedAt": firestore.SERVER_TIMESTAMP}
    if error_code:
        patch["provisionError"] = error_code
    elif status != statuses.SESSION_PROVISION_FAILED:
        patch["provisionError"] = firestore.DELETE_FIELD
    try:
        db().collection("sessions").document(sid).update(patch)
    except NotFound:
        return


def iter_unprovisioned_files(sid: str):
    """Files in a session that still have no resumable URI.

    Drives the provisioning worker and makes it resumable: a task that dies
    halfway re-runs and picks up only what is left, so a retry never mints a
    second upload URI for a file that already has one.
    """
    page_token = None
    while True:
        docs, page_token = _page_session_files(sid, _BATCH_LIMIT, page_token)
        if not docs:
            return
        for d in docs:
            f = d.to_dict()
            if f.get("status") == statuses.FILE_COMPLETED or f.get("uploadUrl"):
                continue
            yield {
                "fileId": d.id,
                "name": f.get("name"),
                "role": f.get("role"),
                "sizeBytes": f.get("sizeBytes", 0),
            }
        if not page_token:
            return


def set_file_upload_url(file_id: str, url: str) -> None:
    db().collection("files").document(file_id).update({
        "uploadUrl": url,
        "updatedAt": firestore.SERVER_TIMESTAMP,
    })


def count_unprovisioned_files(sid: str) -> int:
    return sum(1 for _ in iter_unprovisioned_files(sid))


def list_user_sessions(
    uid: str,
    limit: int = 50,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Cursor-paginated cloud analyses. Returns (page, next_page_token_or_None)."""
    col = db().collection("sessions")
    q = col.where("uid", "==", uid).order_by("__name__").limit(limit + 1)
    if page_token:
        cursor = col.document(page_token).get()
        if cursor.exists:
            q = q.start_after(cursor)
    out = []
    docs = list(q.stream())
    next_token = None
    if len(docs) > limit:
        docs = docs[:limit]
        next_token = docs[-1].id
    for d in docs:
        s = d.to_dict()
        out.append({
            "sessionId": d.id,
            "localSessionId": s.get("localSessionId") or "",
            "specimen": s.get("specimen"),
            "status": s.get("status"),
            "fileCount": s.get("fileCount", 0),
            "completedCount": s.get("completedCount", 0),
            "totalBytes": s.get("totalBytes", 0),
            "driveFolderId": s.get("driveFolderId"),
        })
    return out, next_token


def iter_all_user_sessions(uid: str, *, page_size: int = 100):
    """Yield every session for export without a silent cap."""
    token = None
    while True:
        page, token = list_user_sessions(uid, limit=page_size, page_token=token)
        for session in page:
            yield session
        if not token:
            return


def list_session_files_all(sid: str, *, page_size: int = 200) -> list:
    """Every file in a session, paging past the soft list cap."""
    out = []
    query = db().collection("files").where("sessionId", "==", sid).order_by("__name__")
    cursor = None
    while True:
        page_q = query.limit(page_size)
        if cursor is not None:
            page_q = page_q.start_after(cursor)
        docs = list(page_q.stream())
        if not docs:
            break
        for d in docs:
            f = d.to_dict()
            out.append({
                "fileId": d.id,
                "name": f.get("name"),
                "role": f.get("role"),
                "sizeBytes": f.get("sizeBytes", 0),
                "sha256": f.get("sha256"),
                "status": f.get("status"),
            })
        if len(docs) < page_size:
            break
        cursor = docs[-1]
    return out


def iter_user_sessions(uid: str):
    """Stream every session for destructive Drive cleanup without a silent cap."""
    for d in db().collection("sessions").where("uid", "==", uid).stream():
        s = d.to_dict()
        yield {
            "sessionId": d.id,
            "driveFolderId": s.get("driveFolderId"),
        }


def count_user_sessions(uid: str) -> int:
    """How many analyses this user already has in the cloud (quota check)."""
    agg = db().collection("sessions").where("uid", "==", uid).count().get()
    return int(agg[0][0].value)


#: Session states that still expect more bytes. PROVISIONING is included so a
#: retried POST /v1/sessions joins the session whose upload targets are still
#: being opened, instead of minting a duplicate alongside it.
IN_FLIGHT_STATUSES = statuses.IN_FLIGHT_SESSION_STATUSES


def find_incomplete_session(uid: str, local_session_id: str):
    """An in-flight session for (uid, localSessionId), if any.

    Lets a retried POST /v1/sessions return the same session instead of minting
    a duplicate (and burning quota). Empty localSessionId is never matched —
    clients that omit it still get a fresh session each call.

    One exact query per status rather than an `in` filter: both are indexed the
    same way, and this keeps the match precise instead of over-fetching and
    filtering in Python.
    """
    if not local_session_id:
        return None
    for status in IN_FLIGHT_STATUSES:
        q = (
            db().collection("sessions")
            .where("uid", "==", uid)
            .where("localSessionId", "==", local_session_id)
            .where("status", "==", status)
            .limit(1)
        )
        for d in q.stream():
            return {**d.to_dict(), "sessionId": d.id}
    return None


def create_session(sid: str, user: dict, device: dict, body: SessionCreate):
    """Reserve the session doc BEFORE any Drive folder or file doc is created.

    Writing the parent first means a failure while staging files can never leave
    file docs (or a Drive subtree) with no session pointing at them: the reserved
    doc counts toward the quota and is reclaimable. `driveFolderId` is filled in
    by [set_session_folder] once the folder exists.
    """
    db().collection("sessions").document(sid).set(
        {
            "uid": user["uid"],
            "deviceId": device.get("deviceId"),
            "specimen": body.specimen,
            "localSessionId": body.localSessionId,
            # Reserved, but no upload targets yet. set_session_status moves it to
            # PROVISIONING → UPLOADING (or PROVISION_FAILED).
            "status": statuses.SESSION_PROVISIONING,
            "driveFolderId": None,
            "totalBytes": sum(f.bytes for f in body.files),
            "fileCount": len(body.files),
            "completedCount": 0,
            "metrics": body.metrics,
            "createdAt": firestore.SERVER_TIMESTAMP,
            "updatedAt": firestore.SERVER_TIMESTAMP,
            "schemaVersion": SCHEMA_VERSION,
        }
    )


def set_session_folder(sid: str, folder_id: str):
    """Record the session's Drive folder id once it has been created."""
    db().collection("sessions").document(sid).update(
        {"driveFolderId": folder_id, "updatedAt": firestore.SERVER_TIMESTAMP}
    )


def _file_doc(sid: str, uid: str, f: FileSpec, upload_url: str | None) -> dict:
    return {
        "sessionId": sid,
        "uid": uid,
        "role": f.role,
        "name": f.name,
        "sizeBytes": f.bytes,
        "sha256": f.sha256,
        "status": statuses.FILE_PENDING,
        "uploadUrl": upload_url,
        "driveFileId": None,
        "driveMd5": None,
        "createdAt": firestore.SERVER_TIMESTAMP,
        "updatedAt": firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }


def create_file(sid: str, uid: str, file_id: str, f: FileSpec, upload_url: str | None):
    """Write the file doc. `upload_url` is None until provisioning opens the
    Drive resumable session for it (see iter_unprovisioned_files)."""
    db().collection("files").document(file_id).set(_file_doc(sid, uid, f, upload_url))


def create_files_batch(sid: str, uid: str, files: list[tuple[str, FileSpec]]) -> None:
    """[create_file] for every (file_id, spec) pair, batched — a plain Python
    loop of individual `.set()` calls was one Firestore round trip per file (a
    3-object split-bundle session is 3 already; a legacy per-file-per-frame
    session could be far more). `upload_url` is always None here: provisioning
    fills it in once Drive resumable sessions exist, same as the single-file
    path. Chunked to Firestore's per-batch write cap, same pattern as
    [_delete_refs]."""
    for start in range(0, len(files), _BATCH_LIMIT):
        batch = db().batch()
        for file_id, f in files[start:start + _BATCH_LIMIT]:
            batch.set(db().collection("files").document(file_id), _file_doc(sid, uid, f, None))
        batch.commit()


def complete_file(file_id: str, uid: str, body: FileComplete) -> str:
    """Record a file's Drive pointer. Returns "ok", "already" (a retry of a
    completion that landed — idempotent, must NOT bump the session counter
    again) or "" (rejected).

    Read-status→update runs in a transaction so two concurrent completions of
    the same PENDING file yield exactly one "ok" (the other sees COMPLETED and
    returns "already"). Without this, both could bump the session counter.
    """
    ref = db().collection("files").document(file_id)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _complete(tx):
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return ""
        d = snap.to_dict()
        if d["uid"] != uid or d["sizeBytes"] != body.bytes:
            return ""
        if d.get("status") == statuses.FILE_COMPLETED:
            return "already"
        tx.update(
            ref,
            {
                "status": statuses.FILE_COMPLETED,
                "driveFileId": body.driveFileId,
                "driveMd5": body.md5,
                "uploadUrl": firestore.DELETE_FIELD,  # capability no longer needed
                "updatedAt": firestore.SERVER_TIMESTAMP,
            },
        )
        return "ok"

    try:
        return _complete(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # A concurrent completion of this same file won the race. That is the
        # idempotent case the transaction exists to produce — re-read and answer
        # it precisely rather than 500ing on the loser. "already" is important:
        # it stops the caller bumping the session counter a second time.
        snap = ref.get()
        current = snap.to_dict() if snap.exists else None
        if (current and current.get("uid") == uid
                and current.get("status") == statuses.FILE_COMPLETED):
            return "already"
        return ""


def bump_session_progress(sid: str):
    """One file just completed: advance the session's counter by one.

    O(1) — one atomic increment on the session doc. The version before that
    re-streamed EVERY file doc in the session on every completion, which made an
    N-file upload cost ~N² Firestore reads (a 150-frame analysis burned the whole
    daily free-tier read quota several times over by itself).

    Uses firestore.Increment rather than a read-modify-write transaction. A
    transaction serialises every concurrent completion onto this one document,
    and parallel uploads finish together by design — six concurrent bumps
    exhausted the client's five retries and raised `Aborted: Transaction lock
    timeout`, i.e. a 500 on the last files of an otherwise-successful upload.
    (The fake store in tests applies transactions immediately with no isolation,
    so this was invisible until the Firestore emulator tier was wired into CI.)
    An increment needs no read, so concurrent completions no longer contend.

    Trusting the counter is safe because complete_file is idempotent: a retried
    completion returns "already" and never reaches this function.
    """
    ref = db().collection("sessions").document(sid)
    try:
        ref.update({
            "completedCount": firestore.Increment(1),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
    except NotFound:
        return  # session erased mid-upload; nothing to advance

    # Re-read to decide the COMPLETED flip. Firestore reads are strongly
    # consistent, so the caller whose increment reached fileCount is guaranteed
    # to observe it here. The flip is monotone and idempotent: a caller that
    # reads a lower count simply does nothing, and the one that completes the
    # set finishes the job.
    after = ref.get().to_dict() or {}
    if (
        int(after.get("completedCount", 0)) >= int(after.get("fileCount", 0))
        and after.get("status") != statuses.SESSION_COMPLETED
    ):
        ref.update({
            "status": statuses.SESSION_COMPLETED,
            "completedAt": firestore.SERVER_TIMESTAMP,
        })
