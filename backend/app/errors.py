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
"""
from __future__ import annotations

# --- authentication / authorisation ----------------------------------------
MISSING_BEARER = "missing_bearer"
INVALID_TOKEN = "invalid_token"
NOT_APPROVED = "not_approved"
NOT_ADMIN = "not_admin"
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

# --- upstream / throttling -------------------------------------------------
RATE_LIMITED = "rate_limited"
DRIVE_DOWNLOAD_FAILED = "drive_download_failed"
DRIVE_META_FAILED = "drive_meta_failed"

#: Codes the Android client branches on rather than merely displaying. Changing
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
    }
)
