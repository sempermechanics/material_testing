"""One licence per person.

A person is an email address; the device they use is a lock on the licence
they hold, and moving it is a device change, never a second licence. Every
path that grants a licence asks `licence_held_by` first and refuses when the
answer is another live licence: staff minting for an address, a key typed in
the app, and IT adding someone to a roster. Each of those used to grant
regardless — the mint reporting afterwards that the licence had not attached,
the other two moving the account onto the new licence and leaving the old one
`redeemed` in its name.
"""
from .. import statuses
from ..licenses import (
    MODE_DEMO,
    normalize_email,
)

from ._base import (
    _is_revoked,
    db,
    get_license,
    _license_mode,
    _license_past_grace,
    _get,
    _stream,
)
from .invites import (
    find_user_by_email,
    _invite_ref,
)


def verified_address(user: dict | None) -> str:
    """The account's address, normalised, when it is proven; else "".

    The one reading of "this person's address" for anything an address
    grants: an invite, an institution roster or IT membership, a claim code.
    The stored `email` follows the sign-in token (`repo.users._touch_existing`)
    and so does `emailVerified`; an address nobody has proved they own grants
    nothing, so it is no address at all.
    """
    if not (user or {}).get("emailVerified"):
        return ""
    return normalize_email((user or {}).get("email"))


def _eligible_holder(user: dict | None) -> bool:
    """Whether an existing account may be handed a licence or a seat now:
    approved, with a proven address. Shared by staff minting for an address
    (`repo.mint`), IT adding a roster member (`repo.institution_admin`) and
    the invite claim at sign-in (`repo.entitlement`), so the three cannot
    disagree about who may hold one. An account that is not eligible yet is
    given the invite an address with no account gets, kept for it until it
    qualifies."""
    return (bool(user)
            and user.get("access_status") == statuses.ACCESS_APPROVED
            and bool(verified_address(user)))


def licence_is_live(lic: dict | None) -> bool:
    """Whether a licence still counts as the one its holder has.

    Not revoked, not past its grace, and not a Demo key: a Demo key is what
    every account holds before it is licensed, and replacing it is the point
    of issuing one. A licence past its grace grants nothing, so holding one
    is no reason to refuse a new one.
    """
    if not lic:
        return False
    if _is_revoked(lic):
        return False
    if _license_mode(lic) == MODE_DEMO:
        return False
    return not _license_past_grace(lic)


def licence_held_by(email: str, *, user: dict | None = None, exclude_id: str = "") -> str:
    """The id of a live licence this person holds or is promised, or "".

    Three places say a person has a licence: the account points at it; a
    licence is locked to the address and waiting for them to sign in; or an
    invite promises the address a roster place. `exclude_id` is the licence
    being granted, which may already be one of those.

    `user` is the account when the caller has it; otherwise it is looked up
    by address. Costs a handful of reads, on paths that run when a person is
    given a licence, never per request.
    """
    address = normalize_email(email)
    if user is None and address:
        user = find_user_by_email(address)
    held = (user or {}).get("licenseId") or ""
    if held and held != exclude_id and licence_is_live(get_license(held)):
        return held
    if not address:
        return ""
    for doc in _stream(db().collection("licenses").where("emailLock", "==", address)):
        if doc.id != exclude_id and licence_is_live(doc.to_dict()):
            return doc.id
    invite = _get(_invite_ref(address))
    if invite.exists:
        promised = (invite.to_dict() or {}).get("licenseId") or ""
        if promised and promised != exclude_id and licence_is_live(get_license(promised)):
            return promised
    return ""
