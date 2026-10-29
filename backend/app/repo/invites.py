"""Pending invites: the promise of a licence to an address with no account yet.
"""
from .. import apps, errors
from ..errors import Refusal
from ..licenses import (
    invite_id,
    normalize_email,
)

from . import _base
from ._base import (
    _is_institution,
    _is_revoked,
    db,
    get_license,
    SCHEMA_VERSION,
    _get,
    _stream,
    _set_doc,
    _delete_doc,
)


def find_user_by_email(email: str) -> dict | None:
    """The account holding this email, or None. Used to add a roster member.

    Institution IT works from an email address, but seats are keyed by uid —
    they have to be, since that is what every entitlement check has in hand.
    Demo is open to everyone, so requiring the person to have signed in once
    is not a barrier: it is the same step that gave them demo in the first
    place, and it means no invite records, no email-keyed documents and no
    second identity space to keep consistent.
    """
    wanted = normalize_email(email)
    if not wanted:
        return None
    q = db().collection("users").where("email", "==", wanted).limit(1)
    for d in _stream(q):
        return {**d.to_dict(), "uid": d.id}
    return None


#: Pending invites, keyed by a hash of the invited address. Top-level rather
#: than a subcollection of the licence so claiming one at sign-in is a single
#: document read: a subcollection would need a collection-group query (and its
#: index) on a path that runs for every account that does not yet hold a key.
_INVITES = "licenseInvites"


def _invite_id(email: str, app: str = apps.SEMPER) -> str:
    """An address's claim key for `app`'s licence (ADR-033): Semper's is the
    key every invite has had."""
    return invite_id(email, apps.canonical(app))


def _invite_ref(email: str, app: str = apps.SEMPER):
    return db().collection(_INVITES).document(_invite_id(email, app))


def _held_ref(license_id: str, email: str, app: str = apps.SEMPER):
    """A held invite: IT's add of an address another licence holds or
    promises (`hold_institution_invite`). Its own document in `licenseInvites`,
    never at the address's claim key, so the sign-in claim never reads it and
    the other licence's promise is untouched."""
    return db().collection(_INVITES).document(f"held_{license_id}_{_invite_id(email, app)}")


def _invite_public(doc_id: str, inv: dict) -> dict:
    if inv.get("held"):
        # Listed under the id an ordinary invite of this address has, so
        # nothing in a roster tells a held invite from a pending one.
        doc_id = _invite_id(inv.get("email") or "", inv.get("app") or apps.SEMPER)
    return {
        "id": doc_id,
        "email": inv.get("email") or "",
        "licenseId": inv.get("licenseId") or "",
        "invitedByUid": inv.get("invitedByUid") or "",
        "createdAt": inv.get("createdAt"),
    }


def invite_institution_member(
    license_id: str, email: str, invited_by_uid: str,
) -> dict:
    """Reserve a roster place for someone who has no account yet.

    Returns the invite, or raises `Refusal`. The invite is redeemed by `claim_pending_invite`
    the first time that address signs in, which is also the moment the person
    would otherwise have been given a Demo key.

    Deliberately consumes no seat and no slot. Until a real account claims it
    there is no uid, and every entitlement check in the system is keyed by uid
    — so counting an invite against `maxSeats` would mean decrementing a count
    for a person who may never arrive. An invite is a promise; the seat is
    taken when it is kept.
    """
    lic = get_license(license_id)
    if not lic or not _is_institution(lic):
        raise Refusal(errors.LICENSE_NOT_FOUND)
    if _is_revoked(lic):
        raise Refusal(errors.LICENSE_REVOKED)
    app = apps.licence_slot(lic)
    err, invite = _write_invite(license_id, email, invited_by_uid, app=app)
    if err:
        raise Refusal(err)
    _drop_held_invite(license_id, email, app)
    return invite


def hold_institution_invite(license_id: str, email: str, invited_by_uid: str) -> dict:
    """Institution IT's answer for an address another licence holds or
    promises: what an unknown address gets, a pending invite on this roster
    (ADDENDUM-2, readiness TE3-102). Returns the invite, or raises `Refusal`
    exactly as `invite_institution_member` would.

    Telling IT `member_already_licensed` made the roster an oracle: one
    guessed address at a time, IT learned who another institution had seated
    or invited. The held invite looks the same in the add reply, the roster
    listing, the import and the audit rows IT can read. It is never claimed
    (`claim_pending_invite` reads only the address's own key) and counts for
    nothing in `licence_held_by`, so it cannot move the person off the
    licence they have. IT withdraws it like any invite; adding the address
    again once it is free seats or invites it for real and drops this.
    """
    lic = get_license(license_id)
    if not lic or not _is_institution(lic):
        raise Refusal(errors.LICENSE_NOT_FOUND)
    if _is_revoked(lic):
        raise Refusal(errors.LICENSE_REVOKED)
    address = normalize_email(email)
    if not address:
        raise Refusal(errors.INVALID_EMAIL)
    app = apps.licence_slot(lic)
    own = _get(_invite_ref(address, app))
    if own.exists and ((own.to_dict() or {}).get("licenseId") or "") == license_id:
        return _invite_public(own.id, own.to_dict() or {})
    ref = _held_ref(license_id, address, app)
    snap = _get(ref)
    if snap.exists:
        return _invite_public(ref.id, snap.to_dict() or {})
    data = {
        "email": address,
        "licenseId": license_id,
        "invitedByUid": invited_by_uid,
        "held": True,
        **({"app": app} if app != apps.SEMPER else {}),
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    _set_doc(ref, data)
    return _invite_public(ref.id, _get(ref).to_dict() or data)


def _drop_held_invite(license_id: str, email: str, app: str = apps.SEMPER) -> None:
    """Remove this roster's held invite for the address, if any: it has just
    been seated or invited for real."""
    ref = _held_ref(license_id, email, app)
    if _get(ref).exists:
        _delete_doc(ref)


def _invite_is_stale(license_id: str) -> bool:
    """True when the licence an invite points at can never be claimed.

    `claim_pending_invite` deletes such an invite the moment the invited
    person signs in, so it holds no promise to anyone. Refusing a new invite
    on its behalf only makes the replacement licence undeliverable too.
    """
    if not license_id:
        return True
    lic = get_license(license_id)
    return not lic or _is_revoked(lic)


def _delete_license_invites(license_id: str, email_lock: str = "",
                            app: str = apps.SEMPER) -> None:
    """Drop the promises a revoked licence can no longer keep.

    Revoking used to leave `licenseInvites` untouched, which turned the most
    ordinary correction there is — mint against the wrong address, revoke,
    mint again for the right one — into a licence nobody could receive:
    `_write_invite` refused an address already promised elsewhere, so the
    second mint was minted and undeliverable.

    An individual licence has at most one invite, at the key derived from its
    `emailLock`, so it costs one delete guarded on `licenseId` — the same
    guard `revoke_institution_invite` uses, and for the same reason. Without
    an address to hash, and for an institution licence, it is the query
    instead, over a list that is small by construction.
    """
    if email_lock:
        ref = _invite_ref(email_lock, app)
        snap = _get(ref)
        if snap.exists and ((snap.to_dict() or {}).get("licenseId") or "") == license_id:
            _delete_doc(ref)
        return
    for doc in _stream(db().collection(_INVITES).where("licenseId", "==", license_id)):
        _delete_doc(doc.reference)


def _write_invite(license_id: str, email: str, invited_by_uid: str,
                  app: str = apps.SEMPER) -> tuple[str, dict | None]:
    """Record the promise itself, having already established the licence is
    one worth promising.

    Split from `invite_institution_member` because an individual licence is
    delivered the same way — mint against an address, let the person sign in —
    but reaches this point through a different set of checks. The record is
    identical either way, and `claim_pending_invite` reads the licence to
    decide what to grant, so the invite carries no notion of kind.

    `app` is the licence's app (`apps.licence_slot`): each app has its own
    claim key, so a person may be promised one licence per app (ADR-033).
    """
    address = normalize_email(email)
    if not address:
        return errors.INVALID_EMAIL, None

    ref = _invite_ref(address, app)
    existing = _get(ref)
    if existing.exists:
        held = existing.to_dict() or {}
        # Re-inviting to the same licence is a no-op rather than an error, so
        # IT pasting a list twice is harmless. A different *live* licence is
        # refused: silently moving someone between institutions would be the
        # wrong default, and the address is the only identity we have to go
        # on. A dead one is overwritten — see `_invite_is_stale`.
        if (held.get("licenseId") or "") == license_id:
            return "", _invite_public(ref.id, held)
        if not _invite_is_stale(held.get("licenseId") or ""):
            return errors.INVITE_EXISTS, None

    data = {
        "email": address,
        "licenseId": license_id,
        "invitedByUid": invited_by_uid,
        **({"app": apps.canonical(app)} if apps.canonical(app) != apps.SEMPER else {}),
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    _set_doc(ref, data)
    return "", _invite_public(ref.id, _get(ref).to_dict() or data)


def list_institution_invites(license_id: str) -> list[dict]:
    """Outstanding invites on one licence, oldest first.

    Sorted here rather than in Firestore: ordering by `createdAt` alongside the
    `licenseId` equality would need a composite index for a list that is small
    by construction (it drains as people sign in).
    """
    rows = [
        _invite_public(doc.id, doc.to_dict() or {})
        for doc in _stream(db().collection(_INVITES).where("licenseId", "==", license_id))
    ]
    rows.sort(key=lambda r: (r.get("createdAt") is None, r.get("createdAt")))
    return rows


def revoke_institution_invite(license_id: str, invite_key: str) -> bool:
    """Withdraw an unclaimed invite. False when there is none to withdraw.

    Addressed by the invite's own id — the hash `list_institution_invites`
    returns — rather than by email. That keeps addresses out of request paths
    and access logs, and it is the id the console already has in hand.

    Guarded on `licenseId` so one institution's IT cannot delete another's
    invite by guessing an id.
    """
    ref = db().collection(_INVITES).document(invite_key)
    snap = _get(ref)
    if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        # A held invite is listed under the address's own key, which may be
        # another licence's promise; this roster's entry is beside it.
        ref = db().collection(_INVITES).document(f"held_{license_id}_{invite_key}")
        snap = _get(ref)
    if not snap.exists or ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        return False
    _delete_doc(ref)
    return True
