"""Google Drive: create the session folder tree and initiate resumable uploads.

Metadata calls only. File BYTES never pass through here — the client PUTs
directly to the resumable session URI returned by init_resumable().
"""
import requests

from .config import settings
from .google_auth import drive_access_token

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


def ensure_session_folders(token: str, uid: str, sid: str) -> dict:
    """Build Research Storage/user/{uid}/session/{sid}/{raw,processed,reports,metadata,csv}."""
    root = settings.ROOT_FOLDER_ID
    research = _find_or_create_folder(token, "Research Storage", root)
    user_dir = _find_or_create_folder(token, "user", research)
    uid_dir = _find_or_create_folder(token, uid, user_dir)
    sess_dir = _find_or_create_folder(token, "session", uid_dir)
    sid_dir = _find_or_create_folder(token, sid, sess_dir)
    folders = {"sessionFolderId": sid_dir}
    for role in ("raw", "processed", "reports", "metadata", "csv"):
        folders[role] = _find_or_create_folder(token, role, sid_dir)
    return folders


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
