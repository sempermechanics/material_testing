"""Append-only audit trail. Best-effort: never break a request on a log failure."""
import logging

from google.cloud import firestore

from .firestore_repo import SCHEMA_VERSION, db

log = logging.getLogger("audit")


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
