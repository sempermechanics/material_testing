import json
import logging
import re
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import requests
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from . import audit, drive, firestore_repo as repo
from . import observability as obs
from . import tasks
from .config import settings
from .deps import admin_user, current_user, device_or_legacy_reader, verified_device
from .models import (
    DeviceReg,
    FileComplete,
    ProvisionTask,
    SessionCreate,
    UserConfigPatch,
)
from . import rate_limit
from .validation import (
    AccessStatus,
    DocumentId,
    PageToken,
    SessionId,
    Uid,
    require_header_identifier,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("indic")
_access_log = logging.getLogger("indic.access")


def json_dumps(value) -> str:
    """Compact JSON for the streamed export. `default=str` because Firestore
    hands back datetimes, which json cannot serialise."""
    return json.dumps(value, separators=(",", ":"), default=str)


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
    if settings.ON_CLOUD_RUN and not settings.REQUIRE_ATTESTED_UPLOADS:
        # Deliberately temporary, but must never be silent: while this is off,
        # GET /v1/sessions/{sid}/uploads hands Drive capability URLs to any
        # ID-token caller with no device signature (see deps.device_or_legacy_reader).
        # Flip REQUIRE_ATTESTED_UPLOADS=1 once legacy_unattested_uploads is zero.
        log.warning(
            "=== REQUIRE_ATTESTED_UPLOADS unset: /uploads accepts unattested "
            "legacy callers. Temporary migration window — set it to 1 once the "
            "fleet has moved. ==="
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    _startup_checks()
    yield


# Interactive docs are served locally (useful) but never from a deployed
# service: /docs, /redoc and /openapi.json publish the full route inventory —
# including every /v1/admin/* path — to anyone who reaches the origin, and they
# are not declared in gateway/openapi.yaml so nothing else gates them.
_docs_enabled = not settings.ON_CLOUD_RUN
app = FastAPI(
    title="Semper API",
    version="1.0",
    lifespan=lifespan,
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    """Apply browser-safe defaults without claiming HTTP is secure in local dev."""
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Content-Security-Policy"] = "frame-ancestors 'none'"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Permissions-Policy"] = (
        "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
    )
    forwarded_proto = request.headers.get("x-forwarded-proto", "").split(",", 1)[0].strip()
    if settings.ON_CLOUD_RUN and forwarded_proto == "https":
        response.headers["Strict-Transport-Security"] = (
            "max-age=31536000; includeSubDomains"
        )
    return response


@app.middleware("http")
async def access_log(request: Request, call_next):
    """One structured JSON line per request with UTC timestamp, request ID,
    device context, and outcome. Stamps X-Request-Id. Never logs tokens."""
    start = time.perf_counter()
    request_id = uuid.uuid4().hex[:12]
    request.state.uid = None
    request.state.device_id = None
    request.state.request_id = request_id
    ctx_token = obs.bind_request(request_id)
    status = 500
    try:
        try:
            response = await call_next(request)
        except HTTPException:
            raise
        except obs.DependencyError:
            raise
        except Exception:
            obs.report_exception(log, error_code="internal_error")
            # In production, never leak exception text to clients. Locally and in
            # tests, re-raise so pytest and debuggers still see the real failure.
            if settings.ON_CLOUD_RUN:
                response = JSONResponse(status_code=500, content={"detail": "internal_error"})
            else:
                raise
        status = response.status_code
        response.headers["X-Request-Id"] = request_id
        return response
    finally:
        latency_ms = round((time.perf_counter() - start) * 1000, 1)
        outcome = "ok" if status < 400 else ("client_error" if status < 500 else "server_error")
        uid = getattr(request.state, "uid", None)
        device_id = getattr(request.state, "device_id", None)
        obs.bind_uid(uid)
        obs.bind_device(device_id)
        obs.log_event(
            _access_log, logging.INFO, "http_access",
            method=request.method,
            path=request.url.path,
            status=status,
            latencyMs=latency_ms,
            outcome=outcome,
            errorCode=None if status < 400 else f"http_{status}",
        )
        obs.reset_request(ctx_token)


# Route handlers are deliberately plain `def`, not `async def`. Every Firestore
# and Drive call in this service is synchronous/blocking (google-cloud-firestore
# sync client + `requests`), so an `async def` handler would run that blocking
# I/O directly on the event loop and stall all other requests sharing the worker.
# A `def` handler is instead dispatched to Starlette's threadpool, which is the
# correct model here. Async dependencies (e.g. verified_device awaiting the body)
# still resolve on the loop first — mixing a sync route with an async dependency
# is fully supported. Only genuinely-awaiting code stays async (lifespan, the
# access_log middleware). Do not "modernize" these back to async def.


@app.exception_handler(obs.DependencyError)
async def dependency_error_handler(request: Request, exc: obs.DependencyError):
    obs.log_event(
        log, logging.ERROR, "dependency_failure",
        outcome="error", errorCode=exc.code, dependency=exc.dependency,
        status=exc.status_code,
    )
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.code})


def _client_key(request: Request) -> str:
    """Best available caller identity for the unauthenticated health limiter.

    `request.client.host` behind API Gateway / the Cloud Run front end is the
    *proxy*, so keying on it alone puts every external caller in one bucket —
    one noisy client would then starve the load balancer's own probes. Trust the
    leftmost X-Forwarded-For entry, which the Google front end sets, and fall
    back to the socket peer when the header is absent (direct/local calls).
    """
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        first = forwarded.split(",", 1)[0].strip()
        if first:
            return first[:64]
    return request.client.host if request.client else "unknown"


@app.get("/healthz")
def healthz(request: Request):
    # Liveness only: process is up. Do not probe dependencies here — a slow
    # Firestore/Drive outage must not restart healthy instances.
    if not rate_limit.health_bucket.allow(_client_key(request)):
        raise HTTPException(429, "rate_limited")
    return {"ok": True}


@app.get("/readyz")
def readyz(request: Request):
    """Readiness: Firestore + Drive must answer within a bounded budget.

    Returns stable 503 detail codes (`firestore_unreachable`, `drive_unhealthy`,
    …) so load balancers and smoke checks can act without parsing messages.
    """
    if not rate_limit.health_bucket.allow(_client_key(request)):
        raise HTTPException(429, "rate_limited")
    started = time.perf_counter()
    try:
        repo.ping()
        drive.ping()
    except obs.DependencyError:
        raise
    except Exception as e:  # noqa: BLE001
        obs.log_event(
            log, logging.ERROR, "readyz_unexpected",
            outcome="error", errorCode="readyz_failed", dependency="unknown",
        )
        raise obs.DependencyError("readyz_failed", "unknown") from e
    latency_ms = round((time.perf_counter() - started) * 1000, 1)
    obs.log_event(
        log, logging.INFO, "readyz_ok",
        outcome="ok", latencyMs=latency_ms, dependency="firestore+drive",
    )
    return {"ok": True, "checks": {"firestore": "ok", "drive": "ok"}}


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
    if not rate_limit.export_bucket.allow(uid):
        raise HTTPException(429, "rate_limited")
    profile = repo.get_user(uid) or {}

    audit.record(uid, action="DATA_EXPORT", target={"type": "user", "id": uid})
    header = {
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
        "artifactDownload": {
            "endpoint": "/v1/files/{fileId}/content",
            "note": "Images, .dat results, CSVs and reports are downloadable per file "
                    "using the fileId values above, or via the app's Restore screen.",
        },
    }

    def stream():
        """Emit the document incrementally.

        The previous version built the whole account in memory first — every
        session, plus a files query per session — before writing a byte. For a
        busy account that is an unbounded allocation and a long silence before
        the first byte, on a request with a 60s budget. Streaming keeps memory
        flat and starts the response immediately; `"complete": true` is written
        last, so a truncated transfer is detectable rather than looking like a
        smaller-but-valid export.
        """
        prefix = json_dumps(header)
        yield prefix[:-1].encode()  # drop the closing brace; we continue the object
        yield b',"sessions":['
        first = True
        count = 0
        for s in repo.iter_all_user_sessions(uid):
            s = dict(s)
            s["files"] = repo.list_session_files_all(s["sessionId"])
            yield (b"" if first else b",") + json_dumps(s).encode()
            first = False
            count += 1
        yield b'],"sessionCount":' + str(count).encode()
        yield b',"complete":true}'

    return StreamingResponse(
        stream(),
        media_type="application/json",
        headers={
            "Content-Disposition": 'attachment; filename="semper-account-export.json"',
            # A full-account dump of personal data must not sit in a shared cache.
            "Cache-Control": "no-store",
        },
    )


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
    if not rate_limit.erase_bucket.allow(uid):
        raise HTTPException(429, "rate_limited")
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
        sessions = repo.iter_user_sessions(uid)
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
    if not rate_limit.device_register_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
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
    x_device_id = require_header_identifier(
        x_device_id, name="device_id", maximum=128
    )
    if not rate_limit.challenge_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
    return {"nonce": repo.issue_nonce(user["uid"], x_device_id)}


@app.get("/v1/sessions")
def list_sessions(
    verify: bool = False,
    page_size: int = 50,
    page_token: PageToken = "",
    user=Depends(current_user),
):
    """The caller's cloud analyses. The app reconciles local sync state against
    this, so a session deleted in the cloud stops showing as 'synced'.

    Cursor-paginated (`page_size` 1..100, `page_token`, `nextPageToken`). Quota
    `used` is the full account count, not the page length.

    Firestore is only an index. `?verify=true` additionally confirms each
    session on the *current page* still exists in Drive (bounded parallel
    probes — not a full-account N+1). Orphaned metadata on that page is purged.
    """
    page_size = max(1, min(page_size, 100))
    if verify:
        if not rate_limit.session_verify_bucket.allow(user["uid"]):
            raise HTTPException(429, "rate_limited")
    sessions, next_token = repo.list_user_sessions(
        user["uid"], limit=page_size, page_token=page_token or None,
    )

    purged = 0
    indeterminate = 0
    if verify and sessions:
        token = drive.access_token()
        folders = [s.get("driveFolderId") for s in sessions if s.get("driveFolderId")]
        probe = drive.probe_files(token, folders)
        alive = []
        for s in sessions:
            folder = s.get("driveFolderId")
            state = probe.get(folder, drive.UNKNOWN) if folder else drive.ALIVE
            # Purge ONLY on a confirmed miss. An unreachable Drive (5xx, timeout,
            # token failure) reports UNKNOWN, and deleting the user's session
            # metadata on that would turn a transient outage into data loss —
            # the Drive bytes would survive with nothing left pointing at them.
            if state == drive.MISSING:
                repo.delete_session(s["sessionId"])
                audit.record(user["uid"], action="SESSION_ORPHAN_PURGED",
                             target={"type": "session", "id": s["sessionId"]})
                obs.log_event(
                    log, logging.INFO, "session_orphan_purged",
                    outcome="ok", errorCode="orphan_purged", dependency="drive",
                )
                purged += 1
                continue
            if state == drive.UNKNOWN:
                indeterminate += 1
            alive.append(s)
        sessions = alive
        if indeterminate:
            obs.log_event(
                log, logging.WARNING, "session_verify_indeterminate",
                outcome="degraded", errorCode="drive_probe_unknown",
                dependency="drive", count=indeterminate,
            )

    used = repo.count_user_sessions(user["uid"])
    return {
        "sessions": sessions,
        "quota": {"used": used, "max": repo.resolve_user_config(user)["maxSessions"]},
        "page": {
            "size": page_size,
            "count": len(sessions),
            "nextPageToken": next_token,
            "hasMore": bool(next_token),
        },
        # `indeterminate` tells the client the verification was incomplete, so a
        # session still listed is not proof it was confirmed present.
        "verify": (
            {"requested": verify, "purged": purged, "indeterminate": indeterminate}
            if verify else None
        ),
    }


@app.delete("/v1/sessions/{sid}")
def delete_session(sid: SessionId, ctx=Depends(verified_device)):
    """Erase one analysis from the cloud (GDPR right to erasure).

    Permanently deletes the Drive folder — every raw image, .dat, csv and report
    inside it — then hard-deletes the Firestore metadata (which carries the
    user's email, device id and engine parameters). Nothing is soft-deleted; the
    only trace kept is the audit record that the erasure happened.
    """
    user, device = ctx["user"], ctx["device"]
    if not rate_limit.erase_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
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
def session_uploads(
    sid: SessionId,
    page_size: int = 1000,
    page_token: PageToken = "",
    ctx=Depends(device_or_legacy_reader),
):
    """What still needs uploading for a session — the resume path.

    An interrupted upload re-reads this instead of calling POST /v1/sessions
    again, so it continues into the same session/Drive folder rather than
    creating a duplicate.

    Device-signed, not merely token-authenticated: the response carries Drive
    resumable upload URIs, which are bearer capabilities to write into the
    user's Drive folder. Every other endpoint that mints or consumes those URIs
    (POST /v1/sessions, POST /v1/files/{id}/complete) requires attestation, so a
    stolen ID token alone must not be able to recover them here either.

    Temporarily behind `device_or_legacy_reader`: testers on an older build still
    read this with an ID token only, so an unattested read is accepted (and
    logged as `legacy_unattested_uploads`) until REQUIRE_ATTESTED_UPLOADS is set.
    A client that attests is always held to the strict path — see the wrapper.
    """
    user = ctx["user"]
    if not rate_limit.listing_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, "session_not_found")
    page_size = max(1, min(page_size, 1000))
    uploads, next_token = repo.list_pending_uploads(
        sid, limit=page_size, page_token=page_token or None,
    )
    return {
        "sessionId": sid,
        # PROVISIONING means the upload targets are still being opened — the
        # client should poll rather than treat an empty list as "nothing to do".
        "status": session.get("status"),
        "provisionError": session.get("provisionError"),
        "uploads": uploads,
        "page": {
            "size": page_size,
            "count": len(uploads),
            "nextPageToken": next_token,
            "hasMore": bool(next_token),
        },
    }


@app.get("/v1/sessions/{sid}/files")
def list_session_files(
    sid: SessionId,
    page_size: int = 1000,
    page_token: PageToken = "",
    user=Depends(current_user),
):
    """The manifest for one analysis — what the app needs to restore it.

    Cursor-paginated. This silently truncated at 2000 files before, which for a
    restore means a manifest quietly missing entries.
    """
    if not rate_limit.listing_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, "session_not_found")
    page_size = max(1, min(page_size, 1000))
    files, next_token = repo.list_session_files(
        sid, limit=page_size, page_token=page_token or None,
    )
    return {
        "sessionId": sid,
        "localSessionId": session.get("localSessionId", ""),
        "specimen": session.get("specimen"),
        "status": session.get("status"),
        "files": files,
        "page": {
            "size": page_size,
            "count": len(files),
            "nextPageToken": next_token,
            "hasMore": bool(next_token),
        },
    }


@app.get("/v1/files/{file_id}/content")
def download_file(file_id: DocumentId, request: Request, ctx=Depends(verified_device)):
    """Stream one file back from Drive (restore).

    Drive has no anonymous signed download, so — unlike uploads, which go
    device→Drive directly — these bytes are proxied through Cloud Run.
    Requires the same device attestation as writes (`verified_device`).
    Clients may send `Range: bytes=N-` (unsigned header; signature covers
    method + path + empty body only); we forward Range to Drive and return
    206 + Content-Range so a truncated restore can resume into a partial file.
    """
    user = ctx["user"]
    # Check the bucket before the audit write: audit.record is a Firestore
    # .add(), so limiting afterwards still charges a write per rejected request
    # and files a FILE_DOWNLOAD entry for a download that never happened.
    if not rate_limit.download_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
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
    except requests.RequestException as e:
        status = 502
        if isinstance(e, requests.HTTPError) and e.response is not None:
            status = e.response.status_code
            if status == 416:
                raise HTTPException(416, "range_not_satisfiable") from e
        log.error("drive download %s failed: %s", drive_file_id, e)
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

    if not rate_limit.session_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")

    # Idempotent retry: same localSessionId + still in flight → return existing.
    existing = repo.find_incomplete_session(user["uid"], body.localSessionId)
    if existing:
        sid = existing["sessionId"]
        uploads, _ = repo.list_pending_uploads(sid)
        return {
            "sessionId": sid,
            "status": existing.get("status"),
            "uploads": uploads,
        }

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

    # Write the file docs (cheap, no Drive I/O) so the manifest is durable before
    # any upload target exists. Provisioning then only has to fill in uploadUrl,
    # which is what makes the task idempotent and resumable.
    try:
        for f in body.files:
            repo.create_file(sid, user["uid"], f"{sid}_{f.role}_{f.name}", f, None)
    except Exception:
        repo.delete_session(sid)
        raise

    audit.record(user["uid"], device.get("deviceId"), action="SESSION_CREATE",
                 target={"type": "session", "id": sid})

    # Opening a Drive resumable session per file is ~2 round-trips each; at the
    # 600-file ceiling that cannot fit in a 60s request. Hand it to Cloud Tasks
    # and let the client poll /uploads, which it already does for resume.
    if tasks.enqueue_provision(sid):
        repo.set_session_status(sid, "PROVISIONING")
        obs.log_event(log, logging.INFO, "session_provision_queued",
                      outcome="ok", stage="queued", count=len(body.files))
        return {"sessionId": sid, "status": "PROVISIONING", "uploads": []}

    # No queue configured (local dev, tests, or an environment that has not
    # created it): provision inline. Same outcome, slower request. Because
    # nothing will retry, a failure here rolls the whole session back rather
    # than leaving a shell against the user's quota.
    provision_session(sid, purge_on_failure=True)
    session = repo.get_session(sid) or {}
    uploads, _ = repo.list_pending_uploads(sid)
    return {"sessionId": sid, "status": session.get("status"), "uploads": uploads}


def purge_session(sid: str) -> None:
    """Delete a session's Drive folder AND its Firestore docs.

    repo.delete_session is Firestore-only, so using it alone as a rollback left
    the Drive subtree (and any resumable sessions already opened inside it)
    orphaned, with nothing left pointing at them.
    """
    session = repo.get_session(sid) or {}
    folder = session.get("driveFolderId")
    if folder:
        try:
            drive.delete_file(drive.access_token(), folder)
        except Exception as e:  # noqa: BLE001
            # Best effort: the Firestore rollback below still has to happen, or
            # the user is charged quota for a session they cannot use.
            log.warning("rollback could not delete Drive folder %s: %s", folder, e)
    repo.delete_session(sid)


def provision_session(sid: str, *, purge_on_failure: bool = False) -> dict:
    """Open a Drive resumable session for every file that still lacks one.

    Idempotent and resumable: it only looks at files with no uploadUrl, so a
    retried task never mints a second upload URI for a file that already has
    one. Runs in the Cloud Tasks worker, or inline when no queue is configured.

    `purge_on_failure` is for the inline path, where no retry is coming: the
    session is rolled back completely. The queued path instead leaves it
    PROVISION_FAILED so Cloud Tasks can retry and a polling client is told to
    stop waiting.
    """
    session = repo.get_session(sid)
    if not session:
        return {"sessionId": sid, "provisioned": 0, "status": "gone"}
    uid = session["uid"]
    started = time.monotonic()

    try:
        token = drive.access_token()
        roles = {f["role"] for f in repo.iter_unprovisioned_files(sid)}
        if roles:
            # Only create the Drive subfolders this manifest actually uses (a
            # bundle upload needs none — Session.zip and metadata.json sit at
            # the session root).
            folders = drive.ensure_session_folders(token, uid, sid, roles=roles)
            repo.remember_user_folder(uid, folders["userFolderId"])
            repo.set_session_folder(sid, folders["sessionFolderId"])

            pending = list(repo.iter_unprovisioned_files(sid))

            def open_one(f):
                uri = drive.init_resumable(token, folders[f["role"]], f["name"], f["sizeBytes"])
                repo.set_file_upload_url(f["fileId"], uri)

            # Bounded fan-out rather than a serial loop — same pattern as
            # drive.probe_files. Serially this was the whole problem.
            if pending:
                workers = max(1, min(settings.TASKS_PROVISION_WORKERS, len(pending)))
                with ThreadPoolExecutor(max_workers=workers) as pool:
                    for result in pool.map(open_one, pending):
                        _ = result
            provisioned = len(pending)
        else:
            provisioned = 0
    except Exception as e:  # noqa: BLE001
        obs.log_event(log, logging.ERROR, "session_provision_failed",
                      outcome="error", errorCode="drive_provision_failed", dependency="drive")
        log.error("provisioning session %s failed: %s", sid, e)
        if purge_on_failure:
            # Nothing will retry, so leave nothing behind — including the Drive
            # subtree and any resumable sessions already opened inside it.
            purge_session(sid)
        else:
            # A retry is coming. Keep the session so the task can resume, and
            # mark it so a polling client stops waiting and rebuilds instead.
            repo.set_session_status(sid, "PROVISION_FAILED", error_code="drive_provision_failed")
        raise

    repo.set_session_status(sid, "UPLOADING")
    obs.log_event(log, logging.INFO, "session_provisioned", outcome="ok",
                  count=provisioned, latencyMs=round((time.monotonic() - started) * 1000, 1))
    return {"sessionId": sid, "provisioned": provisioned, "status": "UPLOADING"}


@app.post("/v1/tasks/provision-session")
def provision_session_task(body: ProvisionTask, caller=Depends(tasks.tasks_caller)):
    """Cloud Tasks callback: open the Drive upload targets for one session.

    Authenticated by the OIDC token Cloud Tasks attaches (see tasks.tasks_caller)
    — not a user route. Cloud Tasks retries on a non-2xx, and provision_session
    is idempotent, so a retry resumes rather than duplicating work.
    """
    return provision_session(body.sessionId)


@app.get("/v1/admin/users")
def admin_list_users(
    status: AccessStatus = "",
    limit: int = 50,
    page_token: PageToken = "",
    admin=Depends(admin_user),
):
    """List users, optionally filtered by access_status (e.g. ?status=PENDING).

    Cursor-paginated: `limit` (1..200, default 50) and optional `page_token`.
    Response includes `nextPageToken` / `hasMore`.
    """
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, "rate_limited")
    limit = max(1, min(limit, 200))
    users, next_token = repo.list_users(
        status, limit=limit, page_token=page_token or None,
    )
    return {
        "users": users,
        "page": {
            "size": limit,
            "count": len(users),
            "nextPageToken": next_token,
            "hasMore": bool(next_token),
        },
    }


@app.post("/v1/admin/users/{uid}/approve")
def admin_approve_user(uid: Uid, ctx=Depends(verified_device), admin=Depends(admin_user)):
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, "rate_limited")
    if not repo.set_user_status(uid, "APPROVED"):
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_APPROVE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "APPROVED"}


@app.post("/v1/admin/users/{uid}/revoke")
def admin_revoke_user(uid: Uid, ctx=Depends(verified_device), admin=Depends(admin_user)):
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, "rate_limited")
    if not repo.set_user_status(uid, "SUSPENDED"):
        raise HTTPException(404, "user_not_found")
    audit.record(admin["uid"], action="ADMIN_REVOKE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "SUSPENDED"}


@app.patch("/v1/admin/users/{uid}/config")
def admin_patch_user_config(uid: Uid, body: UserConfigPatch,
                            ctx=Depends(verified_device), admin=Depends(admin_user)):
    """Set or clear per-user product-limit overrides on the Firestore user doc."""
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, "rate_limited")
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
def complete_file(file_id: DocumentId, body: FileComplete, ctx=Depends(verified_device)):
    user = ctx["user"]
    if not rate_limit.file_complete_bucket.allow(user["uid"]):
        raise HTTPException(429, "rate_limited")
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
        # Bind the object to this session's Drive folder — never trust a client
        # pointer into an arbitrary shared-drive file the download proxy would
        # then stream under the SA.
        session = repo.get_session(rec["sessionId"])
        folder = (session or {}).get("driveFolderId")
        parents = meta.get("parents") or []
        if not folder or folder not in parents:
            raise HTTPException(403, "file_not_in_session")
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
