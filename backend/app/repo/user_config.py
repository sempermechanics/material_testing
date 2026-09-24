"""What an account may do: its mode, expiry and grace, and the resolved product limits.
"""

from ..config import settings
from ..licenses import (
    SEATING_FLOATING,
    MODE_DEMO,
    MODE_LICENSED,
    MODES,
    as_utc,
    grace_ends_at,
    legacy_plan,
    normalize_duration,
    normalize_kind,
    normalize_mode,
    normalize_seating,
)

from . import _base
from ._base import (
    db,
    _mode_patch,
    _now,
)


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

    `cloudBackupEnabled` is the *retrieval* entitlement — restore and the
    session bundle. Recording an analysis (session create + upload) is open to
    every approved account under `maxSessions`, so a demo account's frames and
    results are stored, and what a licence adds is getting them back. The app
    reads the flag the same way: it decides whether backup/restore UI exists,
    never whether an analysis is uploaded.

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
    """May this account read its stored analyses back (restore, bundle)?

    Not consulted on the recording path — see `resolve_user_config`."""
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
    deletes = {k: _base.firestore.DELETE_FIELD for k in allowed if k in patch and patch[k] is None}
    if "mode" in deletes:
        deletes["plan"] = _base.firestore.DELETE_FIELD
    if update or deletes:
        ref.update({**update, **deletes, "updatedAt": _base.firestore.SERVER_TIMESTAMP})
    user = {**(snap.to_dict() or {}), **update, "uid": uid}
    for k in deletes:
        user.pop(k, None)
    return resolve_user_config(user)
