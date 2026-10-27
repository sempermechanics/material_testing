"""Append-only audit trail. Best-effort: never break a request on a log failure.

The row is built here; it is written and read in `repo/audit_log.py`, and
mirrored to Cloud Logging as one `audit_row` line (TD-199): a log sink routes
those to a bucket whose retention policy is locked, the copy that no project
role can edit or delete. Firestore's copy is the one the consoles read."""
import json
import logging
from . import observability as obs
# The module, not its names: `repo.devlock` imports this module while the
# package is still initialising, before the package has bound its exports.
from .repo import audit_log

log = logging.getLogger("audit")

#: The logger the mirror is written to. The sink's filter is
#: `jsonPayload.event="audit_row"` (docs/backend/FIRESTORE_DATA_PROTECTION.md).
TRAIL_LOGGER = "semper.audit_trail"
_trail = logging.getLogger(TRAIL_LOGGER)

#: Target types whose id is a licence, or starts `{licenseId}/` (a seat, an
#: invite, a lease). Their events are stamped with `licenseId`, which is what
#: the per-licence export filters on (`page_license_audit`).
LICENCE_TARGET_TYPES = frozenset({"license", "seat", "invite", "lease"})


def address_tag(address: str | None) -> str:
    """An email address as an audit row may hold it: `obs.short_hash`.

    Audit rows survive account erasure by design, so they must not hold
    addresses ([FIRESTORE_DATA_PROTECTION.md], "Account-erasure audit
    policy"; TD-201). The tag still lets a reader who already knows an
    address match it, which is what an investigation needs."""
    return obs.short_hash(address)


def address_tags(addresses) -> list[str]:
    """`address_tag` for each of a list, in order."""
    return [address_tag(a) for a in addresses or []]


def licence_of(target: dict | None) -> str:
    """The licence an event's target belongs to, or "" when it is not one.

    `license` targets carry the licence id; `seat`, `invite` and `lease`
    targets carry `{licenseId}/{uid or invite id}`, and a holder's own unbind
    names a seat by the bare licence id. Sessions, files and users belong to
    no licence.
    """
    target = target or {}
    if target.get("type") not in LICENCE_TARGET_TYPES:
        return ""
    return str(target.get("id") or "").split("/", 1)[0]


def _mirror(row: dict) -> None:
    """The row as one Cloud Logging line, under `auditRow`.

    Nested, not spread: the log-based metrics count lines by top-level fields
    (`semper_auth_denied` by `jsonPayload.action`), and a spread row would be
    counted again. A value JSON cannot hold (a datetime in `detail`) is
    written as its text, as Firestore's copy is read back by the consoles."""
    payload = {**obs.event_payload("audit_row"), "auditRow": row}
    try:
        _trail.info(json.dumps(payload, default=str, separators=(",", ":")))
    except Exception as e:  # noqa: BLE001 - auditing must not fail the request
        obs.log_event(log, logging.ERROR, "audit_mirror_failed",
                      action=row.get("action"), outcome="error", errorCode=type(e).__name__)


def record(uid=None, device_id=None, action="", outcome="OK", target=None, detail=None, ip=None):
    row = {
        "uid": uid,
        "deviceId": device_id,
        "ip": ip,
        "action": action,
        "outcome": outcome,
        "target": target or {},
        "detail": detail or {},
    }
    license_id = licence_of(target)
    if license_id:
        row["licenseId"] = license_id
    if outcome == "DENIED":
        # Refusals also go to Cloud Logging, so the `semper_auth_denied`
        # log-based metric (ops/monitoring) can count them by stage; the
        # Firestore row alone is invisible to alerting.
        obs.log_event(log, logging.WARNING, "audit_denied", action=action,
                      stage=str((detail or {}).get("stage") or ""))
    # The mirror first: a row Firestore loses below still leaves this copy.
    _mirror({**row, "schemaVersion": audit_log.SCHEMA_VERSION})
    try:
        audit_log.add_audit_row(row)
    except Exception as e:  # noqa: BLE001 - auditing must not fail the request
        # One JSON line per lost record, so a log-based metric can count them
        # and alert on `jsonPayload.event="audit_write_failed"`. The action
        # names what went unrecorded and the exception class says why, without
        # its message, which can carry a document path.
        obs.log_event(log, logging.ERROR, "audit_write_failed",
                      action=action, outcome="error", errorCode=type(e).__name__)
