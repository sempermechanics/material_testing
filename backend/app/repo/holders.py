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
from ..licenses import (
    MODE_DEMO,
    STATUS_REVOKED,
    normalize_email,
)

from ._base import (
    db,
    get_license,
    _license_mode,
    _license_past_grace,
)
from .invites import (
    find_user_by_email,
    _invite_ref,
)


def licence_is_live(lic: dict | None) -> bool:
    """Whether a licence still counts as the one its holder has.

    Not revoked, not past its grace, and not a Demo key: a Demo key is what
    every account holds before it is licensed, and replacing it is the point
    of issuing one. A licence past its grace grants nothing, so holding one
    is no reason to refuse a new one.
    """
    if not lic:
        return False
    if (lic.get("status") or "") == STATUS_REVOKED:
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
    for doc in db().collection("licenses").where("emailLock", "==", address).stream():
        if doc.id != exclude_id and licence_is_live(doc.to_dict()):
            return doc.id
    invite = _invite_ref(address).get()
    if invite.exists:
        promised = (invite.to_dict() or {}).get("licenseId") or ""
        if promised and promised != exclude_id and licence_is_live(get_license(promised)):
            return promised
    return ""
