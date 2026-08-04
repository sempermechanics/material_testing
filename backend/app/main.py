import json
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import requests
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from . import audit, drive, firestore_repo as repo
from .config import settings
from .deps import admin_user, current_user, verified_device
from .models import DeviceReg, FileComplete, SessionCreate, UserConfigPatch

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("indic")
_access_log = logging.getLogger("indic.access")


def _startup_checks():
    # Required config (token audience, Drive SA, shared drive). A deployed
    # service missing any of these cannot serve real traffic, so fail the start
    # loudly rather than 500 on the first Drive/token call. Locally, warn and
    # continue so partial setups can still be exercised.
    missing = settings.missing_required()
    if missing:
        joined = ", ".join(missing)
        if settings.ON_CLOUD_RUN:
            raise RuntimeError(
                f"Missing required env vars: {joined}. Refusing to start a "
                "deployed service that cannot reach Drive/Firestore. Set them "
                "via --set-env-vars / --set-secrets."
            )
        log.warning("Missing env vars: %s (running locally — continuing)", joined)
    if settings.DEV_INSECURE_AUTH:
        if settings.ON_CLOUD_RUN and not settings.INSECURE_AUTH_ACK:
            raise RuntimeError(
                "DEV_INSECURE_AUTH=1 on a deployed Cloud Run service: user and "
                "device authentication would be bypassed and every caller would "
                "act as an admin. Refusing to start. For a throwaway smoke-test "
                "deployment set INSECURE_AUTH_I_ACCEPT_THE_RISK=1 as well; "
                "otherwise remove DEV_INSECURE_AUTH."
            )
        log.warning("=== DEV_INSECURE_AUTH=1 : auth is BYPASSED. Never use in production. ===")


@asynccontextmanager
async def lifespan(app: FastAPI):
    _startup_checks()
    yield


app = FastAPI(title="Semper API", version="1.0", lifespan=lifespan)


@app.middleware("http")
async def access_log(request: Request, call_next):
    """One structured JSON line per request: method, path, status, latencyMs,
    outcome, and the caller uid when a dependency resolved it (set on
    request.state by verified_device). Also stamps an X-Request-Id."""
    start = time.perf_counter()
    request_id = uuid.uuid4().hex[:12]
    request.state.uid = None
    request.state.request_id = request_id
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        response.headers["X-Request-Id"] = request_id
        return response
    finally:
        latency_ms = round((time.perf_counter() - start) * 1000, 1)
        outcome = "ok" if status < 400 else ("client_error" if status < 500 else "server_error")
        _access_log.info(json.dumps({
            "requestId": request_id,
            "method": request.method,
            "path": request.url.path,
            "status": status,
            "latencyMs": latency_ms,
            "outcome": outcome,
            "uid": getattr(request.state, "uid", None),
        }))


# Route handlers are deliberately plain `def`, not `async def`. Every Firestore
# and Drive call in this service is synchronous/blocking (google-cloud-firestore
# sync client + `requests`), so an `async def` handler would run that blocking
# I/O directly on the event loop and stall all other requests sharing the worker.
# A `def` handler is instead dispatched to Starlette's threadpool, which is the
# correct model here. Async dependencies (e.g. verified_device awaiting the body)
# still resolve on the loop first — mixing a sync route with an async dependency
# is fully supported. Only genuinely-awaiting code stays async (lifespan, the
# access_log middleware). Do not "modernize" these back to async def.
@app.get("/healthz")
def healthz():
    # Unauthenticated endpoint: it must not report the service's auth posture.
    # Whether the bypass is on is visible in the startup logs, to operators.
    return {"ok": True}


@app.get("/v1/me")
def me(user=Depends(current_user)):
    return {"uid": user["uid"], "email": user.get("email"),
            "role": user.get("role"), "access_status": user["access_status"]}


@app.get("/v1/config")
def app_config(user=Depends(current_user)):
    """Resolved product limits for the caller (per-user override → fleet default)."""
    return repo.resolve_user_config(user)


@app.get("/v1/me/export")
def export_account(ctx=Depends(verified_device)):
    """GDPR data portability (Art. 20): everything we hold about the caller, as JSON.

    Structured and machine-readable: the profile, registered devices, and every
    analysis with its full file manifest. The binary artifacts themselves stay
    downloadable via /v1/files/{id}/content (each file's `fileId` is included),
    which is also what the app's Restore screen uses.

    Device-signed (like DELETE /v1/me): a full-account export is high-consequence
    enough that a stolen ID token alone must not be able to trigger it.
    """
    user = ctx["user"]
    uid = user["uid"]
    profile = repo.get_user(uid) or {}
    sessions = repo.list_user_sessions(uid, limit=1000)
    for s in sessions:
        s["files"] = repo.list_session_files(s["sessionId"])

    audit.record(uid, action="DATA_EXPORT", target={"type": "user", "id": uid},
                 detail={"sessions": len(sessions)})
    return {
        "exportedAtUtc": datetime.now(timezone.utc).isoformat(),
        "schema": "indic.account.export/1",
        "profile": {
            "uid": uid,
            "email": profile.get("email"),
            "displayName": profile.get("displayName"),
            "hostedDomain": profile.get("hd"),
            "role": profile.get("role"),
            "accessStatus": profile.get("access_status"),
            "createdAt": str(profile.get("createdAt")),
            "lastSeenAt": str(profile.get("lastSeenAt")),
        },
        "devices": repo.list_user_devices(uid),
        "sessions": sessions,
        "artifactDownload": {
            "endpoint": "/v1/files/{fileId}/content",
            "note": "Images, .dat results, CSVs and reports are downloadable per file "
                    "using the fileId values above, or via the app's Restore screen.",
        },
    }


@app.delete("/v1/me")
def delete_account(ctx=Depends(verified_device)):
    """Erase the account and ALL of its data (GDPR right to erasure).

    Deletes the user's entire Drive subtree in one shot (every analysis, plus
    anything orphaned by a failed sync), then every Firestore record: sessions,
    file pointers, device registrations and the user profile.

    Only the append-only audit trail survives — it records that actions
    (including this erasure) happened, and holds no analysis content. The caller
    must sign out afterwards: any further authenticated call would create a
    fresh, empty profile.
    """
    user, device = ctx["user"], ctx["device"]
    uid = user["uid"]
    started = time.monotonic()
    token = drive.access_token()

    # 1. The session folders live inside the user folder, so deleting that one
    #    removes the whole subtree in a single call. The per-session loop below
    #    is the fallback for accounts predating the stored pointer — walking N
    #    sessions is N sequential Drive round-trips, which is what made deleting
    #    a busy account take the best part of a minute.
    user_folder = user.get("driveFolderId")
    folders_deleted = 0
    if user_folder:
        drive.delete_file(token, user_folder)  # raises → we abort before Firestore
    else:
        # No stored pointer: delete each session's folder via its OWN stored id
        # rather than trusting a lookup by name. If a name lookup missed we would
        # silently skip Drive and still wipe the metadata, stranding the blobs
        # with nothing left pointing at them.
        sessions = repo.list_user_sessions(uid, limit=1000)
        for s in sessions:
            folder = s.get("driveFolderId")
            if folder:
                drive.delete_file(token, folder)
                folders_deleted += 1
        # Then the scaffolding itself, and anything a failed sync orphaned.
        user_folder = drive.find_user_folder(token, uid)
        if user_folder:
            drive.delete_file(token, user_folder)
        else:
            log.warning("No Drive folder found for uid %s — nothing to purge there", uid)

    drive_ms = int((time.monotonic() - started) * 1000)

    # 3. Only once the blobs are gone: erase the metadata.
    counts = repo.delete_all_user_data(uid)
    total_ms = int((time.monotonic() - started) * 1000)
    # Timed per phase: erasure is the one call a user waits on with nothing to
    # look at, so when it feels slow the log should say which half was slow.
    detail = {
        **counts,
        "driveFolders": folders_deleted,
        "userFolderFound": bool(user_folder),
        "driveMs": drive_ms,
        "firestoreMs": total_ms - drive_ms,
        "totalMs": total_ms,
    }
    audit.record(uid, device.get("deviceId"), action="ACCOUNT_DELETE",
                 target={"type": "user", "id": uid}, detail=detail)
    log.info("Erased account %s: %s", uid, detail)
    return {"deleted": uid, **detail}


@app.post("/v1/devices/register", status_code=201)
def register_device(body: DeviceReg, user=Depends(current_user)):
    active = user.get("activeDeviceId")
    # This ACCOUNT is already bound to a different device → real device switch,
    # needs a reset/rebind. (Same device id re-registering after a reinstall is
    # fine — it just heals the stored public key.)
    if active and active != body.deviceId:
        raise HTTPException(409, "device_conflict")
    # This DEVICE is already bound to a different account. Enforces one-account-
    # per-device: a second person can't sign in on someone else's phone.
    existing = repo.get_device(body.deviceId)
    if existing and existing.get("status") == "ACTIVE" and existing.get("uid") != user["uid"]:
        audit.record(user["uid"], body.deviceId, action="DEVICE_IN_USE", outcome="DENIED",
                     detail={"owner": existing.get("uid")})
        raise HTTPException(409, "device_in_use")
    healed = active == body.deviceId
    repo.register_device(user["uid"], body)  # upsert: refreshes the public key
    audit.record(user["uid"], body.deviceId,
                 action="DEVICE_REBIND" if healed else "DEVICE_REGISTER")
    return {"deviceId": body.deviceId, "healed": healed}


@app.post("/v1/challenge")
def challenge(user=Depends(current_user), x_device_id: str = Header(default="")):
    if not x_device_id:
        raise HTTPException(400, "missing_device_id")
    return {"nonce": repo.issue_nonce(user["uid"], x_device_id)}


@app.get("/v1/sessions")
def list_sessions(verify: bool = False, user=Depends(current_user)):
    """The caller's cloud analyses. The app reconciles local sync state against
    this, so a session deleted in the cloud stops showing as 'synced'.

    Firestore is only an index. `?verify=true` additionally confirms each
    session's folder still exists in Drive, which catches artifacts deleted
    out-of-band (straight in Drive) — the index would otherwise keep claiming
    COMPLETED forever. Any session whose blobs are gone has its orphaned
    metadata purged here, so the app stops trusting it and the quota is freed.

    Verification costs one Drive call per session, so it's opt-in: the app uses
    it for an explicit pull-to-refresh, not for every screen resume.
    """
    sessions = repo.list_user_sessions(user["uid"])

    if verify:
        token = drive.access_token()
        alive = []
        for s in sessions:
            folder = s.get("driveFolderId")
            if folder and not drive.file_exists(token, folder):
                repo.delete_session(s["sessionId"])
                audit.record(user["uid"], action="SESSION_ORPHAN_PURGED",
                             target={"type": "session", "id": s["sessionId"]})
                log.info("Purged orphaned session %s (Drive folder gone)", s["sessionId"])
                continue
            alive.append(s)
        sessions = alive

    return {
        "sessions": sessions,
        "quota": {"used": len(sessions), "max": repo.resolve_user_config(user)["maxSessions"]},
    }


@app.delete("/v1/sessions/{sid}")
def delete_session(sid: str, ctx=Depends(verified_device)):
    """Erase one analysis from the cloud (GDPR right to erasure).

    Permanently deletes the Drive folder — every raw image, .dat, csv and report
    inside it — then hard-deletes the Firestore metadata (which carries the
    user's email, device id and engine parameters). Nothing is soft-deleted; the
    only trace kept is the audit record that the erasure happened.
    """
    user, device = ctx["user"], ctx["device"]
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, "session_not_found")

    folder = session.get("driveFolderId")
    if folder:
        drive.delete_file(drive.access_token(), folder)
    removed = repo.delete_session(sid)

    audit.record(user["uid"], device.get("deviceId"), action="SESSION_DELETE",
                 target={"type": "session", "id": sid},
                 detail={"filesRemoved": removed, "localSessionId": session.get("localSessionId", "")})
    log.info("Erased session %s for uid %s (%d files)", sid, user["uid"], removed)
    return {"deleted": sid, "filesRemoved": removed}


@app.get("/v1/sessions/{sid}/uploads")
def session_uploads(sid: str, user=Depends(current_user)):
    """What still needs uploading for a session — the resume path.

    An interrupted upload re-reads this instead of calling POST /v1/sessions
    again, so it continues into the same session/Drive folder rather than
    creating a duplicate.
    """
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, "session_not_found")
    return {
        "sessionId": sid,
        "status": session.get("status"),
        "uploads": repo.list_pending_uploads(sid),
    }


@app.get("/v1/sessions/{sid}/files")
def list_session_files(sid: str, user=Depends(current_user)):
    """The manifest for one analysis — what the app needs to restore it."""
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, "session_not_found")
    return {
        "sessionId": sid,
        "localSessionId": session.get("localSessionId", ""),
        "specimen": session.get("specimen"),
        "status": session.get("status"),
        "files": repo.list_session_files(sid),
    }


@app.get("/v1/files/{file_id}/content")
def download_file(file_id: str, request: Request, ctx=Depends(verified_device)):
    """Stream one file back from Drive (restore).

    Drive has no anonymous signed download, so — unlike uploads, which go
    device→Drive directly — these bytes are proxied through Cloud Run.
    Requires the same device attestation as writes (`verified_device`).
    Clients may send `Range: bytes=N-` (unsigned header; signature covers
    method + path + empty body only); we forward Range to Drive and return
    206 + Content-Range so a truncated restore can resume into a partial file.
    """
    user = ctx["user"]
    f = repo.get_file(file_id)
    if not f or f.get("uid") != user["uid"]:
        raise HTTPException(404, "file_not_found")
    drive_file_id = f.get("driveFileId")
    if not drive_file_id:
        raise HTTPException(409, "file_not_uploaded")
    token = drive.access_token()
    audit.record(user["uid"], action="FILE_DOWNLOAD", target={"type": "file", "id": file_id})
    byte_range = request.headers.get("range")
    try:
        dl = drive.open_download(token, drive_file_id, byte_range=byte_range)
    except requests.HTTPError as e:
        status = e.response.status_code if e.response is not None else 502
        if status == 416:
            raise HTTPException(416, "range_not_satisfiable") from e
        log.error("drive download %s failed: HTTP %s", drive_file_id, status)
        raise HTTPException(502, "drive_download_failed") from e

    # The stored name is client-supplied (validated for length only), so strip
    # anything that could break out of the quoted filename or inject a header.
    raw_name = f.get("name") or file_id
    safe_name = re.sub(r'[\r\n"\\]', "_", str(raw_name))[:256] or file_id
    out_headers = {
        "Content-Disposition": f'attachment; filename="{safe_name}"',
        "Accept-Ranges": "bytes",
    }
    content_range = dl.headers.get("Content-Range")
    if content_range:
        out_headers["Content-Range"] = content_range
    content_length = dl.headers.get("Content-Length")
    if content_length:
        out_headers["Content-Length"] = content_length
    elif dl.status_code == 200 and f.get("sizeBytes"):
        out_headers["Content-Length"] = str(f.get("sizeBytes", 0))

    return StreamingResponse(
        dl.iter_chunks(),
        status_code=dl.status_code,
        media_type="application/octet-stream",
        headers=out_headers,
    )


@app.post("/v1/sessions")
def create_session(body: SessionCreate, ctx=Depends(verified_device)):
    user, device = ctx["user"], ctx["device"]
    cfg = repo.resolve_user_config(user)

    # Quotas: one session == one analysis.
    if len(body.files) > cfg["maxFilesPerSession"]:
        raise HTTPException(413, "too_many_files")
    used = repo.count_user_sessions(user["uid"])
    if used >= cfg["maxSessions"]:
        raise HTTPException(
            409,
            f"session_quota_exceeded: {used}/{cfg['maxSessions']} analyses stored. "
            "Delete an older analysis to sync a new one.",
        )

    sid = uuid.uuid4().hex
    # Reserve the session doc immediately after the quota check and BEFORE any
    # Drive work, so a failure while staging folders/files can never leave file
    # docs or a Drive subtree with no parent session (which would be invisible to
    # the quota and never reclaimed). The count→reserve window is now two
    # back-to-back Firestore ops with no Drive I/O between them; the residual
    # concurrent-create race is on a *soft* quota, not a security boundary, and is
    # accepted deliberately (a transactional cross-doc count is not modelled by
    # the Firestore client uniformly and adds no security value here).
    repo.create_session(sid, user, device, body)

    try:
        token = drive.access_token()
        # Only create the Drive subfolders this manifest actually uses (a bundle
        # upload needs none — Session.zip and metadata.json sit at the session root).
        folders = drive.ensure_session_folders(token, user["uid"], sid,
                                               roles={f.role for f in body.files})
        # Remember the user's Drive subtree so account erasure can delete it by id,
        # and record the session's own folder for the delete / verify paths.
        repo.remember_user_folder(user["uid"], folders["userFolderId"])
        repo.set_session_folder(sid, folders["sessionFolderId"])

        uploads = []
        for f in body.files:
            session_uri = drive.init_resumable(token, folders[f.role], f.name, f.bytes)
            file_id = f"{sid}_{f.role}_{f.name}"
            repo.create_file(sid, user["uid"], file_id, f, session_uri)
            # 32 MiB (a 256 KiB multiple, as Drive requires): a session is now one
            # large Session.zip, so throughput is chunk-size × round-trips — small
            # chunks leave the link idle waiting on RTTs.
            uploads.append({"fileId": file_id, "uploadUrl": session_uri,
                            "chunkSize": 32 * 1024 * 1024})
    except Exception:
        # Staging failed after the reserve. Roll back the reserved session (and any
        # file docs written so far) so it does not sit against the user's quota as
        # an unusable shell; the client can safely retry a fresh create.
        repo.delete_session(sid)
        raise

    audit.record(user["uid"], device.get("deviceId"), action="SESSION_CREATE",
                 target={"type": "session", "id": sid})
    return {"sessionId": sid, "uploads": uploads}


@app.get("/v1/admin/users")
def admin_list_users(status: str = "", limit: int = 200, admin=Depends(admin_user)):
    """List users, optionally filtered by access_status (e.g. ?status=PENDING).

    `limit` (1..1000, default 200) lets an admin page past the old hard 200-user
    ceiling; values are clamped so a huge scan can't be requested by accident.
    """
    limit = max(1, min(limit, 1000))
    return {"users": repo.list_users(status, limit=limit)}


@app.post("/v1/admin/users/{uid}/approve")
def admin_approve_user(uid: str, admin=Depends(admin_user)):
    if not repo.set_user_status(uid, "APPROVED"):
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_APPROVE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "APPROVED"}


@app.post("/v1/admin/users/{uid}/revoke")
def admin_revoke_user(uid: str, admin=Depends(admin_user)):
    if not repo.set_user_status(uid, "SUSPENDED"):
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_REVOKE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "SUSPENDED"}


@app.patch("/v1/admin/users/{uid}/config")
def admin_patch_user_config(uid: str, body: UserConfigPatch, admin=Depends(admin_user)):
    """Set or clear per-user product-limit overrides on the Firestore user doc."""
    # model_dump(exclude_unset=True) keeps omitted fields out; explicit nulls
    # remain so set_user_config can DELETE_FIELD them.
    patch = body.model_dump(exclude_unset=True)
    if not patch:
        raise HTTPException(400, "empty_patch")
    resolved = repo.set_user_config(uid, patch)
    if resolved is None:
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_CONFIG", target={"type": "user", "id": uid},
                 detail={"patch": patch, "resolved": resolved})
    return {"uid": uid, "config": resolved}


@app.post("/v1/files/{file_id}/complete")
def complete_file(file_id: str, body: FileComplete, ctx=Depends(verified_device)):
    user = ctx["user"]
    rec = repo.get_file(file_id)
    if not rec or rec.get("uid") != user["uid"]:
        raise HTTPException(404, "file_not_found")
    # Verify the upload actually landed intact before trusting this completion.
    # The client uploads straight to Drive, so ask Drive for the real size/md5
    # and reject a truncated or corrupted object. Skipped on an idempotent retry
    # (already COMPLETED), which carries no new bytes.
    if rec.get("status") != "COMPLETED":
        try:
            meta = drive.get_file_meta(drive.access_token(), body.driveFileId)
        except requests.HTTPError as e:
            log.error("drive meta for %s failed: %s", body.driveFileId, e)
            raise HTTPException(502, "drive_meta_failed") from e
        if meta["size"] != rec.get("sizeBytes"):
            raise HTTPException(422, "size_mismatch")
        # Whenever Drive reports an md5 (always, for our binary blobs), the client
        # MUST supply a matching one. Previously a client that simply omitted md5
        # skipped the checksum entirely — a corrupt-but-right-sized upload could be
        # accepted. md5 is only skipped when Drive itself has none (Docs-native
        # types we never store).
        if meta["md5"] and body.md5 != meta["md5"]:
            raise HTTPException(422, "checksum_mismatch")
    outcome = repo.complete_file(file_id, user["uid"], body)
    if not outcome:
        raise HTTPException(409, "size_or_state_mismatch")
    # Only a FIRST completion advances the counter — a retried completion
    # ("already") must not double-count toward session COMPLETED.
    #
    # Advance the session the FILE belongs to, never the one the client named:
    # body.sessionId is unauthenticated input, and bump_session_progress does no
    # ownership check of its own, so trusting it let a caller complete someone
    # else's session. The binding was fixed at upload time (create_file records
    # sessionId on the file doc), so the client's copy is redundant anyway.
    if outcome == "ok":
        repo.bump_session_progress(rec["sessionId"])
    audit.record(user["uid"], ctx["device"].get("deviceId"), action="UPLOAD_COMPLETE",
                 target={"type": "file", "id": file_id})
    return {"status": "ok"}
