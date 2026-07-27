import os

os.environ.setdefault("DEV_INSECURE_AUTH", "1")
os.environ.setdefault("GOOGLE_CLOUD_PROJECT", "test-project")
os.environ.setdefault("SERVICE_ACCOUNT_EMAIL", "test@test.iam.gserviceaccount.com")
os.environ.setdefault("SHARED_DRIVE_ID", "test-drive-id")

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
