"""TD-232: a new device on an account mails its holder, at the verified
address, once; the same device registering again (a key heal) does not."""
import pytest

from app import deps, notify
from app.config import settings
import repo_view as repo
from key_helpers import _ec_pem


class _Response:
    status_code = 200
    headers: dict = {}


@pytest.fixture
def mail(store, monkeypatch):
    monkeypatch.setattr(settings, "RESEND_API_KEY", "test-key")
    monkeypatch.setattr(settings, "NOTIFY_FROM", "Semper <noreply@sempermechanics.com>")
    monkeypatch.setattr(settings, "NOTIFY_SECURITY_FROM", "Semper <security@sempermechanics.com>")
    notify.reset_for_tests()
    sent = []
    monkeypatch.setattr(notify.requests, "post", lambda url, **kw: sent.append(kw["json"]) or _Response())
    yield sent
    notify.reset_for_tests()


def _as(store, monkeypatch, **user):
    store._data.setdefault("users", {}).setdefault("dev-user", {}).update(user)
    monkeypatch.setattr(deps, "_DEV_USER", {**store._data["users"]["dev-user"], "uid": "dev-user"})


async def test_a_registration_mails_the_verified_address_and_a_heal_does_not(
        client, store, mail, monkeypatch):
    _as(store, monkeypatch, email="Holder@Uni.edu", emailVerified=True, access_status="APPROVED",
        role="user")

    first = await client.post("/v1/devices/register",
                              json={"deviceId": "phone-0001", "publicKeyPem": _ec_pem()})
    notify.flush_for_tests()
    assert first.status_code == 201, first.text
    assert first.json()["healed"] is False

    _as(store, monkeypatch)  # the binding the registration wrote
    again = await client.post("/v1/devices/register",
                              json={"deviceId": "phone-0001", "publicKeyPem": _ec_pem()})
    notify.flush_for_tests()
    assert again.json()["healed"] is True

    assert [m["to"] for m in mail] == [["holder@uni.edu"]]


async def test_an_unverified_address_gets_no_mail(client, store, mail, monkeypatch):
    _as(store, monkeypatch, email="holder@uni.edu", emailVerified=False,
        access_status="APPROVED", role="user")

    r = await client.post("/v1/devices/register",
                          json={"deviceId": "phone-0001", "publicKeyPem": _ec_pem()})
    notify.flush_for_tests()

    assert r.status_code == 201, r.text
    assert mail == []
    assert repo.verified_address(store._data["users"]["dev-user"]) == ""
