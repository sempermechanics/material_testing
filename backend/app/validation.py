"""Reusable validation for identifiers crossing HTTP and Firestore boundaries."""
from typing import Annotated

from fastapi import HTTPException
from pydantic import AfterValidator, StringConstraints


def _valid_identifier(value: str) -> str:
    """Accept Unicode IDs while rejecting Firestore/path-breaking input."""
    if value in (".", "..") or "/" in value or "\\" in value:
        raise ValueError("identifier must not contain path separators")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        raise ValueError("identifier must not contain control characters")
    return value


DocumentId = Annotated[
    str,
    StringConstraints(min_length=1, max_length=512),
    AfterValidator(_valid_identifier),
]
SessionId = Annotated[
    str,
    StringConstraints(min_length=1, max_length=128),
    AfterValidator(_valid_identifier),
]
Uid = Annotated[
    str,
    StringConstraints(min_length=1, max_length=128),
    AfterValidator(_valid_identifier),
]
DeviceId = Annotated[
    str,
    StringConstraints(min_length=8, max_length=128),
    AfterValidator(_valid_identifier),
]


def require_header_identifier(
    value: str,
    *,
    name: str,
    minimum: int = 1,
    maximum: int = 256,
) -> str:
    """Validate a header ID with stable, caller-facing 400 errors."""
    if not value:
        raise HTTPException(400, f"missing_{name}")
    if len(value) < minimum or len(value) > maximum:
        raise HTTPException(400, f"invalid_{name}")
    try:
        return _valid_identifier(value)
    except ValueError as exc:
        raise HTTPException(400, f"invalid_{name}") from exc
