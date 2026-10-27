"""The staff licence flows behind `routers/admin.py` (TD-230): minting a key
of either kind, and how an audit row records the IT contacts."""
from fastapi import HTTPException

from .. import audit, errors, repo
from ..licenses import KIND_INDIVIDUAL, KIND_INSTITUTION
from ..models import AdminLicenseCreate


def admin_email_detail(admin_emails) -> dict:
    """`adminEmails` as an audit detail holds it: hashed, with the count
    (TD-201). Audit rows outlive account erasure and must hold no address."""
    emails = list(admin_emails or [])
    return {"adminEmailHashes": audit.address_tags(emails), "adminEmailCount": len(emails)}


def _mint_institution(body: AdminLicenseCreate, admin_uid: str) -> dict:
    minted = repo.create_institution_license(
        domain_lock=body.domainLock,
        admin_emails=body.adminEmails,
        created_by_uid=admin_uid,
        max_seats=body.maxSeats,
        seating=body.seating,
        expires_at=body.expiresAt,
        grace_days=body.graceDays,
        support_until=body.supportUntil,
        max_analyses=body.maxAnalyses,
        note=body.note,
    )
    audit.record(
        admin_uid, action="ADMIN_LICENSE_MINT",
        target={"type": "license", "id": minted["license"]["id"]},
        detail={"kind": KIND_INSTITUTION, "domainLock": body.domainLock,
                **admin_email_detail(body.adminEmails), "maxSeats": body.maxSeats,
                "seating": body.seating},
    )
    return minted


def _mint_individual(body: AdminLicenseCreate, admin_uid: str) -> dict:
    # One licence per person. Refused before anything is written: the mint
    # used to go ahead and only report that the licence had not reached them.
    # The id is the licence they hold, for the desk to open — renewal is
    # Extend on that one.
    held = repo.licence_held_by(body.emailLock)
    if held:
        raise HTTPException(409, f"{errors.EMAIL_ALREADY_LICENSED}: {held}")
    minted = repo.create_individual_license(
        email_lock=body.emailLock,
        device_id_lock=body.deviceIdLock,
        created_by_uid=admin_uid,
        expires_at=body.expiresAt,
        grace_days=body.graceDays,
        support_until=body.supportUntil,
        max_analyses=body.maxAnalyses,
        note=body.note,
    )
    audit.record(
        admin_uid, action="ADMIN_LICENSE_MINT",
        target={"type": "license", "id": minted["license"]["id"]},
        detail={"kind": KIND_INDIVIDUAL, "emailLockHash": audit.address_tag(body.emailLock),
                "deviceIdLock": body.deviceIdLock,
                "inviteError": minted.get("inviteError") or "",
                # Set when the address already had an account, which the
                # mint attached to directly (see _attach_to_existing_holder).
                "claimedByUid": minted.get("claimedByUid") or "",
                "claimError": minted.get("claimError") or ""},
    )
    return minted


def mint_license(body: AdminLicenseCreate, admin_uid: str) -> dict:
    """Mint the key `body` describes, audited as `ADMIN_LICENSE_MINT`; the
    answer carries the plaintext key, once."""
    if body.kind == KIND_INSTITUTION:
        return _mint_institution(body, admin_uid)
    return _mint_individual(body, admin_uid)
