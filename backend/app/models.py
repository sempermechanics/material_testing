from typing import Annotated, List, Literal, Optional

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from pydantic import BaseModel, Field, StringConstraints, field_validator

from .validation import DeviceId, DocumentId, SessionId

# "bundle" = one Session.zip holding raw/, dat/, csv/ and the report archives —
# uploaded as a single file so a session costs ~2 Firestore file docs, not 3F+4.
Role = Literal["raw", "processed", "reports", "metadata", "csv", "dat", "bundle"]

_HEX = frozenset("0123456789abcdefABCDEF")
DisplayString = Annotated[str, StringConstraints(max_length=128)]


class DeviceReg(BaseModel):
    deviceId: DeviceId
    publicKeyPem: str = Field(max_length=4096)
    model: DisplayString = ""
    osVersion: DisplayString = ""
    appVersion: DisplayString = ""

    @field_validator("model", "osVersion", "appVersion")
    @classmethod
    def _display_text(cls, value: str) -> str:
        # Device names and versions are human-facing and may legitimately be
        # Unicode. Reject only control characters that can corrupt logs/docs.
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
            raise ValueError("must not contain control characters")
        return value

    @field_validator("publicKeyPem")
    @classmethod
    def _ec_public_pem(cls, v: str) -> str:
        try:
            key = load_pem_public_key(v.encode())
        except Exception as e:
            raise ValueError("publicKeyPem must be a valid PEM public key") from e
        if not isinstance(key, ec.EllipticCurvePublicKey):
            raise ValueError("publicKeyPem must be an EC public key")
        return v


class FileSpec(BaseModel):
    name: str = Field(min_length=1, max_length=256)
    role: Role
    bytes: int = Field(gt=0, le=5 * 1024 * 1024 * 1024)  # cap 5 GB
    sha256: str = Field(min_length=64, max_length=64)

    @field_validator("name")
    @classmethod
    def _no_path_separators(cls, v: str) -> str:
        # name is interpolated into a Firestore document id ("{sid}_{role}_{name}").
        # A "/" would add path segments (an even-segment path Firestore rejects with
        # a 500), and "." / ".." are reserved id values. Reject those and control
        # chars here so a bad name is a clean 422 at the edge, not a 500 mid-write.
        if "/" in v or "\\" in v:
            raise ValueError("name must not contain path separators")
        if v in (".", ".."):
            raise ValueError("name must not be '.' or '..'")
        if any(ord(c) < 0x20 for c in v):
            raise ValueError("name must not contain control characters")
        return v

    @field_validator("sha256")
    @classmethod
    def _sha256_hex(cls, v: str) -> str:
        if len(v) != 64 or any(c not in _HEX for c in v):
            raise ValueError("sha256 must be 64 hex characters")
        return v


class SessionCreate(BaseModel):
    specimen: str = Field(min_length=1, max_length=200)
    files: List[FileSpec] = Field(min_length=1, max_length=5000)
    metrics: dict = {}
    # The app's local analysis id. Lets the client reconcile its local sync
    # state against what actually exists in the cloud (and restore later).
    localSessionId: str = Field(default="", max_length=128)

    @field_validator("metrics")
    @classmethod
    def _bounded_metrics(cls, v: dict) -> dict:
        # metrics is written verbatim to the Firestore session doc, so an
        # unbounded client dict could inflate it toward the 1 MiB doc limit.
        # It is meant to be a small map of scalar run stats (pointsConverged,
        # avgIcgnIters, execMs), so reject anything larger or nested.
        if len(v) > 32:
            raise ValueError("metrics has too many keys")
        for key, val in v.items():
            if not isinstance(key, str) or len(key) > 64:
                raise ValueError("metrics keys must be strings <= 64 chars")
            # bool is a subclass of int; strings are length-bounded; no nesting.
            if isinstance(val, str):
                if len(val) > 256:
                    raise ValueError("metrics string values must be <= 256 chars")
            elif not isinstance(val, (int, float, bool)):
                raise ValueError("metrics values must be scalar (num / bool / short str)")
        return v


class FileComplete(BaseModel):
    sessionId: SessionId
    driveFileId: DocumentId
    bytes: int = Field(gt=0)
    md5: Optional[str] = None

    @field_validator("md5")
    @classmethod
    def _md5_hex(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        if len(v) != 32 or any(c not in _HEX for c in v):
            raise ValueError("md5 must be 32 hex characters")
        return v


class UserConfigPatch(BaseModel):
    """Admin overrides for per-user product limits. Omitted fields stay unchanged;
    send JSON null to clear an override and re-inherit the fleet default."""
    maxSessions: Optional[int] = Field(default=None, gt=0)
    maxFilesPerSession: Optional[int] = Field(default=None, gt=0)
    maxFrames: Optional[int] = Field(default=None, gt=0)
