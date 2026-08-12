import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from pydantic import ValidationError

from app.models import DeviceReg, FileComplete, FileSpec, SessionCreate

_SHA = "a" * 64
_MD5 = "b" * 32


def _ec_pem() -> str:
    priv = ec.generate_private_key(ec.SECP256R1())
    return priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()


def _rsa_pem() -> str:
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()


class TestDeviceReg:
    def test_valid(self):
        pem = _ec_pem()
        dr = DeviceReg(deviceId="and-12345678", publicKeyPem=pem)
        assert dr.deviceId == "and-12345678"
        assert dr.publicKeyPem == pem

    def test_short_device_id_rejected(self):
        with pytest.raises(ValidationError):
            DeviceReg(deviceId="short", publicKeyPem=_ec_pem())

    def test_garbage_pem_rejected(self):
        with pytest.raises(ValidationError):
            DeviceReg(deviceId="and-12345678", publicKeyPem="not-a-pem")

    def test_rsa_pem_rejected(self):
        with pytest.raises(ValidationError):
            DeviceReg(deviceId="and-12345678", publicKeyPem=_rsa_pem())

    def test_pem_too_long_rejected(self):
        with pytest.raises(ValidationError):
            DeviceReg(deviceId="and-12345678", publicKeyPem="x" * 4097)

    def test_unicode_model_is_accepted_and_bounded(self):
        assert DeviceReg(
            deviceId="and-12345678", publicKeyPem=_ec_pem(), model="फ़ोन 型号"
        ).model == "फ़ोन 型号"
        with pytest.raises(ValidationError):
            DeviceReg(
                deviceId="and-12345678", publicKeyPem=_ec_pem(), model="x" * 129
            )

    @pytest.mark.parametrize("field", ["model", "osVersion", "appVersion"])
    def test_device_metadata_control_characters_rejected(self, field):
        with pytest.raises(ValidationError):
            DeviceReg(
                deviceId="and-12345678",
                publicKeyPem=_ec_pem(),
                **{field: "bad\nvalue"},
            )

    @pytest.mark.parametrize("device_id", ["bad/id-123", "bad\\id-123", "bad\nid-123"])
    def test_path_breaking_device_ids_rejected(self, device_id):
        with pytest.raises(ValidationError):
            DeviceReg(deviceId=device_id, publicKeyPem=_ec_pem())


class TestFileSpec:
    def test_valid(self):
        fs = FileSpec(name="Session.zip", role="bundle", bytes=42, sha256=_SHA)
        assert fs.role == "bundle"

    def test_extras_role_accepted(self):
        # Extras.zip carries the derived deliverables a restore does not need.
        # Rejecting it here would 422 every upload from a current client.
        fs = FileSpec(name="Extras.zip", role="extras", bytes=42, sha256=_SHA)
        assert fs.role == "extras"

    def test_unknown_role_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="f", role="not-a-role", bytes=1, sha256=_SHA)

    def test_empty_name_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="", role="bundle", bytes=42, sha256=_SHA)

    def test_zero_bytes_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="f", role="bundle", bytes=0, sha256=_SHA)

    def test_ordinary_names_accepted(self):
        # name is used as-is inside a Firestore doc id, but these are all safe.
        for name in ("Session.zip", "metadata.json", "oht cfrp (1).tiff"):
            assert FileSpec(name=name, role="bundle", bytes=1, sha256=_SHA).name == name

    @pytest.mark.parametrize("name", ["a/b.zip", "sub/dir/x", "..\\evil", ".", "..", "evil\nname"])
    def test_path_breaking_names_rejected(self, name):
        # A "/" or reserved id would make "{sid}_{role}_{name}" an invalid Firestore
        # document path — a 500 — so it must be rejected as a 422 at the boundary.
        with pytest.raises(ValidationError):
            FileSpec(name=name, role="bundle", bytes=1, sha256=_SHA)

    def test_non_hex_sha256_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="f", role="bundle", bytes=1, sha256="g" * 64)

    def test_short_sha256_rejected(self):
        with pytest.raises(ValidationError):
            FileSpec(name="f", role="bundle", bytes=1, sha256="ab")


class TestSessionCreate:
    def test_valid(self):
        sc = SessionCreate(
            specimen="sp",
            files=[FileSpec(name="f", role="bundle", bytes=1, sha256=_SHA)],
        )
        assert sc.specimen == "sp"

    def test_empty_specimen_rejected(self):
        with pytest.raises(ValidationError):
            SessionCreate(
                specimen="",
                files=[FileSpec(name="f", role="bundle", bytes=1, sha256=_SHA)],
            )

    def test_files_cap_is_5000(self):
        schema = SessionCreate.model_json_schema()
        assert schema["properties"]["files"]["maxItems"] == 5000


class TestFileComplete:
    def test_valid(self):
        fc = FileComplete(sessionId="s", driveFileId="d", bytes=5)
        assert fc.md5 is None

    def test_with_md5(self):
        fc = FileComplete(sessionId="s", driveFileId="d", bytes=5, md5=_MD5)
        assert fc.md5 == _MD5

    def test_zero_bytes_rejected(self):
        with pytest.raises(ValidationError):
            FileComplete(sessionId="s", driveFileId="d", bytes=0)

    def test_bad_md5_rejected(self):
        with pytest.raises(ValidationError):
            FileComplete(sessionId="s", driveFileId="d", bytes=5, md5="not-hex")

    def test_empty_ids_rejected(self):
        with pytest.raises(ValidationError):
            FileComplete(sessionId="", driveFileId="d", bytes=5)
        with pytest.raises(ValidationError):
            FileComplete(sessionId="s", driveFileId="", bytes=5)

    def test_unicode_ids_are_not_rejected(self):
        item = FileComplete(sessionId="सत्र-一", driveFileId="फ़ाइल-二", bytes=5)
        assert item.sessionId == "सत्र-一"

    @pytest.mark.parametrize("bad_id", ["../s", "a/b", "a\\b", "bad\nid"])
    def test_path_breaking_ids_rejected(self, bad_id):
        with pytest.raises(ValidationError):
            FileComplete(sessionId=bad_id, driveFileId="d", bytes=5)
