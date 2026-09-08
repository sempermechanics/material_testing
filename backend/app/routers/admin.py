from fastapi import APIRouter, Depends, HTTPException

from .. import audit, errors, firestore_repo as repo
from .. import rate_limit
from ..deps import admin_user, verified_device
from ..licenses import KIND_INDIVIDUAL, KIND_INSTITUTION
from ..models import AdminLicenseCreate, AdminLicenseUpdate, UserConfigPatch
from ..validation import AccessStatus, DocumentId, PageToken, Uid

router = APIRouter()


@router.get("/v1/admin/users")
def admin_list_users(
    status: AccessStatus = "",
    limit: int = 50,
    page_token: PageToken = "",
    admin=Depends(admin_user),
):
    """List users, optionally filtered by access_status (e.g. ?status=PENDING).

    Cursor-paginated: `limit` (1..200, default 50) and optional `page_token`.
    Response includes `nextPageToken` / `hasMore`.
    """
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    limit = max(1, min(limit, 200))
    users, next_token = repo.list_users(
        status, limit=limit, page_token=page_token or None,
    )
    return {
        "users": users,
        "page": {
            "size": limit,
            "count": len(users),
            "nextPageToken": next_token,
            "hasMore": bool(next_token),
        },
    }


@router.post("/v1/admin/users/{uid}/approve")
def admin_approve_user(uid: Uid, ctx=Depends(verified_device), admin=Depends(admin_user)):
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    if not repo.set_user_status(uid, "APPROVED"):
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_APPROVE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "APPROVED"}


@router.post("/v1/admin/users/{uid}/revoke")
def admin_revoke_user(uid: Uid, ctx=Depends(verified_device), admin=Depends(admin_user)):
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    if not repo.set_user_status(uid, "SUSPENDED"):
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_REVOKE", target={"type": "user", "id": uid})
    return {"uid": uid, "access_status": "SUSPENDED"}


@router.patch("/v1/admin/users/{uid}/config")
def admin_patch_user_config(uid: Uid, body: UserConfigPatch,
                            ctx=Depends(verified_device), admin=Depends(admin_user)):
    """Set or clear per-user product-limit overrides on the Firestore user doc."""
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    # model_dump(exclude_unset=True) keeps omitted fields out; explicit nulls
    # remain so set_user_config can DELETE_FIELD them.
    patch = body.model_dump(exclude_unset=True)
    if not patch:
        raise HTTPException(400, errors.EMPTY_PATCH)
    resolved = repo.set_user_config(uid, patch)
    if resolved is None:
        raise HTTPException(404, errors.USER_NOT_FOUND)
    audit.record(admin["uid"], action="ADMIN_CONFIG", target={"type": "user", "id": uid},
                 detail={"patch": patch, "resolved": resolved})
    return {"uid": uid, "config": resolved}


@router.get("/v1/admin/licenses")
def admin_list_licenses(
    limit: int = 50,
    page_token: PageToken = "",
    admin=Depends(admin_user),
):
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    limit = max(1, min(limit, 200))
    licenses, next_token = repo.list_licenses(limit=limit, page_token=page_token or None)
    return {
        "licenses": licenses,
        "page": {
            "size": limit,
            "count": len(licenses),
            "nextPageToken": next_token,
            "hasMore": bool(next_token),
        },
    }


@router.post("/v1/admin/licenses")
def admin_create_license(
    body: AdminLicenseCreate,
    ctx=Depends(verified_device),
    admin=Depends(admin_user),
):
    """Mint a licensed key. `kind=individual` (default) locks one email and
    one device, plaintext once. `kind=institution` mints an institution key
    instead — no email/device lock at mint time; membership is decided
    per-activation by `domainLock`, and `adminEmails` names the IT contacts
    who self-serve seat management via
    /v1/institutions/licenses/{id}/seats (see routers/institutions.py).
    Only Semper staff (this device-attested admin path) may mint or whole-key
    revoke; institution IT never reaches this route.
    """
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    if body.kind == KIND_INSTITUTION:
        minted = repo.create_institution_license(
            domain_lock=body.domainLock,
            admin_emails=body.adminEmails,
            created_by_uid=admin["uid"],
            max_seats=body.maxSeats,
            seating=body.seating,
            expires_at=body.expiresAt,
            max_analyses=body.maxAnalyses,
            note=body.note,
        )
        audit.record(
            admin["uid"], action="ADMIN_LICENSE_MINT",
            target={"type": "license", "id": minted["license"]["id"]},
            detail={"kind": KIND_INSTITUTION, "domainLock": body.domainLock,
                    "adminEmails": body.adminEmails, "maxSeats": body.maxSeats,
                    "seating": body.seating},
        )
        return minted
    minted = repo.create_individual_license(
        email_lock=body.emailLock,
        device_id_lock=body.deviceIdLock,
        created_by_uid=admin["uid"],
        expires_at=body.expiresAt,
        max_analyses=body.maxAnalyses,
        note=body.note,
    )
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_MINT",
        target={"type": "license", "id": minted["license"]["id"]},
        detail={"kind": KIND_INDIVIDUAL, "emailLock": body.emailLock,
                "deviceIdLock": body.deviceIdLock},
    )
    return minted


@router.patch("/v1/admin/licenses/{license_id}")
def admin_update_license(
    license_id: DocumentId,
    body: AdminLicenseUpdate,
    ctx=Depends(verified_device),
    admin=Depends(admin_user),
):
    """Device-attested, Semper-staff only. Change a license's terms in place.

    Renewal lives here: extending `expiresAt` re-entitles everyone already on
    the license without issuing a new key or asking anyone to re-activate. The
    new terms are pushed to the individual redeemer, or to every non-revoked
    institution seat, before this returns.

    Terms only — `kind`, the locks and the key itself are fixed at mint.
    """
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    patch = body.model_dump(exclude_none=True)
    updated = repo.update_license(license_id, patch, admin["uid"])
    if updated is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_EXTEND",
        target={"type": "license", "id": license_id},
        detail={k: str(v) for k, v in patch.items()},
    )
    return updated


@router.post("/v1/admin/licenses/{license_id}/revoke")
def admin_revoke_license(
    license_id: DocumentId,
    ctx=Depends(verified_device),
    admin=Depends(admin_user),
):
    if not rate_limit.admin_bucket.allow(admin["uid"]):
        raise HTTPException(429, errors.RATE_LIMITED)
    revoked = repo.revoke_license(license_id, admin["uid"])
    if revoked is None:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_REVOKE",
        target={"type": "license", "id": license_id},
    )
    return revoked
