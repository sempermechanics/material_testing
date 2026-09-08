"""Self-service seat management for institution licenses.

No dashboard UI ships in this repo yet — institution IT drives these three
routes directly (script, curl, or their own tooling). Deliberately separate
from `routers/admin.py`: Semper-staff mint and whole-key revoke stay on the
existing device-attested `admin_user` path; institution IT gets a narrower,
token-only surface scoped to exactly the license(s) that name them.

Routes are served under both `/v1/institutions/*` (current) and the
pre-rename `/v1/campus/*`. The aliases exist because institution IT scripts
and curl one-liners are out of our control; drop them only after a
deprecation window (see docs/backend/CLOUD_ARCHITECTURE_GCP.md §20).

Auth is `current_user` (ID token, APPROVED — enforced by current_user itself)
plus a *verified* email present in that specific license's `adminEmails`. Not
`verified_device` — IT manages seats from a browser/curl, not the licensed
device. Not Semper `role=admin` — an institution admin has no authority
outside the licenses that name them, and cross-tenant access (institution A
IT reaching institution B's seats) 404s the same as a license that does not
exist.
"""
from fastapi import APIRouter, Depends, HTTPException

from .. import audit, firestore_repo as repo
from .. import rate_limit
from ..deps import current_user
from ..licenses import KIND_INSTITUTION, normalize_kind
from ..models import InstitutionSeatAdd, InstitutionSeatPatch
from ..validation import DocumentId, Uid

router = APIRouter()


def institution_admin_context(license_id: DocumentId, user: dict = Depends(current_user)) -> dict:
    """Institution IT auth for one institution license.

    Fails closed at every step: unverified email, a license id that does not
    exist or is not an institution license, or an email absent from that
    license's `adminEmails` all deny (404 for "no such institution license
    reachable by you",
    403 for "wrong email") rather than falling through to broader access. The
    404 on a real-but-foreign license is intentional — it must read
    identically to a license that does not exist, so probing license ids from
    another institution learns nothing.
    """
    if not user.get("emailVerified"):
        raise HTTPException(403, "email_not_verified")
    lic = repo.get_license(license_id)
    if not lic or normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        raise HTTPException(404, "license_not_found")
    email = (user.get("email") or "").strip().lower()
    if not repo.is_institution_admin(lic, email):
        raise HTTPException(404, "license_not_found")
    return {"user": user, "license_id": license_id}


@router.get("/v1/institutions/licenses/{license_id}/seats")
@router.get("/v1/campus/licenses/{license_id}/seats", include_in_schema=False)  # pre-rename alias
def list_seats(license_id: DocumentId, ctx=Depends(institution_admin_context)):
    """Every seat on this license: uid, email, device lock, status. No key
    plaintext — only the license's keyPrefix, same redaction as the
    Semper-staff admin listing."""
    if not rate_limit.institution_bucket.allow(ctx["user"]["uid"]):
        raise HTTPException(429, "rate_limited")
    return {
        "license": repo.institution_license_summary(license_id),
        "seats": repo.list_institution_seats(license_id),
    }


@router.post("/v1/institutions/licenses/{license_id}/seats")
@router.post("/v1/campus/licenses/{license_id}/seats", include_in_schema=False)  # pre-rename alias
def add_seat(
    license_id: DocumentId,
    body: InstitutionSeatAdd,
    ctx=Depends(institution_admin_context),
):
    """Put someone on this license's roster, by email.

    They must already have an account. Demo is open to everyone, so "sign in
    once, then I'll add you" is the flow — which is why there are no invite
    records, no email-keyed documents and no second identity space here.
    `404 user_not_found` means exactly that: ask them to sign in first.

    On an assigned license this entitles them immediately. On a floating one
    it makes them eligible and consumes no slot; they check out a lease when
    they want to work.
    """
    if not rate_limit.institution_bucket.allow(ctx["user"]["uid"]):
        raise HTTPException(429, "rate_limited")
    code, seat = repo.add_institution_member(license_id, body.email)
    if code:
        status = {
            "license_not_found": 404,
            "user_not_found": 404,
            "license_seats_exhausted": 409,
            "license_seat_disabled": 409,
        }.get(code, 403)
        raise HTTPException(status, code)
    audit.record(
        ctx["user"]["uid"], action="INSTITUTION_SEAT_ADD",
        target={"type": "seat", "id": f"{license_id}/{(seat or {}).get('uid')}"},
    )
    return {"licenseId": license_id, "seat": seat}


@router.patch("/v1/institutions/licenses/{license_id}/seats/{uid}")
@router.patch("/v1/campus/licenses/{license_id}/seats/{uid}", include_in_schema=False)  # pre-rename alias
def patch_seat(
    license_id: DocumentId,
    uid: Uid,
    body: InstitutionSeatPatch,
    ctx=Depends(institution_admin_context),
):
    """`clearDeviceLock=true` lets a seat holder re-bind to a new device
    without a Semper support ticket. `enabled=false` drops the seat to Demo
    *without* freeing the slot (still counts against maxSeats); `enabled=true`
    restores Professional in place — same uid/account, no data migration
    either direction. At least one field must be set."""
    if not rate_limit.institution_bucket.allow(ctx["user"]["uid"]):
        raise HTTPException(429, "rate_limited")
    if body.clearDeviceLock is None and body.enabled is None:
        raise HTTPException(400, "empty_patch")
    if body.clearDeviceLock:
        if not repo.clear_seat_device_lock(license_id, uid):
            raise HTTPException(404, "seat_not_found")
    if body.enabled is not None:
        if not repo.set_seat_enabled(license_id, uid, body.enabled):
            raise HTTPException(404, "seat_not_found")
    audit.record(
        ctx["user"]["uid"], action="INSTITUTION_SEAT_PATCH",
        target={"type": "seat", "id": f"{license_id}/{uid}"},
        detail={"clearDeviceLock": bool(body.clearDeviceLock), "enabled": body.enabled},
    )
    seats = repo.list_institution_seats(license_id)
    seat = next((s for s in seats if s["uid"] == uid), None)
    if seat is None:
        raise HTTPException(404, "seat_not_found")
    return {"licenseId": license_id, "seat": seat}


@router.delete("/v1/institutions/licenses/{license_id}/seats/{uid}")
@router.delete("/v1/campus/licenses/{license_id}/seats/{uid}", include_in_schema=False)  # pre-rename alias
def revoke_seat(license_id: DocumentId, uid: Uid, ctx=Depends(institution_admin_context)):
    """Single-seat revoke: drops the holder to Demo (in place, no data loss)
    and frees the slot so another domain member can activate. Whole-key revoke
    stays on the Semper-staff POST /v1/admin/licenses/{id}/revoke path."""
    if not rate_limit.institution_bucket.allow(ctx["user"]["uid"]):
        raise HTTPException(429, "rate_limited")
    if not repo.revoke_institution_seat(license_id, uid):
        raise HTTPException(404, "seat_not_found")
    audit.record(
        ctx["user"]["uid"], action="INSTITUTION_SEAT_REVOKE",
        target={"type": "seat", "id": f"{license_id}/{uid}"},
    )
    return {"licenseId": license_id, "uid": uid, "revoked": True}
