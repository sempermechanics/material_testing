"""Server-side Firestore access. Clients never touch Firestore directly.

The code lives in `app/repo/`, one module per aggregate (ADR-001). This module
re-exports it so routers and tests keep calling `firestore_repo.<name>`.

Assigning a name here (what `monkeypatch.setattr(firestore_repo, …)` does) also
assigns it in every repo module holding the same object, so a patch reaches
every caller as it did when this was one module. Production code never assigns
to it. `tests/test_repo_facade.py` pins that, and that the package is acyclic.
"""
import sys
import types

from . import notify  # noqa: F401  (tests reach notify through here)
from .config import settings  # noqa: F401
from .licenses import MODE_DEMO, SEATING_FLOATING, key_hash  # noqa: F401
from .repo._base import (  # noqa: F401
    _CONTENDED,
    db,
    get_license,
    _now,
    ping,
    SCHEMA_VERSION,
    _seat_ref,
)
from .repo.users import (  # noqa: F401
    _auto_approved,
    DeviceInUseError,
    get_or_create_user,
    _is_admin_email,
    list_users,
    set_user_status,
)
from .repo.user_config import (  # noqa: F401
    cloud_backup_enabled,
    effective_mode,
    _grace_days,
    license_summary,
    resolve_user_config,
    set_user_config,
)
from .repo.claims import (  # noqa: F401
    claim_individual_license,
    claim_seat,
    _individual_member_patch,
    _public_claim_error,
)
from .repo.invites import (  # noqa: F401
    find_user_by_email,
    invite_institution_member,
    list_institution_invites,
    revoke_institution_invite,
)
from .repo.mint import (  # noqa: F401
    create_individual_license,
    create_institution_license,
    ensure_demo_license,
)
from .repo.activation import (  # noqa: F401
    activate_license,
)
from .repo.entitlement import (  # noqa: F401
    claim_pending_invite,
    ensure_entitlement,
)
from .repo.license_admin import (  # noqa: F401
    list_licenses,
    revoke_license,
    update_license,
)
from .repo.institution_admin import (  # noqa: F401
    add_institution_member,
    institution_license_summary,
    is_institution_admin,
    list_institution_seats,
    list_licenses_administered_by,
)
from .repo.devlock import (  # noqa: F401
    bind_device_lock,
    _BIND_ROUNDS,
    check_device_lock,
    _device_lock_state,
    DeviceLockContended,
    revalidate_device_lock,
)
from .repo.leases import (  # noqa: F401
    checkout_lease,
    release_lease,
)
from .repo.seats import (  # noqa: F401
    ACTOR_IT,
    ACTOR_SELF,
    ACTOR_STAFF,
    clear_device_lock,
    revoke_institution_seat,
    set_seat_enabled,
)
from .repo.reconcile import (  # noqa: F401
    CHECKED_IN,
    MOVED_ON,
    NEVER_CLAIMED,
    NO_ACCOUNT,
    NO_CHECKIN_SINCE_REVOKE,
    reconcile_institution_seats,
    STILL_LICENSED,
)
from .repo.devices import (  # noqa: F401
    claim_client_nonce,
    consume_nonce,
    get_device,
    issue_nonce,
    register_device,
    user_has_active_device,
)
from .repo.account import (  # noqa: F401
    delete_all_user_data,
    get_user,
    list_user_devices,
    record_improvement_consent,
    record_terms_acceptance,
    remember_user_folder,
)
from .repo.sessions import (  # noqa: F401
    bump_session_progress,
    complete_file,
    count_unprovisioned_files,
    count_user_sessions,
    create_file,
    create_files_batch,
    create_session,
    delete_session,
    find_incomplete_session,
    get_file,
    get_session,
    IN_FLIGHT_STATUSES,
    iter_all_user_sessions,
    iter_unprovisioned_files,
    iter_user_sessions,
    list_pending_uploads,
    list_session_artifacts,
    list_session_files,
    list_session_files_all,
    _LIST_SOFT_LIMIT,
    list_user_sessions,
    set_file_upload_url,
    set_session_folder,
    set_session_status,
    upload_target,
)

from .repo import (
    _base,
    account,
    activation,
    claims,
    devices,
    devlock,
    entitlement,
    institution_admin,
    invites,
    leases,
    license_admin,
    licensing,
    mint,
    reconcile,
    seats,
    sessions,
    user_config,
    users,
)

#: Every module of the package, each after everything it imports.
PACKAGE = (_base, user_config, devlock, claims, invites, mint, activation, entitlement,
           license_admin, institution_admin, licensing, devices, users, leases, seats,
           reconcile, account, sessions)
_MISSING = object()


def __getattr__(name: str):
    """Names not re-exported above (`_DB`, `firestore`, section-local helpers)
    read through to the module that holds them."""
    for mod in PACKAGE:
        if name in vars(mod):
            return vars(mod)[name]
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


class _Facade(types.ModuleType):
    def __setattr__(self, name: str, value) -> None:
        old = getattr(self, name, _MISSING)
        if old is not _MISSING:
            for mod in PACKAGE:
                if vars(mod).get(name, _MISSING) is old:
                    setattr(mod, name, value)
        if name in vars(self) or old is _MISSING:
            super().__setattr__(name, value)


sys.modules[__name__].__class__ = _Facade
