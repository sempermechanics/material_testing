import pytest
from pydantic import ValidationError

from app.models import DeviceReg, FileComplete, FileSpec, SessionCreate


class TestDeviceReg:
    def test_valid(self):
        dr = DeviceReg(deviceId="and-12345678", publicKeyPem="pem")
        assert dr.deviceId == "and-12345678"

    def test_short_device_id_rejected(self):
        with pytest.raises(ValidationError):
            DeviceReg(deviceId="short", publicKeyPem="pem")


class TestFileSpec:
    def test_valid(self):
        fs = FileSpec(name="Session.zip", role="bundle", bytes=42, sha256="a" * 64)
        assert fs.role == "bundle"

    def test_empty_name_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="", role="bundle", bytes=42, sha256="a" * 64)

    def test_zero_bytes_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="f", role="bundle", bytes=0, sha256="a" * 64)


class TestSessionCreate:
    def test_valid(self):
        sc = SessionCreate(
            specimen="sp",
            files=[FileSpec(name="f", role="bundle", bytes=1, sha256="a" * 64)],
        )
        assert sc.specimen == "sp"

    def test_empty_specimen_rejected(self):
        with pytest.raises(ValidationError):
            SessionCreate(
                specimen="",
                files=[FileSpec(name="f", role="bundle", bytes=1, sha256="a" * 64)],
            )


class TestFileComplete:
    def test_valid(self):
        fc = FileComplete(sessionId="s", driveFileId="d", bytes=5)
        assert fc.md5 is None

    def test_with_md5(self):
        fc = FileComplete(sessionId="s", driveFileId="d", bytes=5, md5="m")
        assert fc.md5 == "m"
