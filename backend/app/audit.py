"""Append-only audit trail. Best-effort: never break a request on a log failure."""
import logging
from datetime import datetime, timezone

from google.cloud import firestore

from .firestore_repo import SCHEMA_VERSION, db

log = logging.getLogger("audit")

#: Actions that say which device a licence followed. Seat clears from IT are
#: recorded as INSTITUTION_SEAT_PATCH with clearDeviceLock; staff clears as
#: ADMIN_DEVICE_LOCK_CLEAR. Bind/unbind are the holder's own half of a move.
DEVICE_HISTORY_ACTIONS = frozenset(
    {
        "LICENSE_DEVICE_BIND",
        "LICENSE_DEVICE_UNBIND",
        "ADMIN_DEVICE_LOCK_CLEAR",
        "INSTITUTION_SEAT_PATCH",
    }
)


def record(uid=None, device_id=None, action="", outcome="OK", target=None, detail=None, ip=None):
    try:
        db().collection("audit_logs").add(
            {
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
        )
    except Exception as e:  # noqa: BLE001 - auditing must not fail the request
        log.warning("audit write failed: %s", e)


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
        exact = (
            db().collection("audit_logs")
            .where("target.id", "==", license_id)
            .limit(fetch)
            .stream()
        )
        for snap in exact:
            rows.append(_public_audit(snap.id, snap.to_dict() or {}))
        # Seat targets are `{licenseId}/{uid}`. A range on the string is enough
        # for the automatic single-field index; filter actions below.
        prefix = f"{license_id}/"
        seats = (
            db().collection("audit_logs")
            .where("target.id", ">=", prefix)
            .where("target.id", "<", prefix + "\uffff")
            .limit(fetch)
            .stream()
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
    if row["action"] != "INSTITUTION_SEAT_PATCH":
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
