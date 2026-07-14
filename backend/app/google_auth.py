"""Keyless Google auth: verify inbound ID tokens, mint Drive-scoped tokens.

No service-account JSON keys are used anywhere. On Cloud Run, credentials come
from the metadata server (ADC); the Drive scope is layered on by having the
runtime SA impersonate *itself* via the IAM Credentials API.
"""
import functools

import google.auth
from google.auth import impersonated_credentials
from google.auth.transport.requests import Request as GRequest
from google.oauth2 import id_token as google_id_token

from .config import settings

_request = GRequest()
DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive"]


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


def verify_google_id_token(token: str) -> dict:
    """Verify signature/exp/aud, then enforce corporate claims. Raises on failure."""
    # Checks signature against Google certs, exp, and aud == WEB_CLIENT_ID.
    claims = google_id_token.verify_oauth2_token(token, _request, settings.WEB_CLIENT_ID)
    if claims.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise PermissionError("bad_issuer")
    if not claims.get("email_verified"):
        raise PermissionError("email_unverified")
    if settings.ALLOWED_HD:
        email = claims.get("email", "")
        if claims.get("hd") != settings.ALLOWED_HD or not email.endswith("@" + settings.ALLOWED_HD):
            raise PermissionError("wrong_domain")
    return claims
