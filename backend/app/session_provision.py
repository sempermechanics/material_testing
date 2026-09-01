import logging
import time
from concurrent.futures import ThreadPoolExecutor

from . import drive, firestore_repo as repo, statuses
from . import observability as obs
from .config import settings

log = logging.getLogger("indic")


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
            repo.set_session_status(sid, statuses.SESSION_PROVISION_FAILED,
                                    error_code="drive_provision_failed")
        raise

    repo.set_session_status(sid, "UPLOADING")
    obs.log_event(log, logging.INFO, "session_provisioned", outcome="ok",
                  count=provisioned, latencyMs=round((time.monotonic() - started) * 1000, 1))
    return {"sessionId": sid, "provisioned": provisioned, "status": "UPLOADING"}
