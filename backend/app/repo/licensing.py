"""Licensing, split by concern (TD-64). This module only re-exports.

The code lives in `claims`, `invites`, `mint`, `activation`, `entitlement`,
`license_admin` and `institution_admin`; see ADR-001. Every name this module
defined before the split is still importable from it. New code imports from
the module that owns the name.
"""
from .claims import (  # noqa: F401
    claim_individual_license,
    claim_seat,
    _drop_superseded_demo,
    _emails_match,
    _individual_member_patch,
    _institution_member_patch,
    _license_mirror_patch,
    _public_claim_error,
)
from .holders import (  # noqa: F401
    licence_held_by,
    licence_is_live,
)
from .invites import (  # noqa: F401
    _delete_license_invites,
    find_user_by_email,
    invite_institution_member,
    _invite_is_stale,
    _invite_public,
    _invite_ref,
    _INVITES,
    list_institution_invites,
    revoke_institution_invite,
    _write_invite,
)
from .mint import (  # noqa: F401
    _attach_to_existing_holder,
    create_individual_license,
    create_institution_license,
    ensure_demo_license,
    _holds_only_a_demo_key,
    _license_public,
    _write_license,
)
from .activation import (  # noqa: F401
    _activate_individual,
    _activate_institution,
    activate_license,
    _apply_patch,
    _email_domain,
)
from .entitlement import (  # noqa: F401
    claim_pending_invite,
    _claim_pending_invite,
    ensure_entitlement,
)
from .license_admin import (  # noqa: F401
    _drop_user_to_demo_if_licensed,
    _drop_users_to_demo_if_licensed,
    get_license_public,
    _license_holder_uids,
    list_licenses,
    _refresh_license_mirrors,
    revoke_license,
    update_license,
)
from .upgrade import (  # noqa: F401
    convert_to_institution,
)
from .deletion import (  # noqa: F401
    delete_license,
    list_deleted_licenses,
    restore_license,
)
from .institution_admin import (  # noqa: F401
    add_institution_member,
    institution_license_summary,
    is_institution_admin,
    list_institution_seats,
    list_licenses_administered_by,
)
