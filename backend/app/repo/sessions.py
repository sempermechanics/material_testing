"""Cloud analyses: sessions, their files, paging, provisioning and completion.
"""

from enum import StrEnum

from google.api_core.exceptions import NotFound

from .. import apps, statuses
from ..models import FileComplete, FileSpec, SessionCreate

from . import _base
from ._base import (
    _BATCH_LIMIT,
    _cursor_page,
    _scan,
    db,
    _delete_query_until_empty,
    _run_tx,
    SCHEMA_VERSION,
)


# Soft cap on ordinary response manifests. Erasure deliberately does not use
# this cap; it loops in bounded batches until the relevant query is empty.
_LIST_SOFT_LIMIT = 2000


# ---------------- sessions / files ----------------
def delete_session(sid: str) -> int:
    """Hard-delete an analysis' metadata: every file doc, then the session doc.

    GDPR erasure — records are removed, not flagged. Returns the file count.
    Firestore batches cap at 500 writes, so this chunks.
    """
    file_count = _delete_query_until_empty(
        db().collection("files").where("sessionId", "==", sid)
    )
    db().collection("sessions").document(sid).delete()
    return file_count


def get_session(sid: str):
    snap = db().collection("sessions").document(sid).get()
    return {**snap.to_dict(), "sessionId": sid} if snap.exists else None


def get_file(file_id: str):
    snap = db().collection("files").document(file_id).get()
    return {**snap.to_dict(), "fileId": file_id} if snap.exists else None


def metadata_file_id(sid: str) -> str:
    """The file doc of a session's metadata.json: `{sid}_{role}_{name}`, as
    POST /v1/sessions names every file doc."""
    return f"{sid}_metadata_metadata.json"


def replace_file_content(sid: str, file_id: str, size_bytes: int, sha256: str, drive_md5: str | None) -> None:
    """Record new bytes for a COMPLETED file (a re-sent metadata.json): its size
    and checksums, so the restore's size check and the bundle manifest match
    what Drive now holds. The session's `updatedAt` moves with it."""
    db().collection("files").document(file_id).update({
        "sizeBytes": size_bytes,
        "sha256": sha256,
        "driveMd5": drive_md5,
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    db().collection("sessions").document(sid).update({"updatedAt": _base.firestore.SERVER_TIMESTAMP})


def _session_file_docs(sid: str, page_size: int = _BATCH_LIMIT):
    """Every file document in a session, fetched [page_size] at a time."""
    col = db().collection("files")
    return _scan(col, col.where("sessionId", "==", sid), chunk=page_size)


def _file_view(doc_id: str, f: dict, *fields: str) -> dict:
    """A file as a listing shows it: id, name, role and size, plus `fields`."""
    return {
        "fileId": doc_id,
        "name": f.get("name"),
        "role": f.get("role"),
        "sizeBytes": f.get("sizeBytes", 0),
        **{k: f.get(k) for k in fields},
    }


#: What a restore manifest and the export carry for each file.
_MANIFEST_FIELDS = ("sha256", "status")


def _page_session_files(sid: str, limit: int, page_token: str | None):
    """One cursor-paged slice of a session's files. Returns (docs, next_token).

    Both public listings below used to take a flat `.limit(_LIST_SOFT_LIMIT)`
    with no cursor and no signal, so a session larger than the cap was silently
    truncated — a restore manifest would simply be missing files, and the resume
    list would stop offering them.
    """
    col = db().collection("files")
    return _cursor_page(col, col.where("sessionId", "==", sid), limit, page_token)


def list_pending_uploads(
    sid: str,
    limit: int = _LIST_SOFT_LIMIT,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Files in a session that still need bytes, with their resumable URIs.

    Lets an interrupted upload resume the SAME session instead of creating a
    duplicate (which would also burn the per-user analysis quota). Files already
    COMPLETED have their uploadUrl cleared, so they're naturally excluded.

    Returns (page, next_page_token_or_None). A file that has no uploadUrl yet
    because provisioning has not reached it is also excluded — the session's
    PROVISIONING status is what tells the client to wait.
    """
    docs, next_token = _page_session_files(sid, limit, page_token)
    out = []
    for d in docs:
        f = d.to_dict()
        url = f.get("uploadUrl")
        if f.get("status") == statuses.FILE_COMPLETED or not url:
            continue
        out.append(upload_target(d.id, url, f))
    return out, next_token


def upload_target(file_id: str, url: str, f: dict) -> dict:
    """One entry of an upload manifest, as the app reads it."""
    return {
        "fileId": file_id,
        "uploadUrl": url,
        "chunkSize": 32 * 1024 * 1024,  # the client uploads in chunks of this size (SemperApi.uploadResumable)
        "name": f.get("name"),
        "role": f.get("role"),
        "sizeBytes": f.get("sizeBytes", 0),
    }


def list_session_files(
    sid: str,
    limit: int = _LIST_SOFT_LIMIT,
    page_token: str | None = None,
) -> tuple[list, str | None]:
    """Every file in an analysis — the manifest the app restores from.

    Returns (page, next_page_token_or_None).
    """
    docs, next_token = _page_session_files(sid, limit, page_token)
    return [_file_view(d.id, d.to_dict(), *_MANIFEST_FIELDS) for d in docs], next_token


# ---------------- async provisioning ----------------
def _status_patch(status: str, error_code: str | None) -> dict:
    patch = {"status": status, "updatedAt": _base.firestore.SERVER_TIMESTAMP}
    if error_code:
        patch["provisionError"] = error_code
    elif status != statuses.SESSION_PROVISION_FAILED:
        patch["provisionError"] = _base.firestore.DELETE_FIELD
    return patch


def set_session_status(sid: str, status: str, error_code: str | None = None) -> None:
    """Move a session to `status`. COMPLETED is terminal and is never left.

    Every caller is provisioning, and a Cloud Tasks retry of it can land
    after the last file completed. Writing UPLOADING (or PROVISION_FAILED)
    over COMPLETED then showed a finished analysis as still uploading. The
    read and the write share a transaction, so a completion that lands
    between them — `bump_session_progress` writes the same document — makes
    this retry and see it.
    """
    patch = _status_patch(status, error_code)
    ref = db().collection("sessions").document(sid)

    def _open(snap) -> bool:
        return snap.exists and (snap.to_dict() or {}).get("status") != statuses.SESSION_COMPLETED

    @_base.firestore.transactional
    def _set(tx) -> None:
        if _open(ref.get(transaction=tx)):
            tx.update(ref, patch)

    def _guarded_write() -> None:
        # Losing every attempt means files are completing right now. A plain
        # guarded write still beats leaving the status unwritten.
        if _open(ref.get()):
            try:
                ref.update(patch)
            except NotFound:
                pass

    _run_tx(_set, on_contended=_guarded_write)


def set_new_session_status(sid: str, status: str) -> None:
    """`set_session_status` for a session this request created, as a plain
    write: its upload targets have not left the request yet, so nothing can
    have completed, and the guard's read would be paid on every analysis."""
    try:
        db().collection("sessions").document(sid).update(_status_patch(status, None))
    except NotFound:
        pass


def iter_unprovisioned_files(sid: str):
    """Files in a session that still have no resumable URI.

    Drives the provisioning worker and makes it resumable: a task that dies
    halfway re-runs and picks up only what is left, so a retry never mints a
    second upload URI for a file that already has one.
    """
    for d in _session_file_docs(sid):
        f = d.to_dict()
        if f.get("status") != statuses.FILE_COMPLETED and not f.get("uploadUrl"):
            yield _file_view(d.id, f)


def set_file_upload_urls(opened: list[tuple[str, str]]) -> None:
    """Record each `(file_id, upload_url)` provisioning opened, batched: one
    write round trip per 400 files rather than one per file. Safe to repeat."""
    files = db().collection("files")
    _base._update_refs([
        (files.document(file_id), {"uploadUrl": url,
                                   "updatedAt": _base.firestore.SERVER_TIMESTAMP})
        for file_id, url in opened
    ])


def session_app(session: dict | None) -> str:
    """The app a session was backed up from (ADR-014): `apps.SEMPER` or
    `apps.MATERIAL_TESTING`, the vocabulary of `devices/{id}.app`.

    A session written before the tag has no `app` field and reads as Semper's,
    as a request with no `X-App-Id` does; `scripts/tag_session_apps.py` stamps
    the Material Testing ones before this is relied on.
    """
    return (session or {}).get("app") or apps.SEMPER


def _listed_session(doc_id: str, s: dict) -> dict:
    """One entry of `GET /v1/sessions` (and of the export built on it)."""
    return {
        "sessionId": doc_id,
        "localSessionId": s.get("localSessionId") or "",
        "specimen": s.get("specimen"),
        "status": s.get("status"),
        "fileCount": s.get("fileCount", 0),
        "completedCount": s.get("completedCount", 0),
        "totalBytes": s.get("totalBytes", 0),
        "driveFolderId": s.get("driveFolderId"),
        "app": session_app(s),
    }


def list_user_sessions(
    uid: str,
    limit: int = 50,
    page_token: str | None = None,
    app: str | None = None,
) -> tuple[list, str | None]:
    """Cursor-paginated cloud analyses. Returns (page, next_page_token_or_None).

    `app` keeps only that app's sessions (`session_app`, ADR-014). None lists
    the whole account, which the export and erasure paths
    (`iter_all_user_sessions`) need and keep.

    The filter runs here, over the same uid query, not as a
    `where("app", "==", ...)`: an equality filter cannot match a document with
    no `app` field, which is every session from before the tag, and it would
    need a new composite index. Pages are filled: the scan reads on past the
    other app's sessions until `limit` match or the account ends, so a page is
    short only when it is the last. The token is the id of the last session
    returned, and is issued only when another session (of any app) follows it.
    Worst case one call reads the whole account, which the quota bounds.
    """
    if app is None:
        col = db().collection("sessions")
        docs, next_token = _cursor_page(col, col.where("uid", "==", uid), limit, page_token)
        return [_listed_session(d.id, d.to_dict()) for d in docs], next_token

    out: list = []
    # limit + 1 per read, as `_cursor_page` reads: when every session is this
    # app's, the filtered page costs what the unfiltered one does.
    col = db().collection("sessions")
    scan = _scan(col, col.where("uid", "==", uid), page_token, chunk=limit + 1)
    for d in scan:
        s = d.to_dict()
        if session_app(s) != app:
            continue
        out.append(_listed_session(d.id, s))
        if len(out) == limit:
            return out, (d.id if next(scan, None) is not None else None)
    return out, None


def iter_all_user_sessions(uid: str, *, page_size: int = 100):
    """Yield every session for export without a silent cap."""
    token = None
    while True:
        page, token = list_user_sessions(uid, limit=page_size, page_token=token)
        for session in page:
            yield session
        if not token:
            return


def iter_sessions_with_files(uid: str, *, page_size: int = 100, file_chunk: int = _BATCH_LIMIT):
    """Every session of `uid`, each with its file manifest, for the export.

    Two ordered streams, merged: the sessions by id, and the account's files
    by session id (index `files (uid, sessionId)`). The export used to run one
    files query per session, so an account with a thousand analyses cost a
    thousand round trips before the last byte. Memory holds one session's
    files at a time. A file whose session is gone is not listed, as before.
    """
    col = db().collection("files")
    files = ((f["sessionId"], _file_view(d.id, f, *_MANIFEST_FIELDS))
             for d in _scan(col, col.where("uid", "==", uid), chunk=file_chunk,
                            order_field="sessionId")
             for f in (d.to_dict(),))
    head = next(files, None)
    for session in iter_all_user_sessions(uid, page_size=page_size):
        sid = session["sessionId"]
        listed = []
        while head is not None and head[0] <= sid:
            if head[0] == sid:
                listed.append(head[1])
            head = next(files, None)
        yield {**session, "files": listed}


def list_session_artifacts(sid: str, *, page_size: int = 200) -> list:
    """Every *uploaded* file in a session, with the Drive id needed to read it.

    Separate from the export's manifest (`iter_sessions_with_files`) on purpose. That projection feeds
    `GET /v1/me/export`, where a Drive file id is a handle to bytes the caller
    is not being handed and so is deliberately withheld; this one exists only
    for code that is about to fetch those bytes on the caller's behalf. Keeping
    them apart means adding a field here can never widen the export.

    Pending files are skipped: they have no `driveFileId` yet, and an archive
    is of what was stored, not of what was promised.
    """
    return [
        _file_view(d.id, f, "sha256", "driveFileId", "createdAt")
        for d in _session_file_docs(sid, page_size)
        if (f := d.to_dict()).get("status") == statuses.FILE_COMPLETED and f.get("driveFileId")
    ]


def iter_user_sessions(uid: str):
    """Stream every session for destructive Drive cleanup without a silent cap."""
    for d in db().collection("sessions").where("uid", "==", uid).stream():
        s = d.to_dict()
        yield {
            "sessionId": d.id,
            "driveFolderId": s.get("driveFolderId"),
        }


def count_user_sessions(uid: str) -> int:
    """How many analyses count against this user's quota.

    The one count behind both the create-time check and `quota.used` on
    `GET /v1/sessions`, so the two cannot disagree. A PROVISION_FAILED
    session stores nothing — its upload targets were never opened — and
    counting it charged the user for an upload that never happened, next to
    the new session the app creates for the same analysis. Two equality counts rather than one `!=` query: `!=` also
    drops a document with no `status`, and it needs a composite index.
    A failed session that a Cloud Tasks retry later provisions counts again
    from then on; the quota is soft, so that overshoot is accepted.

    Account-wide, across both apps (ADR-014): the cap is the account's, so a
    session tagged with either app counts, though each app lists only its own.
    """
    sessions = db().collection("sessions").where("uid", "==", uid)
    total = int(sessions.count().get()[0][0].value)
    failed = sessions.where("status", "==", statuses.SESSION_PROVISION_FAILED).count().get()
    return max(0, total - int(failed[0][0].value))


def find_incomplete_session(uid: str, local_session_id: str):
    """An in-flight session for (uid, localSessionId), if any.

    Lets a retried POST /v1/sessions return the same session instead of minting
    a duplicate (and burning quota). Empty localSessionId is never matched —
    clients that omit it still get a fresh session each call.

    One `status in (...)` query on the `(uid, localSessionId, status)` index,
    which serves an `in` on its last field as it serves an equality. It was
    one query per status, run one after the other on every session create.
    """
    if not local_session_id:
        return None
    q = (
        db().collection("sessions")
        .where("uid", "==", uid)
        .where("localSessionId", "==", local_session_id)
        # PROVISIONING is in flight too: a retry joins the session whose upload
        # targets are still being opened rather than minting a duplicate.
        .where("status", "in", list(statuses.IN_FLIGHT_SESSION_STATUSES))
        .limit(1)
    )
    for d in q.stream():
        return {**d.to_dict(), "sessionId": d.id}
    return None


def create_session(sid: str, user: dict, device: dict, body: SessionCreate,
                   app: str = apps.SEMPER):
    """Reserve the session doc BEFORE any Drive folder or file doc is created.

    Writing the parent first means a failure while staging files can never leave
    file docs (or a Drive subtree) with no session pointing at them: the reserved
    doc counts toward the quota and is reclaimable. `driveFolderId` is filled in
    by [set_session_folder] once the folder exists. Returns the doc as written.

    `app` is the app that asked (`deps.request_app`, from `X-App-Id`), never a
    body field: the server derives it, as it does the device binding (ADR-014).
    """
    doc = {
        "uid": user["uid"],
        "app": app,
        "deviceId": device.get("deviceId"),
        "specimen": body.specimen,
        "localSessionId": body.localSessionId,
        # Reserved, but no upload targets yet. Provisioning moves it to
        # PROVISIONING → UPLOADING (or PROVISION_FAILED).
        "status": statuses.SESSION_PROVISIONING,
        "driveFolderId": None,
        "totalBytes": sum(f.bytes for f in body.files),
        "fileCount": len(body.files),
        "completedCount": 0,
        "metrics": body.metrics,
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    db().collection("sessions").document(sid).set(doc)
    return {**doc, "sessionId": sid}


def set_session_folder(sid: str, folder_id: str):
    """Record the session's Drive folder id once it has been created."""
    db().collection("sessions").document(sid).update(
        {"driveFolderId": folder_id, "updatedAt": _base.firestore.SERVER_TIMESTAMP}
    )


def _file_doc(sid: str, uid: str, f: FileSpec, upload_url: str | None) -> dict:
    return {
        "sessionId": sid,
        "uid": uid,
        "role": f.role,
        "name": f.name,
        "sizeBytes": f.bytes,
        "sha256": f.sha256,
        "status": statuses.FILE_PENDING,
        "uploadUrl": upload_url,
        "driveFileId": None,
        "driveMd5": None,
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }


def create_files_batch(sid: str, uid: str, files: list[tuple[str, FileSpec]]) -> None:
    """Write the file doc for every (file_id, spec) pair, batched — one
    `.set()` per file was one Firestore round trip each (a 3-object
    split-bundle session is 3 already; a legacy per-file-per-frame session
    could be far more). `uploadUrl` starts as None: provisioning fills it in
    once the Drive resumable session exists (`iter_unprovisioned_files`).
    Chunked to Firestore's per-batch write cap, same pattern as
    [_delete_refs]."""
    for start in range(0, len(files), _BATCH_LIMIT):
        batch = db().batch()
        for file_id, f in files[start:start + _BATCH_LIMIT]:
            batch.set(db().collection("files").document(file_id), _file_doc(sid, uid, f, None))
        batch.commit()


class Completion(StrEnum):
    """What `complete_file` did. The values are what it used to return."""
    #: This call recorded the completion: advance the session's counter.
    DONE = "ok"
    #: A retry of a completion that landed. Idempotent; must NOT bump again.
    ALREADY = "already"
    #: Not this caller's file, the wrong size, or no such file.
    REJECTED = ""


def complete_file(file_id: str, uid: str, body: FileComplete) -> Completion:
    """Record a file's Drive pointer, as one `Completion`.

    Read-status→update runs in a transaction so two concurrent completions of
    the same PENDING file yield exactly one "ok" (the other sees COMPLETED and
    returns ALREADY). Without this, both could bump the session counter.
    """
    ref = db().collection("files").document(file_id)

    @_base.firestore.transactional
    def _complete(tx):
        snap = ref.get(transaction=tx)
        if not snap.exists:
            return Completion.REJECTED
        d = snap.to_dict()
        if d["uid"] != uid or d["sizeBytes"] != body.bytes:
            return Completion.REJECTED
        if d.get("status") == statuses.FILE_COMPLETED:
            return Completion.ALREADY
        tx.update(
            ref,
            {
                "status": statuses.FILE_COMPLETED,
                "driveFileId": body.driveFileId,
                "driveMd5": body.md5,
                "uploadUrl": _base.firestore.DELETE_FIELD,  # capability no longer needed
                "updatedAt": _base.firestore.SERVER_TIMESTAMP,
            },
        )
        return Completion.DONE

    def _lost_race() -> Completion:
        # A concurrent completion of this same file won the race. That is the
        # idempotent case the transaction exists to produce — re-read and answer
        # it precisely rather than 500ing on the loser. ALREADY is important:
        # it stops the caller bumping the session counter a second time.
        snap = ref.get()
        current = snap.to_dict() if snap.exists else None
        if (current and current.get("uid") == uid
                and current.get("status") == statuses.FILE_COMPLETED):
            return Completion.ALREADY
        return Completion.REJECTED

    return _run_tx(_complete, on_contended=_lost_race)


def bump_session_progress(sid: str):
    """One file just completed: advance the session's counter by one.

    O(1) — one atomic increment on the session doc. The version before that
    re-streamed EVERY file doc in the session on every completion, which made an
    N-file upload cost ~N² Firestore reads (a 150-frame analysis burned the whole
    daily free-tier read quota several times over by itself).

    Uses firestore.Increment rather than a read-modify-write transaction. A
    transaction serialises every concurrent completion onto this one document,
    and parallel uploads finish together by design — six concurrent bumps
    exhausted that transaction's retries (the client default of five, before
    `_TX_ATTEMPTS`) and raised `Aborted: Transaction lock
    timeout`, i.e. a 500 on the last files of an otherwise-successful upload.
    (The fake store in tests applies transactions immediately with no isolation,
    so this was invisible until the Firestore emulator tier was wired into CI.)
    An increment needs no read, so concurrent completions no longer contend.

    Trusting the counter is safe because complete_file is idempotent: a retried
    completion returns ALREADY and never reaches this function.
    """
    ref = db().collection("sessions").document(sid)
    try:
        ref.update({
            "completedCount": _base.firestore.Increment(1),
            "updatedAt": _base.firestore.SERVER_TIMESTAMP,
        })
    except NotFound:
        return  # session erased mid-upload; nothing to advance

    # Re-read to decide the COMPLETED flip. Firestore reads are strongly
    # consistent, so the caller whose increment reached fileCount is guaranteed
    # to observe it here. The flip is monotone and idempotent: a caller that
    # reads a lower count simply does nothing, and the one that completes the
    # set finishes the job.
    after = ref.get().to_dict() or {}
    if (
        int(after.get("completedCount", 0)) >= int(after.get("fileCount", 0))
        and after.get("status") != statuses.SESSION_COMPLETED
    ):
        ref.update({
            "status": statuses.SESSION_COMPLETED,
            "completedAt": _base.firestore.SERVER_TIMESTAMP,
        })
