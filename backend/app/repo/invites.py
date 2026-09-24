"""Pending invites: the promise of a licence to an address with no account yet.
"""
from ..licenses import (
    KIND_INSTITUTION,
    invite_id,
    normalize_email,
    normalize_kind,
)

from . import _base
from ._base import (
    db,
    get_license,
    SCHEMA_VERSION,
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
    wanted = (email or "").strip().lower()
    if not wanted:
        return None
    q = db().collection("users").where("email", "==", wanted).limit(1)
    for d in q.stream():
        return {**d.to_dict(), "uid": d.id}
    return None


#: Pending invites, keyed by a hash of the invited address. Top-level rather
#: than a subcollection of the licence so claiming one at sign-in is a single
#: document read: a subcollection would need a collection-group query (and its
#: index) on a path that runs for every account that does not yet hold a key.
_INVITES = "licenseInvites"


def _invite_ref(email: str):
    return db().collection(_INVITES).document(invite_id(email))


def _invite_public(doc_id: str, inv: dict) -> dict:
    return {
        "id": doc_id,
        "email": inv.get("email") or "",
        "licenseId": inv.get("licenseId") or "",
        "invitedByUid": inv.get("invitedByUid") or "",
        "createdAt": inv.get("createdAt"),
    }


def invite_institution_member(
    license_id: str, email: str, invited_by_uid: str,
) -> tuple[str, dict | None]:
    """Reserve a roster place for someone who has no account yet.

    Returns (error, invite). The invite is redeemed by `claim_pending_invite`
    the first time that address signs in, which is also the moment the person
    would otherwise have been given a Demo key.

    Deliberately consumes no seat and no slot. Until a real account claims it
    there is no uid, and every entitlement check in the system is keyed by uid
    — so counting an invite against `maxSeats` would mean decrementing a count
    for a person who may never arrive. An invite is a promise; the seat is
    taken when it is kept.
    """
    lic = get_license(license_id)
    if not lic:
        return "license_not_found", None
    if normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        return "license_not_found", None
    if (lic.get("status") or "active") == "revoked":
        return "license_revoked", None
    return _write_invite(license_id, email, invited_by_uid)


def _invite_is_stale(license_id: str) -> bool:
    """True when the licence an invite points at can never be claimed.

    `claim_pending_invite` deletes such an invite the moment the invited
    person signs in, so it holds no promise to anyone. Refusing a new invite
    on its behalf only makes the replacement licence undeliverable too.
    """
    if not license_id:
        return True
    lic = get_license(license_id)
    return not lic or (lic.get("status") or "active") == "revoked"


def _delete_license_invites(license_id: str, email_lock: str = "") -> None:
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
        ref = _invite_ref(email_lock)
        snap = ref.get()
        if snap.exists and ((snap.to_dict() or {}).get("licenseId") or "") == license_id:
            ref.delete()
        return
    for doc in db().collection(_INVITES).where("licenseId", "==", license_id).stream():
        doc.reference.delete()


def _write_invite(license_id: str, email: str,
                  invited_by_uid: str) -> tuple[str, dict | None]:
    """Record the promise itself, having already established the licence is
    one worth promising.

    Split from `invite_institution_member` because an individual licence is
    delivered the same way — mint against an address, let the person sign in —
    but reaches this point through a different set of checks. The record is
    identical either way, and `claim_pending_invite` reads the licence to
    decide what to grant, so the invite carries no notion of kind.
    """
    address = normalize_email(email)
    if not address:
        return "invalid_email", None

    ref = _invite_ref(address)
    existing = ref.get()
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
            return "invite_exists", None

    data = {
        "email": address,
        "licenseId": license_id,
        "invitedByUid": invited_by_uid,
        "createdAt": _base.firestore.SERVER_TIMESTAMP,
        "schemaVersion": SCHEMA_VERSION,
    }
    ref.set(data)
    return "", _invite_public(ref.id, ref.get().to_dict() or data)


def list_institution_invites(license_id: str) -> list[dict]:
    """Outstanding invites on one licence, oldest first.

    Sorted here rather than in Firestore: ordering by `createdAt` alongside the
    `licenseId` equality would need a composite index for a list that is small
    by construction (it drains as people sign in).
    """
    rows = [
        _invite_public(doc.id, doc.to_dict() or {})
        for doc in db().collection(_INVITES).where("licenseId", "==", license_id).stream()
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
    snap = ref.get()
    if not snap.exists:
        return False
    if ((snap.to_dict() or {}).get("licenseId") or "") != license_id:
        return False
    ref.delete()
    return True
