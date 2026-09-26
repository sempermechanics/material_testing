"""Reusable validation for identifiers crossing HTTP and Firestore boundaries."""
from typing import Annotated, Literal

from fastapi import HTTPException
from pydantic import AfterValidator, StringConstraints


def _valid_identifier(value: str) -> str:
    """Accept Unicode IDs while rejecting Firestore/path-breaking input."""
    if value in (".", "..") or "/" in value or "\\" in value:
        raise ValueError("identifier must not contain path separators")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        raise ValueError("identifier must not contain control characters")
    return value


def _valid_page_token(value: str) -> str:
    """A cursor token is a bare document ID; empty means "first page".

    It is fed straight to `CollectionReference.document()`, which accepts a
    slash-separated *path* — not just an ID. Without this an unvalidated token
    can address a document in a different collection, and an odd-segment token
    raises deep inside the Firestore client and surfaces as an opaque 500.
    """
    if not value:
        return value
    return _valid_identifier(value)


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
PageToken = Annotated[
    str,
    StringConstraints(max_length=512),
    AfterValidator(_valid_page_token),
]
# The only values ever written to users/{uid}.access_status. Constraining the
# admin filter to these keeps an unbounded caller-supplied string out of the
# Firestore `where()` on every listing.
AccessStatus = Literal["", "PENDING", "APPROVED", "SUSPENDED"]
# The staff desk's licence search: an address (at most 320), a domain or a key
# prefix. Only ever an equality value in a `where()`, never a path.
LicenceSearch = Annotated[str, StringConstraints(max_length=320)]


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
