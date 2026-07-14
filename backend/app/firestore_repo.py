"""Server-side Firestore access. Clients never touch Firestore directly."""
import secrets
from datetime import datetime, timedelta, timezone

from google.cloud import firestore

from .config import settings
from .models import DeviceReg, FileComplete, FileSpec, SessionCreate

_DB = None


def db() -> firestore.Client:
    global _DB
    if _DB is None:
        _DB = firestore.Client(project=settings.GCP_PROJECT or None)
    return _DB


def _now():
    return datetime.now(timezone.utc)


# ---------------- users ----------------
def _is_admin_email(claims: dict) -> bool:
    return claims.get("email", "").lower() in settings.ADMIN_EMAILS


def _auto_approved(claims: dict) -> bool:
    """Admins, blanket AUTO_APPROVE, or the verified email is in AUTO_APPROVE_HD."""
    if settings.AUTO_APPROVE or _is_admin_email(claims):
        return True
    hd = settings.AUTO_APPROVE_HD
    if not hd:
        return False
    email = claims.get("email", "")
    return claims.get("hd") == hd or email.endswith("@" + hd)


def get_or_create_user(claims: dict) -> dict:
    uid = claims["sub"]
    ref = db().collection("users").document(uid)
    snap = ref.get()
    if snap.exists:
        # Keep admin role in sync with ADMIN_EMAILS for pre-existing users.
        patch = {"lastSeenAt": firestore.SERVER_TIMESTAMP}
        if _is_admin_email(claims) and snap.to_dict().get("role") != "admin":
            patch["role"] = "admin"
        ref.update(patch)
        return {**snap.to_dict(), **patch, "uid": uid}
    data = {
        "email": claims.get("email"),
        "hd": claims.get("hd"),
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
def create_session(sid: str, user: dict, device: dict, body: SessionCreate, folders: dict):
    db().collection("sessions").document(sid).set(
        {
            "uid": user["uid"],
            "deviceId": device.get("deviceId"),
            "specimen": body.specimen,
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


def complete_file(file_id: str, uid: str, body: FileComplete) -> bool:
    ref = db().collection("files").document(file_id)
    snap = ref.get()
    if not snap.exists:
        return False
    d = snap.to_dict()
    if d["uid"] != uid or d["sizeBytes"] != body.bytes:
        return False
    ref.update(
        {
            "status": "COMPLETED",
            "driveFileId": body.driveFileId,
            "driveMd5": body.md5,
            "uploadUrl": firestore.DELETE_FIELD,  # capability no longer needed
            "updatedAt": firestore.SERVER_TIMESTAMP,
        }
    )
    return True


def maybe_complete_session(sid: str):
    files = list(db().collection("files").where("sessionId", "==", sid).stream())
    done = sum(1 for f in files if f.to_dict().get("status") == "COMPLETED")
    upd = {"completedCount": done, "updatedAt": firestore.SERVER_TIMESTAMP}
    if files and done == len(files):
        upd["status"] = "COMPLETED"
        upd["completedAt"] = firestore.SERVER_TIMESTAMP
    db().collection("sessions").document(sid).update(upd)
