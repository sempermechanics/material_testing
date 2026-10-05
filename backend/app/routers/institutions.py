"""Self-service seat management for institution licenses.

Institution IT drives these routes from the institution console (or curl).
The roster routes themselves are `routers/roster.py`, mounted here for IT and
in `routers/admin.py` for Semper staff; this module is IT's tier.
Deliberately separate from `routers/admin.py`: Semper-staff mint and
whole-key revoke stay on the staff step-up path; institution IT gets a
narrower surface scoped to exactly the license(s) that name them.

Routes are served under `/v1/institutions/*` only. The pre-rename
`/v1/campus/*` aliases were retired on 2026-09-26 after 30 days with no
request to them (TD-45; docs/backend/CLOUD_ARCHITECTURE_GCP.md §20.5).

Auth is `current_user` (ID token, APPROVED) plus a *verified* email present
in that specific license's `adminEmails`, **plus** the same browser step-up
every dashboard uses (completed second factor on a recent sign-in). Not
Semper `role=admin` — an institution admin has no authority outside the
licenses that name them, and cross-tenant access (institution A IT reaching
institution B's seats) 404s the same as a license that does not exist —
membership is checked before MFA so a probe learns nothing about the factor.
"""
from fastapi import APIRouter, Depends, Header, HTTPException, Request

from .. import errors, repo
from .. import rate_limit
from ..config import settings
from ..deps import current_user, step_up_check
from ..licenses import KIND_INSTITUTION, normalize_email, normalize_kind
from ..validation import DocumentId
from .roster import RosterAdmin, Tier, roster_router

router = APIRouter()


def institution_admin_context(license_id: DocumentId,
                              user: dict = Depends(current_user)) -> RosterAdmin:
    """Institution IT auth for one institution license (membership only).

    Fails closed at every step: an unverified email is 403, and a license id
    that does not exist, is not an institution license, or does not name the
    caller in `adminEmails` is 404. The 404 on a real-but-foreign license is
    intentional — it must read identically to a license that does not exist,
    so probing license ids from another institution learns nothing.

    The roster routes use `institution_admin_stepup`, which layers the
    dashboard step-up on top of this check.
    """
    if not user.get("emailVerified"):
        raise HTTPException(403, errors.EMAIL_NOT_VERIFIED)
    lic = repo.get_license(license_id)
    if not lic or normalize_kind(lic.get("kind")) != KIND_INSTITUTION:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    if not repo.is_institution_admin(lic, normalize_email(user.get("email"))):
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    return RosterAdmin(user, license_id, lic)


async def institution_admin_stepup(
    license_id: DocumentId,
    request: Request,
    user: dict = Depends(current_user),
    x_device_id: str = Header(default=""),
    x_nonce: str = Header(default=""),
    x_signature: str = Header(default=""),
) -> RosterAdmin:
    """Institution IT + dashboard MFA step-up.

    Membership first (so foreign licences still 404), then the same
    device-or-MFA gate the operator and account consoles use.
    """
    admin = institution_admin_context(license_id, user)
    await step_up_check(request, user, x_device_id, x_nonce, x_signature,
                        max_age_seconds=settings.ADMIN_WEB_REAUTH_SECONDS)
    return admin


@router.get("/v1/institutions/licenses")
def list_my_licenses(user=Depends(current_user)):
    """Which institution licences this caller administers.

    Every other route here is addressed by a licence id the caller already
    holds. Sign-in holds an address and nothing else, so without this a member
    of institution IT can only reach their roster by being told the id out of
    band — which is how it worked, and why the console had a text box asking
    for one. This is the read that lets a single sign-in page decide where to
    send somebody.

    Verified email, same as `institution_admin_context` — `adminEmails` names
    addresses, and an address nobody has proved they own must not be able to
    read a customer's roster. An empty list is the ordinary answer for the
    overwhelming majority of accounts and is not an error.
    """
    rate_limit.enforce(rate_limit.institution_bucket, user["uid"])
    if not user.get("emailVerified"):
        raise HTTPException(403, errors.EMAIL_NOT_VERIFIED)
    licenses = repo.list_licenses_administered_by(user.get("email") or "")
    return {"licenses": licenses}


router.include_router(
    roster_router(Tier(
        reader=institution_admin_stepup, writer=institution_admin_stepup,
        bucket=rate_limit.institution_bucket, actor=repo.ACTOR_IT, audit="INSTITUTION",
    )),
    prefix="/v1/institutions/licenses/{license_id}",
)
