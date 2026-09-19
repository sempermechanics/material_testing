"""Stable error codes returned as FastAPI's `{"detail": "<code>"}`.

These strings are a wire contract, not messages: the Android client branches on
them (`app/src/main/java/com/indicvision/semper/data/net/ApiErrors.kt`) to tell a
device conflict from a quota rejection inside the same status code. Naming them
here means a rename is one edit that `tests/test_error_codes.py` then holds
against the client's copy, instead of a literal typed in a router that silently
stops matching.

Codes are lowercase snake_case and carry no user data — they are safe to log and
safe to show. Anything a caller needs beyond the code (counts, sizes) goes after
a colon, as `session_quota_exceeded` does.

Not exhaustive in one respect: `validation.py` generates a `missing_<field>` /
`invalid_<field>` family per rejected identifier, so those codes are built at
runtime rather than named here.
"""
from __future__ import annotations

# --- authentication / authorisation ----------------------------------------
MISSING_BEARER = "missing_bearer"
INVALID_TOKEN = "invalid_token"
NOT_APPROVED = "not_approved"
NOT_ADMIN = "not_admin"
TERMS_VERSION_MISMATCH = "terms_version_mismatch"
INVALID_SIGNATURE = "invalid_signature"
BAD_SIGNATURE = "bad_signature"
NONCE_INVALID_OR_REPLAYED = "nonce_invalid_or_replayed"

# --- device binding --------------------------------------------------------
DEVICE_CONFLICT = "device_conflict"
DEVICE_IN_USE = "device_in_use"
DEVICE_NOT_ACTIVE = "device_not_active"

# --- Cloud Tasks callback --------------------------------------------------
INVALID_TASK_TOKEN = "invalid_task_token"
NOT_TASK_INVOKER = "not_task_invoker"

# --- sessions, files, quotas -----------------------------------------------
SESSION_NOT_FOUND = "session_not_found"
SESSION_QUOTA_EXCEEDED = "session_quota_exceeded"
TOO_MANY_FILES = "too_many_files"
FILE_NOT_FOUND = "file_not_found"
FILE_NOT_UPLOADED = "file_not_uploaded"
FILE_NOT_IN_SESSION = "file_not_in_session"
SIZE_MISMATCH = "size_mismatch"
CHECKSUM_MISMATCH = "checksum_mismatch"
SIZE_OR_STATE_MISMATCH = "size_or_state_mismatch"
RANGE_NOT_SATISFIABLE = "range_not_satisfiable"

# --- admin -----------------------------------------------------------------
USER_NOT_FOUND = "user_not_found"
EMPTY_PATCH = "empty_patch"

# --- licensing, seats and leases -------------------------------------------
# `feature_not_licensed` gates a cloud feature behind a licensed mode. The
# `license_*` family is returned by activation. The seat/lease codes describe a
# floating pool: `no_floating_seat` in particular is the pool being FULL, not
# a fault in the account — the caller stays eligible and may retry.
FEATURE_NOT_LICENSED = "feature_not_licensed"
LICENSE_NOT_FOUND = "license_not_found"
LICENSE_REVOKED = "license_revoked"
LICENSE_EXPIRED = "license_expired"
LICENSE_EMAIL_MISMATCH = "license_email_mismatch"
LICENSE_DEVICE_MISMATCH = "license_device_mismatch"
LICENSE_ALREADY_REDEEMED = "license_already_redeemed"
LICENSE_SEAT_DISABLED = "license_seat_disabled"
LICENSE_SEATS_EXHAUSTED = "license_seats_exhausted"
SEAT_NOT_FOUND = "seat_not_found"
NOT_ELIGIBLE = "not_eligible"
NO_LICENSE = "no_license"
NO_FLOATING_SEAT = "no_floating_seat"
SEATING_NOT_FLOATING = "seating_not_floating"
# Reconciliation is a roster operation. An individual licence has one
# redeemer and no seats, so there are no two counts to compare and an empty
# report would read like a clean bill of health.
KIND_NOT_INSTITUTION = "kind_not_institution"
EMAIL_NOT_VERIFIED = "email_not_verified"
# Self-service device change only. Clearing a lock is not revoking — the
# entitlement is untouched and the next device to sign in binds — so the only
# thing that limits it is how often the holder may do it themselves. Staff and
# IT are never subject to this.
DEVICE_CHANGE_TOO_SOON = "device_change_too_soon"

# --- institution invites ---------------------------------------------------
# An invite reserves a roster place for an address with no account yet. It is
# consumed at that address's first sign-in; until then it holds no seat and no
# uid. `invite_exists` means the address is already promised to a DIFFERENT
# licence — re-inviting to the same one is a no-op, not an error.
INVITE_EXISTS = "invite_exists"
INVITE_NOT_FOUND = "invite_not_found"
INVALID_EMAIL = "invalid_email"

# --- admin second factor ---------------------------------------------------
# The console is a browser, which cannot produce a device attestation, so the
# staff web path proves itself with a second factor and a recent sign-in
# instead. `mfa_required` means the account has no second factor on the token
# at all; `reauth_required` means it has one but the sign-in is too old for a
# state-changing call and the operator must re-authenticate.
MFA_REQUIRED = "mfa_required"
REAUTH_REQUIRED = "reauth_required"

# The caller sent X-Device-Id but no valid App Check token, while
# APP_CHECK_MODE=enforce. It means "this is not our app binary", not "this
# account is not allowed" — the account may be perfectly entitled. Distinct
# from `not_approved` so a support conversation starts in the right place.
APP_CHECK_REQUIRED = "app_check_required"

# --- upstream / throttling -------------------------------------------------
RATE_LIMITED = "rate_limited"
DRIVE_DOWNLOAD_FAILED = "drive_download_failed"
DRIVE_META_FAILED = "drive_meta_failed"

# Reported as `{"detail": …}` too: an unhandled exception on Cloud Run, and the
# DependencyError codes the readiness probe and the Drive/Firestore clients
# raise (main.dependency_error_handler returns exc.code as the detail).
INTERNAL_ERROR = "internal_error"
DRIVE_UNREACHABLE = "drive_unreachable"
DRIVE_UNHEALTHY = "drive_unhealthy"
FIRESTORE_UNREACHABLE = "firestore_unreachable"
READYZ_FAILED = "readyz_failed"

#: Codes the Android client branches on or surfaces by name. Changing
#: one of these needs the matching edit in ApiErrors.kt in the same commit --
#: tests/test_error_codes.py fails otherwise.
CLIENT_BRANCHED = frozenset(
    {
        NOT_APPROVED,
        DEVICE_CONFLICT,
        DEVICE_IN_USE,
        DEVICE_NOT_ACTIVE,
        NONCE_INVALID_OR_REPLAYED,
        BAD_SIGNATURE,
        SESSION_NOT_FOUND,
        FILE_NOT_FOUND,
        RATE_LIMITED,
        SESSION_QUOTA_EXCEEDED,
        # The app renders this as "all seats are in use, try again" rather
        # than a failure, so a rename here needs the matching ApiErrors.kt edit.
        NO_FLOATING_SEAT,
        FEATURE_NOT_LICENSED,
        LICENSE_DEVICE_MISMATCH,
        APP_CHECK_REQUIRED,
    }
)
