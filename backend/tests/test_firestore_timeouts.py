"""Every Firestore round trip carries a deadline under the gateway's (TD-185).

Without one the client's GAPIC defaults apply (60 s for a get or a write,
300 s for a query or `get_all`, retried on DEADLINE_EXCEEDED), and the JSON
routes' gateway deadline is 60 s, so a hung call outlived the request. The
helpers in `app/repo/_base.py` pass `timeout` and `retry`; this pins that no
app module calls the client around them, and that the values reach the client.

`conftest.firestore_calls_carry_a_deadline` checks the same thing at run time
on every test that uses the store double.
"""
import ast
import pathlib
import re

import pytest

import fake_firestore
from app.repo import _base

BACKEND = pathlib.Path(__file__).resolve().parents[1]
APP_DIR = BACKEND / "app"

#: The `_base` functions that may call the client directly: the helpers.
HELPERS = frozenset({
    "_get", "_stream", "_count", "_commit", "_set_doc", "_create_doc",
    "_update_doc", "_delete_doc", "_add_doc", "_get_all",
})

#: Zero-argument `.get()` calls that are not Firestore: context variables
#: (`observability`) and the mail worker's queue (`notify`).
NOT_FIRESTORE_GETS = frozenset({
    ("observability.py", "_request_id"),
    ("observability.py", "_uid"),
    ("observability.py", "_device_id"),
    ("observability.py", "_app_id"),
    ("observability.py", "_app_version"),
    ("notify.py", "_work"),
})

#: Receivers whose writes are buffered and sent by a commit, not round trips.
BUFFERED = frozenset({"tx", "transaction", "batch"})


def _looks_like_a_document(src: str) -> bool:
    """The repo's naming for a DocumentReference: built with `.document(`,
    held in a `ref` / `*_ref`, or a snapshot's `.reference`."""
    tail = src.rsplit(".", 1)[-1]
    return (
        ".document(" in src
        or tail == "ref"
        or tail.endswith("_ref")
        or tail == "reference"
        or bool(re.search(r"_ref\([^()]*\)$", src))
    )


def _raw_calls(path: pathlib.Path, root: pathlib.Path = APP_DIR):
    """`file:line: source` for each call in [path] that is a Firestore round trip."""
    text = path.read_text(encoding="utf-8")
    tree = ast.parse(text)
    rel = path.relative_to(root).as_posix()
    allowed_spans = []
    if rel == "repo/_base.py":
        allowed_spans = [
            (node.lineno, node.end_lineno)
            for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name in HELPERS
        ]
    found = []
    for node in ast.walk(tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)):
            continue
        if any(lo <= node.lineno <= hi for lo, hi in allowed_spans):
            continue
        attr = node.func.attr
        recv = ast.get_source_segment(text, node.func.value) or ""
        kwargs = {k.arg for k in node.keywords}
        raw = (
            attr in ("stream", "get_all", "commit")
            or (attr == "get" and "transaction" in kwargs)
            or (attr == "get" and not node.args and not node.keywords
                and (path.name, recv) not in NOT_FIRESTORE_GETS)
            or (attr == "add" and ".collection(" in recv)
            or (attr in ("set", "update", "delete", "create")
                and recv not in BUFFERED and _looks_like_a_document(recv))
        )
        if raw:
            found.append(f"{rel}:{node.lineno}: {ast.get_source_segment(text, node)[:80]}")
    return found


def test_no_app_module_calls_firestore_around_the_helpers():
    """A raw `.get()` / `.stream()` / `.commit()` / document write in app code
    runs on the client's defaults. Route it through `repo/_base.py`."""
    found = []
    for path in sorted(APP_DIR.rglob("*.py")):
        found.extend(_raw_calls(path))
    assert found == []


@pytest.mark.parametrize("snippet", [
    'db().collection("users").document(uid).get()',
    "ref.get(transaction=tx)",
    "q.stream()",
    'sessions.count().get()',
    "db().get_all(refs)",
    "batch.commit()",
    "user_ref.update(patch)",
    "doc.reference.delete()",
    '_invite_ref(address).get()',
    'db().collection("audit_logs").add(row)',
])
def test_the_guard_sees_each_kind_of_raw_call(tmp_path, snippet):
    """The guard is only as good as its patterns; prove each one fires."""
    path = tmp_path / "mod.py"
    path.write_text(f"def f():\n    return {snippet}\n", encoding="utf-8")
    assert len(_raw_calls(path, tmp_path)) == 1


@pytest.mark.parametrize("snippet", [
    "patch.update(other)",
    "tx.update(ref, patch)",
    "batch.set(ref, data)",
    'data.get("key")',
    "seen.add(uid)",
])
def test_the_guard_leaves_non_firestore_calls_alone(tmp_path, snippet):
    path = tmp_path / "mod.py"
    path.write_text(f"def f():\n    return {snippet}\n", encoding="utf-8")
    assert _raw_calls(path, tmp_path) == []


def _gateway_deadline() -> float:
    """The top-level `x-google-backend` deadline of the JSON routes."""
    text = (BACKEND / "gateway" / "openapi.yaml").read_text(encoding="utf-8")
    return float(re.search(r"^  deadline: ([0-9.]+)$", text, re.M).group(1))


def test_every_budget_ends_inside_the_gateway_deadline():
    """A retried call can take about twice its budget: the retry window, then
    one more attempt that api-core gives the full timeout again."""
    gateway = _gateway_deadline()
    assert gateway == 60.0
    for timeout, retry in (
        (_base._GET_TIMEOUT_S, _base._READ_RETRY),
        (_base._QUERY_TIMEOUT_S, _base._QUERY_RETRY),
        (_base._WRITE_TIMEOUT_S, _base._WRITE_RETRY),
    ):
        assert retry.timeout == timeout
        assert 2 * timeout < gateway


def test_a_timed_out_attempt_is_not_retried():
    """DEADLINE_EXCEEDED is in the client's default predicates; left in, a
    hung call would start over until the 300 s retry window closed."""
    from google.api_core.exceptions import DeadlineExceeded, ServiceUnavailable

    for retry in (_base._READ_RETRY, _base._QUERY_RETRY, _base._WRITE_RETRY):
        assert not retry._predicate(DeadlineExceeded("slow"))
        assert retry._predicate(ServiceUnavailable("blip"))


def _calls(method):
    return [c for c in fake_firestore.CALLS if c[0] == method]


def test_the_store_sees_each_kind_of_deadline(store):
    """The values reach the client, per kind of call."""
    users = _base.db().collection("users")
    ref = users.document("u1")
    _base._set_doc(ref, {"email": "a@x.test"})
    _base._update_doc(ref, {"email": "b@x.test"})
    _base._create_doc(users.document("u2"), {"email": "c@x.test"})
    _base._get(ref)
    _base._stream(users.where("email", "==", "b@x.test"))
    _base._count(users.count())
    _base._get_all([ref])
    batch = _base.db().batch()
    batch.update(ref, {"email": "d@x.test"})
    _base._commit(batch)
    _base._delete_doc(ref)

    read = (_base._GET_TIMEOUT_S, _base._READ_RETRY)
    query = (_base._QUERY_TIMEOUT_S, _base._QUERY_RETRY)
    write = (_base._WRITE_TIMEOUT_S, _base._WRITE_RETRY)
    expected = {
        "set": write, "update": write, "create": write, "delete": write, "commit": write,
        "get": read, "stream": query, "count": query, "get_all": query,
    }
    for method, (timeout, retry) in expected.items():
        seen = _calls(method)
        assert seen, method
        assert all(c[1] == timeout and c[2] is retry for c in seen), (method, seen)


def test_a_transactional_read_carries_the_read_deadline(store):
    ref = _base.db().collection("users").document("u1")
    _base._get(ref, transaction=_base.db().transaction())
    assert _calls("get") == [
        ("get", _base._GET_TIMEOUT_S, _base._READ_RETRY, "app.repo._base"),
    ]


def test_a_repo_read_reaches_the_store_with_its_deadline(store):
    """End to end through a public repo function, not just the helper."""
    from app import repo

    store._data["licenses"] = {"L1": {"kind": "individual"}}
    assert repo.get_license("L1") == {"kind": "individual"}
    assert _calls("get") == [
        ("get", _base._GET_TIMEOUT_S, _base._READ_RETRY, "app.repo._base"),
    ]


def test_the_audit_write_carries_the_write_deadline(monkeypatch):
    """`audit.record` adds through a collection the store double does not
    implement, so this one is checked against a stub collection."""
    from app import audit

    seen = []

    class _Collection:
        def add(self, data, *, retry=None, timeout=None):
            seen.append((timeout, retry))

    class _Client:
        def collection(self, name):
            assert name == "audit_logs"
            return _Collection()

    monkeypatch.setattr(_base, "_DB", _Client())
    audit.record(uid="u1", action="X")
    assert seen == [(_base._WRITE_TIMEOUT_S, _base._WRITE_RETRY)]


def test_the_runtime_check_names_a_call_that_skipped_the_helpers(store):
    """The conftest check is only useful if a raw call from app code lands
    in UNTIMED. Simulate one from an `app.*` module's globals."""
    ref = _base.db().collection("users").document("u1")
    namespace = {"__name__": "app.repo.example", "ref": ref}
    exec(compile("ref.get()", "app/repo/example.py", "exec"), namespace)
    try:
        assert fake_firestore.UNTIMED == [("get", "app.repo.example", 1)]
    finally:
        fake_firestore.UNTIMED.clear()
