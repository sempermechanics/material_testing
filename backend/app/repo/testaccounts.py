"""Staff test accounts for QC and CI (ADR-027): the account record, the
staff listing and the accounts past their end.

A test account is an ordinary account with a `testAccount` tag on
`users/{uid}`; every branch that treats it differently asks
`test_account_of`. This module is the only writer of the tag. The account's
licence, devices, sessions and erasure go through the modules that own them.
"""
from .. import apps, errors, statuses
from ..errors import Refusal
from ..licenses import as_utc, normalize_email

from . import _base
from ._base import (
    PURPOSE_CI,
    PURPOSE_QC,
    SCHEMA_VERSION,
    STAFF_RELEASED,
    _create_doc,
    _get,
    _now,
    _run_tx,
    _stream,
    _update_doc,
    db,
    test_account_of,
)
from .devices import _retire_device
from .users import _link_auth_uid

PURPOSES = (PURPOSE_QC, PURPOSE_CI)

#: `status` in the staff listing.
STATUS_ACTIVE = "active"
STATUS_EXPIRED = "expired"
STATUS_SUSPENDED = "suspended"


def create_test_account_user(uid: str, email: str, tag: dict) -> dict:
    """Write the account a staff test account signs in to: `users/{uid}` for
    the Firebase Auth user staff just created, APPROVED, its address
    verified, with `testAccount` = `tag`, and its sign-in link.

    The fields are the ones a first sign-in writes (`get_or_create_user`),
    so the account's first request reads it as any other account: it is
    approved because staff made it, not by any domain rule. `role` is
    "user" and stays so: an `ADMIN_EMAILS` address is refused before this,
    so a test account never steps up as staff. `create`, so an existing
    document is never overwritten."""
    data = {
        "email": normalize_email(email),
        "emailVerified": True,
        "signInProvider": "password",
        "displayName": None,
        "role": "user",
        "access_status": statuses.ACCESS_APPROVED,
        "activeDeviceId": None,
        "claimedDeviceId": None,
        "linkedAuthUids": [],
        "testAccount": dict(tag),
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    _create_doc(db().collection("users").document(uid), data)
    _link_auth_uid(uid, uid)
    return {**data, "uid": uid}


def get_test_account(uid: str) -> dict | None:
    """The test account `uid`, or None when there is none or it is a real
    account: these routes never touch a real one."""
    snap = _get(db().collection("users").document(uid))
    if not snap.exists:
        return None
    user = {**(snap.to_dict() or {}), "uid": uid}
    return user if test_account_of(user) is not None else None


def set_test_account_expiry(uid: str, tag: dict, expires_at) -> dict:
    """Store a new `testAccount.expiresAt`; the tag as it now stands. The
    whole map is written, as the tag was read."""
    tag = {**tag, "expiresAt": expires_at}
    _update_doc(db().collection("users").document(uid), {
        "testAccount": tag, "updatedAt": _base.firestore.SERVER_TIMESTAMP,
    })
    return tag


def licences_of_test_account(user: dict) -> list[str]:
    """The test-account licences minted for this account that are not
    revoked: the one it holds, and any still waiting at its address (a mint
    whose attach lost a race). Never a real licence, and never a Demo key."""
    out = []
    held = user.get("licenseId") or ""
    if held:
        lic = _base.get_license(held) or {}
        if lic.get("testAccount") is True and not _base._is_revoked(lic):
            out.append(held)
    address = normalize_email(user.get("email"))
    if address:
        for doc in _stream(db().collection("licenses").where("emailLock", "==", address)):
            lic = doc.to_dict() or {}
            if (doc.id not in out and lic.get("testAccount") is True
                    and not _base._is_revoked(lic)):
                out.append(doc.id)
    return out


def release_test_device(uid: str, device_id: str) -> None:
    """Release one ACTIVE phone of the test account `uid` (TD-249), in one
    transaction: the device REVOKED and stamped `staffReleased`, so
    `register_device` refuses it for this account from then on, and the
    app's `activeDeviceId` dropped when it names this phone. Not the release
    hold (`releasedDeviceId`, `_release_patch`), which a test account does not
    take, and its sessions are untouched. Raises `Refusal(device_not_found)`
    when the device is not, or is no longer, an ACTIVE device of `uid`."""
    user_ref = db().collection("users").document(uid)
    device_ref = db().collection("devices").document(device_id)

    @_base.firestore.transactional
    def _release(tx):
        dev_snap = _get(device_ref, tx)
        dev = (dev_snap.to_dict() or {}) if dev_snap.exists else {}
        if dev.get("uid") != uid or dev.get("status") != statuses.DEVICE_ACTIVE:
            raise Refusal(errors.DEVICE_NOT_FOUND)
        user_snap = _get(user_ref, tx)
        # The app by its name today (ADR-030); a value no app has binds
        # no field, so there is nothing to drop for it.
        app = apps.canonical(dev.get("app"))
        known = app in apps.ALL
        bound = (apps.read_field(user_snap.to_dict(), "activeDeviceId", app)
                 if known and user_snap.exists else None)
        _retire_device(tx, device_ref, statuses.DEVICE_REVOKED, {STAFF_RELEASED: True})
        patch = {"updatedAt": _base.firestore.SERVER_TIMESTAMP}
        if bound == device_id:
            patch.update(apps.written_fields("activeDeviceId", app, _base.firestore.DELETE_FIELD))
        tx.update(user_ref, patch)

    def _contended():
        raise Refusal(errors.CLAIM_CONTENDED)

    _run_tx(_release, on_contended=_contended)


def active_test_devices(uid: str) -> list[dict]:
    """The account's ACTIVE phones in every app, as the staff listing shows
    them: `{deviceId, app, model, registeredAt}`, oldest first (one without
    `registeredAt` first). The same equality query on `uid` as
    `_active_devices`; a record from before devices named their app is
    Semper's, and one tagged `materialtesting` Semper Classroom's (ADR-030)."""
    found = []
    for snap in _stream(db().collection("devices").where("uid", "==", uid)):
        dev = snap.to_dict() or {}
        if dev.get("status") == statuses.DEVICE_ACTIVE:
            found.append({"deviceId": snap.id, "app": apps.canonical(dev.get("app")),
                          "model": dev.get("model") or "",
                          "registeredAt": dev.get("registeredAt")})
    found.sort(key=lambda d: (d["registeredAt"] is not None, str(d["registeredAt"] or ""),
                              d["deviceId"]))
    return found


def status_of_test_account(user: dict, now=None) -> str:
    """`suspended` when the account is not approved, `expired` once its
    `expiresAt` has passed (the expiry job has not ended it yet), else
    `active`."""
    if user.get("access_status") != statuses.ACCESS_APPROVED:
        return STATUS_SUSPENDED
    expires = as_utc((test_account_of(user) or {}).get("expiresAt"))
    if expires is not None and expires <= (now or _now()):
        return STATUS_EXPIRED
    return STATUS_ACTIVE



def row_of_test_account(user: dict, now=None) -> dict:
    """One account as the staff listing shows it."""
    tag = test_account_of(user) or {}
    uid = user["uid"]
    devices = [d for d in active_test_devices(uid) if d["app"] in apps.ALL]
    return {
        "uid": uid,
        "email": user.get("email") or "",
        "purpose": tag.get("purpose") or "",
        "expiresAt": tag.get("expiresAt"),
        "maxDevices": _base.max_test_devices(tag),
        "note": tag.get("note") or "",
        "createdAt": tag.get("createdAt"),
        # Every app's phones: the console shows how many are in use, and
        # each one with its release (TD-249).
        "activeDevices": len(devices),
        "devices": devices,
        "status": status_of_test_account(user, now),
    }



def list_test_accounts() -> list[dict]:
    """Every test account, newest first. One `in` query on the tag's
    `purpose` (the automatic single-field index), then a devices query each:
    a handful of accounts, read only when staff open the page."""
    query = db().collection("users").where("testAccount.purpose", "in", list(PURPOSES))
    now = _now()
    rows = [row_of_test_account({**(d.to_dict() or {}), "uid": d.id}, now) for d in _stream(query)]
    rows.sort(key=lambda r: str(r.get("createdAt") or ""), reverse=True)
    return rows


def expired_test_accounts(now=None) -> list[dict]:
    """The test accounts whose `expiresAt` has passed, for the expiry job.
    A range on the one field (its automatic index); an account with no end
    (`null`, CI only) is not in it, and a real account has no such field."""
    query = db().collection("users").where("testAccount.expiresAt", "<", now or _now())
    out = []
    for d in _stream(query):
        user = {**(d.to_dict() or {}), "uid": d.id}
        if test_account_of(user) is not None:
            out.append(user)
    return out
