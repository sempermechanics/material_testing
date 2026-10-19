"""Helpers more than one service uses."""
import json


def json_dumps(value) -> str:
    """Compact JSON for the streamed export and bundle manifest. `default=str`
    because Firestore hands back datetimes, which json cannot serialise."""
    return json.dumps(value, separators=(",", ":"), default=str)
