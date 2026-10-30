"""An upgrade is a new licence the holder activates (ADR-033).

Staff issue the holder a new licence for the same app, carrying the old one's
terms unless they name new ones: more phones, a later end, or a move to an
institution. The holder keeps working on the old licence until they tap
Activate on the new one, and that single act moves them: the account takes
the new licence, the old one is revoked with `supersededBy` naming it, and
the phone that tapped is activated on the new one. The old licence's other
phones each activate again, within the new count.

While it waits, the new licence is promised by a pointer on the holder's
account (`pendingLicenseId`, in the old licence's app slot) and on the old
licence (`upgradePendingId`). A holder who has never signed in has no account
to point at: their invite moves to the new licence and the old one is revoked
at once, as a conversion does.

Staff may apply an upgrade themselves (`apply_upgrade` with no phone), for a
holder on a build that has no Activate; that build's first phone then
activates itself while `IMPLICIT_ACTIVATION` is `legacy`.
"""
from .. import apps, audit, errors
from ..errors import Refusal
from ..licenses import (
    KIND_INDIVIDUAL,
    MODE_DEMO,
    MODE_LICENSED,
    email_domain,
    normalize_email,
    as_utc,
    normalize_kind,
)

from . import _base
from ._base import (
    ACTOR_SELF,
    _is_institution,
    _is_revoked,
    _license_mode,
    _load_user,
    _now,
    _update_doc,
    db,
    get_license,
)
from .activations import activate_device
from .claims import (
    _member_patch,
    _public_claim_error,
    claim_individual_license,
    claim_seat,
)
from .holders import verified_address
from .invites import (
    _delete_license_invites,
    _write_invite,
)
from .license_admin import (
    get_license_public,
    revoke_license,
)
from .mint import (
    _write_license,
    create_institution_license,
)

#: On the old licence while its upgrade waits: the new licence's id.
UPGRADE_PENDING_ID = "upgradePendingId"
#: On the new licence: the licence it replaces.
SUPERSEDES = "supersedes"


def _licences(license_id: str) -> dict:
    lic = get_license(license_id)
    if not lic:
        raise Refusal(errors.LICENSE_NOT_FOUND)
    return lic


def _upgradable(license_id: str, lic: dict) -> None:
    """Refuse an upgrade of `lic`: only a live individual licence that names
    its app moves this way. A licence from before the split converts in place
    (`repo.upgrade`); an institution is resized by an edit."""
    if (normalize_kind(lic.get("kind")) != KIND_INDIVIDUAL or not lic.get("app")
            or _license_mode(lic) == MODE_DEMO):
        raise Refusal(errors.LICENSE_NOT_UPGRADABLE)
    if _is_revoked(lic):
        raise Refusal(errors.LICENSE_REVOKED)
    pending = lic.get(UPGRADE_PENDING_ID) or ""
    if pending:
        waiting = get_license(pending)
        if waiting and not _is_revoked(waiting):
            raise Refusal(errors.UPGRADE_PENDING, pending)


def _holder(license_id: str, lic: dict) -> tuple[str, dict | None]:
    """The account holding `lic` in its app's slot, as `(uid, user)`, or
    `("", None)` before anyone has signed in. A redeemer who has moved to
    another licence since holds nothing to upgrade."""
    uid = lic.get("redeemedByUid") or ""
    if not uid:
        return "", None
    user = _load_user(uid)
    if not user or apps.slot_holding(user, license_id) is None:
        raise Refusal(errors.LICENSE_NOT_UPGRADABLE)
    return uid, user


def _mint_successor(lic: dict, address: str, admin_uid: str, terms: dict,
                    institution: dict | None) -> dict:
    """The new licence, unclaimed: `lic`'s terms, as overridden by `terms`,
    for the same app. Individual, locked to `address`, or an institution
    licence on `institution`'s domain. Nobody is invited or attached here."""
    app = apps.canonical(lic.get("app"))
    expires_at = None if terms.get("perpetual") else terms.get("expiresAt", lic.get("expiresAt"))
    if "expiresAt" in terms and as_utc(terms["expiresAt"]) <= _now():
        raise Refusal(errors.EXPIRY_IN_PAST)
    common = {
        "created_by_uid": admin_uid,
        "expires_at": expires_at,
        "grace_days": lic.get("graceDays"),
        "support_until": lic.get("supportUntil"),
        "max_analyses": terms.get("maxAnalyses", lic.get("maxAnalyses")),
        "note": terms.get("note") if terms.get("note") is not None else (lic.get("note") or ""),
        "app": app,
    }
    if institution is not None:
        out = create_institution_license(
            domain_lock=institution["domainLock"], admin_emails=institution["adminEmails"],
            max_seats=institution.get("maxSeats"), max_devices=terms.get("maxDevices"),
            **common,
        )
        return {**out["license"], "key": out["key"]}
    max_devices = terms.get("maxDevices")
    if max_devices is None:
        max_devices = lic.get("maxDevices")
    key, new_id, stored = _write_license(
        mode=MODE_LICENSED, email_lock=address, device_id_lock="", status="unused",
        kind=KIND_INDIVIDUAL, test_account=lic.get("testAccount") is True,
        max_devices=1 if max_devices is None else max_devices, **common,
    )
    return {**stored, "id": new_id, "key": key}


def issue_upgrade(license_id: str, *, admin_uid: str, terms: dict | None = None,
                  institution: dict | None = None) -> dict:
    """Issue the holder of `license_id` a new licence for the same app.
    Returns `{key, license, supersededId, holderUid, pending}`, or raises
    `Refusal`. The key is returned once, as a mint does.

    `terms` overrides what carries over: `maxDevices`, `expiresAt` or
    `perpetual`, `maxAnalyses`, `note`. `institution` (`domainLock`,
    `adminEmails`, `maxSeats`) makes it an assigned institution licence,
    whose domain must be the holder's.

    A holder who has signed in keeps the old licence until they activate the
    new one (`pending`). One who has not has their invite moved, and the old
    licence is revoked now.
    """
    terms = terms or {}
    lic = _licences(license_id)
    _upgradable(license_id, lic)
    holder_uid, user = _holder(license_id, lic)
    address = normalize_email((verified_address(user) if user else "") or lic.get("emailLock"))
    if institution is not None and address and email_domain(address) != institution["domainLock"]:
        raise Refusal(errors.CONVERT_DOMAIN_MISMATCH)

    minted = _mint_successor(lic, address, admin_uid, terms, institution)
    new_id = minted["id"]
    new_ref = db().collection("licenses").document(new_id)
    _update_doc(new_ref, {SUPERSEDES: license_id})
    slot = apps.licence_slot(lic)
    if user is not None:
        _update_doc(db().collection("licenses").document(license_id), {UPGRADE_PENDING_ID: new_id})
        _update_doc(db().collection("users").document(holder_uid),
                    apps.written_fields("pendingLicenseId", slot, new_id))
    else:
        if address:
            _delete_license_invites(license_id, lic.get("emailLock") or "", app=slot)
            _write_invite(new_id, address, admin_uid, app=slot)
        _retire(license_id, new_id, admin_uid)
    return {
        "key": minted["key"],
        "license": get_license_public(new_id),
        "supersededId": license_id,
        "holderUid": holder_uid,
        "pending": user is not None,
    }


def _retire(old_id: str, new_id: str, actor_uid: str) -> None:
    """Revoke the replaced licence, naming its successor. The holder's account
    points at the new licence by now, so the revoke's demotion passes them by."""
    revoke_license(old_id, actor_uid)
    _update_doc(db().collection("licenses").document(old_id), {
        "supersededBy": new_id,
        "supersededAt": _base.firestore.SERVER_TIMESTAMP,
        UPGRADE_PENDING_ID: _base.firestore.DELETE_FIELD,
    })


def pending_upgrade(user: dict, app: str) -> dict | None:
    """The licence waiting for `user` to activate for `app`, as the app and
    the account page show it, or None. Reads only while one waits."""
    new_id = apps.licence_view(user, app).get("pendingLicenseId") or ""
    if not new_id:
        return None
    lic = get_license(new_id)
    if not lic or _is_revoked(lic):
        return None
    public = get_license_public(new_id) or {}
    return {"licenseId": new_id, "prefix": public.get("keyPrefix") or "",
            "kind": public.get("kind") or "", "expiresAt": public.get("expiresAt"),
            "maxDevices": public.get("maxDevices"), "supersedes": lic.get(SUPERSEDES) or ""}


def apply_upgrade(new_id: str, uid: str, app: str, *, actor: str = ACTOR_SELF,
                  by_uid: str = "", device_id: str = "") -> dict:
    """Move `uid` onto the licence issued them, then activate `device_id` on
    it when one is named. Raises `Refusal`.

    In order, each step safe to run again: the account takes the new licence
    (a seat on an institution one), the old licence is revoked with
    `supersededBy`, the pointer is cleared, and the phone is activated. The
    phone is checked before anything moves, so a phone that cannot activate
    leaves the holder where they were. A step that dies part-way is finished
    by the next Activate, since the pointer stays until the old licence is
    revoked.
    """
    new = _licences(new_id)
    old_id = new.get(SUPERSEDES) or ""
    if not old_id or _is_revoked(new):
        raise Refusal(errors.NO_PENDING_UPGRADE)
    user = _load_user(uid) or {}
    slot = apps.licence_slot(new)
    pointed = apps.read_field(user, "pendingLicenseId", slot) == new_id
    if not pointed and apps.slot_holding(user, new_id) is None:
        raise Refusal(errors.NO_PENDING_UPGRADE)
    if device_id:
        dev = (_base._get(db().collection("devices").document(device_id)).to_dict() or {})
        if not (dev.get("uid") == uid and apps.same_app(dev.get("app"), app)):
            raise Refusal(errors.DEVICE_NOT_FOUND)

    address = normalize_email(verified_address(user) or new.get("emailLock"))
    patch = _member_patch(new_id, new)
    if _is_institution(new):
        err = claim_seat(new_id, uid, address, "", patch, app=slot)
    else:
        err = claim_individual_license(new_id, uid, address, patch)
    if err:
        raise Refusal(_public_claim_error(err))
    old = get_license(old_id) or {}
    if old and not _is_revoked(old):
        _retire(old_id, new_id, by_uid or uid)
    if pointed:
        _update_doc(db().collection("users").document(uid),
                    apps.written_fields("pendingLicenseId", slot, _base.firestore.DELETE_FIELD))
    audit.record(by_uid or uid, device_id or None, action="LICENSE_UPGRADE_APPLY",
                 target={"type": "license", "id": new_id},
                 detail={"supersededId": old_id, "uid": uid, "app": app, "actor": actor})
    activation = (activate_device(uid, device_id, app, actor=actor, by_uid=by_uid)
                  if device_id else None)
    return {"licenseId": new_id, "supersededId": old_id, "activation": activation}
