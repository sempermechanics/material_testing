"""Keyless auth: verify inbound Firebase ID tokens, mint Drive-scoped tokens.

Identity is federated through **Firebase Authentication** (email-link, Google,
email/password). The client signs in with Firebase and sends a Firebase ID
token; we verify it here. No service-account JSON keys anywhere — on Cloud Run,
credentials come from the metadata server (ADC); the Drive scope is layered on
by having the runtime SA impersonate *itself* via the IAM Credentials API.
"""
import functools
import logging

import firebase_admin
import google.auth
from firebase_admin import app_check as fb_app_check, auth as fb_auth
from google.auth import impersonated_credentials
from google.auth.transport.requests import Request as GRequest

from . import errors
from . import observability as obs
from . import rate_limit
from .config import settings

log = logging.getLogger("semper.auth")
_request = GRequest()
DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive"]

# Initialize the Firebase Admin SDK once, using ADC (the Cloud Run SA). The
# project id is needed so verify_id_token can check the token's audience/issuer.
try:
    firebase_admin.get_app()
except ValueError:
    _fb_project = settings.FIREBASE_PROJECT_ID
    firebase_admin.initialize_app(options={"projectId": _fb_project} if _fb_project else None)


@functools.lru_cache(maxsize=1)
def _base_creds():
    # Cloud Run: metadata-server identity for the attached SA (no key file).
    # Local dev: your user ADC from `gcloud auth application-default login`.
    creds, _ = google.auth.default()
    return creds


_drive_creds = None


def drive_access_token() -> str:
    """A Drive-scoped OAuth token for the runtime SA, minted keylessly.

    Requires the SA to hold roles/iam.serviceAccountTokenCreator on itself.
    """
    global _drive_creds
    if _drive_creds is None:
        _drive_creds = impersonated_credentials.Credentials(
            source_credentials=_base_creds(),
            target_principal=settings.SERVICE_ACCOUNT_EMAIL,
            target_scopes=DRIVE_SCOPES,
            lifetime=3600,
        )
    if not _drive_creds.valid:
        _drive_creds.refresh(_request)
    return _drive_creds.token


def verify_id_token(token: str, *, check_revoked: bool = False) -> dict:
    """Verify a **Firebase** ID token; raises on failure.

    firebase-admin checks the signature (against Google's rotating public certs),
    expiry, audience (== project id) and issuer. The returned claims include
    ``uid``/``sub`` (the stable Firebase user id, same across all providers for
    one account), ``email``, ``email_verified``, ``name`` and
    ``firebase.sign_in_provider``. Domain gating and the email-verified rule are
    applied later, in get_or_create_user, so unverified users can still sign in
    and land PENDING.

    `check_revoked=True` also asks the Auth project whether the user's refresh
    tokens were revoked after this token was minted, or the account disabled
    (`fb_auth.RevokedIdTokenError` / `fb_auth.UserDisabledError`). That is a
    network call and needs the runtime account to hold Firebase Auth admin on
    the Auth project, so only `step_up.step_up_check` asks it, and only when
    `CHECK_REVOKED_ON_STEP_UP` is on.
    """
    return fb_auth.verify_id_token(token, check_revoked=check_revoked)


# --- Admin-SDK user management (best-effort) --------------------------------
# Both calls need `roles/firebaseauth.admin` (or a role with
# firebaseauth.users.update / .delete) for the runtime service account on the
# *Auth* project (FIREBASE_PROJECT_ID), which it does not hold yet. Neither may
# fail the request it rides on: the state change in Firestore is the control,
# and these only shorten how long an already-minted token keeps working. Each
# logs and answers False, and the caller records that answer in its audit row.
# Both spend `rate_limit.auth_admin_bucket` first (TD-200): over it, the call
# is not made, `auth_admin_rate_limited` is logged, and the answer is False.


def _within_budget(uid: str, action: str) -> bool:
    if rate_limit.auth_admin_allowed():
        return True
    obs.log_event(log, logging.WARNING, "auth_admin_rate_limited", uid=uid,
                  action=action, outcome="skipped", dependency="firebase_auth")
    return False


def revoke_sessions(uid: str) -> bool:
    """Revoke every refresh token `uid` holds, so no new ID token can be
    minted from an existing sign-in. Tokens already minted stay valid until
    they expire (an hour at most) unless a route checks revocation. True when
    the Auth project accepted it; False when it refused, or when this
    instance's Auth Admin budget is spent and the call was not made."""
    if not _within_budget(uid, "revoke_sessions"):
        return False
    try:
        fb_auth.revoke_refresh_tokens(uid)
    except Exception as e:  # noqa: BLE001 - best-effort; see the block comment
        log.warning("revoke_refresh_tokens failed uid=%s: %s: %s",
                    uid, type(e).__name__, str(e)[:200])
        return False
    return True


def delete_auth_user(uid: str) -> bool:
    """Delete the Firebase Auth user `uid`. A user that is already gone counts
    as deleted. True when the Auth project no longer holds the user; False
    when the delete failed or the Auth Admin budget is spent."""
    if not _within_budget(uid, "delete_auth_user"):
        return False
    try:
        fb_auth.delete_user(uid)
    except fb_auth.UserNotFoundError:
        return True
    except Exception as e:  # noqa: BLE001 - best-effort; see the block comment
        log.warning("delete_user failed uid=%s: %s: %s",
                    uid, type(e).__name__, str(e)[:200])
        return False
    return True


# --- Admin-SDK user creation, for staff test accounts (ADR-027) -------------
# Unlike the two calls above these are the operation itself, not a
# best-effort follow-up: a test account whose Auth user was not made does not
# exist. So each raises, `obs.DependencyError` (503) when the budget is spent
# or the Auth project fails, and the caller refuses the request. The password
# goes to the Auth project and nowhere else: no log line below names it, and
# a failure is logged by exception type alone, since an Admin-SDK message can
# echo the address.


class EmailInUse(Exception):
    """The Auth project already holds a user with this address."""


def _admin_call(uid: str, action: str, call):
    if not _within_budget(uid, action):
        raise obs.DependencyError(errors.AUTH_CHECK_BUSY, "firebase_auth")
    try:
        return call()
    except fb_auth.EmailAlreadyExistsError as e:
        raise EmailInUse() from e
    except Exception as e:  # noqa: BLE001 - one refusal for every Auth failure
        obs.log_event(log, logging.ERROR, "auth_admin_failed", uid=uid or None, action=action,
                      outcome="error", errorCode=type(e).__name__, dependency="firebase_auth")
        raise obs.DependencyError(errors.INTERNAL_ERROR, "firebase_auth") from e


def auth_user_exists(email: str) -> bool:
    """Whether the Auth project holds a user with this address."""
    def lookup() -> bool:
        try:
            fb_auth.get_user_by_email(email)
        except fb_auth.UserNotFoundError:
            return False
        return True

    return _admin_call("", "get_user_by_email", lookup)


def create_auth_user(email: str, password: str) -> str:
    """Create an email/password Auth user with a verified address; its uid.
    Raises `EmailInUse` when the address is taken."""
    return _admin_call("", "create_user", lambda: fb_auth.create_user(
        email=email, password=password, email_verified=True).uid)


def set_auth_password(uid: str, password: str) -> None:
    """Give the Auth user `uid` a new password."""
    _admin_call(uid, "update_user", lambda: fb_auth.update_user(uid, password=password))


def verify_app_check_token(token: str) -> dict:
    """Verify a **Firebase App Check** token; raises on failure.

    Proves the call came from a genuine, unmodified build of the app on a real
    device (Play Integrity on Android), which an ID token cannot: the Web API
    key that mints ID tokens ships inside the APK and is an identifier, not a
    secret. Attests the *binary*; `verify_id_token` attests the *account*, and
    `deps.verified_device` attests the *device*. Three separate questions.

    Needs no extra credential — the same ADC app initialised above verifies
    against the project's App Check public keys, which firebase-admin caches.
    """
    return fb_app_check.verify_token(token)
