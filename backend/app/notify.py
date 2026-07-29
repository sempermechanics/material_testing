"""Outbound notification mail. Best-effort: never break a request on a send failure."""
import logging

import requests

from .config import settings

log = logging.getLogger("notify")

_RESEND_URL = "https://api.resend.com/emails"

# Sent inline on the request that creates the user. Cloud Run only guarantees
# CPU while a request is in flight, so a post-response thread could stall until
# the next call arrives; a short blocking send, once per account, is simpler and
# more predictable. The request it rides on is heading for a 403 anyway.
_TIMEOUT_S = 5


def _enabled() -> bool:
    return bool(settings.RESEND_API_KEY and settings.NOTIFY_FROM)


def access_request(uid: str, email, display_name, provider) -> None:
    """Tell support a new account is sitting in PENDING and needs a decision.

    Called once per account, from get_or_create_user. Swallows everything: a
    user must still get their clean "not approved" answer when mail is down.
    """
    if not _enabled():
        log.info("notify disabled (no RESEND_API_KEY/NOTIFY_FROM); pending user %s not mailed", uid)
        return
    body = (
        "A new inDIC account is waiting for approval.\n\n"
        f"Account: {email or '(no email)'}\n"
        f"Name: {display_name or '(none)'}\n"
        f"Sign-in: {provider or '(unknown)'}\n"
        f"User id: {uid}\n\n"
        "To approve, open Settings -> Access requests in the app, or call:\n"
        f"  POST /v1/admin/users/{uid}/approve\n"
    )
    try:
        response = requests.post(
            _RESEND_URL,
            headers={"Authorization": f"Bearer {settings.RESEND_API_KEY}"},
            json={
                "from": settings.NOTIFY_FROM,
                "to": [settings.SUPPORT_EMAIL],
                "subject": f"inDIC access request — {email or uid}",
                "text": body,
            },
            timeout=_TIMEOUT_S,
        )
        if response.status_code >= 400:
            log.warning("access-request mail rejected (%s): %s", response.status_code, response.text)
    except Exception as e:  # noqa: BLE001 - notification must not fail the request
        log.warning("access-request mail failed for %s: %s", uid, e)
