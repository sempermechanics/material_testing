"""License key format and hashing. Firestore persistence lives in firestore_repo."""
import hashlib
import re
import secrets

PLAN_DEMO = "demo"
PLAN_PROFESSIONAL = "professional"
PLANS = frozenset({PLAN_DEMO, PLAN_PROFESSIONAL})

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
