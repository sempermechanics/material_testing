"""Drive helpers that hold connections or fan out."""
import threading

import pytest
import requests

import fake_firestore
from app import audit, drive, firestore_repo as repo


class _Streamed:
    """A streamed `requests.Response` stand-in that records whether it was closed."""

    def __init__(self, status: int):
        self.status_code = status
        self.headers = {}
        self.closed = False

    def close(self):
        self.closed = True

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}", response=self)


@pytest.mark.parametrize("status", [404, 416, 503])
def test_open_download_closes_a_streamed_error_before_raising(monkeypatch, status):
    resp = _Streamed(status)
    monkeypatch.setattr(drive, "_request_with_retry", lambda *a, **k: resp)
    with pytest.raises(requests.HTTPError) as err:
        drive.open_download("tok", "f1")
    assert err.value.response.status_code == status
    assert resp.closed


def test_open_download_leaves_a_good_stream_open(monkeypatch):
    resp = _Streamed(206)
    monkeypatch.setattr(drive, "_request_with_retry", lambda *a, **k: resp)
    dl = drive.open_download("tok", "f1", byte_range="bytes=10-")
    assert dl.status_code == 206
    assert not resp.closed


def test_delete_files_deletes_each_id_once_and_skips_blanks(monkeypatch):
    deleted, lock = [], threading.Lock()

    def delete(_token, fid):
        with lock:
            deleted.append(fid)

    monkeypatch.setattr(drive, "delete_file", delete)
    assert drive.delete_files("tok", ["a", None, "b", "a", ""]) == 2
    assert sorted(deleted) == ["a", "b"]


def test_delete_files_propagates_a_failure(monkeypatch):
    def delete(_token, fid):
        if fid == "bad":
            raise PermissionError("organizer rights")

    monkeypatch.setattr(drive, "delete_file", delete)
    with pytest.raises(PermissionError):
        drive.delete_files("tok", ["ok", "bad", "ok2"])


@pytest.mark.asyncio
async def test_account_erase_without_a_stored_folder_deletes_every_session_folder(
        client, monkeypatch):
    """The fallback for accounts that predate the stored user-folder pointer."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["users"] = {"dev-user": {"email": "dev@local", "access_status": "APPROVED"}}
    store._data["sessions"] = {
        f"s{i}": {"uid": "dev-user", "driveFolderId": f"folder-{i}"} for i in range(5)
    }
    store._data["sessions"]["s-none"] = {"uid": "dev-user", "driveFolderId": None}
    deleted, lock = [], threading.Lock()

    def delete(_token, fid):
        with lock:
            deleted.append(fid)

    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "delete_file", delete)
    monkeypatch.setattr(drive, "find_user_folder", lambda _t, _uid: "user-folder")

    resp = await client.delete("/v1/me")
    assert resp.status_code == 200, resp.text
    assert resp.json()["driveFolders"] == 5
    assert sorted(deleted) == sorted([f"folder-{i}" for i in range(5)] + ["user-folder"])
    assert store._data["sessions"] == {}
