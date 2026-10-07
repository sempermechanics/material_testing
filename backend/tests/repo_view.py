"""`app.repo` as the tests see it: every name, public and private, and one way
to patch one.

Production code reads `app.repo` and its modules plainly (ADR-021). A test
that replaces a repo function has to reach every caller, and a caller inside
the package holds its own binding (`activation` imported `claim_seat` by
name), so assigning the name on one module would leave the others on the real
function and the test would prove nothing. `patch` replaces the name in every
module of the package that holds the same object. That is what the
`firestore_repo` facade did on assignment; it lives here now, in the tests
that need it, instead of in a production module.

    import repo_view as repo
    repo.patch(monkeypatch, "claim_seat", fake)
    repo.claim_seat(...)      # any name of any module, read at call time
"""
import app.repo as _package

_MISSING = object()

#: The package itself (what routers read) and each of its modules.
MODULES = (_package, *_package.PACKAGE)


def __getattr__(name: str):
    for module in MODULES:
        value = vars(module).get(name, _MISSING)
        if value is not _MISSING:
            return value
    raise AttributeError(f"app.repo has no {name!r}")


def patch(monkeypatch, name: str, value) -> None:
    """Replace `name` wherever the package holds it, undone with `monkeypatch`."""
    old = __getattr__(name)
    holders = [m for m in MODULES if vars(m).get(name, _MISSING) is old]
    for module in holders:
        monkeypatch.setattr(module, name, value)
