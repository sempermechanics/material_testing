"""Retry delay for outbound HTTP (Drive, Resend): honour Retry-After, bounded."""
from __future__ import annotations

import math

# Same ceiling as the exponential fallback. An upstream that asks for longer
# (Retry-After: 3600) would otherwise park a request thread, or the single
# notify worker, for that long (TD-52); past the cap the attempt fails and the
# caller's own retry takes over.
MAX_DELAY_S = 16.0


def retry_delay(attempt: int, retry_after: str | None, *, floor: float = 0.0) -> float:
    """Seconds before retry `attempt` (0-based), clamped to [floor, MAX_DELAY_S]."""
    if retry_after is not None:
        try:
            wanted = float(retry_after)
        except ValueError:
            wanted = math.nan
        if math.isfinite(wanted):
            return min(max(floor, wanted), MAX_DELAY_S)
        if wanted == math.inf:
            return MAX_DELAY_S
    return max(floor, float(min(2 ** attempt, MAX_DELAY_S)))
