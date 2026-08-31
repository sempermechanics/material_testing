from fastapi import APIRouter, Depends, HTTPException

from .. import audit, errors, firestore_repo as repo
from .. import rate_limit
from ..deps import admin_user, verified_device
from ..models import UserConfigPatch
from ..validation import AccessStatus, PageToken, Uid

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
