"""Upgrade by issue (ADR-033): staff issue the holder a new licence, and the
holder moves onto it by tapping Activate (`POST /v1/me/devices/activate` with
`{"upgrade": true}`, in `routers/activations.py`).

Two staff routes, both device-attested or second-factor, like a mint:

- `POST /v1/admin/licenses/{id}/upgrade` issues the new licence for the
  holder of `{id}`, carrying its terms unless the body names new ones, and
  returns the key once. The old licence keeps working until the holder
  activates the new one.
- `POST /v1/admin/licenses/{id}/apply-upgrade` moves the holder onto the
  issued licence `{id}` without a phone, for a holder on a build that has no
  Activate.
"""
from fastapi import APIRouter, Depends, HTTPException

from .. import audit, errors, rate_limit, repo
from ..deps import rate_limited
from ..models import AdminLicenseUpgrade
from ..step_up import attested_or_mfa_admin
from ..validation import DocumentId

router = APIRouter()


@router.post(
    "/v1/admin/licenses/{license_id}/upgrade",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_issue_upgrade(
    license_id: DocumentId,
    body: AdminLicenseUpgrade,
    ctx=Depends(attested_or_mfa_admin),
):
    """Issue the holder of an individual licence that names its app a new
    licence for the same app. `409 license_not_upgradable` for any other
    licence (one from before the split converts with `/convert`), `409
    upgrade_pending: <id>` while an earlier one waits, `422
    convert_domain_mismatch` for an institution licence off the holder's
    domain. `pending` is false for a holder who never signed in: their
    invite moved, and the old licence is revoked now."""
    admin = ctx.user
    out = repo.issue_upgrade(license_id, admin_uid=admin["uid"], terms=body.terms(),
                             institution=body.institution())
    audit.record(
        admin["uid"], action="ADMIN_LICENSE_UPGRADE_ISSUE",
        target={"type": "license", "id": license_id},
        detail={"to": out["license"]["id"], "kind": body.kind, "pending": out["pending"],
                "holderUid": out["holderUid"], "maxDevices": out["license"].get("maxDevices")},
    )
    return out


@router.post(
    "/v1/admin/licenses/{license_id}/apply-upgrade",
    dependencies=[rate_limited(rate_limit.admin_bucket)],
)
def admin_apply_upgrade(license_id: DocumentId, ctx=Depends(attested_or_mfa_admin)):
    """Move the holder onto the issued licence `{id}` now, as their Activate
    would, but with no phone: each of their phones activates again on it.
    `404 no_pending_upgrade` when `{id}` is not an upgrade waiting for its
    holder."""
    admin = ctx.user
    new = repo.get_license(license_id)
    if not new:
        raise HTTPException(404, errors.LICENSE_NOT_FOUND)
    old = repo.get_license(new.get("supersedes") or "") or {}
    holder = old.get("redeemedByUid") or ""
    if not holder:
        raise HTTPException(404, errors.NO_PENDING_UPGRADE)
    return repo.apply_upgrade(license_id, holder, new.get("app") or "", actor=repo.ACTOR_STAFF,
                              by_uid=admin["uid"])
