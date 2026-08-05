"""A small in-memory Firestore double.

Enough of the client surface for firestore_repo.py to run in tests without a
live backend: documents, `.set/.update/.get/.delete`, `==` queries, `.count()`,
batches, and a pass-through transaction. Install it with `install(monkeypatch)`.
"""
from datetime import datetime, timezone


class _Sentinel:
    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return f"<{self.name}>"


SERVER_TIMESTAMP = _Sentinel("SERVER_TIMESTAMP")
DELETE_FIELD = _Sentinel("DELETE_FIELD")


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

    def update(self, patch):
        cur = self._bucket().get(self.id)
        if cur is None:
            raise KeyError(f"update on missing doc {self._collection}/{self.id}")
        for k, v in patch.items():
            if v is DELETE_FIELD:
                cur.pop(k, None)
            elif v is SERVER_TIMESTAMP:
                cur[k] = datetime.now(timezone.utc)
            else:
                cur[k] = v

    def delete(self):
        self._bucket().pop(self.id, None)


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

    def where(self, field, op, value):
        assert op == "==", f"fake store only supports '==', got {op!r}"
        return _Query(
            self._store, self._collection,
            self._filters + [(field, value)], self._limit,
            self._order_by, self._start_after,
        )

    def limit(self, n):
        return _Query(
            self._store, self._collection, self._filters, n,
            self._order_by, self._start_after,
        )

    def order_by(self, field):
        return _Query(
            self._store, self._collection, self._filters, self._limit,
            field, self._start_after,
        )

    def start_after(self, snapshot_or_doc):
        return _Query(
            self._store, self._collection, self._filters, self._limit,
            self._order_by, snapshot_or_doc,
        )

    def _matching(self):
        bucket = self._store._data.get(self._collection, {})
        rows = []
        for doc_id, data in bucket.items():
            if all(data.get(f) == v for f, v in self._filters):
                rows.append(_Snapshot(doc_id, data, _DocRef(self._store, self._collection, doc_id)))
        if self._order_by == "__name__" or self._order_by is None:
            rows.sort(key=lambda snap: snap.id)
        elif self._order_by:
            field = self._order_by
            rows.sort(key=lambda snap: ((snap.to_dict() or {}).get(field) is None,
                                        (snap.to_dict() or {}).get(field)))
        if self._start_after is not None:
            after_id = getattr(self._start_after, "id", None)
            if after_id is not None:
                rows = [snap for snap in rows if snap.id > after_id]
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
    def __init__(self, store):
        self._ops = []

    def delete(self, ref):
        self._ops.append(ref)

    def commit(self):
        for ref in self._ops:
            ref.delete()
        self._ops = []


class FakeClient:
    def __init__(self):
        self._data = {}

    def collection(self, name):
        return _Collection(self, name)

    def batch(self):
        return _Batch(self)

    def transaction(self):
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
        transactional = staticmethod(transactional)

    monkeypatch.setattr(repo, "firestore", _FakeFirestore)
    return client
