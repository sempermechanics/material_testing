"""Keyless auth: verify inbound Firebase ID tokens, mint Drive-scoped tokens.

Identity is federated through **Firebase Authentication** (email-link, Google,
email/password). The client signs in with Firebase and sends a Firebase ID
token; we verify it here. No service-account JSON keys anywhere — on Cloud Run,
credentials come from the metadata server (ADC); the Drive scope is layered on
by having the runtime SA impersonate *itself* via the IAM Credentials API.
"""
import functools

import firebase_admin
import google.auth
from firebase_admin import auth as fb_auth
from google.auth import impersonated_credentials
from google.auth.transport.requests import Request as GRequest

from .config import settings

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


def verify_id_token(token: str) -> dict:
    """Verify a **Firebase** ID token; raises on failure.

    firebase-admin checks the signature (against Google's rotating public certs),
    expiry, audience (== project id) and issuer. The returned claims include
    ``uid``/``sub`` (the stable Firebase user id, same across all providers for
    one account), ``email``, ``email_verified``, ``name`` and
    ``firebase.sign_in_provider``. Domain gating and the email-verified rule are
    applied later, in get_or_create_user, so unverified users can still sign in
    and land PENDING.
    """
    return fb_auth.verify_id_token(token)
