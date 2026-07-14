import logging
import uuid

from fastapi import Depends, FastAPI, Header, HTTPException

from . import audit, drive, firestore_repo as repo
from .config import settings
from .deps import current_user, verified_device
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
    if repo.user_has_active_device(user["uid"]):
        raise HTTPException(409, "device_conflict")  # replacement requires admin rebind
    repo.register_device(user["uid"], body)
    audit.record(user["uid"], body.deviceId, action="DEVICE_REGISTER")
    return {"deviceId": body.deviceId}


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


@app.post("/v1/files/{file_id}/complete")
async def complete_file(file_id: str, body: FileComplete, ctx=Depends(verified_device)):
    user = ctx["user"]
    if not repo.complete_file(file_id, user["uid"], body):
        raise HTTPException(409, "size_or_state_mismatch")
    repo.maybe_complete_session(body.sessionId)
    audit.record(user["uid"], ctx["device"].get("deviceId"), action="UPLOAD_COMPLETE",
                 target={"type": "file", "id": file_id})
    return {"status": "ok"}
