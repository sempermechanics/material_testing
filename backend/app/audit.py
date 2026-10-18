"""Append-only audit trail. Best-effort: never break a request on a log failure."""
import logging
from datetime import datetime, timezone

from google.cloud import firestore

from . import observability as obs
from .repo import _base
from .repo._base import SCHEMA_VERSION, _add_doc, _get, _stream, db

log = logging.getLogger("audit")

#: Target types whose id is a licence, or starts `{licenseId}/` (a seat, an
#: invite, a lease). Their events are stamped with `licenseId`, which is what
#: the per-licence export filters on (`page_license_audit`).
LICENCE_TARGET_TYPES = frozenset({"license", "seat", "invite", "lease"})

#: Seat patches that are device moves only when they carry `clearDeviceLock`:
#: IT's (INSTITUTION_SEAT_PATCH) and staff's on the roster (ADMIN_SEAT_PATCH).
SEAT_PATCH_ACTIONS = frozenset({"INSTITUTION_SEAT_PATCH", "ADMIN_SEAT_PATCH"})

#: Actions that say which device a licence followed. Staff clears are
#: ADMIN_DEVICE_LOCK_CLEAR, or a roster seat patch with clearDeviceLock.
#: Bind/unbind are the holder's own half of a move.
DEVICE_HISTORY_ACTIONS = frozenset(
    {
        "LICENSE_DEVICE_BIND",
        "LICENSE_DEVICE_UNBIND",
        "ADMIN_DEVICE_LOCK_CLEAR",
        *SEAT_PATCH_ACTIONS,
    }
)


def address_tag(address: str | None) -> str:
    """An email address as an audit row may hold it: `obs.short_hash`.

    Audit rows survive account erasure by design, so they must not hold
    addresses ([FIRESTORE_DATA_PROTECTION.md], "Account-erasure audit
    policy"; TD-242). The tag still lets a reader who already knows an
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


def record(uid=None, device_id=None, action="", outcome="OK", target=None, detail=None, ip=None):
    row = {
        "ts": firestore.SERVER_TIMESTAMP,
        "uid": uid,
        "deviceId": device_id,
        "ip": ip,
        "action": action,
        "outcome": outcome,
        "target": target or {},
        "detail": detail or {},
        "schemaVersion": SCHEMA_VERSION,
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
    try:
        _add_doc(db().collection("audit_logs"), row)
    except Exception as e:  # noqa: BLE001 - auditing must not fail the request
        # One JSON line per lost record, so a log-based metric can count them
        # and alert on `jsonPayload.event="audit_write_failed"`. The action
        # names what went unrecorded and the exception class says why, without
        # its message, which can carry a document path.
        obs.log_event(log, logging.ERROR, "audit_write_failed",
                      action=action, outcome="error", errorCode=type(e).__name__)


def list_license_device_history(license_id: str, *, limit: int = 50) -> list[dict]:
    """Recent device-move events for one licence (staff console).

    Reads by `target.id` equality for the licence itself, and by prefix for
    seat targets (`{licenseId}/{uid}`). Sorted newest-first in Python so the
    query stays a single-field equality/range and needs no composite index.
    """
    limit = max(1, min(int(limit), 200))
    # Over-fetch a little so seat prefix rows and IT patches that are not
    # device clears can be filtered without a second round trip.
    fetch = min(limit * 4, 400)
    rows: list[dict] = []
    try:
        exact = _stream(
            db().collection("audit_logs")
            .where("target.id", "==", license_id)
            .limit(fetch)
        )
        for snap in exact:
            rows.append(_public_audit(snap.id, snap.to_dict() or {}))
        # Seat targets are `{licenseId}/{uid}`. A range on the string is enough
        # for the automatic single-field index; filter actions below.
        prefix = f"{license_id}/"
        seats = _stream(
            db().collection("audit_logs")
            .where("target.id", ">=", prefix)
            .where("target.id", "<", prefix + "\uffff")
            .limit(fetch)
        )
        for snap in seats:
            rows.append(_public_audit(snap.id, snap.to_dict() or {}))
    except Exception as e:  # noqa: BLE001
        log.warning("audit history read failed: %s", e)
        return []

    filtered = [
        r for r in rows
        if r["action"] in DEVICE_HISTORY_ACTIONS
        and _is_device_history_row(r)
    ]
    filtered.sort(key=lambda r: r.get("ts") or "", reverse=True)
    return filtered[:limit]


def _is_device_history_row(row: dict) -> bool:
    if row["action"] not in SEAT_PATCH_ACTIONS:
        return True
    detail = row.get("detail") or {}
    return bool(detail.get("clearDeviceLock"))


def _public_audit(doc_id: str, data: dict) -> dict:
    ts = data.get("ts")
    if isinstance(ts, datetime):
        ts_out = ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    else:
        ts_out = str(ts) if ts else ""
    return {
        "id": doc_id,
        "ts": ts_out,
        "uid": data.get("uid") or "",
        "action": data.get("action") or "",
        "outcome": data.get("outcome") or "",
        "target": data.get("target") or {},
        "detail": data.get("detail") or {},
    }


#: The largest page of one licence's audit log. The CSV export asks for a
#: whole date range in one answer, so the same ceiling bounds a download.
MAX_AUDIT_PAGE = 5000


def page_license_audit(license_id: str, *, limit: int, page_token: str | None = None,
                       since: datetime | None = None,
                       until: datetime | None = None) -> tuple[list[dict], str | None]:
    """One page of the events stamped with `license_id`, newest first.

    Returns (events, next_token). `since` is inclusive, `until` exclusive. The
    query is `licenseId ==` plus a range on `ts`, ordered by `ts` descending,
    which the composite index (licenseId, ts DESC) serves. Events written
    before `record` stamped `licenseId` (2026-10-18) carry no `licenseId` and
    are not in it.

    The page token is the last event's id. One that is not this licence's
    (deleted, mistyped, or another licence's) restarts from the newest event
    rather than positioning the page on a document the caller may not see.
    Unlike `list_license_device_history` a read failure is raised, not
    answered as an empty log: an export that silently comes back empty reads
    as "nothing happened".
    """
    limit = max(1, min(int(limit), MAX_AUDIT_PAGE))
    col = db().collection("audit_logs")
    query = col.where("licenseId", "==", license_id)
    if since is not None:
        query = query.where("ts", ">=", since)
    if until is not None:
        query = query.where("ts", "<", until)
    query = query.order_by("ts", direction=_base.firestore.Query.DESCENDING).limit(limit + 1)
    if page_token:
        cursor = _get(col.document(page_token))
        if cursor.exists and (cursor.to_dict() or {}).get("licenseId") == license_id:
            query = query.start_after(cursor)
    docs = _stream(query)
    next_token = None
    if len(docs) > limit:
        docs = docs[:limit]
        next_token = docs[-1].id
    # `_public_audit` leaves out the caller's IP and device id. What remains
    # (actor uid, action, target, detail) is the licence's own business: its
    # administrators already read the same members, devices and terms from
    # the roster and the licence summary.
    return [_public_audit(d.id, d.to_dict() or {}) for d in docs], next_token
