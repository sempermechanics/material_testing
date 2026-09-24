"""The `app.repo` package and its `firestore_repo` facade (ADR-001).

The split moved code without changing it, so the suite's 500-odd tests still
patch `firestore_repo.<name>`. These pin what keeps those patches meaningful.
"""
import ast
import pathlib

import pytest

from app import firestore_repo as repo
from app.repo import _base, licensing, seats, user_config

REPO_DIR = pathlib.Path(__file__).resolve().parents[1] / "app" / "repo"


def _tree(module):
    return ast.parse(pathlib.Path(module.__file__).read_text(encoding="utf-8"))


def test_a_patch_through_the_facade_reaches_callers_inside_the_package():
    """`ensure_entitlement` (licensing) calls `claim_seat` through its own
    binding. A patch that reached only the facade would leave that caller on
    the real function and the test that set it would prove nothing."""
    real = repo.claim_seat

    def fake(*a, **k):
        return repo._CONTENDED

    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(repo, "claim_seat", fake)
        assert licensing.claim_seat is fake
        assert repo.claim_seat is fake
    assert licensing.claim_seat is real
    assert repo.claim_seat is real


def test_a_shared_helper_is_patched_in_every_module_that_imported_it():
    real = repo._now
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(repo, "_now", lambda: "then")
        assert user_config._now() == seats._now() == _base._now() == "then"
    assert user_config._now is seats._now is _base._now is real


def test_names_the_facade_does_not_list_still_read_and_patch_through():
    """`fake_firestore.install` sets `_DB` and `firestore`, which live only in
    `_base`; the other modules read them from there at call time."""
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(repo, "_DB", "client")
        assert _base._DB == "client"
        assert repo._DB == "client"
        assert "_DB" not in vars(repo)
    assert _base._DB is None or _base._DB != "client"


def test_every_public_name_is_listed_on_the_facade():
    """Routers and scripts import from the facade by name."""
    missing = []
    for module in repo.PACKAGE:
        for node in _tree(module).body:
            name = getattr(node, "name", None)
            if name and not name.startswith("_") and name not in vars(repo):
                missing.append(f"{module.__name__}.{name}")
    assert missing == []


def test_the_package_imports_run_one_way():
    """Each module imports only modules before it in `PACKAGE`, and none
    imports the facade (which would make the order load-bearing again)."""
    order = [m.__name__.rsplit(".", 1)[1] for m in repo.PACKAGE]
    assert sorted(order) == sorted(p.stem for p in REPO_DIR.glob("*.py") if p.stem != "__init__")
    backwards = []
    for i, module in enumerate(repo.PACKAGE):
        for node in ast.walk(_tree(module)):
            if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module:
                if order.index(node.module) >= i:
                    backwards.append(f"{order[i]} -> {node.module}")
            if isinstance(node, ast.ImportFrom) and (node.module or "").endswith("firestore_repo"):
                backwards.append(f"{order[i]} -> firestore_repo")
    assert backwards == []


def test_only_base_binds_the_firestore_module():
    """Everything else says `_base.firestore`, so the fake patches one place."""
    binders = [
        module.__name__ for module in repo.PACKAGE[1:]
        if "firestore" in vars(module)
    ]
    assert binders == []
