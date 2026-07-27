import pytest


@pytest.mark.asyncio
async def test_me_returns_dev_user(client):
    resp = await client.get("/v1/me")
    assert resp.status_code == 200
    body = resp.json()
    assert body["uid"] == "dev-user"
    assert body["access_status"] == "APPROVED"
