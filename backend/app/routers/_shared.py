"""Helpers more than one router uses.

Routers import from here, never from each other: `sessions` used to reach into
`account` for `json_dumps`, which made the order routers are loaded in matter
(TD-54).
"""
import json


def json_dumps(value) -> str:
    """Compact JSON for the streamed export and bundle manifest. `default=str`
    because Firestore hands back datetimes, which json cannot serialise."""
    return json.dumps(value, separators=(",", ":"), default=str)


def clamp_page_size(page_size: int, ceiling: int) -> int:
    """A requested page size held to 1..ceiling."""
    return max(1, min(page_size, ceiling))


def page_block(size: int, count: int, next_token: str | None) -> dict:
    """The `page` object every cursor-paginated listing returns."""
    return {
        "size": size,
        "count": count,
        "nextPageToken": next_token,
        "hasMore": bool(next_token),
    }
