"""Per-account records: the Drive user folder, terms and consent, and erasing everything.
"""

from . import _base
from ._base import (
    db,
    _delete_query_until_empty,
    _now,
)


def remember_user_folder(uid: str, folder_id: str, sessions_folder_id: str | None = None) -> None:
    """Persist the user's Drive subtree id so erasure never has to guess by name.

    `sessions_folder_id` (the user's session/ folder) lets the next upload skip
    the four-level name walk in drive.ensure_session_folders.
    """
    fields = {"driveFolderId": folder_id}
    if sessions_folder_id:
        fields["driveSessionsFolderId"] = sessions_folder_id
    db().collection("users").document(uid).update(fields)


def get_user(uid: str):
    snap = db().collection("users").document(uid).get()
    return {**snap.to_dict(), "uid": uid} if snap.exists else None


def record_terms_acceptance(uid: str, version: str, device_id: str | None, source: str) -> dict:
    """Store the clickwrap record: which Terms version this account agreed to.

    Overwrites the previous record — only the latest acceptance matters for the
    gate, and the audit trail keeps the history. Returns the stored record with
    a client-usable timestamp (the SERVER_TIMESTAMP sentinel is not JSON).
    """
    record = {
        "version": version,
        "acceptedAt": _base.firestore.SERVER_TIMESTAMP,
        "deviceId": device_id,
        "source": source,
    }
    db().collection("users").document(uid).update({
        "termsAccepted": record, "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    return {**record, "acceptedAt": _now().isoformat()}


def record_improvement_consent(uid: str, granted: bool, version: str, device_id: str | None,
                               source: str) -> dict:
    """Store the separate product-improvement consent (never bundled into the Terms).

    `version` is the Terms/Privacy version the choice was made against, so a
    later policy change can tell an old "yes" from a fresh one.
    """
    record = {
        "granted": bool(granted),
        "version": version,
        "at": _base.firestore.SERVER_TIMESTAMP,
        "deviceId": device_id,
        "source": source,
    }
    db().collection("users").document(uid).update({
        "improvementConsent": record, "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    return {**record, "at": _now().isoformat()}


def list_user_devices(uid: str) -> list:
    out = []
    for d in db().collection("devices").where("uid", "==", uid).stream():
        v = d.to_dict()
        out.append({
            "deviceId": d.id,
            "status": v.get("status"),
            "model": v.get("model"),
            "osVersion": v.get("osVersion"),
            "appVersion": v.get("appVersion"),
            "registeredAt": str(v.get("registeredAt")),
        })
    return out


def delete_all_user_data(uid: str) -> dict:
    """Erase every Firestore record belonging to a user (GDPR account deletion).

    Sessions + their file docs, the device registrations, and the user profile
    itself. Audit records are intentionally kept: they hold no analysis content,
    only the fact that actions (including this erasure) occurred.
    """
    # File docs carry the uid, so the whole account is one query rather than one
    # per session. Deleting session by session meant a query and a batch commit
    # each — sequential round-trips that made erasing a busy account crawl.
    files = _delete_query_until_empty(
        db().collection("files").where("uid", "==", uid)
    )
    sessions = _delete_query_until_empty(
        db().collection("sessions").where("uid", "==", uid)
    )
    devices = _delete_query_until_empty(
        db().collection("devices").where("uid", "==", uid)
    )
    db().collection("users").document(uid).delete()
    return {"sessions": sessions, "files": files, "devices": devices}
