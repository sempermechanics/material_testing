"""Best-effort per-instance rate limits keyed on uid.

ESPv2 / API Gateway quotas (see gateway/openapi.yaml x-google-management) are
the durable cross-instance limit. This token bucket is belt-and-braces inside
each Cloud Run instance — it does not coordinate across replicas.
"""
from __future__ import annotations

import threading
import time


class TokenBucket:
    """Simple refill-per-second token bucket."""

    def __init__(self, rate_per_sec: float, burst: float):
        self._rate = rate_per_sec
        self._burst = burst
        self._lock = threading.Lock()
        self._tokens: dict[str, float] = {}
        self._updated: dict[str, float] = {}
        # A key idle for burst/rate seconds has refilled to full, so its stored
        # state is indistinguishable from a key we have never seen — dropping it
        # changes no decision. Without this the two dicts only ever grow, for the
        # lifetime of the instance; notify._prune_sent exists for the same reason.
        self._idle_ttl = (burst / rate_per_sec) if rate_per_sec > 0 else 60.0
        self._next_prune = 0.0

    def _prune(self, now: float) -> None:
        """Drop fully-refilled keys. Called under the lock, at most once per TTL."""
        if now < self._next_prune:
            return
        self._next_prune = now + self._idle_ttl
        stale = [k for k, ts in self._updated.items() if now - ts >= self._idle_ttl]
        for key in stale:
            del self._updated[key]
            self._tokens.pop(key, None)

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            self._prune(now)
            last = self._updated.get(key, now)
            stored = self._tokens.get(key, self._burst)
            tokens = min(self._burst, stored + (now - last) * self._rate)
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
# Full-account export is the heaviest read in the service: every session, and a
# file query per session. One caller can otherwise burn the project's Firestore
# read quota from a single endpoint. It is also absent from the gateway quotas.
export_bucket = TokenBucket(rate_per_sec=0.05, burst=2.0)
# Erasure walks a Drive subtree and batch-deletes Firestore. Not something a
# legitimate client does in a loop.
erase_bucket = TokenBucket(rate_per_sec=0.2, burst=3.0)
# Admin routes are trusted but unmetered — this is flood protection only.
admin_bucket = TokenBucket(rate_per_sec=5.0, burst=20.0)
# License key guesses must not be brute-forced.
license_activate_bucket = TokenBucket(rate_per_sec=0.2, burst=3.0)
# Per-session manifest/resume listings each query up to _LIST_SOFT_LIMIT docs.
listing_bucket = TokenBucket(rate_per_sec=5.0, burst=20.0)
