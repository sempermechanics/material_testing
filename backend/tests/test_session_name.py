"""A backup is listed by its analysis's name (TD-208).

The app sent only `specimen`, the reference image's file name, so a sweep or a
renamed analysis was listed as `steel_24`. `POST /v1/sessions` now takes an
optional `name`, `GET /v1/sessions` lists it, and a rename re-sent through
`PUT /v1/sessions/{sid}/metadata` moves it. An older app sends no name: the
session lists `name: ""` and the app falls back to `specimen`.
"""
import pytest

from app import audit, drive, statuses
from app.models import SessionCreate
import repo_view as repo

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_SHA = "a" * 64
_FILES = [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}]
_META_FILE = "s1_metadata_metadata.json"


@pytest.fixture
def store(store, monkeypatch):
    store._data["users"] = {DEV_UID: {"email": "dev@test", "access_status": "APPROVED"}}
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    return store


# ------------------------------------------------------------------ create


async def test_a_session_keeps_the_name_it_was_created_with(store, client):
    r = await client.post("/v1/sessions", json={"specimen": "steel_24.png", "name": "  Steel 24 sweep ",
                                                "localSessionId": "loc-1", "files": _FILES})

    assert r.status_code == 200
    doc = store._data["sessions"][r.json()["sessionId"]]
    assert (doc["specimen"], doc["name"]) == ("steel_24.png", "Steel 24 sweep")


async def test_an_older_app_that_sends_no_name_still_creates(store, client):
    r = await client.post("/v1/sessions", json={"specimen": "steel_24.png", "localSessionId": "loc-1",
                                                "files": _FILES})

    assert r.status_code == 200
    assert store._data["sessions"][r.json()["sessionId"]]["name"] == ""


def test_a_name_over_200_characters_is_refused():
    with pytest.raises(ValueError):
        SessionCreate(specimen="s", name="x" * 201, files=_FILES)
    assert SessionCreate(specimen="s", name="x" * 200, files=_FILES).name == "x" * 200


# ------------------------------------------------------------------- list


async def test_the_list_carries_the_name_and_blank_for_a_session_without_one(store, client):
    done = {"uid": DEV_UID, "status": statuses.SESSION_COMPLETED, "specimen": "steel_24.png"}
    store._data["sessions"] = {
        "a-named": {**done, "name": "Steel 24 sweep"},
        "b-old": dict(done),
        "c-null": {**done, "name": None},
    }

    r = await client.get("/v1/sessions")

    assert r.status_code == 200
    listed = {s["sessionId"]: (s["name"], s["specimen"]) for s in r.json()["sessions"]}
    assert listed == {
        "a-named": ("Steel 24 sweep", "steel_24.png"),
        "b-old": ("", "steel_24.png"),
        "c-null": ("", "steel_24.png"),
    }


# ----------------------------------------------------------------- rename


@pytest.fixture
def backed_up(store, monkeypatch):
    store._data["sessions"] = {
        "s1": {"uid": DEV_UID, "status": statuses.SESSION_COMPLETED, "localSessionId": "loc-1",
               "specimen": "steel_24.png", "name": "steel_24", "driveFolderId": "sf1"},
    }
    store._data["files"] = {
        _META_FILE: {
            "uid": DEV_UID, "sessionId": "s1", "role": "metadata", "name": "metadata.json",
            "sizeBytes": 10, "sha256": "0" * 64, "status": statuses.FILE_COMPLETED,
            "driveFileId": "drive-meta", "driveMd5": "a" * 32,
        },
    }
    monkeypatch.setattr(drive, "replace_content",
                        lambda token, drive_file_id, data, mime="application/json":
                        {"size": len(data), "md5": "b" * 32})
    return store


def _metadata(**over):
    return {"schema": "indic.session.metadata/6", "localSessionId": "loc-1", **over}


async def test_a_rename_sent_as_metadata_renames_the_listed_session(backed_up, client):
    r = await client.put("/v1/sessions/s1/metadata", json=_metadata(name=" Steel 24, second pull "))

    assert r.status_code == 200
    assert backed_up._data["sessions"]["s1"]["name"] == "Steel 24, second pull"
    listed = (await client.get("/v1/sessions")).json()["sessions"]
    assert [s["name"] for s in listed] == ["Steel 24, second pull"]


@pytest.mark.parametrize("name", [None, "", "   ", 42, ["a"]])
async def test_metadata_with_no_usable_name_leaves_the_name(backed_up, client, name):
    body = _metadata() if name is None else _metadata(name=name)

    r = await client.put("/v1/sessions/s1/metadata", json=body)

    assert r.status_code == 200
    assert backed_up._data["sessions"]["s1"]["name"] == "steel_24"


async def test_a_refused_metadata_body_does_not_rename(backed_up, client):
    backed_up._data["sessions"]["s1"]["status"] = statuses.SESSION_UPLOADING

    r = await client.put("/v1/sessions/s1/metadata", json=_metadata(name="Renamed"))

    assert r.status_code == 409
    assert backed_up._data["sessions"]["s1"]["name"] == "steel_24"


def test_the_metadata_name_is_trimmed_and_cut_to_the_create_limit():
    assert repo.session_name_of({"name": "  a b  "}) == "a b"
    assert repo.session_name_of({"name": "x" * 250}) == "x" * repo.SESSION_NAME_MAX_CHARS
    assert repo.session_name_of({"name": 7}) == "" == repo.session_name_of({})
