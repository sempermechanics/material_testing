"""Server-side Firestore access. Clients never touch Firestore directly."""
import secrets
from datetime import datetime, timedelta, timezone

from google.cloud import firestore

from .config import settings
from .models import DeviceReg, FileComplete, FileSpec, SessionCreate

_DB = None

# Firestore caps a write batch at 500 operations.
_BATCH_LIMIT = 400


def db() -> firestore.Client:
    global _DB
    if _DB is None:
        _DB = firestore.Client(project=settings.GCP_PROJECT or None)
    return _DB


def _now():
    return datetime.now(timezone.utc)


# ---------------- users ----------------
def _is_admin_email(claims: dict) -> bool:
    # Never grant admin off an unverified email — an email/password user could
    # otherwise claim an admin address without proving they own it.
    return bool(claims.get("email_verified")) and \
        claims.get("email", "").lower() in settings.ADMIN_EMAILS


def _auto_approved(claims: dict) -> bool:
    """Admins, blanket AUTO_APPROVE, or a *verified* email in AUTO_APPROVE_HD.

    Auto-approval always requires a verified email. Email-link and Google/
    Microsoft sign-ins are verified by construction; a new email/password user
    is not verified until they confirm, so they land PENDING until then.
    """
    if _is_admin_email(claims):
        return True
    if not claims.get("email_verified"):
        return False
    if settings.AUTO_APPROVE:
        return True
    hd = settings.AUTO_APPROVE_HD
    if not hd:
        return False
    return claims.get("email", "").lower().endswith("@" + hd)


def get_or_create_user(claims: dict) -> dict:
    uid = claims["sub"]
    ref = db().collection("users").document(uid)
    snap = ref.get()
    verified = bool(claims.get("email_verified"))
    provider = (claims.get("firebase") or {}).get("sign_in_provider")
    if snap.exists:
        cur = snap.to_dict()
        patch = {"lastSeenAt": firestore.SERVER_TIMESTAMP, "emailVerified": verified}
        if provider:
            patch["signInProvider"] = provider
        # Keep admin role in sync with ADMIN_EMAILS for pre-existing users.
        if _is_admin_email(claims) and cur.get("role") != "admin":
            patch["role"] = "admin"
        # A previously-PENDING user who has since verified a domain email (or been
        # made admin) is auto-approved on this sign-in.
        if cur.get("access_status") == "PENDING" and _auto_approved(claims):
            patch["access_status"] = "APPROVED"
        ref.update(patch)
        return {**cur, **patch, "uid": uid}
    data = {
        "email": claims.get("email"),
        "emailVerified": verified,
        "signInProvider": provider,
        "displayName": claims.get("name"),
        "role": "admin" if _is_admin_email(claims) else "user",
        "access_status": "APPROVED" if _auto_approved(claims) else "PENDING",
        "activeDeviceId": None,
        "createdAt": firestore.SERVER_TIMESTAMP,
        "lastSeenAt": firestore.SERVER_TIMESTAMP,
    }
    ref.set(data)
    return {**data, "uid": uid}


def list_users(status: str = "", limit: int = 200) -> list:
    col = db().collection("users")
    query = col.where("access_status", "==", status) if status else col
    out = []
    for d in query.limit(limit).stream():
        u = d.to_dict()
        out.append({
            "uid": d.id,
            "email": u.get("email"),
            "displayName": u.get("displayName"),
            "role": u.get("role"),
            "access_status": u.get("access_status"),
            "activeDeviceId": u.get("activeDeviceId"),
        })
    return out


def set_user_status(uid: str, status: str) -> bool:
    ref = db().collection("users").document(uid)
    if not ref.get().exists:
        return False
    ref.update({"access_status": status, "updatedAt": firestore.SERVER_TIMESTAMP})
    return True


# ---------------- devices ----------------
def get_device(device_id: str):
    snap = db().collection("devices").document(device_id).get()
    return {**snap.to_dict(), "deviceId": device_id} if snap.exists else None


def user_has_active_device(uid: str) -> bool:
    u = db().collection("users").document(uid).get()
    return bool(u.exists and u.to_dict().get("activeDeviceId"))


def register_device(uid: str, body: DeviceReg) -> dict:
    dev = {
        "uid": uid,
        "publicKeyPem": body.publicKeyPem,
        "status": "ACTIVE",
        "model": body.model,
        "osVersion": body.osVersion,
        "appVersion": body.appVersion,
        "registeredAt": firestore.SERVER_TIMESTAMP,
        "lastAssertionAt": firestore.SERVER_TIMESTAMP,
    }
    db().collection("devices").document(body.deviceId).set(dev)
    db().collection("users").document(uid).update({"activeDeviceId": body.deviceId})
    return {**dev, "deviceId": body.deviceId}


# ---------------- challenge / nonce ----------------
def issue_nonce(uid: str, device_id: str) -> str:
    nonce = secrets.token_urlsafe(32)
    db().collection("challenges").document(nonce).set(
        {"uid": uid, "deviceId": device_id, "expireAt": _now() + timedelta(seconds=120)}
    )
    return nonce


def consume_nonce(nonce: str, uid: str, device_id: str) -> bool:
    ref = db().collection("challenges").document(nonce)
    snap = ref.get()
    if not snap.exists:
        return False
    d = snap.to_dict()
    ref.delete()  # single-use
    if d.get("uid") != uid or d.get("deviceId") != device_id:
        return False
    exp = d.get("expireAt")
    return bool(exp and exp > _now())


# ---------------- sessions / files ----------------
def delete_session(sid: str) -> int:
    """Hard-delete an analysis' metadata: every file doc, then the session doc.

    GDPR erasure — records are removed, not flagged. Returns the file count.
    Firestore batches cap at 500 writes, so this chunks.
    """
    files = list(db().collection("files").where("sessionId", "==", sid).stream())
    removed = 0
    for start in range(0, len(files), _BATCH_LIMIT):
        batch = db().batch()
        for d in files[start:start + _BATCH_LIMIT]:
            batch.delete(d.reference)
            removed += 1
        batch.commit()
    db().collection("sessions").document(sid).delete()
    return removed


def remember_user_folder(uid: str, folder_id: str) -> None:
    """Persist the user's Drive subtree id so erasure never has to guess by name."""
    db().collection("users").document(uid).update({"driveFolderId": folder_id})


def get_user(uid: str):
    snap = db().collection("users").document(uid).get()
    return {**snap.to_dict(), "uid": uid} if snap.exists else None


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
    sessions = list(db().collection("sessions").where("uid", "==", uid).stream())
    files_removed = 0
    for s in sessions:
        files_removed += delete_session(s.id)

    devices = list(db().collection("devices").where("uid", "==", uid).stream())
    for d in devices:
        d.reference.delete()

    db().collection("users").document(uid).delete()
    return {"sessions": len(sessions), "files": files_removed, "devices": len(devices)}


def get_session(sid: str):
    snap = db().collection("sessions").document(sid).get()
    return {**snap.to_dict(), "sessionId": sid} if snap.exists else None


def get_file(file_id: str):
    snap = db().collection("files").document(file_id).get()
    return {**snap.to_dict(), "fileId": file_id} if snap.exists else None


def list_pending_uploads(sid: str) -> list:
    """Files in a session that still need bytes, with their resumable URIs.

    Lets an interrupted upload resume the SAME session instead of creating a
    duplicate (which would also burn the per-user analysis quota). Files already
    COMPLETED have their uploadUrl cleared, so they're naturally excluded.
    """
    out = []
    for d in db().collection("files").where("sessionId", "==", sid).stream():
        f = d.to_dict()
        url = f.get("uploadUrl")
        if f.get("status") == "COMPLETED" or not url:
            continue
        out.append({
            "fileId": d.id,
            "uploadUrl": url,
            "chunkSize": 32 * 1024 * 1024,  # keep in sync with create_session
            "name": f.get("name"),
            "role": f.get("role"),
            "sizeBytes": f.get("sizeBytes", 0),
        })
    return out


def list_session_files(sid: str) -> list:
    """Every file in an analysis — the manifest the app restores from."""
    out = []
    for d in db().collection("files").where("sessionId", "==", sid).stream():
        f = d.to_dict()
        out.append({
            "fileId": d.id,
            "name": f.get("name"),
            "role": f.get("role"),
            "sizeBytes": f.get("sizeBytes", 0),
            "sha256": f.get("sha256"),
            "status": f.get("status"),
        })
    return out


def list_user_sessions(uid: str, limit: int = 200) -> list:
    """The user's cloud analyses — what the app reconciles its sync state against."""
    q = db().collection("sessions").where("uid", "==", uid).limit(limit)
    out = []
    for d in q.stream():
        s = d.to_dict()
        out.append({
            "sessionId": d.id,
            "localSessionId": s.get("localSessionId") or "",
            "specimen": s.get("specimen"),
            "status": s.get("status"),
            "fileCount": s.get("fileCount", 0),
            "completedCount": s.get("completedCount", 0),
            "totalBytes": s.get("totalBytes", 0),
            "driveFolderId": s.get("driveFolderId"),
        })
    return out


def count_user_sessions(uid: str) -> int:
    """How many analyses this user already has in the cloud (quota check)."""
    agg = db().collection("sessions").where("uid", "==", uid).count().get()
    return int(agg[0][0].value)


def create_session(sid: str, user: dict, device: dict, body: SessionCreate, folders: dict):
    db().collection("sessions").document(sid).set(
        {
            "uid": user["uid"],
            "deviceId": device.get("deviceId"),
            "specimen": body.specimen,
            "localSessionId": body.localSessionId,
            "status": "UPLOADING",
            "driveFolderId": folders["sessionFolderId"],
            "totalBytes": sum(f.bytes for f in body.files),
            "fileCount": len(body.files),
            "completedCount": 0,
            "metrics": body.metrics,
            "createdAt": firestore.SERVER_TIMESTAMP,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        }
    )


def create_file(sid: str, uid: str, file_id: str, f: FileSpec, upload_url: str):
    db().collection("files").document(file_id).set(
        {
            "sessionId": sid,
            "uid": uid,
            "role": f.role,
            "name": f.name,
            "sizeBytes": f.bytes,
            "sha256": f.sha256,
            "status": "PENDING",
            "uploadUrl": upload_url,
            "driveFileId": None,
            "driveMd5": None,
            "createdAt": firestore.SERVER_TIMESTAMP,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        }
    )


def complete_file(file_id: str, uid: str, body: FileComplete) -> str:
    """Record a file's Drive pointer. Returns "ok", "already" (a retry of a
    completion that landed — idempotent, must NOT bump the session counter
    again) or "" (rejected).
    """
    ref = db().collection("files").document(file_id)
    snap = ref.get()
    if not snap.exists:
        return ""
    d = snap.to_dict()
    if d["uid"] != uid or d["sizeBytes"] != body.bytes:
        return ""
    if d.get("status") == "COMPLETED":
        return "already"
    ref.update(
        {
            "status": "COMPLETED",
            "driveFileId": body.driveFileId,
            "driveMd5": body.md5,
            "uploadUrl": firestore.DELETE_FIELD,  # capability no longer needed
            "updatedAt": firestore.SERVER_TIMESTAMP,
        }
    )
    return "ok"


def bump_session_progress(sid: str):
    """One file just completed: advance the session's counter by one.

    O(1) — a transaction on the session doc alone. The previous version
    re-streamed EVERY file doc in the session on every completion, which made
    an N-file upload cost ~N² Firestore reads (a 150-frame analysis burned the
    whole daily free-tier read quota several times over by itself).

    Trusting the counter is safe because complete_file is idempotent: a retried
    completion returns "already" and never reaches this function.
    """
    ref = db().collection("sessions").document(sid)
    transaction = db().transaction()

    @firestore.transactional
    def _bump(tx):
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return
        s = snap.to_dict()
        done = int(s.get("completedCount", 0)) + 1
        upd = {"completedCount": done, "updatedAt": firestore.SERVER_TIMESTAMP}
        if done >= int(s.get("fileCount", 0)):
            upd["status"] = "COMPLETED"
            upd["completedAt"] = firestore.SERVER_TIMESTAMP
        tx.update(ref, upd)

    _bump(transaction)
