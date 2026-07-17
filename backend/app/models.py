from typing import List, Literal, Optional

from pydantic import BaseModel, Field

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


class SessionCreate(BaseModel):
    specimen: str = Field(min_length=1, max_length=200)
    files: List[FileSpec] = Field(min_length=1, max_length=600)
    metrics: dict = {}
    # The app's local analysis id. Lets the client reconcile its local sync
    # state against what actually exists in the cloud (and restore later).
    localSessionId: str = Field(default="", max_length=128)


class FileComplete(BaseModel):
    sessionId: str
    driveFileId: str
    bytes: int
    md5: Optional[str] = None
