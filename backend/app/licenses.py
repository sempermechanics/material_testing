"""License key format, hashing, and the entitlement-mode vocabulary.

Firestore persistence lives in firestore_repo.

`mode` is the account's enforcement state: `licensed` (a valid, unexpired
license is attached) or `demo` (everything else — no license, expired past
grace, revoked, or an institution member holding no seat). It replaced the
older `plan` field, whose `professional` value is the same state under the
previous name.

`duration` is orthogonal to `kind`: a license of either kind may be perpetual
or timed. A timed license stops granting use at `expiresAt` plus `graceDays`;
entitlements are unchanged during grace, so a renewal that lands late does not
interrupt work. A perpetual one never stops.

`seating` is orthogonal to both, and applies to institution licenses. An
`assigned` license entitles every member of its roster. A `floating` one
separates the roster from the count: any member may use the license, but only
`maxSeats` hold a live lease at a time, and a member without one is demo
rather than blocked.

The wire keeps BOTH names for now. An installed app decodes `plan` and fails
closed to Demo when it is absent (see LicenseEntitlements on Android), so
dropping `plan` from a response would silently demote every user in the fleet.
`legacy_plan()` produces the mirror; remove it only once the fleet has moved to
a build that reads `mode`.
"""
import hashlib
import re
import secrets
from datetime import datetime, timedelta, timezone

MODE_DEMO = "demo"
MODE_LICENSED = "licensed"
MODES = frozenset({MODE_DEMO, MODE_LICENSED})

#: Pre-rename wire values, still emitted alongside `mode`. `professional` and
#: `licensed` denote the same state.
LEGACY_PLAN_DEMO = "demo"
LEGACY_PLAN_PROFESSIONAL = "professional"

KIND_INDIVIDUAL = "individual"
KIND_INSTITUTION = "institution"
KINDS = frozenset({KIND_INDIVIDUAL, KIND_INSTITUTION})

#: Pre-rename value for KIND_INSTITUTION, accepted on read.
LEGACY_KIND_CAMPUS = "campus"

#: How long a license grants use for. Orthogonal to `kind` — an individual or
#: an institution license may be either shape.
#:   perpetual — never stops granting use. May still carry `supportUntil`,
#:               which is informational and never gates anything.
#:   timed     — stops at `expiresAt`, plus `graceDays`.
DURATION_PERPETUAL = "perpetual"
DURATION_TIMED = "timed"
DURATIONS = frozenset({DURATION_PERPETUAL, DURATION_TIMED})

#: How an institution license allocates its seats. Orthogonal to `duration`.
#:   assigned — a seat is claimed once and held until IT removes it. Every
#:              member on the roster is entitled, so roster size IS the count.
#:   floating — the roster and the slot count are separate: any member may
#:              use the license, but only `maxSeats` hold a live lease at
#:              once. A member without a lease is demo, not blocked.
#: An individual license is always `assigned` — one person, one seat.
SEATING_ASSIGNED = "assigned"
SEATING_FLOATING = "floating"
SEATINGS = frozenset({SEATING_ASSIGNED, SEATING_FLOATING})


def legacy_plan(mode: str) -> str:
    """The `plan` value that denotes `mode`, for the dual-keyed wire response."""
    return LEGACY_PLAN_PROFESSIONAL if mode == MODE_LICENSED else LEGACY_PLAN_DEMO


def normalize_mode(raw) -> str:
    """Coerce a stored `mode` or legacy `plan` value to a mode. Unknown → demo.

    Accepts both vocabularies so a user document written before migration 002
    resolves correctly during the dual-read window.
    """
    value = raw.strip().lower() if isinstance(raw, str) else ""
    if value == LEGACY_PLAN_PROFESSIONAL:
        return MODE_LICENSED
    return value if value in MODES else MODE_DEMO


def normalize_kind(raw) -> str:
    """Coerce a stored `kind` to the current vocabulary. Unknown → individual."""
    value = raw.strip().lower() if isinstance(raw, str) else ""
    if value == LEGACY_KIND_CAMPUS:
        return KIND_INSTITUTION
    return value if value in KINDS else KIND_INDIVIDUAL


def normalize_duration(raw, *, has_expiry: bool) -> str:
    """Coerce a stored `duration`, inferring it when the field is absent.

    Licenses minted before duration existed carry no such field, so infer from
    whether they have an expiry at all. That is exactly the distinction the
    field makes explicit, so the inference is lossless — it exists to spare a
    migration, not to guess.
    """
    value = raw.strip().lower() if isinstance(raw, str) else ""
    if value in DURATIONS:
        return value
    return DURATION_TIMED if has_expiry else DURATION_PERPETUAL


def normalize_seating(raw) -> str:
    """Coerce a stored `seating`. Unknown or absent → assigned.

    Every license minted before floating existed allocated permanently held
    seats, which is exactly `assigned` — so the default is not a guess, it is
    what those documents already mean. No migration needed.
    """
    value = raw.strip().lower() if isinstance(raw, str) else ""
    return value if value in SEATINGS else SEATING_ASSIGNED


def as_utc(value):
    """A tz-aware UTC datetime, or None if `value` is not a datetime.

    `expiresAt` reaches Firestore from a client-supplied payload, so a naive
    datetime can be persisted. Every comparison against `_now()` has to coerce
    first or raise TypeError; doing it in one place keeps that from being
    re-derived at each call site.
    """
    if not isinstance(value, datetime):
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def grace_ends_at(expires_at, grace_days: int):
    """When entitlement actually stops: `expiresAt` plus the grace window.

    None for a license with no expiry (perpetual), which never stops.
    """
    expiry = as_utc(expires_at)
    if expiry is None:
        return None
    return expiry + timedelta(days=max(0, int(grace_days or 0)))


# Ambiguous 0/O/1/I omitted so support can read a key over the phone.
_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
_PREFIX = "SEMP"
_GROUPS = 4
_GROUP_LEN = 4


def generate_key() -> str:
    """Return a new `SEMP-XXXX-XXXX-XXXX-XXXX` key."""
    groups = [
        "".join(secrets.choice(_ALPHABET) for _ in range(_GROUP_LEN))
        for _ in range(_GROUPS)
    ]
    return f"{_PREFIX}-" + "-".join(groups)


def canonicalize(key: str) -> str:
    """Uppercase and strip separators so typed keys match the stored hash."""
    return re.sub(r"[^A-Za-z0-9]", "", (key or "")).upper()


def key_hash(key: str) -> str:
    canonical = canonicalize(key)
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def key_prefix(key: str) -> str:
    """First displayed group (`SEMP-ABCD`) for support lookup without the secret."""
    canonical = canonicalize(key)
    body = canonical[len(_PREFIX):] if canonical.startswith(_PREFIX) else canonical
    return f"{_PREFIX}-{body[:_GROUP_LEN]}" if body else _PREFIX
