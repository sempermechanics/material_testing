"""The Android apps that share this backend, and the device fields each one owns.

Semper and Semper Classroom sign in to the same accounts, but Android scopes
`ANDROID_ID` to the signing key, so one phone reports a different device id to
each app. An account binds one phone **per app** ([ADR-010]): each app has its
own registered device, release hold, licence or seat lock, and self-service
device-change cooldown.

The app is named by the `X-App-Id` header, the app's `applicationId`. No
header is Semper, so every build shipped before the header keeps its binding.
An id not listed here is refused (`unknown_app`) rather than read as Semper:
reading it as Semper would let a build nobody registered take Semper's slot.

Semper's slot is the fields the account has always had (`activeDeviceId`,
`deviceIdLock`, ...), so nothing is migrated. Another app's slot is the same
names with a suffix (`activeDeviceIdSemperClassroom`): flat fields, so every
existing single-field update, query and transaction reads them unchanged.

Semper Classroom was Material Testing until 2026-10 ([ADR-030]). Its short
name was `materialtesting` and its suffix `MaterialTesting`. A backend from
before the rename knows only those, and one can serve beside this one while a
deploy shifts traffic, or again after a rollback. So the old names stay the
stored truth until TD-56 retires them:

- `stored_name` is the short name written to `devices/{id}.app` and
  `sessions/{sid}.app`: still `materialtesting`, which every backend reads;
- `canonical` reads a stored or sent short name as the app it is today;
  compare stored `app` values through it (`same_app`), never with `==`, and
  answer with it;
- `read_field` reads the old field only: every backend writes and deletes it,
  so it is always current, while a new field a previous backend did not
  delete may be stale;
- `written_fields` writes both, so the new fields fill as accounts change, and
  `scripts/rename_classroom_app.py` fills the rest before TD-56 moves reads
  and tags to the new names.

The header is a claim until App Check enforces the token's `app_id`. A
modified Semper build that calls itself Semper Classroom takes that app's
slot, which is one more phone and no more (ADR-010, Trade-offs).

[ADR-010]: ../../docs/adr/ADR-010-device-binding-per-app.md
[ADR-030]: ../../docs/adr/ADR-030-semper-classroom-app-identity.md
"""
from __future__ import annotations

SEMPER = "semper"
SEMPER_CLASSROOM = "semperclassroom"

#: Material Testing's short name before the rename (ADR-030). Old builds still
#: pin it in their offline entitlement (`token_claim`), and stored records still
#: hold it, so it is read as `SEMPER_CLASSROOM` everywhere (`canonical`).
LEGACY_MATERIAL_TESTING = "materialtesting"

#: Every app, Semper first. Staff and IT clears release all of them: the
#: holder's phone changed, and it changed for every app on it.
ALL = (SEMPER, SEMPER_CLASSROOM)

_LEGACY_NAMES = {LEGACY_MATERIAL_TESTING: SEMPER_CLASSROOM}

#: The short name stored for each app while TD-56 is open (`stored_name`).
_STORED_NAMES = {SEMPER_CLASSROOM: LEGACY_MATERIAL_TESTING}

#: Both apps moved from `com.indicvision.*` to `com.sempermechanics.*`
#: (ADR-019), and Material Testing became Semper Classroom (ADR-030). The old
#: ids stay until no installed build sends them; each maps to the same app, so
#: a phone keeps its slot and its backups across the move.
_BY_APPLICATION_ID = {
    "com.sempermechanics.semper": SEMPER,
    "com.sempermechanics.semperclassroom": SEMPER_CLASSROOM,
    "com.sempermechanics.materialtesting": SEMPER_CLASSROOM,
    "com.indicvision.semper": SEMPER,
    "com.indicvision.semper.materialtesting": SEMPER_CLASSROOM,
}

#: The short name each build verifies its offline entitlement against
#: (`EntitlementPins.kt`): a build that predates the rename refuses a token
#: that names `semperclassroom` as WRONG_APP, so it is sent the name it pins.
_TOKEN_CLAIM_BY_APPLICATION_ID = {
    "com.sempermechanics.materialtesting": LEGACY_MATERIAL_TESTING,
    "com.indicvision.semper.materialtesting": LEGACY_MATERIAL_TESTING,
}

#: The old ids may no longer register a device (410 `app_retired`). Android
#: gives every app signed with one key the same `ANDROID_ID`, so a retired
#: build still installed beside its successor reports the same device id, and
#: its registration replaced the successor's key on `devices/{id}`: every
#: signed call from the new app then failed `bad_signature` (2026-10-10).
#: Semper Classroom is signed with Material Testing's key, so its old id is
#: retired with the rename. Its other calls are served as before, so the access
#: log still shows when no build sends an old id (TD-174, TD-56).
RETIRED = frozenset({
    "com.indicvision.semper",
    "com.indicvision.semper.materialtesting",
    "com.sempermechanics.materialtesting",
})

_SUFFIX = {
    SEMPER: "",
    SEMPER_CLASSROOM: "SemperClassroom",
}

#: The suffix each app's fields had before the rename, where it changed.
_LEGACY_SUFFIX = {
    SEMPER_CLASSROOM: "MaterialTesting",
}


def from_header(value: str | None) -> str | None:
    """The app an `X-App-Id` header names: Semper when it is absent, None
    when it names an app this backend does not know."""
    value = (value or "").strip()
    if not value:
        return SEMPER
    return _BY_APPLICATION_ID.get(value)


def is_retired(value: str | None) -> bool:
    """Whether an `X-App-Id` header names a retired app id (`RETIRED`)."""
    return (value or "").strip() in RETIRED


def token_claim(value: str | None) -> str | None:
    """The `app` claim of an entitlement token for the build an `X-App-Id`
    header names: its short name as that build pins it. None when unknown."""
    value = (value or "").strip()
    if value in _TOKEN_CLAIM_BY_APPLICATION_ID:
        return _TOKEN_CLAIM_BY_APPLICATION_ID[value]
    return from_header(value)


def logged_application_id(value: str | None) -> str:
    """The `X-App-Id` header as the access log records it: the id when this
    backend knows it, `none` when absent, `unknown` otherwise. The header is
    caller text, so only a listed id reaches the log (TD-174: it shows when no
    build sends a `com.indicvision.*` id any more)."""
    value = (value or "").strip()
    if not value:
        return "none"
    return value if value in _BY_APPLICATION_ID else "unknown"


def canonical(value: str | None) -> str:
    """A stored or sent short name as the app it is today: Semper when empty
    (a record from before ADR-014's tag), `semperclassroom` for
    `materialtesting`, and anything else unchanged, so an unknown value stays
    unknown (`value in ALL` is False)."""
    value = (value or "").strip().lower()
    if not value:
        return SEMPER
    return _LEGACY_NAMES.get(value, value)


def stored_name(app: str) -> str:
    """The short name to store on a record for `app`: Semper Classroom's old
    name while TD-56 is open, since a backend from before the rename reads
    `semperclassroom` as an unknown app (`bad_signature` on its device, its
    sessions hidden from it)."""
    app = canonical(app)
    return _STORED_NAMES.get(app, app)


def same_app(a: str | None, b: str | None) -> bool:
    """Whether two short names, stored or sent, name the same app."""
    return canonical(a) == canonical(b)


def from_name(value: str | None) -> str | None:
    """An app named by its short name (`semper`, `semperclassroom`, or the old
    `materialtesting`), as the consoles send it. Semper when empty, None when
    unknown."""
    value = canonical(value)
    return value if value in _SUFFIX else None


def field(name: str, app: str) -> str:
    """The field that holds `name` for `app`: `name` itself for Semper."""
    return name + _SUFFIX[canonical(app)]


def legacy_field(name: str, app: str) -> str | None:
    """The field that held `name` for `app` before ADR-030, or None when the
    app's field name did not change."""
    suffix = _LEGACY_SUFFIX.get(canonical(app))
    return None if suffix is None else name + suffix


def read_field(doc: dict | None, name: str, app: str, default=None):
    """`name` for `app` from a record, else `default`. For a renamed field that
    is the old field alone: every backend writes and deletes it, this one
    (`written_fields`) and one from before the rename, so it is always current.
    The new field is not read, since a previous backend that deleted the old
    one left the new one behind, stale. TD-56 moves reads to the new field
    once no previous backend can serve."""
    doc = doc or {}
    return doc.get(legacy_field(name, app) or field(name, app), default)


def written_fields(name: str, app: str, value) -> dict:
    """The update that sets `name` for `app` to `value`: the new field and,
    while TD-56 is open, the old one as a mirror. `value` may be a Firestore
    sentinel (`DELETE_FIELD`, `SERVER_TIMESTAMP`); both fields get it."""
    out = {field(name, app): value}
    old = legacy_field(name, app)
    if old is not None:
        out[old] = value
    return out


def spread(name: str, values: dict[str, str]) -> dict[str, str]:
    """`{app: value}` as the per-app fields a response or audit record carries,
    every app present: `{"releasedDeviceId": ..., "releasedDeviceIdSemperClassroom": ...}`.
    Semper's value keeps the name callers already read. Each renamed field is
    mirrored under its old name, for a console from before the rename."""
    values = {canonical(app): value for app, value in values.items()}
    out: dict[str, str] = {}
    for app in ALL:
        out.update(written_fields(name, app, values.get(app) or ""))
    return out
