from typing import List, Literal, Optional

from pydantic import BaseModel, Field, field_validator

# "bundle" = one Session.zip holding raw/, dat/, csv/ and the report archives —
# uploaded as a single file so a session costs ~2 Firestore file docs, not 3F+4.
Role = Literal["raw", "processed", "reports", "metadata", "csv", "dat", "bundle"]


class DeviceReg(BaseModel):
    deviceId: str = Field(min_length=8, max_length=128)
    publicKeyPem: str
    model: str = ""
    osVersion: str = ""
    appVersion: str = ""


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


class SessionCreate(BaseModel):
    specimen: str = Field(min_length=1, max_length=200)
    files: List[FileSpec] = Field(min_length=1, max_length=600)
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
    sessionId: str
    driveFileId: str
    bytes: int
    md5: Optional[str] = None


class UserConfigPatch(BaseModel):
    """Admin overrides for per-user product limits. Omitted fields stay unchanged;
    send JSON null to clear an override and re-inherit the fleet default."""
    maxSessions: Optional[int] = Field(default=None, gt=0)
    maxFilesPerSession: Optional[int] = Field(default=None, gt=0)
    maxFrames: Optional[int] = Field(default=None, gt=0)
