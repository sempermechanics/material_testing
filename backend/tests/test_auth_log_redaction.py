"""A refused ID token is never written to the log (readiness TE5-105).

google-auth and firebase-admin put the presented token in some of their
messages ("Wrong number of segments in token: <token>", "Illegal ID token
provided: <token>"), and `_verified_claims` logged the exception as it came.
"""
import logging

import pytest

from app import deps
from app.config import settings

SECRET = "eyJhbGciOiJSUzI1NiJ9.c2VjcmV0LXBheWxvYWQ"  # noqa: S105 - a made-up malformed token, the thing that must not be logged


@pytest.mark.asyncio
async def test_a_refused_token_is_not_in_the_log(store, client, monkeypatch, caplog):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)

    def refuse(token, **_kw):
        raise ValueError(f"Wrong number of segments in token: {token.encode()}")

    monkeypatch.setattr(deps, "verify_id_token", refuse)
    with caplog.at_level(logging.WARNING):
        r = await client.get("/v1/me", headers={"Authorization": f"Bearer {SECRET}"})

    assert (r.status_code, r.json()) == (401, {"detail": "invalid_token"})
    assert SECRET not in caplog.text
    assert "c2VjcmV0LXBheWxvYWQ" not in caplog.text
    assert "ValueError" in caplog.text and "Wrong number of segments" in caplog.text
