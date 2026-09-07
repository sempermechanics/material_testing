"""License key format, hashing, and the entitlement-mode vocabulary.

Firestore persistence lives in firestore_repo.

`mode` is the account's enforcement state: `licensed` (a valid, unexpired
license is attached) or `demo` (everything else — no license, expired past
grace, revoked, or an institution member holding no seat). It replaced the
older `plan` field, whose `professional` value is the same state under the
previous name.

The wire keeps BOTH names for now. An installed app decodes `plan` and fails
closed to Demo when it is absent (see LicenseEntitlements on Android), so
dropping `plan` from a response would silently demote every user in the fleet.
`legacy_plan()` produces the mirror; remove it only once the fleet has moved to
a build that reads `mode`.
"""
import hashlib
import re
import secrets

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
