"""Google Drive: create the session folder tree and initiate resumable uploads.

Metadata calls only. File BYTES never pass through here — the client PUTs
directly to the resumable session URI returned by init_resumable().
"""
import logging

import requests

from .config import settings
from .google_auth import drive_access_token

log = logging.getLogger("indic.drive")

API = "https://www.googleapis.com/drive/v3"
UPLOAD = (
    "https://www.googleapis.com/upload/drive/v3/files"
    "?uploadType=resumable&supportsAllDrives=true"
)
FOLDER_MIME = "application/vnd.google-apps.folder"


def access_token() -> str:
    return drive_access_token()


def _headers(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _find_or_create_folder(token: str, name: str, parent: str) -> str:
    safe = name.replace("'", "\\'")
    q = (
        f"name='{safe}' and mimeType='{FOLDER_MIME}' and "
        f"'{parent}' in parents and trashed=false"
    )
    r = requests.get(
        f"{API}/files",
        headers=_headers(token),
        params={
            "q": q,
            "fields": "files(id)",
            "supportsAllDrives": "true",
            "includeItemsFromAllDrives": "true",
            "corpora": "drive",
            "driveId": settings.SHARED_DRIVE_ID,
        },
        timeout=30,
    )
    r.raise_for_status()
    files = r.json().get("files", [])
    if files:
        return files[0]["id"]
    r = requests.post(
        f"{API}/files",
        headers=_headers(token),
        params={"supportsAllDrives": "true"},
        json={"name": name, "mimeType": FOLDER_MIME, "parents": [parent],
              "driveId": settings.SHARED_DRIVE_ID},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["id"]


def _find_folder(token: str, name: str, parent: str):
    """Look a folder up WITHOUT creating it — used on the erasure path."""
    safe = name.replace("'", "\\'")
    q = (
        f"name='{safe}' and mimeType='{FOLDER_MIME}' and "
        f"'{parent}' in parents and trashed=false"
    )
    r = requests.get(
        f"{API}/files",
        headers=_headers(token),
        params={
            "q": q,
            "fields": "files(id)",
            "supportsAllDrives": "true",
            "includeItemsFromAllDrives": "true",
            "corpora": "drive",
            "driveId": settings.SHARED_DRIVE_ID,
        },
        timeout=30,
    )
    r.raise_for_status()
    files = r.json().get("files", [])
    return files[0]["id"] if files else None


def find_user_folder(token: str, uid: str):
    """The user's whole Drive subtree (…/Research Storage/user/{uid}), or None.

    Best-effort cleanup only. Callers deleting real data must go through the
    stored `driveFolderId` per session — a name walk that quietly returns None
    (renamed folder, wrong ROOT_FOLDER_ID, a level missing) would look exactly
    like "nothing to delete" and strand the user's blobs.
    """
    research = _find_folder(token, "Research Storage", settings.ROOT_FOLDER_ID)
    if not research:
        log.warning("find_user_folder: no 'Research Storage' under root %s", settings.ROOT_FOLDER_ID)
        return None
    user_dir = _find_folder(token, "user", research)
    if not user_dir:
        log.warning("find_user_folder: no 'user' folder under Research Storage")
        return None
    found = _find_folder(token, uid, user_dir)
    if not found:
        log.warning("find_user_folder: no folder named %s under user/", uid)
    return found


def ensure_session_folders(token: str, uid: str, sid: str, roles=None) -> dict:
    """Build Research Storage/user/{uid}/session/{sid}/ plus the role subfolders
    the manifest actually uses. "bundle" (Session.zip) and "metadata" live at the
    session root — no subfolder, no extra Drive round-trips.
    """
    root = settings.ROOT_FOLDER_ID
    research = _find_or_create_folder(token, "Research Storage", root)
    user_dir = _find_or_create_folder(token, "user", research)
    uid_dir = _find_or_create_folder(token, uid, user_dir)
    sess_dir = _find_or_create_folder(token, "session", uid_dir)
    sid_dir = _find_or_create_folder(token, sid, sess_dir)
    # userFolderId is returned so it can be persisted on the user doc: account
    # deletion then erases Drive via a stored id instead of re-walking names.
    folders = {"sessionFolderId": sid_dir, "userFolderId": uid_dir,
               "bundle": sid_dir, "metadata": sid_dir}
    wanted = roles if roles is not None else ("raw", "processed", "reports", "csv", "dat")
    for role in wanted:
        if role not in folders:
            folders[role] = _find_or_create_folder(token, role, sid_dir)
    return folders


def file_exists(token: str, file_id: str) -> bool:
    """Is this file/folder still really in Drive (and not trashed)?

    Firestore is only an index — if someone deletes a session folder straight in
    Drive, the index still claims COMPLETED. This is the check that catches that.
    """
    r = requests.get(
        f"{API}/files/{file_id}",
        headers=_headers(token),
        params={"fields": "id,trashed", "supportsAllDrives": "true"},
        timeout=30,
    )
    if r.status_code == 404:
        return False
    r.raise_for_status()
    return not r.json().get("trashed", False)


def delete_file(token: str, file_id: str) -> None:
    """**Permanently** delete a file/folder (GDPR erasure).

    files.delete on a Shared Drive skips the trash and removes descendants, so
    deleting a session folder erases every artifact inside it. A 404 is treated
    as success — the goal is "it is gone", and it already is.
    """
    r = requests.delete(
        f"{API}/files/{file_id}",
        headers=_headers(token),
        params={"supportsAllDrives": "true"},
        timeout=120,
    )
    if r.status_code in (200, 204):
        return  # explicit success

    if r.status_code == 404:
        # Ambiguous: Drive returns 404 both for "already gone" AND for "you may
        # not touch this" (it hides existence instead of returning 403). Taking
        # it as success once meant erasure silently no-op'd while reporting that
        # it had deleted everything. Never believe a 404 — check.
        if file_exists(token, file_id):
            log.error("drive delete %s: 404 but the file is STILL THERE — permission problem", file_id)
            raise PermissionError(
                f"Drive refused to delete {file_id}. The service account needs "
                "Manager (organizer) rights on the shared drive: files.delete "
                "requires organizer rights on the parent."
            )
        return  # genuinely absent

    log.error("drive delete %s FAILED: HTTP %s %s", file_id, r.status_code, r.text[:500])
    r.raise_for_status()


class DriveDownload:
    """Open Drive media response — status/headers for Range, then chunked body."""

    def __init__(self, response: requests.Response):
        self.status_code = response.status_code
        self.headers = response.headers
        self._response = response

    def iter_chunks(self, chunk_size: int = 256 * 1024):
        try:
            for chunk in self._response.iter_content(chunk_size=chunk_size):
                if chunk:
                    yield chunk
        finally:
            self._response.close()


def open_download(
    token: str,
    drive_file_id: str,
    byte_range: str | None = None,
) -> DriveDownload:
    """Open a Drive file media stream, optionally with an HTTP Range.

    Drive supports `Range` on `alt=media` and answers with 206 + Content-Range
    when a range is honored. There is still no anonymous signed download URL,
    so restore bytes remain proxied through Cloud Run — but clients can resume
    a truncated transfer from the last byte instead of restarting.
    """
    headers = _headers(token)
    if byte_range:
        headers["Range"] = byte_range
    r = requests.get(
        f"{API}/files/{drive_file_id}",
        headers=headers,
        params={"alt": "media", "supportsAllDrives": "true"},
        stream=True,
        timeout=600,
    )
    if r.status_code not in (200, 206):
        r.raise_for_status()
    return DriveDownload(r)


def stream_file(token: str, drive_file_id: str, chunk_size: int = 256 * 1024):
    """Yield a Drive file's bytes for restore/download.

    NOTE: unlike uploads (which go device→Drive directly via a resumable URI),
    Drive offers no anonymous signed download, so restore bytes must be proxied
    through here. That costs egress and is the main argument for moving blobs to
    GCS (signed URLs) if downloads ever become common. See
    docs/backend/CLOUD_ARCHITECTURE_GCP.md §0 and §19.
    """
    yield from open_download(token, drive_file_id).iter_chunks(chunk_size)


def init_resumable(token: str, parent_folder_id: str, filename: str, size_bytes: int) -> str:
    """Start a resumable session; return the URI the client uploads bytes to."""
    r = requests.post(
        UPLOAD,
        headers={**_headers(token), "Content-Type": "application/json; charset=UTF-8",
                 "X-Upload-Content-Length": str(size_bytes)},
        json={"name": filename, "parents": [parent_folder_id],
              "driveId": settings.SHARED_DRIVE_ID},
        timeout=30,
    )
    r.raise_for_status()
    return r.headers["Location"]
