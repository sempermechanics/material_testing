"""The `app.repo` package and its `firestore_repo` facade (ADR-001).

The split moved code without changing it, so the suite's 500-odd tests still
patch `firestore_repo.<name>`. These pin what keeps those patches meaningful.
"""
import ast
import pathlib

import pytest

from app import firestore_repo as repo
from app.repo import (
    _base,
    activation,
    claims,
    entitlement,
    institution_admin,
    licensing,
    seats,
    user_config,
)

REPO_DIR = pathlib.Path(__file__).resolve().parents[1] / "app" / "repo"


def _tree(module):
    return ast.parse(pathlib.Path(module.__file__).read_text(encoding="utf-8"))


def test_a_patch_through_the_facade_reaches_callers_inside_the_package():
    """`ensure_entitlement` (entitlement) calls `claim_seat` through its own
    binding, as do `activation` and `institution_admin`. A patch that reached only the facade would leave that caller on
    the real function and the test that set it would prove nothing."""
    real = repo.claim_seat

    def fake(*a, **k):
        return repo._CONTENDED

    holders = (claims, activation, entitlement, institution_admin, licensing)
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(repo, "claim_seat", fake)
        assert all(m.claim_seat is fake for m in holders)
        assert repo.claim_seat is fake
    assert all(m.claim_seat is real for m in holders)
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


#: What each licensing module may import from the package (TD-64). Claims and
#: invites are leaves; everything else builds on them, and `licensing` only
#: re-exports.
LICENSING_IMPORTS = {
    "claims": {"_base"},
    "invites": {"_base"},
    "mint": {"_base", "claims", "invites"},
    "activation": {"_base", "claims", "devlock", "user_config"},
    "entitlement": {"_base", "claims", "invites", "mint"},
    "license_admin": {"_base", "claims", "invites", "mint"},
    "institution_admin": {"_base", "claims", "invites", "mint"},
    "licensing": {"claims", "invites", "mint", "activation", "entitlement",
                  "license_admin", "institution_admin"},
}


def _package_imports(module) -> set[str]:
    return {
        node.module for node in ast.walk(_tree(module))
        if isinstance(node, ast.ImportFrom) and node.level == 1 and node.module
    }


def test_the_licensing_modules_import_only_their_layer():
    by_name = {m.__name__.rsplit(".", 1)[1]: m for m in repo.PACKAGE}
    extra = {
        name: sorted(_package_imports(by_name[name]) - allowed)
        for name, allowed in LICENSING_IMPORTS.items()
        if _package_imports(by_name[name]) - allowed
    }
    assert extra == {}


def test_licensing_only_re_exports():
    """The old import path keeps working, but no code lands there again."""
    defined = [
        getattr(node, "name", None) or type(node).__name__
        for node in _tree(licensing).body
        if not isinstance(node, (ast.ImportFrom, ast.Expr))
    ]
    assert defined == []
    by_name = {m.__name__.rsplit(".", 1)[1]: m for m in repo.PACKAGE}
    for node in _tree(licensing).body:
        if isinstance(node, ast.ImportFrom):
            owner = by_name[node.module]
            for alias in node.names:
                assert getattr(licensing, alias.name) is vars(owner)[alias.name]
