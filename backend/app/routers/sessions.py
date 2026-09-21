import io
import logging
import re
import uuid
import zipfile
from datetime import datetime

import requests
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from .. import audit, drive, errors, firestore_repo as repo, statuses
from .. import observability as obs
from .. import rate_limit
from .. import tasks
from ..deps import attested_or_mfa_user, current_user, device_or_legacy_reader, verified_device
from ..models import SessionCreate
from ..session_provision import provision_session
from ..validation import PageToken, SessionId
from .account import json_dumps

log = logging.getLogger("indic")
router = APIRouter()


@router.get("/v1/sessions")
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
            raise HTTPException(429, errors.RATE_LIMITED)
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


@router.delete("/v1/sessions/{sid}")
def delete_session(sid: SessionId, ctx=Depends(verified_device)):
    """Erase one analysis from the cloud (GDPR right to erasure).

    Permanently deletes the Drive folder — every raw image, .dat, csv and report
    inside it — then hard-deletes the Firestore metadata (which carries the
    user's email, device id and engine parameters). Nothing is soft-deleted; the
    only trace kept is the audit record that the erasure happened.
    """
    user, device = ctx["user"], ctx["device"]
    if not rate_limit.erase_bucket.allow(user["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, errors.SESSION_NOT_FOUND)

    folder = session.get("driveFolderId")
    if folder:
        drive.delete_file(drive.access_token(), folder)
    removed = repo.delete_session(sid)

    audit.record(user["uid"], device.get("deviceId"), action="SESSION_DELETE",
                 target={"type": "session", "id": sid},
                 detail={"filesRemoved": removed, "localSessionId": session.get("localSessionId", "")})
    log.info("Erased session %s for uid %s (%d files)", sid, user["uid"], removed)
    return {"deleted": sid, "filesRemoved": removed}


@router.get("/v1/sessions/{sid}/uploads")
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
        raise HTTPException(429, errors.RATE_LIMITED)
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, errors.SESSION_NOT_FOUND)
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


@router.get("/v1/sessions/{sid}/files")
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
        raise HTTPException(429, errors.RATE_LIMITED)
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, errors.SESSION_NOT_FOUND)
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


#: Listed first in the archive so a reader has the inventory before the bytes,
#: and can tell which entry is missing if the transfer died half way.
_BUNDLE_MANIFEST = "manifest.json"

#: Zip cannot represent a date before this.
_ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)


class _ZipSink(io.RawIOBase):
    """A write-only file object that hands each write straight back out.

    `zipfile` wants something it can call `write()` on; `StreamingResponse`
    wants a generator it can pull from. This is the join between the two: the
    zip writer writes, the generator drains. Nothing accumulates beyond the
    chunk in flight, which is the entire point — at the 600-file ceiling an
    analysis must not be assembled in memory first.
    """

    def __init__(self):
        self._buf = bytearray()

    def writable(self) -> bool:
        return True

    def write(self, data) -> int:
        self._buf += data
        return len(data)

    def drain(self) -> bytes:
        out = bytes(self._buf)
        del self._buf[:]
        return out


def _entry_name(artifact: dict) -> str:
    """`<role>/<name>`, matching the app's own `SessionZip.entryName`.

    The name is attacker-supplied in the sense that it came from a client
    upload, so it is reduced to a bare leaf here: no directory components, no
    drive letters, nothing that starts with a dot. A zip that unpacks outside
    the directory it was extracted into is the oldest bug in the format.
    """
    role = re.sub(r"[^A-Za-z0-9_-]", "_", str(artifact.get("role") or "extras"))
    leaf = str(artifact.get("name") or "").replace("\\", "/").rsplit("/", 1)[-1]
    leaf = re.sub(r'[\r\n:"|?*]', "_", leaf).lstrip(". ")
    return f"{role}/{leaf or artifact['fileId']}"


def _zip_time(value) -> tuple:
    if isinstance(value, datetime) and value.year >= 1980:
        return (value.year, value.month, value.day, value.hour, value.minute, value.second)
    return _ZIP_EPOCH


@router.get("/v1/sessions/{sid}/bundle")
def download_session_bundle(sid: SessionId, ctx=Depends(attested_or_mfa_user)):
    """One analysis as a single zip — how the data leaves through a browser.

    `GET /v1/files/{id}/content` already serves the bytes, but it is
    device-attested and one file at a time: the phone's restore path, useless
    to someone sitting at a desk who has lost the phone. This route is the
    same data at the step-up tier, which a browser can satisfy with a second
    factor and a recent sign-in, and in one request instead of six hundred.

    Every artifact goes in at `<role>/<name>`, the layout the app writes and
    reads, so an archive pulled from the web unpacks into something the app
    recognises. Modern analyses store two entries (`bundle/Session.zip` and
    `extras/Extras.zip`); older ones store a file per artifact. Both are the
    same loop — the archive is of whatever was stored, with no special case.

    Stored, not deflated: the contents are already-compressed PNG and zip
    data, so compressing again would spend CPU per byte to save nothing, and
    the stream would run at the speed of the compressor rather than of Drive.

    **Every refusal happens before the first byte.** Once a response body has
    started there is no status code left to send, so the ownership check, the
    entitlement check and the Drive token are all resolved up front. A failure
    after that can only truncate the archive, which is why the manifest is
    written first and why the zip's central directory — written last — is the
    signal that the transfer completed.
    """
    user = ctx["user"]
    if not rate_limit.download_bucket.allow(user["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    session = repo.get_session(sid)
    if not session or session.get("uid") != user["uid"]:
        raise HTTPException(404, errors.SESSION_NOT_FOUND)
    if not repo.cloud_backup_enabled(user):
        raise HTTPException(403, errors.FEATURE_NOT_LICENSED_DETAIL)
    artifacts = repo.list_session_artifacts(sid)
    if not artifacts:
        # The session exists but nothing finished uploading, so there is
        # nothing to archive. Same code the single-file route uses for it.
        raise HTTPException(409, errors.FILE_NOT_UPLOADED)
    try:
        token = drive.access_token()
    except Exception:
        log.exception("session_bundle_token_failed")
        raise HTTPException(502, errors.DRIVE_DOWNLOAD_FAILED) from None

    manifest = json_dumps({
        "sessionId": sid,
        "specimen": session.get("specimen"),
        "createdAt": session.get("createdAt"),
        "fileCount": len(artifacts),
        "files": [{
            "entry": _entry_name(a), "fileId": a["fileId"], "role": a.get("role"),
            "name": a.get("name"), "sizeBytes": a.get("sizeBytes", 0),
            "sha256": a.get("sha256"),
        } for a in artifacts],
    }).encode()

    audit.record(
        user["uid"], (ctx.get("device") or {}).get("deviceId"),
        action="SESSION_BUNDLE_DOWNLOAD",
        target={"type": "session", "id": sid},
        detail={"fileCount": len(artifacts), "via": ctx.get("via") or ""},
    )

    def stream():
        sink = _ZipSink()
        with zipfile.ZipFile(sink, "w", zipfile.ZIP_STORED, allowZip64=True) as zf:
            zf.writestr(_BUNDLE_MANIFEST, manifest)
            if (chunk := sink.drain()):
                yield chunk
            for artifact in artifacts:
                info = zipfile.ZipInfo(_entry_name(artifact),
                                       date_time=_zip_time(artifact.get("createdAt")))
                # Declared up front so zipfile can decide on zip64 headers
                # before it has seen the bytes; it cannot seek back to fix
                # them on an unseekable sink.
                info.file_size = int(artifact.get("sizeBytes") or 0)
                try:
                    dl = drive.open_download(token, artifact["driveFileId"])
                    with zf.open(info, "w") as dst:
                        for part in dl.iter_chunks():
                            dst.write(part)
                            if (chunk := sink.drain()):
                                yield chunk
                except requests.RequestException:
                    obs.log_event(log, logging.ERROR, "session_bundle_failed",
                                  outcome="error", errorCode=errors.DRIVE_DOWNLOAD_FAILED,
                                  dependency="drive")
                    raise
                if (chunk := sink.drain()):
                    yield chunk
        yield sink.drain()

    return StreamingResponse(
        stream(),
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="semper-analysis-{sid}.zip"',
            "Cache-Control": "no-store",
        },
    )


@router.post("/v1/sessions")
def create_session(body: SessionCreate, request: Request, ctx=Depends(verified_device)):
    """Record an analysis: create the session and hand back its upload slots.

    Open to every approved account, demo included. Recording is not the
    licensed feature — retrieval is. A demo account's frames and results are
    stored under the same quota (`DEMO_MAX_ANALYSES`) and are never deleted
    on downgrade; what a licence buys is getting them back (`/content` and
    the session bundle), so the `cloudBackupEnabled` gate lives on those two
    routes and deliberately not here. Installed builds that predate licensing
    retry a 403 from this route forever, which is one more reason the gate
    would be the wrong shape.
    """
    user, device = ctx["user"], ctx["device"]
    cfg = repo.resolve_user_config(user)

    if not rate_limit.session_bucket.allow(user["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)

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
        raise HTTPException(413, errors.TOO_MANY_FILES)
    used = repo.count_user_sessions(user["uid"])
    if used >= cfg["maxSessions"]:
        raise HTTPException(
            409,
            f"{errors.SESSION_QUOTA_EXCEEDED}: {used}/{cfg['maxSessions']} analyses stored. "
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
    # which is what makes the task idempotent and resumable. Batched — a
    # 3-object split-bundle session was 3 round trips here for no reason.
    try:
        repo.create_files_batch(sid, user["uid"], [(f"{sid}_{f.role}_{f.name}", f) for f in body.files])
    except Exception:
        repo.delete_session(sid)
        raise

    counts = obs.metrics_counts(body.metrics, file_count=len(body.files))
    audit.record(
        user["uid"],
        device.get("deviceId"),
        action="SESSION_CREATE",
        target={"type": "session", "id": sid},
        detail=counts,
    )
    # Access-log middleware reads this after the response returns.
    request.state.usage_counts = counts

    # Opening a Drive resumable session per file is ~2 round-trips each; at the
    # 600-file ceiling that cannot fit in a 60s request. Hand it to Cloud Tasks
    # and let the client poll /uploads, which it already does for resume.
    if tasks.enqueue_provision(sid):
        repo.set_session_status(sid, statuses.SESSION_PROVISIONING)
        obs.log_event(log, logging.INFO, "session_provision_queued",
                      outcome="ok", stage="queued", count=len(body.files))
        return {"sessionId": sid, "status": statuses.SESSION_PROVISIONING, "uploads": []}

    # No queue configured (local dev, tests, or an environment that has not
    # created it): provision inline. Same outcome, slower request. Because
    # nothing will retry, a failure here rolls the whole session back rather
    # than leaving a shell against the user's quota.
    provision_session(sid, purge_on_failure=True)
    session = repo.get_session(sid) or {}
    uploads, _ = repo.list_pending_uploads(sid)
    return {"sessionId": sid, "status": session.get("status"), "uploads": uploads}
