"""The Firestore repository, one module per aggregate (ADR-001, ADR-021).

Clients never touch Firestore directly; routers, deps and the provisioning
worker call this package (`from app import repo`, then `repo.<name>`). It
re-exports each module's public names and nothing else: a private helper is
imported from the module that defines it.

Modules import only modules listed before them in `PACKAGE`
(`tests/test_repo_package.py`), and only `_base` binds the Firestore client
module, so a test double patches one place (`tests/fake_firestore.install`).
"""
from ._base import (  # noqa: F401
    SCHEMA_VERSION,
    db,
    ping,
    get_license,
)
from .user_config import (  # noqa: F401
    INACTIVE_LICENCE_ENDED,
    INACTIVE_NO_SEAT,
    Entitlement,
    entitlement_of,
    effective_mode,
    inactive_licence_reason,
    resolve_user_config,
    license_summary,
    cloud_backup_enabled,
    set_user_config,
)
from .devlock import (  # noqa: F401
    DeviceLockContended,
    bind_device_lock,
    released_device_held,
    revalidate_device_lock,
)
from .claims import (  # noqa: F401
    claim_seat,
    claim_individual_license,
)
from .invites import (  # noqa: F401
    find_user_by_email,
    invite_institution_member,
    list_institution_invites,
    revoke_institution_invite,
)
from .holders import (  # noqa: F401
    licence_is_live,
    licence_held_by,
)
from .mint import (  # noqa: F401
    ensure_demo_license,
    create_individual_license,
    create_institution_license,
)
from .activation import (  # noqa: F401
    activate_license,
)
from .entitlement import (  # noqa: F401
    claim_pending_invite,
    ensure_entitlement,
)
from .license_admin import (  # noqa: F401
    SEARCH_LIMIT,
    list_licenses,
    get_license_public,
    revoke_license,
    expiry_change_error,
    analysis_cap_error,
    license_edit_error,
    update_license,
)
from .upgrade import (  # noqa: F401
    convert_to_institution,
)
from .deletion import (  # noqa: F401
    DELETED,
    DELETED_SEATS,
    HOLD_DAYS,
    delete_license,
    list_deleted_licenses,
    restore_license,
)
from .institution_admin import (  # noqa: F401
    is_institution_admin,
    list_licenses_administered_by,
    add_institution_member,
    institution_license_summary,
    institution_seat,
    list_institution_seats,
    page_institution_seats,
)
from .devices import (  # noqa: F401
    get_device,
    register_device,
    issue_nonce,
    consume_nonce,
    claim_client_nonce,
)
from .users import (  # noqa: F401
    DeviceInUseError,
    get_or_create_user,
    list_users,
    release_account_device,
    set_user_status,
)
from .leases import (  # noqa: F401
    checkout_lease,
    release_lease,
)
from .seats import (  # noqa: F401
    ACTOR_SELF,
    ACTOR_STAFF,
    ACTOR_IT,
    clear_device_lock,
    set_seat_enabled,
    revoke_institution_seat,
)
from .reconcile import (  # noqa: F401
    STILL_LICENSED,
    NO_CHECKIN_SINCE_REVOKE,
    MOVED_ON,
    NO_ACCOUNT,
    CHECKED_IN,
    ON_HOLD,
    DEMOTED,
    reconcile_institution_seats,
)
from .account import (  # noqa: F401
    remember_user_folder,
    get_user,
    record_terms_acceptance,
    record_improvement_consent,
    list_user_devices,
    delete_all_user_data,
)
from .sessions import (  # noqa: F401
    delete_session,
    get_session,
    get_file,
    metadata_file_id,
    replace_file_content,
    list_pending_uploads,
    upload_target,
    list_session_files,
    set_session_status,
    set_new_session_status,
    iter_unprovisioned_files,
    set_file_upload_url,
    session_app,
    list_user_sessions,
    iter_all_user_sessions,
    list_session_files_all,
    list_session_artifacts,
    iter_user_sessions,
    count_user_sessions,
    find_incomplete_session,
    create_session,
    set_session_folder,
    create_files_batch,
    Completion,
    complete_file,
    bump_session_progress,
)

from . import (
    _base,
    account,
    activation,
    claims,
    deletion,
    devices,
    devlock,
    entitlement,
    holders,
    institution_admin,
    invites,
    leases,
    license_admin,
    mint,
    reconcile,
    seats,
    sessions,
    upgrade,
    user_config,
    users,
)

#: Every module of the package, each after everything it imports.
PACKAGE = (_base, user_config, devlock, claims, invites, holders, mint, activation, entitlement, license_admin, upgrade, deletion, institution_admin, devices, users, leases, seats, reconcile, account, sessions)
