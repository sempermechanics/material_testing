"""Institution licence self-service for the licence's own IT admins.
"""
import logging

from .. import apps, errors
from ..licenses import email_domain, normalize_email
from ..errors import Refusal

from ._base import (
    _cursor_page,
    _is_institution,
    _is_revoked,
    db,
    get_license,
    _seat_ref,
    _get,
    _stream,
)
from .claims import (
    claim_seat,
    _member_patch,
    _public_claim_error,
)
from .holders import (
    _eligible_holder,
    licence_held_by,
    verified_address,
)
from .invites import (
    find_user_by_email,
    hold_institution_invite,
    invite_institution_member,
    _drop_held_invite,
)
from .mint import (
    _license_public,
)

log = logging.getLogger("semper.firestore")


# ---------------- institution seat administration ----------------
# Reached only via routers/institutions.py, gated on current_user + APPROVED +
# verified email present in the license's adminEmails — deliberately NOT
# verified_device and NOT Semper role=admin. Mint/whole-key-revoke stays on
# the existing device-attested admin path in routers/admin.py.

def is_institution_admin(license_doc: dict, email: str) -> bool:
    admin_emails = {normalize_email(e) for e in (license_doc.get("adminEmails") or [])}
    return bool(email) and normalize_email(email) in admin_emails


def list_licenses_administered_by(email: str) -> list[dict]:
    """Every live institution licence that names this address in `adminEmails`.

    The inverse of `is_institution_admin`, which can only answer for a licence
    id you already hold. Sign-in has an address and nothing else, so without
    this a member of institution IT has no way to reach their own roster
    except by being told the id out of band.

    `kind` and `status` are filtered in Python rather than added to the query:
    one address administers a handful of licences at most, and each extra
    equality clause on top of `array_contains` costs another composite index
    for no measurable gain.

    Same redaction as `institution_license_summary` — no key plaintext.
    """
    wanted = normalize_email(email)
    if not wanted:
        return []
    out = []
    q = db().collection("licenses").where("adminEmails", "array_contains", wanted)
    for d in _stream(q):
        data = d.to_dict() or {}
        if not _is_institution(data):
            continue
        if _is_revoked(data):
            continue
        out.append(_license_public(d.id, data))
    out.sort(key=lambda lic: lic["id"])
    return out


def add_institution_member(license_id: str, email: str, invited_by_uid: str = "", *,
                           institution_it: bool = False) -> tuple[dict | None, dict | None]:
    """Put someone on an institution license's roster. Returns (seat, invite),
    or raises `Refusal`.

    On an assigned license this entitles them immediately. On a floating one
    it makes them eligible; they still check out a lease to work, and adding
    a member therefore consumes no slot.

    Exactly one of `seat` and `invite` is set on success. An address with no
    account yet becomes a pending invite rather than a `user_not_found` error:
    IT works from a list of addresses and cannot make people sign up first, so
    refusing them was pushing a scheduling problem onto the wrong person.

    Idempotent for an active seat, and re-adding someone previously revoked
    gives them a fresh slot. A *disabled* seat is refused with
    `license_seat_disabled` — disable is a deliberate hold that IT lifts with
    `enabled=true`, and silently undoing it here would make the two routes
    fight over the same state.

    `institution_it` is the licence's own IT calling (ADDENDUM-2, readiness
    TE3-102), with two rules Semper staff do not have:
    - only an address on the licence's `domainLock` may be added; anything
      else is `member_off_domain`, decided before the person is looked up.
      Staff may still seat an external collaborator.
    - an address another licence holds or promises is not refused with
      `member_already_licensed`: it gets the invite an unknown address gets,
      held rather than claimable (`hold_institution_invite`), so the reply
      says nothing about other licences. The case is logged for staff.
    """
    lic = get_license(license_id)
    if not lic:
        raise Refusal(errors.LICENSE_NOT_FOUND)
    if institution_it:
        domain_lock = (lic.get("domainLock") or "").strip().lower()
        if not domain_lock or email_domain(email) != domain_lock:
            raise Refusal(errors.MEMBER_OFF_DOMAIN)
    user = find_user_by_email(email)
    # One licence per person: someone who holds, or is promised, a different
    # live licence is refused rather than moved onto this roster. Their own
    # seat on this licence is excluded, so re-adding a member stays a no-op.
    held_by = licence_held_by(email, user=user or {}, exclude_id=license_id,
                              app=lic.get("app"))
    if held_by and institution_it:
        log.info("roster add held: licence=%s already held by licence=%s", license_id, held_by)
        return None, hold_institution_invite(license_id, email, invited_by_uid)
    if held_by:
        raise Refusal(errors.MEMBER_ALREADY_LICENSED)
    # An account that is not approved, or whose address is unproven, is not
    # seated: the address is the whole claim to the seat. It gets the invite
    # an address with no account gets, which its next request claims once it
    # qualifies (`repo.entitlement`), as the mint path already did.
    if not _eligible_holder(user):
        try:
            return None, invite_institution_member(license_id, email, invited_by_uid)
        except Refusal as refusal:
            # `invite_exists`: promised to another licence that is past its
            # grace but not revoked. To IT that is one more case of "held".
            if not (institution_it and refusal.code == errors.INVITE_EXISTS):
                raise
            log.info("roster add held: licence=%s address promised elsewhere", license_id)
            return None, hold_institution_invite(license_id, email, invited_by_uid)
    uid = user["uid"]

    # No device lock: IT adds a member before that member has picked a device,
    # and the lock is set the first time they actually use the license.
    err = claim_seat(license_id, uid, verified_address(user), "",
                     _member_patch(license_id, lic))
    if err:
        raise Refusal(_public_claim_error(err))
    _drop_held_invite(license_id, email, apps.licence_slot(lic))
    return institution_seat(license_id, uid), None


def institution_license_summary(license_id: str, lic: dict | None = None) -> dict | None:
    """Public (no key plaintext) summary of one institution license, for IT
    self-service — same redaction as the Semper-staff admin listing, scoped to
    callers who already passed the adminEmails membership check.

    `lic` is the licence document when the caller has just read it (the
    membership check does), so the roster listing does not read it twice."""
    lic = lic if lic is not None else get_license(license_id)
    return _license_public(license_id, lic) if lic else None


def institution_seat(license_id: str, uid: str) -> dict | None:
    """One seat as the roster lists it, or None. One document read, where
    finding it in `list_institution_seats` read the whole roster."""
    snap = _get(_seat_ref(license_id, uid))
    return _seat_public(snap.id, snap.to_dict() or {}) if snap.exists else None


def page_institution_seats(license_id: str, limit: int,
                           page_token: str | None = None) -> tuple[list[dict], str | None]:
    """One page of a roster, in uid order. Returns (seats, next_token)."""
    col = db().collection("licenses").document(license_id).collection("seats")
    docs, next_token = _cursor_page(col, col, limit, page_token)
    return [_seat_public(d.id, d.to_dict() or {}) for d in docs], next_token


def list_institution_seats(license_id: str) -> list[dict]:
    return [
        _seat_public(doc.id, doc.to_dict() or {})
        for doc in _stream(db().collection("licenses").document(license_id).collection("seats"))
    ]


def _seat_public(seat_id: str, s: dict) -> dict:
    """A seat as the roster shows it."""
    return {
        "uid": seat_id,
        "email": s.get("email") or "",
        **apps.spread("deviceIdLock", {app: apps.read_field(s, "deviceIdLock", app)
                                       for app in apps.ALL}),
        "status": s.get("status") or "active",
        # The lease is the point of the floating roster view: without it
        # IT cannot see who is actually using a seat right now, only who
        # is allowed to. Null on an assigned licence, which has no leases.
        "leaseExpiresAt": s.get("leaseExpiresAt"),
        "lastHeartbeatAt": s.get("lastHeartbeatAt"),
        "createdAt": s.get("createdAt"),
        "updatedAt": s.get("updatedAt"),
    }
