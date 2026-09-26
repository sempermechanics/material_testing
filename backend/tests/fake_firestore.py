"""A small in-memory Firestore double.

Enough of the client surface for the repo package (`app/repo/`) to run in
tests without a live backend: documents, `.set/.update/.get/.delete`, filtered and
one-field ordered queries, `.count()`,
batches, and a pass-through transaction. Install it with `install(monkeypatch)`.
"""
import operator
from datetime import datetime, timezone

from google.api_core.exceptions import AlreadyExists, NotFound


class _Sentinel:
    # No __dict__, so that jsonable_encoder rejects a sentinel that leaks into
    # a response the way it rejects the real one (the real client's sentinel
    # is likewise not encodable); with a __dict__ it would encode as {} and a
    # write's return value reaching a response would pass here, 500 in prod.
    __slots__ = ("name",)

    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return f"<{self.name}>"


SERVER_TIMESTAMP = _Sentinel("SERVER_TIMESTAMP")
DELETE_FIELD = _Sentinel("DELETE_FIELD")
# The real client's `Query.ASCENDING` / `Query.DESCENDING` are these strings.
ASCENDING = "ASCENDING"
DESCENDING = "DESCENDING"


class Increment:
    """Server-side atomic increment, as used by bump_session_progress."""

    def __init__(self, value):
        self.value = value


def transactional(fn):
    """Run the body immediately — no retries, which the tests do not need."""
    return fn


def _resolve(data: dict) -> dict:
    out = {}
    for k, v in data.items():
        if v is SERVER_TIMESTAMP:
            out[k] = datetime.now(timezone.utc)
        else:
            out[k] = v
    return out


class _Snapshot:
    def __init__(self, doc_id, data, reference):
        self.id = doc_id
        self._data = data
        self.reference = reference

    @property
    def exists(self):
        return self._data is not None

    def to_dict(self):
        return dict(self._data) if self._data is not None else None


class _DocRef:
    def __init__(self, store, collection, doc_id):
        self._store = store
        self._collection = collection
        self.id = doc_id

    def _bucket(self):
        return self._store._data.setdefault(self._collection, {})

    def get(self, transaction=None):
        data = self._bucket().get(self.id)
        return _Snapshot(self.id, data, self)

    def set(self, data):
        self._bucket()[self.id] = _resolve(data)

    def create(self, data):
        # Real Firestore raises AlreadyExists; get_or_create_user catches it to
        # settle a first-sign-in race, so the double has to raise it too.
        if self.id in self._bucket():
            raise AlreadyExists(f"document {self._collection}/{self.id} exists")
        self._bucket()[self.id] = _resolve(data)

    def update(self, patch):
        cur = self._bucket().get(self.id)
        if cur is None:
            # The real client raises NotFound, and callers catch that — a KeyError
            # here would let a wrong except clause pass in tests and fail in prod.
            raise NotFound(f"update on missing doc {self._collection}/{self.id}")
        for k, v in patch.items():
            if v is DELETE_FIELD:
                cur.pop(k, None)
            elif v is SERVER_TIMESTAMP:
                cur[k] = datetime.now(timezone.utc)
            elif isinstance(v, Increment):
                cur[k] = int(cur.get(k, 0)) + v.value
            else:
                cur[k] = v

    def delete(self):
        self._bucket().pop(self.id, None)

    def collection(self, name):
        """Subcollection under this document (e.g. licenses/{id}/seats/{uid}),
        stored as its own flat bucket keyed by the joined path — mirrors how
        the real client addresses subcollections without needing a nested
        document tree in this double."""
        return _Collection(self._store, f"{self._collection}/{self.id}/{name}")


class _AggResult:
    def __init__(self, value):
        self.value = value


class _Query:
    def __init__(self, store, collection, filters=None, limit=None, order_by=None, start_after=None):
        self._store = store
        self._collection = collection
        self._filters = filters or []
        self._limit = limit
        self._order_by = order_by
        self._start_after = start_after

    #: Positional `.where(field, op, value)` only — the production code uses
    #: that legacy signature rather than FieldFilter precisely so this double
    #: can implement it.
    _OPS = {
        "==": operator.eq,
        "!=": operator.ne,
        "<": operator.lt,
        "<=": operator.le,
        ">": operator.gt,
        ">=": operator.ge,
        # Membership, not comparison: the left side is the stored list.
        "array_contains": lambda stored, wanted: (
            isinstance(stored, (list, tuple)) and wanted in stored
        ),
        # The stored value is one of the wanted list.
        "in": lambda stored, wanted: stored in wanted,
    }

    def where(self, field, op, value):
        assert op in self._OPS, f"fake store does not support {op!r}"
        return _Query(
            self._store, self._collection,
            self._filters + [(field, op, value)], self._limit,
            self._order_by, self._start_after,
        )

    def limit(self, n):
        return _Query(
            self._store, self._collection, self._filters, n,
            self._order_by, self._start_after,
        )

    def order_by(self, field, direction=None):
        """One sort field, as the repo uses. Ties break on the document id in
        the same direction, which is what Firestore's implicit `__name__`
        ordering does."""
        return _Query(
            self._store, self._collection, self._filters, self._limit,
            (field, direction == DESCENDING), self._start_after,
        )

    def start_after(self, snapshot_or_doc):
        return _Query(
            self._store, self._collection, self._filters, self._limit,
            self._order_by, snapshot_or_doc,
        )

    @classmethod
    def _field_value(cls, data: dict, field: str):
        """Resolve `a.b.c` the way Firestore document fields do for queries."""
        cur = data
        for part in field.split("."):
            if not isinstance(cur, dict) or part not in cur:
                return None, False
            cur = cur[part]
        return cur, True

    @classmethod
    def _passes(cls, data, field, op, value) -> bool:
        """One filter clause.

        A document missing the field never matches an inequality, matching
        Firestore: a field that is absent is not indexed, so such documents
        are simply not in the result set. Getting this wrong would make an
        expired-lease sweep also pick up seats that hold no lease at all.
        """
        stored, present = cls._field_value(data, field)
        if not present:
            return op == "!=" if "!" in op else False
        if op in ("array_contains", "in"):
            return cls._OPS[op](stored, value)
        try:
            return cls._OPS[op](stored, value)
        except TypeError:
            return False  # mismatched types are never comparable in Firestore

    def _sort_key(self, doc_id, data):
        field, _desc = self._order_by or ("__name__", False)
        if field == "__name__":
            return (False, "", doc_id)
        value, present = self._field_value(data or {}, field)
        return (not present or value is None, value if present else None, doc_id)

    def _matching(self):
        bucket = self._store._data.get(self._collection, {})
        rows = []
        for doc_id, data in bucket.items():
            if all(self._passes(data, f, op, v) for f, op, v in self._filters):
                rows.append(_Snapshot(doc_id, data, _DocRef(self._store, self._collection, doc_id)))
        field, desc = self._order_by or ("__name__", False)
        if field != "__name__":
            # Firestore leaves out documents that lack the order field.
            rows = [snap for snap in rows if self._field_value(snap.to_dict() or {}, field)[1]]
        rows.sort(key=lambda snap: self._sort_key(snap.id, snap.to_dict()), reverse=desc)
        if self._start_after is not None:
            after_id = getattr(self._start_after, "id", None)
            if after_id is not None:
                after_data = self._start_after.to_dict() if hasattr(self._start_after, "to_dict") else None
                after = self._sort_key(after_id, after_data)
                rows = [snap for snap in rows
                        if (self._sort_key(snap.id, snap.to_dict()) < after if desc
                            else self._sort_key(snap.id, snap.to_dict()) > after)]
        if self._limit is not None:
            rows = rows[: self._limit]
        return rows

    def stream(self):
        return iter(self._matching())

    def count(self):
        rows = self._matching()

        class _Countable:
            def get(_self):
                return [[_AggResult(len(rows))]]

        return _Countable()


class _Collection(_Query):
    def document(self, doc_id):
        return _DocRef(self._store, self._collection, doc_id)


class _Transaction:
    """Pass-through transaction: applies writes immediately (no isolation),
    which is all the single-doc bump logic needs to be exercised."""

    def update(self, ref, patch):
        ref.update(patch)

    def set(self, ref, data):
        ref.set(data)

    def delete(self, ref):
        ref.delete()


class _Batch:
    """Buffers writes until commit(), like a real WriteBatch.

    Ordering is preserved across mixed op types — a real batch applies writes in
    the order they were added, and callers rely on that (e.g. superseding the
    previous device before setting the new one).
    """

    def __init__(self, store):
        self._ops = []

    def delete(self, ref):
        self._ops.append(("delete", ref, None))

    def set(self, ref, data, merge=False):
        self._ops.append(("set", ref, (data, merge)))

    def update(self, ref, data):
        self._ops.append(("update", ref, data))

    def commit(self):
        ops, self._ops = self._ops, []
        for kind, ref, payload in ops:
            if kind == "delete":
                ref.delete()
            elif kind == "set":
                data, merge = payload
                ref.set(data, merge=merge) if merge else ref.set(data)
            else:
                ref.update(payload)


class FakeClient:
    def __init__(self):
        self._data = {}

    def collection(self, name):
        return _Collection(self, name)

    def get_all(self, references, transaction=None):
        # The real client yields in arbitrary order; reversing keeps callers
        # honest about keying results by id rather than by position.
        return [ref.get() for ref in reversed(list(references))]

    def batch(self):
        return _Batch(self)

    def transaction(self, **kwargs):
        # Real Client.transaction takes max_attempts; accept and ignore it so a
        # caller tuning retries does not blow up only in tests.
        return _Transaction()


def install(monkeypatch):
    """Point firestore_repo at a fresh FakeClient and fake firestore sentinels.

    Returns the FakeClient so a test can seed/inspect `client._data`.
    """
    from app import firestore_repo as repo

    client = FakeClient()
    monkeypatch.setattr(repo, "_DB", client)

    class _FakeFirestore:
        SERVER_TIMESTAMP = SERVER_TIMESTAMP
        DELETE_FIELD = DELETE_FIELD
        Increment = Increment
        transactional = staticmethod(transactional)

        class Query:
            ASCENDING = ASCENDING
            DESCENDING = DESCENDING

    monkeypatch.setattr(repo, "firestore", _FakeFirestore)
    return client
