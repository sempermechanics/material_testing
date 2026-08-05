"""Best-effort per-instance rate limits keyed on uid.

ESPv2 / API Gateway quotas (see gateway/openapi.yaml x-google-management) are
the durable cross-instance limit. This token bucket is belt-and-braces inside
each Cloud Run instance — it does not coordinate across replicas.
"""
from __future__ import annotations

import threading
import time
from collections import defaultdict


class TokenBucket:
    """Simple refill-per-second token bucket."""

    def __init__(self, rate_per_sec: float, burst: float):
        self._rate = rate_per_sec
        self._burst = burst
        self._lock = threading.Lock()
        self._tokens: dict[str, float] = defaultdict(lambda: burst)
        self._updated: dict[str, float] = defaultdict(time.monotonic)

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            last = self._updated[key]
            tokens = min(self._burst, self._tokens[key] + (now - last) * self._rate)
            self._updated[key] = now
            if tokens < 1.0:
                self._tokens[key] = tokens
                return False
            self._tokens[key] = tokens - 1.0
            return True


# Challenge minting is cheap but abusable for nonce spam.
challenge_bucket = TokenBucket(rate_per_sec=2.0, burst=10.0)
# Download proxy streams SA-scoped Drive bytes — keep tight.
download_bucket = TokenBucket(rate_per_sec=1.0, burst=5.0)
# Session create burns quota / Drive folders.
session_bucket = TokenBucket(rate_per_sec=1.0, burst=5.0)
# Device registration writes two security-sensitive documents.
device_register_bucket = TokenBucket(rate_per_sec=0.5, burst=3.0)
# Completion performs Drive metadata I/O plus transactional Firestore writes.
file_complete_bucket = TokenBucket(rate_per_sec=2.0, burst=10.0)
# verify=true fans out to one Drive request per returned session.
session_verify_bucket = TokenBucket(rate_per_sec=0.2, burst=2.0)
# Health is public; this limits only accidental/hostile per-instance floods.
health_bucket = TokenBucket(rate_per_sec=5.0, burst=20.0)
