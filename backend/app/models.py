from typing import List, Literal, Optional

from pydantic import BaseModel, Field

Role = Literal["raw", "processed", "reports", "metadata", "csv"]


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
    files: List[FileSpec] = Field(min_length=1, max_length=500)
    metrics: dict = {}


class FileComplete(BaseModel):
    sessionId: str
    driveFileId: str
    bytes: int
    md5: Optional[str] = None
