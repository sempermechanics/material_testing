"""GET /v1/files/{id}/content must use verified_device (not bare current_user)."""
import inspect

from app.main import download_file
from app.deps import verified_device


def test_download_file_depends_on_verified_device():
    params = inspect.signature(download_file).parameters
    assert "ctx" in params
    dep = params["ctx"].default
    assert dep.dependency is verified_device
