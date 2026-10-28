"""The store double stamps `SERVER_TIMESTAMP` from a strictly increasing clock
(readiness TE3-101).

Windows' wall clock on CPython 3.12 moves in 15.6 ms steps, so two writes in
one tick got the same stamp. Real Firestore commit times are microsecond and a
later commit is later; a test comparing two stamps (a seat's `revokedAt` and the
holder's next `lastSeenAt`, `repo/reconcile.py`) must not depend on the host's
clock resolution.
"""
from datetime import datetime, timezone

import fake_firestore


class _CoarseClock(datetime):
    """A wall clock that never moves: the worst case of a coarse tick."""

    @classmethod
    def now(cls, tz=None):
        return datetime(2026, 10, 28, 12, 0, 0, tzinfo=tz or timezone.utc)


def test_two_server_timestamps_in_one_clock_tick_are_strictly_ordered(monkeypatch):
    monkeypatch.setattr(fake_firestore, "datetime", _CoarseClock)
    monkeypatch.setattr(fake_firestore, "_last_stamp", None)
    store = fake_firestore.FakeClient()
    ref = store.collection("seats").document("s1")

    ref.set({"revokedAt": fake_firestore.SERVER_TIMESTAMP})
    ref.update({"lastSeenAt": fake_firestore.SERVER_TIMESTAMP})
    ref.update({"updatedAt": fake_firestore.SERVER_TIMESTAMP})

    doc = ref.get().to_dict()
    assert doc["revokedAt"] < doc["lastSeenAt"] < doc["updatedAt"]


def test_stamps_follow_the_wall_clock_when_it_moves():
    store = fake_firestore.FakeClient()
    ref = store.collection("c").document("d")
    before = datetime.now(timezone.utc)
    ref.set({"at": fake_firestore.SERVER_TIMESTAMP})
    stamp = ref.get().to_dict()["at"]
    assert stamp.tzinfo is not None
    assert (stamp - before).total_seconds() < 5
