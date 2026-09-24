"""Helpers shared by the test_licenses_*.py files.

Split out of the single 2,500-line test_licenses.py (TD-55). Each helper
used by only one file stayed in that file; these are the ones two or more
share, and what they call."""

from app import firestore_repo as repo


def _recording_stubs(monkeypatch):
    """Everything POST /v1/sessions touches besides Firestore, stubbed the way
    test_async_provisioning does — this file only cares about the gate."""
    from app import audit, drive, tasks

    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(
        drive, "ensure_session_folders",
        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"},
    )
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    monkeypatch.setattr(tasks, "enqueue_provision", lambda sid: True)


def _mint_institution(max_seats=None):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
    )


def _mint_individual(email="solo@lab.org", **kw):
    return repo.create_individual_license(
        email_lock=email, created_by_uid="admin", **kw
    )


def _signed_in(store, uid, email):
    user = {"uid": uid, "email": email,
            "access_status": "APPROVED", "emailVerified": True}
    store._data["users"][uid] = dict(user)
    return user
