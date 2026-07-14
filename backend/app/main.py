import logging
import uuid

from fastapi import Depends, FastAPI, Header, HTTPException

from . import audit, drive, firestore_repo as repo
from .config import settings
from .deps import admin_user, current_user, verified_device
from .models import DeviceReg, FileComplete, SessionCreate

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("indic")

app = FastAPI(title="inDIC API", version="1.0")


@app.on_event("startup")
def _startup():
    missing = [k for k in ("WEB_CLIENT_ID", "SERVICE_ACCOUNT_EMAIL", "SHARED_DRIVE_ID")
               if not getattr(settings, k)]
    if missing:
        log.warning("Missing env vars: %s", ", ".join(missing))
    if settings.DEV_INSECURE_AUTH:
        log.warning("=== DEV_INSECURE_AUTH=1 : auth is BYPASSED. Never use in production. ===")


@app.get("/healthz")
def healthz():
    return {"ok": True, "dev_insecure_auth": settings.DEV_INSECURE_AUTH}


@app.get("/v1/me")
async def me(user=Depends(current_user)):
    return {"uid": user["uid"], "email": user.get("email"),
            "role": user.get("role"), "access_status": user["access_status"]}


@app.post("/v1/devices/register", status_code=201)
async def register_device(body: DeviceReg, user=Depends(current_user)):
    active = user.get("activeDeviceId")
    # A different active device = a real device switch → requires a reset/rebind.
    # The SAME device id re-registering (reinstall wipes the Keystore key) is
    # allowed and simply heals the stored public key.
    if active and active != body.deviceId:
        raise HTTPException(409, "device_conflict")
    healed = active == body.deviceId
    repo.register_device(user["uid"], body)  # upsert: refreshes the public key
    audit.record(user["uid"], body.deviceId,
                 action="DEVICE_REBIND" if healed else "DEVICE_REGISTER")
    return {"deviceId": body.deviceId, "healed": healed}


@app.post("/v1/challenge")
async def challenge(user=Depends(current_user), x_device_id: str = Header(default="")):
    if not x_device_id:
        raise HTTPException(400, "missing_device_id")
    return {"nonce": repo.issue_nonce(user["uid"], x_device_id)}


@app.post("/v1/sessions")
async def create_session(body: SessionCreate, ctx=Depends(verified_device)):
    user, device = ctx["user"], ctx["device"]
    sid = uuid.uuid4().hex
    token = drive.access_token()
    folders = drive.ensure_session_folders(token, user["uid"], sid)

    uploads = []
    for f in body.files:
        session_uri = drive.init_resumable(token, folders[f.role], f.name, f.bytes)
        file_id = f"{sid}_{f.role}_{f.name}"
        repo.create_file(sid, user["uid"], file_id, f, session_uri)
        uploads.append({"fileId": file_id, "uploadUrl": session_uri,
                        "chunkSize": 8 * 1024 * 1024})

    repo.create_session(sid, user, device, body, folders)
    audit.record(user["uid"], device.get("deviceId"), action="SESSION_CREATE",
                 target={"type": "session", "id": sid})
    return {"sessionId": sid, "uploads": uploads}


@app.get("/v1/admin/users")
async def admin_list_users(status: str = "", admin=Depends(admin_user)):
    """List users, optionally filtered by access_status (e.g. ?status=PENDING)."""
    return {"users": repo.list_users(status)}


@app.post("/v1/admin/users/{uid}/approve")
async def admin_approve_user(uid: str, admin=Depends(admin_user)):
    if not repo.set_user_status(uid, "APPROVED"):
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_APPROVE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "APPROVED"}


@app.post("/v1/admin/users/{uid}/revoke")
async def admin_revoke_user(uid: str, admin=Depends(admin_user)):
    if not repo.set_user_status(uid, "SUSPENDED"):
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_REVOKE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "SUSPENDED"}


@app.post("/v1/files/{file_id}/complete")
async def complete_file(file_id: str, body: FileComplete, ctx=Depends(verified_device)):
    user = ctx["user"]
    if not repo.complete_file(file_id, user["uid"], body):
        raise HTTPException(409, "size_or_state_mismatch")
    repo.maybe_complete_session(body.sessionId)
    audit.record(user["uid"], ctx["device"].get("deviceId"), action="UPLOAD_COMPLETE",
                 target={"type": "file", "id": file_id})
    return {"status": "ok"}
