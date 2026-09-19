"""Rename the licensing vocabulary: `campus` -> `institution`, `plan` -> `mode`.

Two field renames, applied to the documents that carry the old spellings:

  users/{uid}       plan: professional|demo  ->  mode: licensed|demo
                    licenseKind: campus      ->  institution
  licenses/{id}     plan: professional|demo  ->  mode: licensed|demo
                    kind: campus             ->  institution

Both old fields are LEFT IN PLACE and kept consistent with the new ones. The
`plan` mirror is what an installed Android build reads; it fails closed to Demo
when the key is absent, so deleting it here would demote every licensed account
in the fleet the moment this ran. Removing the mirror is a later migration,
gated on adoption of a `mode`-reading app build — see licenses.legacy_plan.

`licenses` is not in migration 001's walk list, so license documents are at
schemaVersion 1 only because `_write_license` stamps them at creation. Adding
the collection here is what brings them into the migration chain at all.

Seat documents (`licenses/{id}/seats/{uid}`) carry neither field — only uid,
email, deviceIdLock and status — so they need no transform. That is fortunate:
the runner walks top-level collections and has no collection-group support.

Not done here: moving licenses off the key hash as their document id. The
runner hands `transform` only the document body, so it cannot read an id, and
`batch.update` cannot re-key a document — that needs a bespoke copy/delete
script that also rewrites every `users.licenseId`. It is deferred to the change
that needs it, which is issuing a license with no key at all (a floating
institution pool). `_write_license` records `keyHash` as a field from now on so
new documents are already shaped for it.
"""
from_version = 1
to_version = 2

collections = ("users", "licenses")

_MODE_BY_PLAN = {"professional": "licensed", "demo": "demo"}
_PLAN_BY_MODE = {"licensed": "professional", "demo": "demo"}


def _text(value) -> str:
    return value.strip().lower() if isinstance(value, str) else ""


def transform(doc: dict) -> dict | None:
    update = {}

    # `mode` wins when both are present: a dual-writing revision has already
    # set it, and the mirror may be the stale half.
    mode = _text(doc.get("mode"))
    if mode not in _PLAN_BY_MODE:
        mode = _MODE_BY_PLAN.get(_text(doc.get("plan")), "")
    if mode:
        if _text(doc.get("mode")) != mode:
            update["mode"] = mode
        if _text(doc.get("plan")) != _PLAN_BY_MODE[mode]:
            update["plan"] = _PLAN_BY_MODE[mode]

    # users carry `licenseKind`; licenses carry `kind`. Same values, and a
    # document has one or the other, so both are checked unconditionally.
    for field in ("kind", "licenseKind"):
        if _text(doc.get(field)) == "campus":
            update[field] = "institution"

    return update or None
