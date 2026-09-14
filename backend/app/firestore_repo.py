"""Server-side Firestore access. Clients never touch Firestore directly."""
import logging
import secrets
from datetime import datetime, timedelta, timezone

from google.api_core.exceptions import Aborted, AlreadyExists, NotFound
from google.cloud import firestore

from . import errors, notify, statuses
from .config import settings
from .licenses import (
    DURATION_PERPETUAL,
    DURATION_TIMED,
    SEATING_ASSIGNED,
    SEATING_FLOATING,
    KIND_INDIVIDUAL,
    KIND_INSTITUTION,
    MODE_DEMO,
    MODE_LICENSED,
    MODES,
    as_utc,
    generate_key,
    grace_ends_at,
    invite_id,
    key_hash,
    key_prefix,
    legacy_plan,
    normalize_duration,
    normalize_email,
    normalize_kind,
    normalize_mode,
    normalize_seating,
)
from .models import DeviceReg, FileComplete, FileSpec, SessionCreate

log = logging.getLogger("indic.firestore")
_DB = None

# Every server-owned document carries this integer. Migrations must be
# idempotent and advance documents only after an export/restore checkpoint.
SCHEMA_VERSION = 2

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

#: How many expired leases one checkout reclaims. A pool cannot have more live
#: leases than maxSeats, so this only ever has to clear the backlog of one
#: quiet period; anything left is picked up by the next claim.
_LEASE_SWEEP_LIMIT = 50


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


#: What the claim transactions answer when they only lost the race. Private
#: to this module: `errors.py` names wire codes, and this one never reaches the
#: wire. It exists so contention stops being indistinguishable in the logs from
#: a licence that genuinely has no room left. Callers that hand a code to a
#: route put it through `_public_claim_error` first, so no route's error
#: mapping changes.
_CONTENDED = "_contended"


def _public_claim_error(err: str, fallback: str) -> str:
    """The wire code for a claim failure, contention included.

    Contention fails closed as `fallback` — granting a seat or a licence we
    could not commit is the one outcome that breaks the cap, and a caller who
    lost the race succeeds on their next request.
    """
    return fallback if err == _CONTENDED else err


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
        return ensure_entitlement({**cur, "uid": uid}, device_id)

    patch = {**changed, "lastSeenAt": firestore.SERVER_TIMESTAMP}
    ref.update(patch)
    return ensure_entitlement({**cur, **patch, "uid": uid}, device_id)


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
    return ensure_entitlement(created, device_id)


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


def _mode_patch(mode: str) -> dict:
    """The user-document fields that record an entitlement mode.

    Writes `mode` and the pre-rename `plan` mirror together. Cloud Run rolls
    traffic, so an instance running the previous revision can read a document
    this one just wrote; it looks at `plan`. Drop the mirror only after the
    fleet and every deployed revision read `mode` (see licenses.legacy_plan).
    """
    return {"mode": mode, "plan": legacy_plan(mode)}


def _stored_mode(user: dict) -> str:
    """This account's recorded mode, reading `mode` and falling back to the
    pre-rename `plan` for a document migration 002 has not reached yet."""
    raw = user.get("mode")
    if not (isinstance(raw, str) and raw.strip().lower() in MODES):
        raw = user.get("plan")
    return normalize_mode(raw)


def _grace_days(user: dict) -> int:
    """This account's grace window in days. Absent reads as ZERO.

    Deliberately not `settings.LICENSE_GRACE_DAYS_DEFAULT`: that is the value
    stamped onto a license at mint. A user document that predates grace has no
    `licenseGraceDays`, and defaulting those to a non-zero window would
    retroactively reinstate every account that expired inside it the moment
    this deploys. New mints carry the field explicitly.
    """
    raw = user.get("licenseGraceDays")
    try:
        return max(0, int(raw))
    except (TypeError, ValueError):
        return 0


def _expiry_state(user: dict) -> tuple[bool, bool, object]:
    """(entitlement_over, in_grace, grace_ends_at) for this account.

    `entitlement_over` is the only one that gates: it is true once even the
    grace window has passed. `in_grace` means past `expiresAt` but still
    entitled — the app shows a renewal warning, nothing is withdrawn.

    Fails OPEN on a malformed timestamp, matching the behaviour this replaced:
    a garbage `licenseExpiresAt` keeps the account licensed rather than
    cutting off a paying user over a bad write.
    """
    expiry = as_utc(user.get("licenseExpiresAt"))
    if expiry is None:
        return False, False, None
    ends = grace_ends_at(expiry, _grace_days(user))
    try:
        now = _now()
        return ends <= now, expiry <= now < ends, ends
    except TypeError:
        return False, False, ends


def _lease_live(user: dict) -> bool:
    """Whether this account holds an unexpired floating-seat lease.

    Reads the mirror on the user document, not the seat — that is what keeps
    `effective_mode` a pure function and the hot read path free of Firestore.
    The mirror is written by checkout and cleared by release, so the worst
    staleness is one lease length, and an expired lease reads as expired here
    whether or not anything has released it yet.

    Fails CLOSED, unlike `_expiry_state`. A missing or malformed lease is "no
    seat", because on a floating license the absence of a lease is the normal
    state — most of the roster holds none at any moment — so treating an
    unreadable one as live would hand out the pool for free.
    """
    ends = as_utc(user.get("leaseExpiresAt"))
    if ends is None:
        return False
    try:
        return ends > _now()
    except TypeError:
        return False


def effective_mode(user: dict) -> str:
    """Licensed until the key expires past grace; anything else → demo.

    Grace is inside the licensed branch on purpose: an account in grace keeps
    every entitlement it had. Only the warning changes.

    A floating seat adds one more condition: the member is entitled only while
    holding a live lease. Without one they are demo — not blocked, not
    revoked. That is the whole point of a pool; being between leases is the
    ordinary state for most of the roster.
    """
    if _stored_mode(user) != MODE_LICENSED:
        return MODE_DEMO
    entitlement_over, _in_grace, _ends = _expiry_state(user)
    if entitlement_over:
        return MODE_DEMO
    if normalize_seating(user.get("licenseSeating")) == SEATING_FLOATING:
        return MODE_LICENSED if _lease_live(user) else MODE_DEMO
    return MODE_LICENSED


def resolve_user_config(user: dict) -> dict:
    """Product limits and license entitlements for this account.

    Missing fields are not written at user creation so changing the env default
    updates everyone who has not been individually overridden. A missing mode
    is Demo. Licensed cloud/share flags stay off when the key has expired.

    The response is dual-keyed: `mode` is current, `plan` is the pre-rename
    mirror kept for installed clients. Both always describe the same state.
    """
    dat_codec_override = _bool_override(user, "datCodecEncodingEnabled")
    summary = license_summary(user)
    mode = summary["mode"]
    is_licensed = mode == MODE_LICENSED
    if is_licensed:
        max_sessions = (
            _positive_int_override(user, "maxSessions")
            or _positive_int_override(user, "licenseMaxAnalyses")
            or settings.LICENSED_MAX_SESSIONS_PER_USER
        )
    else:
        max_sessions = settings.DEMO_MAX_ANALYSES
    return {
        "mode": mode,
        # Pre-rename mirror. An installed app decodes `plan` and fails closed
        # to Demo when it is missing, so removing this key demotes the whole
        # fleet. Remove only once adoption of a `mode`-reading build is high.
        "plan": legacy_plan(mode),
        "licenseKind": summary["licenseKind"],
        "licenseDuration": summary["duration"],
        # Both null for a perpetual license. `inGrace` means past expiry but
        # still fully entitled — the app warns, it does not gate on this.
        "licenseExpiresAt": summary["expiresAt"],
        "licenseGraceEndsAt": summary["graceEndsAt"],
        "inGrace": summary["inGrace"],
        # `floating` tells the app it must hold a lease, and when to renew it.
        # A demo mode with seating=floating means "no seat right now", which
        # the app presents as a checkout prompt rather than a dead end.
        "licenseSeating": summary["seating"],
        "leaseExpiresAt": summary["leaseExpiresAt"],
        "leaseHeartbeatMinutes": settings.LICENSE_LEASE_HEARTBEAT_MINUTES,
        "cloudBackupEnabled": is_licensed,
        "shareEnabled": is_licensed,
        "maxSessions": max_sessions,
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
        "licensePrefix": summary["prefix"],
    }


def license_summary(user: dict) -> dict:
    """What license this account holds and when it stops — pure, no reads.

    `current_user` already returns the whole user document, so both /v1/me and
    resolve_user_config compute this without touching Firestore. Everything
    here is mirrored onto the user at activation; see _license_mirror_patch.
    """
    expiry = as_utc(user.get("licenseExpiresAt"))
    _over, in_grace, ends = _expiry_state(user)
    return {
        "mode": effective_mode(user),
        "licenseKind": normalize_kind(user.get("licenseKind")) if user.get("licenseKind") else "",
        "duration": normalize_duration(
            user.get("licenseDuration"), has_expiry=expiry is not None,
        ),
        "prefix": user.get("licensePrefix") or "",
        "expiresAt": expiry,
        "graceEndsAt": ends,
        "inGrace": in_grace,
        "seating": normalize_seating(user.get("licenseSeating")),
        # Null on an assigned seat, which never needs one. On a floating seat
        # this is what the app renews before it lapses.
        "leaseExpiresAt": as_utc(user.get("leaseExpiresAt")),
    }


def cloud_backup_enabled(user: dict) -> bool:
    return bool(resolve_user_config(user)["cloudBackupEnabled"])


#: Per-user config override fields and how to cast an incoming patch value for
#: each — int(True) == 1 would silently turn a bool override into an int, so a
#: single int() cast for every field (as before datCodecEncodingEnabled) is
#: wrong here; each field casts to its own resolve_user_config type.
_CONFIG_CASTERS = {
    "maxSessions": int,
    "maxFilesPerSession": int,
    "maxFrames": int,
    "datCodecEncodingEnabled": bool,
    "mode": lambda v: normalize_mode(v),
}


def set_user_config(uid: str, patch: dict) -> dict | None:
    """Persist per-user limit overrides. Returns resolved config, or None if missing."""
    ref = db().collection("users").document(uid)
    snap = ref.get()
    if not snap.exists:
        return None
    # An operator (or an un-updated console) may still send the pre-rename
    # `plan`. Fold it onto `mode` before filtering, since `plan` is no longer
    # an accepted key and would otherwise be dropped silently.
    patch = dict(patch)
    if "plan" in patch and "mode" not in patch:
        raw = patch.pop("plan")
        patch["mode"] = normalize_mode(raw) if raw is not None else None
    patch.pop("plan", None)

    allowed = tuple(_CONFIG_CASTERS)
    update = {
        k: _CONFIG_CASTERS[k](patch[k]) for k in allowed if k in patch and patch[k] is not None
    }
    if "mode" in update:
        # normalize_mode never returns anything outside MODES, so an unknown
        # value arrives here as demo rather than being rejected. Write the
        # legacy mirror alongside it (see _mode_patch).
        update.update(_mode_patch(update["mode"]))
    # Explicit null clears an override so the user re-inherits the fleet default.
    deletes = {k: firestore.DELETE_FIELD for k in allowed if k in patch and patch[k] is None}
    if "mode" in deletes:
        deletes["plan"] = firestore.DELETE_FIELD
    if update or deletes:
        ref.update({**update, **deletes, "updatedAt": firestore.SERVER_TIMESTAMP})
    user = {**(snap.to_dict() or {}), **update, "uid": uid}
    for k in deletes:
        user.pop(k, None)
    return resolve_user_config(user)


def _emails_match(left, right) -> bool:
    a = (left or "").strip().lower()
    b = (right or "").strip().lower()
    return bool(a and b and a == b)


def _email_domain(email: str) -> str:
    email = (email or "").strip().lower()
    return email.rsplit("@", 1)[-1] if "@" in email else ""


def _license_mode(data: dict) -> str:
    """The mode a license grants, reading `mode` then the pre-rename `plan`."""
    raw = data.get("mode")
    if not (isinstance(raw, str) and raw.strip().lower() in MODES):
        raw = data.get("plan")
    return normalize_mode(raw)


def _license_public(license_id: str, data: dict) -> dict:
    return {
        "id": license_id,
        "keyPrefix": data.get("keyPrefix") or "",
        "kind": normalize_kind(data.get("kind")),
        "mode": _license_mode(data),
        # Pre-rename mirror; see resolve_user_config.
        "plan": legacy_plan(_license_mode(data)),
        "status": data.get("status") or "unused",
        "emailLock": data.get("emailLock") or "",
        "deviceIdLock": data.get("deviceIdLock") or "",
        "domainLock": data.get("domainLock") or "",
        "adminEmails": list(data.get("adminEmails") or []),
        "maxSeats": data.get("maxSeats"),
        "seating": normalize_seating(data.get("seating")),
        # seatsUsed counts the roster; leasesActive counts who is using it
        # right now. On an assigned license they are the same number, so only
        # the first is meaningful.
        "seatsUsed": data.get("seatsUsed", 0),
        "leasesActive": data.get("leasesActive", 0),
        "createdAt": data.get("createdAt"),
        "createdByUid": data.get("createdByUid") or "",
        "redeemedAt": data.get("redeemedAt"),
        "redeemedByUid": data.get("redeemedByUid") or "",
        "duration": normalize_duration(
            data.get("duration"), has_expiry=data.get("expiresAt") is not None,
        ),
        "expiresAt": data.get("expiresAt"),
        "graceDays": data.get("graceDays"),
        "graceEndsAt": grace_ends_at(data.get("expiresAt"), data.get("graceDays") or 0),
        # Informational; never gates. A perpetual license whose support has
        # lapsed still grants full use.
        "supportUntil": data.get("supportUntil"),
        "maxAnalyses": data.get("maxAnalyses"),
        "note": data.get("note") or "",
    }


def _write_license(
    *,
    mode: str,
    email_lock: str,
    device_id_lock: str,
    created_by_uid: str,
    status: str,
    kind: str = KIND_INDIVIDUAL,
    domain_lock: str = "",
    admin_emails: list[str] | None = None,
    max_seats: int | None = None,
    seating: str = SEATING_ASSIGNED,
    redeemed_by_uid: str | None = None,
    expires_at=None,
    grace_days: int | None = None,
    support_until=None,
    max_analyses: int | None = None,
    note: str = "",
) -> tuple[str, str, dict]:
    """Mint a key, persist the hash, return (plaintext, license_id, stored)."""
    key = generate_key()
    license_id = key_hash(key)
    stored = {
        "keyPrefix": key_prefix(key),
        # Redundant with the document id, which is this same hash. Recorded as
        # a field so licenses can later move to opaque ids — needed once a
        # license may be issued with no key at all.
        "keyHash": license_id,
        "kind": kind,
        "mode": mode,
        # Pre-rename mirror; see _mode_patch.
        "plan": legacy_plan(mode),
        "status": status,
        "emailLock": (email_lock or "").strip().lower(),
        "deviceIdLock": device_id_lock,
        "createdAt": firestore.SERVER_TIMESTAMP,
        "createdByUid": created_by_uid,
        "schemaVersion": SCHEMA_VERSION,
        "note": note or "",
    }
    if kind == KIND_INSTITUTION:
        stored["domainLock"] = (domain_lock or "").strip().lower()
        stored["adminEmails"] = [
            (e or "").strip().lower() for e in (admin_emails or []) if (e or "").strip()
        ]
        if max_seats is not None:
            stored["maxSeats"] = int(max_seats)
        stored["seatsUsed"] = 0
        stored["seating"] = normalize_seating(seating)
        stored["leasesActive"] = 0
    if expires_at is not None:
        stored["expiresAt"] = expires_at
        stored["duration"] = DURATION_TIMED
        # Stamped explicitly at mint so the value in force is recorded on the
        # document rather than inherited from whatever the env says later.
        stored["graceDays"] = (
            settings.LICENSE_GRACE_DAYS_DEFAULT if grace_days is None else max(0, int(grace_days))
        )
    else:
        stored["duration"] = DURATION_PERPETUAL
    if support_until is not None:
        # Informational only. A perpetual license whose support has lapsed
        # still grants full use — nothing reads this to gate anything.
        stored["supportUntil"] = support_until
    if max_analyses is not None:
        stored["maxAnalyses"] = int(max_analyses)
    if redeemed_by_uid:
        stored["redeemedByUid"] = redeemed_by_uid
        stored["redeemedAt"] = firestore.SERVER_TIMESTAMP
    db().collection("licenses").document(license_id).set(stored)
    return key, license_id, stored


def ensure_demo_license(user: dict, device_id: str | None) -> dict:
    """Issue a redeemed Demo key once the account is approved, verified, and bound.

    Idempotent. Licensed accounts are left alone. The plaintext Demo key is
    not returned — the user never types it; the record exists so the seat is
    locked to this email and device.

    The attachment is a compare-and-set on the stored document, not a blind
    update. `user` was read before this request began, and several requests
    arrive together at app launch: one that lost the race to claim a real
    licence — the delivery path for every individual licence and every invited
    seat — falls through to here holding a stale copy, and a blind write would
    overwrite the entitlement granted moments earlier with a Demo key.
    """
    uid = user.get("uid")
    if not uid or user.get("licenseId"):
        return user
    if user.get("access_status") != "APPROVED":
        return user
    if not user.get("emailVerified"):
        return user
    email = (user.get("email") or "").strip().lower()
    device = device_id or user.get("activeDeviceId") or user.get("claimedDeviceId")
    if not email or not device:
        return user
    _key, license_id, stored = _write_license(
        mode=MODE_DEMO,
        email_lock=email,
        device_id_lock=device,
        created_by_uid="system",
        status="redeemed",
        redeemed_by_uid=uid,
    )
    patch = {
        **_mode_patch(MODE_DEMO),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": stored["keyPrefix"],
        "updatedAt": firestore.SERVER_TIMESTAMP,
    }
    user_ref = db().collection("users").document(uid)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _attach(tx) -> bool:
        snap = user_ref.get(transaction=tx)
        if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or ""):
            return False
        tx.update(user_ref, patch)
        return True

    try:
        attached = _attach(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        attached = False

    if attached:
        return {**user, **patch}
    # Something reached this account first. Drop the key nobody will ever hold
    # rather than leaving a redeemed Demo record pointing at no one, and answer
    # with what the account actually has — which on the losing side of an
    # invite claim is the licence, not demo.
    db().collection("licenses").document(license_id).delete()
    current = user_ref.get()
    if not current.exists:
        return user
    return {**user, **(current.to_dict() or {}), "uid": uid}


def create_individual_license(
    *,
    email_lock: str,
    device_id_lock: str = "",
    created_by_uid: str,
    expires_at=None,
    max_analyses: int | None = None,
    note: str = "",
) -> dict:
    """Ops mint of an individual licensed key. Returns the plaintext key
    once; only the hash is stored.

    Also records a pending invite against `email_lock`, which is how the
    licence actually reaches the customer: they sign in with that address and
    `claim_pending_invite` attaches the licence on their first request. The
    key is the fallback for support recovery, not the delivery mechanism —
    nobody should have to type one.

    `device_id_lock` stays available for the rare mint against a device we
    already know, but is empty in normal use; the lock is bound at first
    sign-in instead.
    """
    key, license_id, stored = _write_license(
        mode=MODE_LICENSED,
        email_lock=email_lock,
        device_id_lock=device_id_lock,
        created_by_uid=created_by_uid,
        status="unused",
        kind="individual",
        expires_at=expires_at,
        max_analyses=max_analyses,
        note=note,
    )
    err, _ = _write_invite(license_id, email_lock, created_by_uid)
    if err:
        # The licence exists and the key in hand still redeems it, so this is
        # degraded delivery rather than a failed mint. `invite_exists` means
        # the address is already promised another licence — a real conflict
        # for ops to resolve, and one the returned licence makes visible.
        log.warning("individual licence %s minted without an invite: %s", license_id, err)
    return {"key": key, "license": _license_public(license_id, stored),
            "inviteError": err or ""}


def create_institution_license(
    *,
    domain_lock: str,
    admin_emails: list[str],
    created_by_uid: str,
    max_seats: int | None = None,
    seating: str = SEATING_ASSIGNED,
    expires_at=None,
    max_analyses: int | None = None,
    note: str = "",
) -> dict:
    """Ops mint of an institution key. Seats are granted individually via
    activate_license as members of `domain_lock` redeem the same key; ops never
    pre-allocates seats. Returns the plaintext key once."""
    key, license_id, stored = _write_license(
        mode=MODE_LICENSED,
        email_lock="",
        device_id_lock="",
        created_by_uid=created_by_uid,
        status="active",
        kind=KIND_INSTITUTION,
        domain_lock=domain_lock,
        admin_emails=admin_emails,
        max_seats=max_seats,
        seating=seating,
        expires_at=expires_at,
        max_analyses=max_analyses,
        note=note,
    )
    return {"key": key, "license": _license_public(license_id, stored)}


def list_licenses(limit: int = 50, page_token: str | None = None) -> tuple[list, str | None]:
    col = db().collection("licenses")
    query = col.order_by("__name__").limit(limit + 1)
    if page_token:
        cursor = col.document(page_token).get()
        if cursor.exists:
            query = query.start_after(cursor)
    docs = list(query.stream())
    next_token = None
    if len(docs) > limit:
        docs = docs[:limit]
        next_token = docs[-1].id
    return [_license_public(d.id, d.to_dict() or {}) for d in docs], next_token


def _seat_ref(license_id: str, uid: str):
    return db().collection("licenses").document(license_id).collection("seats").document(uid)


def _license_mirror_patch(lic: dict) -> dict:
    """The license terms copied onto the user document at activation.

    `effective_mode` and `license_summary` read only the user dict, which is
    what keeps `resolve_user_config` free of Firestore reads on request paths
    that call it for every session and file. The cost is that these are a
    snapshot: changing the license after activation does not reach anyone who
    already holds a seat. `update_license` fans the new values back out — it is
    the only writer that has to, and it is rare.

    Every field is written on every activation, cleared with DELETE_FIELD when
    the license does not carry it, so re-activating onto a different license
    never leaves a stale term behind.
    """
    expires_at = lic.get("expiresAt")
    grace_days = lic.get("graceDays")
    max_analyses = lic.get("maxAnalyses")
    return {
        "licenseExpiresAt": expires_at if expires_at is not None else firestore.DELETE_FIELD,
        "licenseGraceDays": (
            max(0, int(grace_days)) if isinstance(grace_days, (int, float))
            else firestore.DELETE_FIELD
        ),
        "licenseDuration": normalize_duration(
            lic.get("duration"), has_expiry=expires_at is not None,
        ),
        # Not the lease itself — only whether this license needs one. The lease
        # is written by checkout and cleared by release, and re-stamping it
        # here would hand a seat back to someone who had released it.
        "licenseSeating": normalize_seating(lic.get("seating")),
        "licenseMaxAnalyses": int(max_analyses) if max_analyses else firestore.DELETE_FIELD,
    }


def _activate_individual(user: dict, uid: str, email: str, device_id: str, lic: dict, ref, key: str):
    status = lic.get("status") or "unused"
    if status == "revoked":
        return "license_revoked", None
    if not _emails_match(lic.get("emailLock"), email):
        return "license_email_mismatch", None
    locked = lic.get("deviceIdLock") or ""
    if not locked and device_id:
        # Bind-on-first-use, the same rule the request path applies. A licence
        # minted against an address alone has no lock, so a key typed here for
        # support recovery has to be able to set one rather than demand it.
        # Re-read rather than assume: bind_device_lock is first-writer-wins,
        # and losing the race means some other device owns this licence.
        bind_device_lock(ref, device_id)
        locked = (ref.get().to_dict() or {}).get("deviceIdLock") or ""
    if locked != device_id:
        return "license_device_mismatch", None
    if status == "redeemed" and lic.get("redeemedByUid") != uid:
        return "license_already_redeemed", None

    mode = _license_mode(lic)
    license_id = ref.id
    user_patch = {
        **_mode_patch(mode),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": lic.get("keyPrefix") or key_prefix(key),
        "updatedAt": firestore.SERVER_TIMESTAMP,
    }
    user_patch.update(_license_mirror_patch(lic))

    license_patch = {}
    if status == "unused":
        license_patch = {
            "status": "redeemed",
            "redeemedByUid": uid,
            "redeemedAt": firestore.SERVER_TIMESTAMP,
        }

    batch = db().batch()
    batch.update(db().collection("users").document(uid), user_patch)
    if license_patch:
        batch.update(ref, license_patch)
    batch.commit()

    merged = _apply_patch(user, user_patch)
    return "", resolve_user_config(merged)


# ---------------- floating-seat leases ----------------
# A floating license separates the roster from the count: every member may use
# the license, but only `maxSeats` hold a live lease at once. The lease lives
# on the seat document — `check_device_lock` already reads that document on
# every institution request, so consulting it costs nothing extra — and its
# expiry is mirrored onto the user so `effective_mode` stays a pure function.

def _lease_clear_patch() -> dict:
    """Seat fields that record no lease. Written on release and on revoke."""
    return {
        "leaseExpiresAt": firestore.DELETE_FIELD,
        "leaseDeviceId": firestore.DELETE_FIELD,
        "lastHeartbeatAt": firestore.DELETE_FIELD,
    }


def _seat_lease_live(seat: dict) -> bool:
    """Whether this seat document holds an unexpired lease.

    Fails closed, like `_lease_live`: an unreadable lease frees the slot
    rather than parking it.
    """
    ends = as_utc(seat.get("leaseExpiresAt"))
    if ends is None:
        return False
    try:
        return ends > _now()
    except TypeError:
        return False


def _sweep_expired_leases(lic_ref, now) -> int:
    """Release leases that ran out without anyone calling release.

    `leasesActive` drifts upward every time an app is killed, uninstalled or
    simply goes offline mid-lease, so the counter alone cannot be trusted to
    say whether the pool is full. This reconciles it before a claim reads it.

    A single-field inequality on one subcollection, so no composite index is
    needed. Positional `.where(field, op, value)` deliberately — the fake store
    used by the unit tests implements only that form, not `FieldFilter`.

    Deliberately outside the claim transaction: a transaction may not run a
    query, and sweeping first is safe because releasing a genuinely expired
    lease is correct regardless of who wins the claim that follows.
    """
    expired = list(
        lic_ref.collection("seats")
        .where("leaseExpiresAt", "<=", now)
        .limit(_LEASE_SWEEP_LIMIT)
        .stream()
    )
    if not expired:
        return 0
    batch = db().batch()
    for doc in expired:
        batch.update(doc.reference, {
            **_lease_clear_patch(),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
    batch.update(lic_ref, {"leasesActive": firestore.Increment(-len(expired))})
    batch.commit()
    log.info("Reclaimed %d expired lease(s) on license %s", len(expired), lic_ref.id)
    return len(expired)


def checkout_lease(user: dict, device_id: str) -> tuple[str, dict | None]:
    """Claim or extend a floating-seat lease. Returns (error_code, config).

    Re-checkout IS the heartbeat — extending an existing lease takes the same
    path and must not consume a second slot. There is deliberately no separate
    heartbeat route and no audit write here: the app calls this every half
    hour, and both `FILE_DOWNLOAD` and `_LAST_SEEN_THROTTLE` record what
    writing per request on a hot path costs.
    """
    uid = user.get("uid") or ""
    license_id = user.get("licenseId")
    if not license_id:
        return "no_license", None
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None
    if normalize_seating(lic.get("seating")) != SEATING_FLOATING:
        # An assigned seat is always entitled; there is nothing to check out,
        # and pretending otherwise would let a client invent a lease field.
        return "seating_not_floating", None
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    if _license_past_grace(lic):
        return "license_expired", None

    lic_ref = db().collection("licenses").document(license_id)
    now = _now()
    _sweep_expired_leases(lic_ref, now)
    expires_at = now + timedelta(hours=settings.LICENSE_LEASE_HOURS)

    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _checkout(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            # Not on the roster. Institution IT adds members; there is no
            # self-service path onto a floating license.
            return "not_eligible"
        seat = seat_snap.to_dict() or {}
        if seat.get("status") in ("revoked", "disabled"):
            return "not_eligible"

        renewing = _seat_lease_live(seat)
        if not renewing:
            max_seats = (lic_snap.to_dict() or {}).get("maxSeats") if lic_snap.exists else None
            active = int((lic_snap.to_dict() or {}).get("leasesActive") or 0)
            if max_seats is not None and active >= int(max_seats):
                return "no_floating_seat"

        tx.update(seat_ref, {
            "leaseExpiresAt": expires_at,
            "leaseDeviceId": device_id,
            "lastHeartbeatAt": now,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
        if not renewing:
            tx.update(lic_ref, {"leasesActive": firestore.Increment(1)})
        tx.update(user_ref, {
            "leaseExpiresAt": expires_at,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
        return ""

    try:
        err = _checkout(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Fail closed: granting a lease we could not commit is what would
        # overfill the pool. The client retries and wins as soon as there
        # is room.
        return "no_floating_seat", None
    if err:
        return err, None
    return "", resolve_user_config({**user, "leaseExpiresAt": expires_at})


def release_lease(user: dict) -> tuple[str, dict | None]:
    """Give a floating slot back. Idempotent — releasing twice frees one slot.

    The user's mirror is cleared in the same commit, so the account resolves
    demo from the next request rather than staying licensed until the lease
    would have expired on its own.
    """
    uid = user.get("uid") or ""
    license_id = user.get("licenseId")
    if not license_id:
        return "no_license", None

    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _release(tx) -> str:
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            return "not_eligible"
        held = _seat_lease_live(seat_snap.to_dict() or {})
        tx.update(seat_ref, {
            **_lease_clear_patch(),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
        if held:
            # Only decrement for a lease that was actually counted. A second
            # release, or one after expiry, must not push the pool negative.
            tx.update(lic_ref, {"leasesActive": firestore.Increment(-1)})
        tx.update(user_ref, {
            "leaseExpiresAt": firestore.DELETE_FIELD,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
        return ""

    try:
        err = _release(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Releasing is idempotent, so a lost race means someone else already
        # did it. Answer from the current state rather than reporting failure.
        err = "" if seat_ref.get().exists else "not_eligible"
    if err:
        return err, None
    merged = {k: v for k, v in user.items() if k != "leaseExpiresAt"}
    return "", resolve_user_config(merged)


def _drop_superseded_demo(uid: str, license_id: str) -> None:
    """Delete the auto-minted Demo key an account has just stopped pointing at.

    `ensure_demo_license` is a compare-and-set, so a request that lost the
    race can no longer stamp Demo *over* a real licence. The opposite order is
    what is left: the loser commits its Demo key first, the winning claim then
    moves the account's pointer, and the Demo record survives
    `status: "redeemed"` with nobody holding it — indistinguishable in
    `GET /v1/admin/licenses` from a live key, one more of them for every raced
    sign-in.

    This runs after a claim commits, deliberately not inside it. Reading
    `users/{uid}` in the claim's transaction looked like the tidy answer, and
    it is wrong: a transaction that reads and then writes one document locks
    it, so several requests arriving at one account together — the shape of
    every app launch, and of the invite delivery path — abort each other
    instead of queueing. Measured against the emulator, six concurrent
    sign-ins starved out completely and the account landed on Demo. A plain
    query and a guarded delete take no locks, and the worst a lost race costs
    here is that the record survives to the next claim: the condition being
    closed is litter in the operator listing, never a wrong entitlement.

    The discriminator is the licence document, never the holder's `mode`
    mirror. `_drop_user_to_demo_if_licensed` leaves a revoked holder demoted
    in place and still pointing at the real, revoked licence, so a mirror test
    would delete revocation records — considerably worse than the leak this
    closes. Only `ensure_demo_license` writes `mode: demo` and
    `createdByUid: "system"` together.
    """
    snap = db().collection("users").document(uid).get()
    if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        # Something has moved the account on again. Whatever it holds now is
        # not this claim's to reason about.
        return
    for doc in db().collection("licenses").where("redeemedByUid", "==", uid).stream():
        if doc.id == license_id:
            continue
        lic = doc.to_dict() or {}
        if _license_mode(lic) == MODE_DEMO and (lic.get("createdByUid") or "") == "system":
            doc.reference.delete()


def claim_seat(license_id: str, uid: str, email: str, device_id: str, user_patch: dict,
               invite_ref=None) -> str:
    """Take a seat on the roster, atomically. Returns an error code, or "".

    This was a read-then-`WriteBatch` — atomic for its writes, but carrying no
    reads and no preconditions, so two members activating at once on a pool of
    ten both saw nine free and the count landed at eleven. A batch is not a
    transaction. With a floating pool the count is the actual boundary rather
    than a soft allocation, so the whole claim now reads and writes under one.

    Every read happens before every write, as Firestore requires. The seat
    validation lives inside for the same reason as the count: it is decided
    from data read in the transaction.

    `invite_ref`, when given, is a pending invite being redeemed: it is read
    with the other reads and deleted with the other writes, so the seat and
    the invite settle together. Consuming it in a second write would leave a
    window where IT has revoked the invite but the seat is granted anyway, or
    where the invite is gone and the claim then fails — either way the roster
    and the invite list disagree.
    """
    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)
    user_ref = db().collection("users").document(uid)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _claim(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        seat_snap = seat_ref.get(transaction=tx)
        invite_snap = invite_ref.get(transaction=tx) if invite_ref is not None else None
        if not lic_snap.exists:
            return "license_not_found"
        if invite_ref is not None and not invite_snap.exists:
            # Revoked between the read that found it and this transaction.
            return "invite_not_found"
        lic = lic_snap.to_dict() or {}
        if (lic.get("status") or "active") == "revoked":
            return "license_revoked"

        seat = seat_snap.to_dict() if seat_snap.exists else None
        if seat and seat.get("status") == "revoked":
            # revoke_institution_seat() already freed this slot. A revoked seat
            # is not a permanent ban — the holder may be re-admitted and takes
            # a fresh slot through the normal maxSeats check below.
            seat = None

        if seat:
            if seat.get("status") == "disabled":
                return "license_seat_disabled"
            locked_device = seat.get("deviceIdLock") or ""
            if device_id and locked_device and locked_device != device_id:
                return "license_device_mismatch"
            seat_patch = {"updatedAt": firestore.SERVER_TIMESTAMP}
            if device_id:
                # Only when a device actually redeemed. IT adding a member
                # passes no device, and must not wipe the lock of someone who
                # already has one.
                seat_patch["deviceIdLock"] = device_id
            tx.update(seat_ref, seat_patch)
        else:
            # `maxSeats` caps the ROSTER on an assigned license, where holding
            # a seat is holding the entitlement. On a floating one it caps
            # concurrent LEASES instead — a fifty-person lab sharing ten slots
            # is the whole point, so the roster is deliberately uncapped and
            # the check moves to checkout_lease.
            max_seats = lic.get("maxSeats")
            seats_used = int(lic.get("seatsUsed") or 0)
            floating = normalize_seating(lic.get("seating")) == SEATING_FLOATING
            if not floating and max_seats is not None and seats_used >= int(max_seats):
                return "license_seats_exhausted"
            tx.set(seat_ref, {
                "uid": uid,
                "email": (email or "").strip().lower(),
                "deviceIdLock": device_id,
                "status": "active",
                "createdAt": firestore.SERVER_TIMESTAMP,
                "updatedAt": firestore.SERVER_TIMESTAMP,
            })
            tx.update(lic_ref, {"seatsUsed": firestore.Increment(1)})
        tx.update(user_ref, user_patch)
        if invite_ref is not None:
            tx.delete(invite_ref)
        return ""

    try:
        err = _claim(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Fail closed. Handing out a seat we could not commit is the one
        # outcome that breaks the cap; a caller who lost the race just tries
        # again, and on a pool with room they win immediately.
        return _CONTENDED
    if not err:
        _drop_superseded_demo(uid, license_id)
    return err


def _individual_member_patch(license_id: str, lic: dict) -> dict:
    """The user-document patch that attaches an individual licence.

    The counterpart to `_institution_member_patch`, and shared for the same
    reason: a licence reached by typing its key and one reached by signing in
    at the invited address must entitle the holder identically.
    """
    patch = {
        **_mode_patch(_license_mode(lic)),
        "licenseId": license_id,
        "licenseKind": KIND_INDIVIDUAL,
        "licensePrefix": lic.get("keyPrefix") or "",
        "updatedAt": firestore.SERVER_TIMESTAMP,
    }
    patch.update(_license_mirror_patch(lic))
    return patch


def claim_individual_license(license_id: str, uid: str, email: str,
                             user_patch: dict, invite_ref=None) -> str:
    """Attach an individual licence to `uid`, atomically. Error code, or "".

    The individual counterpart to `claim_seat`, transactional for the same
    reason: `redeemedByUid` names one account, so two requests arriving
    together must not both come away holding the licence. Reads before
    writes, and the invite is consumed in the same transaction, so the promise
    and the grant settle together rather than leaving a window where one
    exists without the other.

    No device lock is written here. Binding happens on the request path — see
    `revalidate_device_lock` — because this runs for a caller who may not have
    presented a device at all.
    """
    lic_ref = db().collection("licenses").document(license_id)
    user_ref = db().collection("users").document(uid)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _claim(tx) -> str:
        lic_snap = lic_ref.get(transaction=tx)
        invite_snap = invite_ref.get(transaction=tx) if invite_ref is not None else None
        if not lic_snap.exists:
            return "license_not_found"
        if invite_ref is not None and not invite_snap.exists:
            # Withdrawn between the read that found it and this transaction.
            return "invite_not_found"
        lic = lic_snap.to_dict() or {}
        status = lic.get("status") or "unused"
        if status == "revoked":
            return "license_revoked"
        if not _emails_match(lic.get("emailLock"), email):
            return "license_email_mismatch"
        redeemer = lic.get("redeemedByUid")
        if redeemer and redeemer != uid:
            return "license_already_redeemed"
        if status == "unused":
            tx.update(lic_ref, {
                "status": "redeemed",
                "redeemedByUid": uid,
                "redeemedAt": firestore.SERVER_TIMESTAMP,
            })
        tx.update(user_ref, user_patch)
        if invite_ref is not None:
            tx.delete(invite_ref)
        return ""

    try:
        err = _claim(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Fail closed, as claim_seat does: granting a licence we could not
        # commit is the outcome that breaks single-redeemer. The caller retries
        # on their next request, which for the invite path is moments away.
        return _CONTENDED
    if not err:
        _drop_superseded_demo(uid, license_id)
    return err


def _activate_institution(user: dict, uid: str, email: str, device_id: str, lic: dict, ref, key: str):
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    domain_lock = (lic.get("domainLock") or "").strip().lower()
    if not domain_lock or _email_domain(email) != domain_lock:
        return "license_email_mismatch", None

    license_id = ref.id
    user_patch = {
        **_mode_patch(MODE_LICENSED),
        "licenseId": license_id,
        "licenseKind": KIND_INSTITUTION,
        "licensePrefix": lic.get("keyPrefix") or key_prefix(key),
        "updatedAt": firestore.SERVER_TIMESTAMP,
    }
    user_patch.update(_license_mirror_patch(lic))

    err = claim_seat(license_id, uid, email, device_id, user_patch)
    if err:
        return _public_claim_error(err, "license_seats_exhausted"), None

    merged = _apply_patch(user, user_patch)
    return "", resolve_user_config(merged)


def _apply_patch(user: dict, patch: dict) -> dict:
    merged = {**user, **{k: v for k, v in patch.items() if v is not firestore.DELETE_FIELD}}
    for k, v in patch.items():
        if v is firestore.DELETE_FIELD:
            merged.pop(k, None)
    return merged


def activate_license(uid: str, email: str, device_id: str, key: str) -> tuple[str, dict | None]:
    """Redeem a key onto this uid. Returns (error_code, config_or_none).

    Empty error_code means success. Branches on the license's `kind`:
    - individual: single email+device lock, same behaviour as before
      institution licensing existed. Same uid re-entering the same key is OK.
    - institution: verified-email domain match against `domainLock`; a seat is
      created (or re-validated) in `licenses/{id}/seats/{uid}`, capped at
      `maxSeats` when set. Re-entry from the same device is idempotent; from a
      different device it re-locks the seat only when no device is locked yet.
    """
    user = _load_user(uid)
    if not user:
        return "user_not_found", None
    license_id = key_hash(key)
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return "license_not_found", None
    lic = snap.to_dict() or {}
    if _license_past_grace(lic):
        # Without this the activation "succeeds": the past expiry is mirrored
        # onto the user, effective_mode immediately resolves demo, and the
        # caller is handed err="" with a demo config and no explanation.
        return "license_expired", None
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        return _activate_institution(user, uid, email, device_id, lic, ref, key)
    return _activate_individual(user, uid, email, device_id, lic, ref, key)


def _license_past_grace(lic: dict) -> bool:
    """True once a license grants nothing, grace included.

    Reads the license document rather than the user mirror, because at
    activation there is no mirror yet. Fails open on a malformed timestamp,
    matching _expiry_state.
    """
    ends = grace_ends_at(lic.get("expiresAt"), lic.get("graceDays") or 0)
    if ends is None:
        return False
    try:
        return ends <= _now()
    except TypeError:
        return False


# Verdicts from _device_lock_state. "Unbound" is deliberately distinct from
# "matches": both let the request through, but only one of them is a
# instruction to write.
_LOCK_OK = "ok"
_LOCK_UNBOUND = "unbound"
_LOCK_VIOLATION = "violation"


def _device_lock_state(user: dict, device_id: str) -> tuple[str, object | None]:
    """Judge `device_id` against this account's entitlement.

    Returns the verdict and, when the lock is still empty, the document that
    holds it. Three outcomes rather than the bool this replaced, because "no
    lock yet" and "lock matches" are the same answer to *may this device
    proceed* and opposite answers to *what should be written*.

    Empty locks are now the normal way a licence starts life, not an edge
    case. An individual licence minted against an email alone, and an
    institution seat created from an invite or added by IT, both reach a
    device for the first time with nothing bound — so the caller binds, and
    the licence ties itself to a device without anyone typing a key.

    Individual: the lock lives on the licence, one device for the licence.
    Institution: on the seat, one device per member.
    """
    license_id = user.get("licenseId")
    if not license_id:
        return _LOCK_OK, None
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return _LOCK_OK, None
    lic = snap.to_dict() or {}
    if (lic.get("status") or "") == "revoked":
        return _LOCK_VIOLATION, None
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        return _lock_verdict(lic.get("deviceIdLock"), device_id, ref)
    seat_ref = _seat_ref(license_id, user.get("uid") or "")
    seat_snap = seat_ref.get()
    if not seat_snap.exists:
        return _LOCK_OK, None
    seat = seat_snap.to_dict() or {}
    if seat.get("status") in ("revoked", "disabled"):
        return _LOCK_VIOLATION, None
    return _lock_verdict(seat.get("deviceIdLock"), device_id, seat_ref)


def _lock_verdict(locked, device_id: str, ref) -> tuple[str, object | None]:
    """The individual and institution branches differ only in which document
    carries the lock, so the comparison itself lives in one place."""
    locked = locked or ""
    if not locked:
        return _LOCK_UNBOUND, ref
    return (_LOCK_OK, None) if locked == device_id else (_LOCK_VIOLATION, None)


def bind_device_lock(ref, device_id: str) -> bool:
    """Claim an empty device lock for `device_id`. True if this call bound it.

    Transactional rather than a bare update: two devices signing in at once
    both read an empty lock, and with a plain write the later one would win,
    so the licence would silently follow whichever request Firestore happened
    to order second. Re-reading inside the transaction makes the first binding
    stick and turns the second device into a mismatch on its next request,
    which is the answer a device lock exists to give.
    """
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _bind(tx) -> bool:
        snap = ref.get(transaction=tx)
        if not snap.exists or ((snap.to_dict() or {}).get("deviceIdLock") or ""):
            return False
        tx.update(ref, {"deviceIdLock": device_id})
        return True

    try:
        return _bind(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Another device bound it first. This one is a mismatch from its next
        # request onward, which revalidate_device_lock will act on.
        return False


def check_device_lock(user: dict, device_id: str) -> bool:
    """True if `device_id` may still use this account's entitlement.

    An unbound lock passes: it is not a violation, it is a licence that has
    not met a device yet. Binding is revalidate_device_lock's job, because
    only it knows the caller is a real authed request rather than a check.
    """
    return _device_lock_state(user, device_id)[0] != _LOCK_VIOLATION


def revalidate_device_lock(user: dict, device_id: str | None) -> dict:
    """Re-check this account's entitlement against `device_id` on every authed
    call that carries X-Device-Id — activation is not "trust forever". A
    revoked license/seat, or a device that no longer matches the lock, drops
    the account to Demo immediately rather than waiting for the next explicit
    revoke/activate to notice. No-op (and no write) for Demo accounts, accounts
    with no license on file, or a call with no device id to check.

    Also the moment an unbound licence acquires its device. A licence minted
    against an email, or a seat added to a roster, carries no lock until
    someone actually signs in — so the first authed request that presents a
    device id binds it here. That is what makes "we mint against your address
    and you sign in" tie a licence to a device with no key and no activation
    step; see _device_lock_state.

    This only ever *removes* entitlement in place — it never deletes or hides
    the account's sessions/files, and re-locking to a *different* device
    happens only after a staff, IT or self-service clear of the existing lock.
    """
    if not device_id or not user.get("licenseId"):
        return user
    if effective_mode(user) != MODE_LICENSED:
        return user
    verdict, ref = _device_lock_state(user, device_id)
    if verdict == _LOCK_UNBOUND:
        if bind_device_lock(ref, device_id):
            log.info("device lock bound uid=%s license=%s",
                     user.get("uid"), user.get("licenseId"))
            # Imported here rather than at module scope: `audit` reads `db`
            # from this module, so the two cannot import each other eagerly.
            # The record matters because it is the second half of a device
            # change — `clear_device_lock` writes the device that was given
            # up, and this writes the one that took its place.
            from . import audit
            audit.record(
                user.get("uid"), device_id, action="LICENSE_DEVICE_BIND",
                target={"type": "license", "id": user.get("licenseId")},
                detail={"deviceId": device_id},
            )
        return user
    if verdict == _LOCK_OK:
        return user
    uid = user.get("uid")
    if uid:
        db().collection("users").document(uid).update({
            **_mode_patch(MODE_DEMO),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
    return {**user, **_mode_patch(MODE_DEMO)}


def revoke_license(license_id: str, admin_uid: str) -> dict | None:
    """Whole-key revoke. Every redeemer (individual redeemer, or every
    institution seat holder) drops to Demo and every occupied seat is freed."""
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}
    ref.update({
        "status": "revoked",
        "revokedAt": firestore.SERVER_TIMESTAMP,
        "revokedByUid": admin_uid,
    })
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        for seat_doc in ref.collection("seats").stream():
            seat = seat_doc.to_dict() or {}
            seat_uid = seat.get("uid") or seat_doc.id
            _drop_user_to_demo_if_licensed(seat_uid, license_id)
            seat_doc.reference.update({
                "status": "revoked",
                "revokedAt": firestore.SERVER_TIMESTAMP,
                "updatedAt": firestore.SERVER_TIMESTAMP,
            })
        ref.update({"seatsUsed": 0})
        _delete_license_invites(license_id)
    else:
        redeemer = lic.get("redeemedByUid")
        if redeemer and _license_mode(lic) == MODE_LICENSED:
            _drop_user_to_demo_if_licensed(redeemer, license_id)
        _delete_license_invites(license_id, lic.get("emailLock") or "")
    return _license_public(license_id, {**lic, "status": "revoked"})


def update_license(license_id: str, patch: dict, admin_uid: str) -> dict | None:
    """Change a license's terms and push them to everyone already holding it.

    Renewal is the reason this exists. `_license_mirror_patch` snapshots the
    terms onto each user at activation so the hot read path needs no Firestore
    lookup; the cost is that editing the license alone reaches nobody. So this
    writes the document and then fans the new mirror out — to the individual
    redeemer, or to every seat on an institution license.

    The fan-out is bounded by `seatsUsed` and renewal is rare, which is what
    makes this the right side of the trade against a per-request read. Revoked
    seats are skipped: they hold no entitlement to refresh, and touching them
    would quietly resurrect a revoked member on the next resolve.

    Returns the updated public license, or None if there is no such license.
    """
    ref = db().collection("licenses").document(license_id)
    snap = ref.get()
    if not snap.exists:
        return None
    lic = snap.to_dict() or {}

    update = {k: v for k, v in patch.items() if v is not None}
    if not update:
        return _license_public(license_id, lic)
    if "expiresAt" in update:
        # A license given an expiry becomes timed; the mint-time validator
        # cannot speak for an edit made years later.
        update["duration"] = DURATION_TIMED
    if "graceDays" in update:
        update["graceDays"] = max(0, int(update["graceDays"]))
    update["updatedAt"] = firestore.SERVER_TIMESTAMP
    update["updatedByUid"] = admin_uid
    ref.update(update)

    merged = {**lic, **update}
    mirror = _license_mirror_patch(merged)
    for uid in _license_holder_uids(ref, merged):
        _refresh_license_mirror(uid, license_id, mirror)
    return _license_public(license_id, merged)


def _license_holder_uids(ref, lic: dict) -> list[str]:
    """Everyone currently entitled by this license."""
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        redeemer = lic.get("redeemedByUid")
        return [redeemer] if redeemer else []
    out = []
    for seat_doc in ref.collection("seats").stream():
        seat = seat_doc.to_dict() or {}
        if seat.get("status") == "revoked":
            continue
        out.append(seat.get("uid") or seat_doc.id)
    return out


def _refresh_license_mirror(uid: str, license_id: str, mirror: dict) -> None:
    """Re-stamp one holder's copy of the license terms.

    Guarded on `licenseId` for the same reason as the demo drop below: a user
    who has since moved to a different license must not have this one's terms
    written over theirs.
    """
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if not user_snap.exists:
        return
    if (user_snap.to_dict() or {}).get("licenseId") != license_id:
        return
    user_ref.update({**mirror, "updatedAt": firestore.SERVER_TIMESTAMP})


def _drop_user_to_demo_if_licensed(uid: str, license_id: str) -> None:
    """Drop a user to Demo only if they are still pointed at this exact
    license — activation is in-place (same uid/doc, no data migration), and
    downgrade must never delete or hide existing sessions/files, only stop
    new analysis creation once the account is back over the Demo cap."""
    user_ref = db().collection("users").document(uid)
    user_snap = user_ref.get()
    if user_snap.exists and (user_snap.to_dict() or {}).get("licenseId") == license_id:
        user_ref.update({
            **_mode_patch(MODE_DEMO),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })


# ---------------- institution seat administration ----------------
# Reached only via routers/institutions.py, gated on current_user + APPROVED +
# verified email present in the license's adminEmails — deliberately NOT
# verified_device and NOT Semper role=admin. Mint/whole-key-revoke stays on
# the existing device-attested admin path in routers/admin.py.

def is_institution_admin(license_doc: dict, email: str) -> bool:
    admin_emails = {e.strip().lower() for e in (license_doc.get("adminEmails") or [])}
    return bool(email) and email.strip().lower() in admin_emails


def get_license(license_id: str) -> dict | None:
    snap = db().collection("licenses").document(license_id).get()
    return snap.to_dict() if snap.exists else None


def list_licenses_administered_by(email: str) -> list[dict]:
    """Every live institution licence that names this address in `adminEmails`.

    The inverse of `is_institution_admin`, which can only answer for a licence
    id you already hold. Sign-in has an address and nothing else, so without
    this a member of institution IT has no way to reach their own roster
    except by being told the id out of band.

    `kind` and `status` are filtered in Python rather than added to the query:
    one address administers a handful of licences at most, and each extra
    equality clause on top of `array_contains` costs another composite index
    for no measurable gain.

    Same redaction as `institution_license_summary` — no key plaintext.
    """
    wanted = (email or "").strip().lower()
    if not wanted:
        return []
    out = []
    q = db().collection("licenses").where("adminEmails", "array_contains", wanted)
    for d in q.stream():
        data = d.to_dict() or {}
        if normalize_kind(data.get("kind")) != KIND_INSTITUTION:
            continue
        if (data.get("status") or "") == "revoked":
            continue
        out.append(_license_public(d.id, data))
    out.sort(key=lambda lic: lic["id"])
    return out


def find_user_by_email(email: str) -> dict | None:
    """The account holding this email, or None. Used to add a roster member.

    Institution IT works from an email address, but seats are keyed by uid —
    they have to be, since that is what every entitlement check has in hand.
    Demo is open to everyone, so requiring the person to have signed in once
    is not a barrier: it is the same step that gave them demo in the first
    place, and it means no invite records, no email-keyed documents and no
    second identity space to keep consistent.
    """
    wanted = (email or "").strip().lower()
    if not wanted:
        return None
    q = db().collection("users").where("email", "==", wanted).limit(1)
    for d in q.stream():
        return {**d.to_dict(), "uid": d.id}
    return None


#: Pending invites, keyed by a hash of the invited address. Top-level rather
#: than a subcollection of the licence so claiming one at sign-in is a single
#: document read: a subcollection would need a collection-group query (and its
#: index) on a path that runs for every account that does not yet hold a key.
_INVITES = "licenseInvites"


def _invite_ref(email: str):
    return db().collection(_INVITES).document(invite_id(email))


def _institution_member_patch(license_id: str, lic: dict) -> dict:
    """The user-document patch that puts someone on an institution licence.

    Shared by the two ways onto a roster — IT adding an existing account, and
    a newcomer redeeming an invite at sign-in — so the two cannot drift into
    entitling people differently.
    """
    patch = {
        **_mode_patch(MODE_LICENSED),
        "licenseId": license_id,
        "licenseKind": KIND_INSTITUTION,
        "licensePrefix": lic.get("keyPrefix") or "",
        "updatedAt": firestore.SERVER_TIMESTAMP,
    }
    patch.update(_license_mirror_patch(lic))
    return patch


def _invite_public(doc_id: str, inv: dict) -> dict:
    return {
        "id": doc_id,
        "email": inv.get("email") or "",
        "licenseId": inv.get("licenseId") or "",
        "invitedByUid": inv.get("invitedByUid") or "",
        "createdAt": inv.get("createdAt"),
    }


def invite_institution_member(
    license_id: str, email: str, invited_by_uid: str,
) -> tuple[str, dict | None]:
    """Reserve a roster place for someone who has no account yet.

    Returns (error, invite). The invite is redeemed by `claim_pending_invite`
    the first time that address signs in, which is also the moment the person
    would otherwise have been given a Demo key.

    Deliberately consumes no seat and no slot. Until a real account claims it
    there is no uid, and every entitlement check in the system is keyed by uid
    — so counting an invite against `maxSeats` would mean decrementing a count
    for a person who may never arrive. An invite is a promise; the seat is
    taken when it is kept.
    """
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        return "license_not_found", None
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    return _write_invite(license_id, email, invited_by_uid)


def _invite_is_stale(license_id: str) -> bool:
    """True when the licence an invite points at can never be claimed.

    `claim_pending_invite` deletes such an invite the moment the invited
    person signs in, so it holds no promise to anyone. Refusing a new invite
    on its behalf only makes the replacement licence undeliverable too.
    """
    if not license_id:
        return True
    lic = get_license(license_id)
    return not lic or (lic.get("status") or "active") == "revoked"


def _delete_license_invites(license_id: str, email_lock: str = "") -> None:
    """Drop the promises a revoked licence can no longer keep.

    Revoking used to leave `licenseInvites` untouched, which turned the most
    ordinary correction there is — mint against the wrong address, revoke,
    mint again for the right one — into a licence nobody could receive:
    `_write_invite` refused an address already promised elsewhere, so the
    second mint was minted and undeliverable.

    An individual licence has at most one invite, at the key derived from its
    `emailLock`, so it costs one delete guarded on `licenseId` — the same
    guard `revoke_institution_invite` uses, and for the same reason. Without
    an address to hash, and for an institution licence, it is the query
    instead, over a list that is small by construction.
    """
    if email_lock:
        ref = _invite_ref(email_lock)
        snap = ref.get()
        if snap.exists and ((snap.to_dict() or {}).get("licenseId") or "") == license_id:
            ref.delete()
        return
    for doc in db().collection(_INVITES).where("licenseId", "==", license_id).stream():
        doc.reference.delete()


def _write_invite(license_id: str, email: str,
                  invited_by_uid: str) -> tuple[str, dict | None]:
    """Record the promise itself, having already established the licence is
    one worth promising.

    Split from `invite_institution_member` because an individual licence is
    delivered the same way — mint against an address, let the person sign in —
    but reaches this point through a different set of checks. The record is
    identical either way, and `claim_pending_invite` reads the licence to
    decide what to grant, so the invite carries no notion of kind.
    """
    address = normalize_email(email)
    if not address:
        return "invalid_email", None

    ref = _invite_ref(address)
    existing = ref.get()
    if existing.exists:
        held = existing.to_dict() or {}
        # Re-inviting to the same licence is a no-op rather than an error, so
        # IT pasting a list twice is harmless. A different *live* licence is
        # refused: silently moving someone between institutions would be the
        # wrong default, and the address is the only identity we have to go
        # on. A dead one is overwritten — see `_invite_is_stale`.
        if (held.get("licenseId") or "") == license_id:
            return "", _invite_public(ref.id, held)
        if not _invite_is_stale(held.get("licenseId") or ""):
            return "invite_exists", None

    data = {
        "email": address,
        "licenseId": license_id,
        "invitedByUid": invited_by_uid,
        "createdAt": firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    ref.set(data)
    return "", _invite_public(ref.id, ref.get().to_dict() or data)


def list_institution_invites(license_id: str) -> list[dict]:
    """Outstanding invites on one licence, oldest first.

    Sorted here rather than in Firestore: ordering by `createdAt` alongside the
    `licenseId` equality would need a composite index for a list that is small
    by construction (it drains as people sign in).
    """
    rows = [
        _invite_public(doc.id, doc.to_dict() or {})
        for doc in db().collection(_INVITES).where("licenseId", "==", license_id).stream()
    ]
    rows.sort(key=lambda r: (r.get("createdAt") is None, r.get("createdAt")))
    return rows


def revoke_institution_invite(license_id: str, invite_key: str) -> bool:
    """Withdraw an unclaimed invite. False when there is none to withdraw.

    Addressed by the invite's own id — the hash `list_institution_invites`
    returns — rather than by email. That keeps addresses out of request paths
    and access logs, and it is the id the console already has in hand.

    Guarded on `licenseId` so one institution's IT cannot delete another's
    invite by guessing an id.
    """
    ref = db().collection(_INVITES).document(invite_key)
    snap = ref.get()
    if not snap.exists:
        return False
    if ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        return False
    ref.delete()
    return True


def claim_pending_invite(user: dict) -> dict:
    """Redeem a pending invite for an account that has just become usable.

    Both licence kinds arrive here. An institution invite takes a seat; an
    individual one attaches the licence itself — the two ways a licence is
    delivered without anybody typing a key, and the only two. The invite
    record does not say which; the licence it points at does, which is why
    there is one collection rather than two.

    Returns the updated user when something was claimed, otherwise the user
    unchanged. Called from `ensure_entitlement`, i.e. exactly where a Demo key
    would otherwise be minted — so an invited newcomer lands licensed on their
    first request rather than demo-then-upgraded.

    The guards are `ensure_demo_license`'s, and they bound the cost: this runs
    only for an approved, verified account that holds no licence yet, which is
    a one-request window before the Demo key exists. It is not a per-request
    read.

    Email verification is required and not merely preferred. The invite names
    an address, and the address is the whole claim to the seat; honouring an
    unverified one would let anyone who can type someone else's address take
    the institution seat meant for them.
    """
    uid = user.get("uid")
    if not uid or user.get("licenseId"):
        return user
    if user.get("access_status") != "APPROVED" or not user.get("emailVerified"):
        return user
    address = normalize_email(user.get("email"))
    if not address:
        return user

    ref = _invite_ref(address)
    snap = ref.get()
    if not snap.exists:
        return user
    license_id = ((snap.to_dict() or {}).get("licenseId") or "")
    lic = get_license(license_id) if license_id else None
    if not lic:
        # The licence was deleted out from under the invite. Drop it rather
        # than leaving a record that can never be redeemed.
        ref.delete()
        return user
    if (lic.get("status") or "active") == "revoked":
        ref.delete()
        return user

    # No device lock is passed either way: the invite predates any device
    # choice, and the lock is bound on the first authed request that carries a
    # device id — see revalidate_device_lock.
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        patch = _institution_member_patch(license_id, lic)
        err = claim_seat(license_id, uid, address, "", patch, invite_ref=ref)
    else:
        patch = _individual_member_patch(license_id, lic)
        err = claim_individual_license(license_id, uid, address, patch, invite_ref=ref)
    if err:
        # Leave the invite in place either way: seats exhausted or already
        # redeemed may be resolved by ops, and contention resolves itself on
        # the next request. Only the first two are worth a warning — logging
        # a lost race at the same level made a busy sign-in read exactly like
        # a licence with no room left.
        if err == _CONTENDED:
            log.info("invite claim lost the race uid=%s license=%s", uid, license_id)
        else:
            log.warning("invite claim failed uid=%s license=%s err=%s", uid, license_id, err)
        # Answer with the account as stored, not with the caller's copy. The
        # copy was read before this request began, and the request that beat
        # us to the claim has already granted the entitlement; returning the
        # stale dict serves one request as demo to someone who is licensed.
        # `ensure_demo_license` usually rescues this by re-reading, but it
        # returns early when there is no device id to mint against — which is
        # every browser request, since the consoles send no `X-Device-Id`.
        stored = db().collection("users").document(uid).get()
        if not stored.exists:
            return user
        return {**user, **(stored.to_dict() or {}), "uid": uid}
    return {**user, **patch, "uid": uid}


def ensure_entitlement(user: dict, device_id: str | None) -> dict:
    """Give a newly-usable account whatever it is entitled to.

    An institution seat it was invited to, if there is one, else a Demo key.
    The order is the point: `ensure_demo_license` stamps a `licenseId`, and
    every later call short-circuits on that field, so minting Demo first would
    strand the invite permanently.
    """
    claimed = claim_pending_invite(user)
    if claimed.get("licenseId"):
        return claimed
    return ensure_demo_license(claimed, device_id)


def add_institution_member(license_id: str, email: str,
                           invited_by_uid: str = "") -> tuple[str, dict | None, dict | None]:
    """Put someone on an institution license's roster. Returns (error, seat, invite).

    On an assigned license this entitles them immediately. On a floating one
    it makes them eligible; they still check out a lease to work, and adding
    a member therefore consumes no slot.

    Exactly one of `seat` and `invite` is set on success. An address with no
    account yet becomes a pending invite rather than a `user_not_found` error:
    IT works from a list of addresses and cannot make people sign up first, so
    refusing them was pushing a scheduling problem onto the wrong person.

    Idempotent for an active seat, and re-adding someone previously revoked
    gives them a fresh slot. A *disabled* seat is refused with
    `license_seat_disabled` — disable is a deliberate hold that IT lifts with
    `enabled=true`, and silently undoing it here would make the two routes
    fight over the same state.
    """
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None, None
    user = find_user_by_email(email)
    if not user:
        err, invite = invite_institution_member(license_id, email, invited_by_uid)
        return err, None, invite
    uid = user["uid"]

    # No device lock: IT adds a member before that member has picked a device,
    # and the lock is set the first time they actually use the license.
    err = claim_seat(license_id, uid, user.get("email") or email, "",
                     _institution_member_patch(license_id, lic))
    if err:
        return _public_claim_error(err, "license_seats_exhausted"), None, None
    seats = list_institution_seats(license_id)
    return "", next((s for s in seats if s["uid"] == uid), None), None


def institution_license_summary(license_id: str) -> dict | None:
    """Public (no key plaintext) summary of one institution license, for IT
    self-service — same redaction as the Semper-staff admin listing, scoped to
    callers who already passed the adminEmails membership check."""
    lic = get_license(license_id)
    return _license_public(license_id, lic) if lic else None


def list_institution_seats(license_id: str) -> list[dict]:
    out = []
    for doc in db().collection("licenses").document(license_id).collection("seats").stream():
        s = doc.to_dict() or {}
        out.append({
            "uid": doc.id,
            "email": s.get("email") or "",
            "deviceIdLock": s.get("deviceIdLock") or "",
            "status": s.get("status") or "active",
            # The lease is the point of the floating roster view: without it
            # IT cannot see who is actually using a seat right now, only who
            # is allowed to. Null on an assigned licence, which has no leases.
            "leaseExpiresAt": s.get("leaseExpiresAt"),
            "lastHeartbeatAt": s.get("lastHeartbeatAt"),
            "createdAt": s.get("createdAt"),
            "updatedAt": s.get("updatedAt"),
        })
    return out


#: Who asked for a device change. Only the holder is rate-limited; see
#: `clear_device_lock`.
ACTOR_SELF = "self"
ACTOR_STAFF = "staff"
ACTOR_IT = "it"


def _restore_holder_mode(license_id: str, lic: dict, ref, scope: str, uid: str) -> None:
    """Give the holder their mode back now that the lock they missed is gone.

    A device change is usually preceded by the holder trying the new device:
    `revalidate_device_lock` finds the mismatch and demotes the account in
    place, writing `mode: demo` onto the user document. Clearing the lock
    afterwards would not undo that on its own — `revalidate_device_lock`
    returns early for an account that reads as demo, so it would never reach
    the bind branch and the holder would sit on Demo holding a live licence.
    Re-stamping the mode here is what makes clearing the lock the whole
    device change rather than half of one.

    Nothing is resurrected. The write is skipped for a revoked licence, for a
    seat that is revoked or on hold, and for an account that has since moved
    to a different licence; and `effective_mode` still re-applies expiry,
    grace and the floating-lease check to whatever is written here, so a
    licence that has run out stays demo either way.

    Deliberately outside the caller's transaction: reading `users/{uid}` and
    then writing it inside one takes a lock on that document, which is what
    starved out concurrent claims before `_drop_superseded_demo` moved the
    same guarded read out of `claim_seat`.
    """
    if (lic.get("status") or "") == "revoked":
        return
    if scope == "seat":
        seat = ref.get()
        if not seat.exists or (seat.to_dict() or {}).get("status") != "active":
            return
        holder = uid
    else:
        holder = lic.get("redeemedByUid") or ""
    if not holder:
        return
    user_ref = db().collection("users").document(holder)
    snap = user_ref.get()
    if not snap.exists or (snap.to_dict() or {}).get("licenseId") != license_id:
        return
    user_ref.update({
        **_mode_patch(_license_mode(lic)),
        "updatedAt": firestore.SERVER_TIMESTAMP,
    })


def clear_device_lock(license_id: str, uid: str = "", *,
                      actor: str = ACTOR_STAFF) -> tuple[str, dict | None]:
    """Unbind a licence or a seat from the device it is on. Error code, or "".

    One primitive with three callers — Semper staff, institution IT, and the
    holder — because there is one operation. Since Gap A, clearing the lock is
    the *whole* device change: an empty lock reads as `_LOCK_UNBOUND`, and
    `revalidate_device_lock` binds it to whatever signs in next, first writer
    wins. Nothing is re-activated and nothing is typed.

    **Clearing is not revoking.** Entitlement, seat, lease and data are all
    untouched; only the lock goes empty. A holder demoted in place by the
    mismatch they hit on the new device gets their mode back here — see
    `_restore_holder_mode`, without which clearing would be half a device
    change.

    `uid` selects the seat on an institution licence. An individual licence
    holds its lock on the licence document itself, so `uid` is ignored there.

    The cooldown applies to `ACTOR_SELF` alone. A second factor proves *who*
    is asking, not *how often*, so one person could otherwise re-bind daily
    and pass a single licence round a lab. It is counted against
    `deviceChangedAt`, which only this path writes: a staff or IT clear
    neither reads nor writes that stamp, so a support request always works
    however recently the holder changed device themselves.

    On success the second element is the audit detail, including the device
    that was given up — the other half of the record `revalidate_device_lock`
    writes when the replacement binds.
    """
    lic_snap = db().collection("licenses").document(license_id).get()
    if not lic_snap.exists:
        return errors.LICENSE_NOT_FOUND, None
    lic = lic_snap.to_dict() or {}
    if normalize_kind(lic.get("kind")) == KIND_INSTITUTION:
        if not uid:
            return errors.SEAT_NOT_FOUND, None
        ref, scope = _seat_ref(license_id, uid), "seat"
    else:
        ref, scope = db().collection("licenses").document(license_id), "license"

    detail = {"scope": scope, "licenseId": license_id, "uid": uid, "actor": actor}
    not_found = errors.SEAT_NOT_FOUND if scope == "seat" else errors.LICENSE_NOT_FOUND

    if actor != ACTOR_SELF:
        # No invariant to protect: staff and IT have no cooldown, and two
        # clears landing together produce the same empty lock. A plain read
        # and update keeps the support path out of the transaction machinery
        # entirely.
        snap = ref.get()
        if not snap.exists:
            return not_found, None
        previous = (snap.to_dict() or {}).get("deviceIdLock") or ""
        ref.update({"deviceIdLock": "", "updatedAt": firestore.SERVER_TIMESTAMP})
        _restore_holder_mode(license_id, lic, ref, scope, uid)
        return "", {**detail, "previousDeviceId": previous}

    now = _now()
    cooldown = timedelta(days=max(0, settings.SELF_DEVICE_CHANGE_COOLDOWN_DAYS))
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _clear(tx) -> tuple[str, dict | None]:
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return not_found, None
        doc = snap.to_dict() or {}
        changed = as_utc(doc.get("deviceChangedAt"))
        if cooldown and changed and now - changed < cooldown:
            return errors.DEVICE_CHANGE_TOO_SOON, None
        tx.update(ref, {
            "deviceIdLock": "",
            "deviceChangedAt": now,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
        return "", {
            **detail,
            "previousDeviceId": doc.get("deviceIdLock") or "",
            "nextChangeAllowedAt": (now + cooldown).isoformat() if cooldown else "",
        }

    try:
        err, cleared = _clear(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # Two self-service clears at once is the only way to reach this, and
        # the cooldown is exactly what one of them must lose.
        return errors.DEVICE_CHANGE_TOO_SOON, None
    if not err:
        _restore_holder_mode(license_id, lic, ref, scope, uid)
    return err, cleared


def set_seat_enabled(license_id: str, uid: str, enabled: bool) -> bool:
    """Disable drops the seat holder to Demo but does NOT free the slot — the
    seat still counts against maxSeats so IT can re-enable without a fresh
    activation. Enable restores the licensed mode in place, no data migration."""
    ref = _seat_ref(license_id, uid)
    snap = ref.get()
    if not snap.exists:
        return False
    ref.update({
        "status": "active" if enabled else "disabled",
        "updatedAt": firestore.SERVER_TIMESTAMP,
    })
    if not enabled:
        _drop_user_to_demo_if_licensed(uid, license_id)
    else:
        seat = snap.to_dict() or {}
        user_ref = db().collection("users").document(uid)
        user_snap = user_ref.get()
        if user_snap.exists and (user_snap.to_dict() or {}).get("licenseId") == license_id:
            user_ref.update({
                **_mode_patch(MODE_LICENSED),
                "updatedAt": firestore.SERVER_TIMESTAMP,
            })
        del seat
    return True


def revoke_institution_seat(license_id: str, uid: str) -> bool:
    """Single-seat revoke: drops the holder to Demo and frees the slot
    (decrements seatsUsed) so another roster member can take it.

    Transactional for the same reason `claim_seat` is, and against the mirror
    image of its race: two concurrent revokes of one seat both read a status
    that is not yet "revoked", both decrement, and the pool undercounts by one
    forever. A releasable lease is dropped in the same commit — a revoked seat
    must not keep occupying a floating slot.
    """
    lic_ref = db().collection("licenses").document(license_id)
    seat_ref = _seat_ref(license_id, uid)
    transaction = db().transaction(max_attempts=_TX_ATTEMPTS)

    @firestore.transactional
    def _revoke(tx) -> bool:
        seat_snap = seat_ref.get(transaction=tx)
        if not seat_snap.exists:
            return False
        seat = seat_snap.to_dict() or {}
        if seat.get("status") == "revoked":
            return True  # idempotent: already revoked, counters already settled
        lic_snap = lic_ref.get(transaction=tx)
        seats_used = int((lic_snap.to_dict() or {}).get("seatsUsed") or 0) if lic_snap.exists else 0
        held_lease = _seat_lease_live(seat)

        tx.update(seat_ref, {
            "status": "revoked",
            # Stamped separately from `updatedAt` because reconciliation asks
            # "has the holder been back since the revoke?", and `updatedAt`
            # moves for any later write to the seat (a staff device clear, for
            # one) which would quietly reset that question.
            "revokedAt": firestore.SERVER_TIMESTAMP,
            **_lease_clear_patch(),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        })
        counters = {}
        if seats_used > 0:
            counters["seatsUsed"] = firestore.Increment(-1)
        if held_lease:
            counters["leasesActive"] = firestore.Increment(-1)
        if counters and lic_snap.exists:
            tx.update(lic_ref, counters)
        return True

    try:
        revoked = _revoke(transaction)
    except Exception as exc:  # noqa: BLE001
        if not _lost_to_contention(exc):
            raise
        # The other writer won and did the same thing. Re-read to answer
        # precisely rather than reporting a failure that did not happen.
        revoked = seat_ref.get().exists
    if revoked:
        _drop_user_to_demo_if_licensed(uid, license_id)
    return revoked


# ---------------- reconciliation: intended seats vs entitled accounts ----------------
# IT's roster is a statement of intent. `seatsUsed` moves the instant a seat is
# revoked, so the institution console can only ever show what IT *meant* to
# happen. What actually happened is spread across two other places: the
# holder's user document, which `revoke_institution_seat` demotes outside its
# transaction and therefore best-effort, and the holder's device, which keeps
# working off a cached /v1/config until it next comes back. Reconciliation is
# the read that puts all three side by side.

#: Why a revoked seat is still counted as running. Returned per seat, because
#: the two causes want opposite responses: one is a fault to repair, the other
#: a lag to wait out.
STILL_LICENSED = "still_licensed"
NO_CHECKIN_SINCE_REVOKE = "no_checkin_since_revoke"
#: Why a revoked seat is settled.
MOVED_ON = "moved_on"
NO_ACCOUNT = "no_account"
CHECKED_IN = "checked_in"
#: An active seat nobody has taken up. Counted inside `active`, not against it.
NEVER_CLAIMED = "never_claimed"


def _seat_revoked_at(seat: dict):
    """When the seat was revoked, as well as we can know it.

    Both revoke paths stamp `revokedAt`. A seat revoked before that field
    existed has only `updatedAt`, which for a revoked seat is the same instant
    — nothing writes to one again in the ordinary course.
    """
    return seat.get("revokedAt") or seat.get("updatedAt")


def reconcile_institution_seats(license_id: str) -> tuple[str, dict | None]:
    """Compare every seat on an institution licence against its holder.

    Returns `(error_code, report)`; exactly one of the two is set.

    Three buckets, and the one that matters is the third:

    * **active** — the seat is on the roster. `neverClaimed` counts the subset
      nobody has signed in to take up; they are invited, not entitled.
    * **revokedConfirmed** — the seat is revoked and the revoke has landed:
      the account moved on, never existed, or is demoted *and* has made a
      request since, which is when its device last re-read its entitlement.
    * **revokedStillRunning** — the seat is revoked and something is still
      entitled by it. `still_licensed` means the demotion never landed and the
      backend itself would answer "licensed"; revoking the seat again repairs
      it, the operation being idempotent and re-running the demotion.
      `no_checkin_since_revoke` means the record is right and the device has
      simply not been back to hear it — nothing to repair, and Gap B's
      periodic refresh is what shortens it.

    Costs one read per seat, which is why it is admin-tier and on demand
    rather than a field on any hot path.
    """
    lic_snap = db().collection("licenses").document(license_id).get()
    if not lic_snap.exists:
        return errors.LICENSE_NOT_FOUND, None
    lic = lic_snap.to_dict() or {}
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        # An individual licence has one redeemer and no roster, so there are
        # no two counts to reconcile; saying so beats returning an empty
        # report that reads like a clean bill of health.
        return errors.KIND_NOT_INSTITUTION, None

    seats, counts = [], {
        "active": 0, "neverClaimed": 0,
        "revokedConfirmed": 0, "revokedStillRunning": 0,
    }
    entitled = 0
    for seat_doc in db().collection("licenses").document(license_id) \
            .collection("seats").stream():
        seat = seat_doc.to_dict() or {}
        uid = seat.get("uid") or seat_doc.id
        user_snap = db().collection("users").document(uid).get()
        user = user_snap.to_dict() if user_snap.exists else None

        on_this_license = bool(user) and user.get("licenseId") == license_id
        holds = on_this_license and _stored_mode(user) == MODE_LICENSED
        revoked_at = _seat_revoked_at(seat)
        last_seen = user.get("lastSeenAt") if user else None

        if seat.get("status") != "revoked":
            bucket = "active"
            reason = "" if holds else NEVER_CLAIMED
            if not holds:
                counts["neverClaimed"] += 1
        elif holds:
            bucket, reason = "revokedStillRunning", STILL_LICENSED
        elif not user:
            bucket, reason = "revokedConfirmed", NO_ACCOUNT
        elif not on_this_license:
            bucket, reason = "revokedConfirmed", MOVED_ON
        elif _seen_since(last_seen, revoked_at):
            bucket, reason = "revokedConfirmed", CHECKED_IN
        else:
            bucket, reason = "revokedStillRunning", NO_CHECKIN_SINCE_REVOKE

        counts[bucket] += 1
        if holds:
            entitled += 1
        seats.append({
            "uid": uid,
            "email": seat.get("email") or (user or {}).get("email") or "",
            "status": seat.get("status") or "active",
            "bucket": bucket,
            "reason": reason,
            "revokedAt": revoked_at if seat.get("status") == "revoked" else None,
            "lastSeenAt": last_seen,
            "userMode": _stored_mode(user) if user else "",
            "userLicenseId": (user or {}).get("licenseId") or "",
        })

    intended = int(lic.get("seatsUsed") or 0)
    return "", {
        "licenseId": license_id,
        "maxSeats": int(lic.get("maxSeats") or 0),
        # What IT believes, straight off the counter their console reads...
        "intended": intended,
        # ...what the counter would say if recounted from the seats themselves.
        # A difference between these two is a counter drift, a different fault
        # from anything the buckets describe.
        "intendedRecounted": counts["active"],
        # Accounts the backend would answer "licensed" for under this licence
        # right now. Equals active - neverClaimed + revokedStillRunning's
        # still_licensed half, by construction.
        "entitled": entitled,
        "counts": counts,
        "seats": seats,
    }


def _seen_since(last_seen, revoked_at) -> bool:
    """Has the account made a request since the seat was revoked?

    Conservative in one direction on purpose. `lastSeenAt` is throttled to
    `_LAST_SEEN_THROTTLE`, so a request made shortly after a revoke may not
    have moved the stamp yet and the seat reads as not-checked-in for up to
    that long. Over-reporting a revoke as unlanded is the safe way to be
    wrong; the opposite would tell an operator a device had been told when it
    had not.
    """
    last_seen, revoked_at = as_utc(last_seen), as_utc(revoked_at)
    if last_seen is None:
        return False
    if revoked_at is None:
        # No stamp at all: the seat predates both fields. We cannot date the
        # revoke, so we cannot claim the account has been back since it.
        return False
    return last_seen > revoked_at


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
    user = _load_user(uid)
    if user:
        ensure_entitlement(user, body.deviceId)
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


def list_session_artifacts(sid: str, *, page_size: int = 200) -> list:
    """Every *uploaded* file in a session, with the Drive id needed to read it.

    Separate from `list_session_files_all` on purpose. That projection feeds
    `GET /v1/me/export`, where a Drive file id is a handle to bytes the caller
    is not being handed and so is deliberately withheld; this one exists only
    for code that is about to fetch those bytes on the caller's behalf. Keeping
    them apart means adding a field here can never widen the export.

    Pending files are skipped: they have no `driveFileId` yet, and an archive
    is of what was stored, not of what was promised.
    """
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
            if f.get("status") != statuses.FILE_COMPLETED or not f.get("driveFileId"):
                continue
            out.append({
                "fileId": d.id,
                "name": f.get("name"),
                "role": f.get("role"),
                "sizeBytes": f.get("sizeBytes", 0),
                "sha256": f.get("sha256"),
                "driveFileId": f.get("driveFileId"),
                "createdAt": f.get("createdAt"),
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
