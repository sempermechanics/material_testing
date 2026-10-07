# ADR-021: Retire the `firestore_repo` facade; `app.repo` is a plain package

**Status:** Accepted, built
**Date:** 2026-10-05
**Deciders:** backend owner
**Supersedes:** [ADR-001](ADR-001-firestore-repo-package.md) §Decision 2, the facade
(the package split itself stands)

## Context

ADR-001 split `firestore_repo.py` into `app/repo/` and kept `firestore_repo.py`
as a facade, so that the 500-odd tests that patched `firestore_repo.<name>`
would not have to change. The facade did three things a plain module does not:
- it reassigned its own module `__class__`, so that an assignment to it was
  repeated in every repo module that held the same object;
- it read unknown names through to the package (`__getattr__`);
- it re-exported `notify` and `settings` for tests.

`repo/licensing.py` added a second, re-export-only layer.

All of that machinery existed for tests, but it lived in a production module.
Every reader of a router saw `firestore_repo` and had to learn that assigning
to it was special. `audit.py` imported through it, which forced a deferred
import inside `repo/devlock.py`.

## Decision

- `backend/app/repo/__init__.py` re-exports each module's public names: plain
  imports, no hooks. Routers, `deps`, `session_provision` and scripts use
  `from app import repo` and call `repo.<name>`. A private helper is imported
  from the module that defines it.
- `firestore_repo.py` and `repo/licensing.py` are deleted. `audit` imports
  `app.repo._base`, and `repo/devlock.py` imports `audit` at module scope.
- Patching moves to the tests. `tests/repo_view.py` reads any name, public or
  private, from the package. `repo_view.patch(monkeypatch, name, value)`
  replaces the name in every module of the package that holds the same
  object, which is what the facade did on assignment, and the test asks for
  it explicitly.
- `tests/test_repo_package.py` pins:
  - every public name is exported, and no private one;
  - imports run one way through `PACKAGE`, and none imports the package itself;
  - only `_base` binds the Firestore module and holds `_DB`;
  - a patch reaches callers inside the package;
  - no app module mentions `firestore_repo`.

## Options considered

1. **Plain package API plus a test-side patch helper (chosen).** Routers keep
   their `repo.<name>` call sites, and the one non-obvious mechanism is in
   test code, named where it is used.
2. **Routers import each owner module** (`from ..repo import sessions as
   sessions_repo`). This is more explicit, but it means about 200 rewritten
   call sites and nine import lines per router, and a test that patched one
   module would still miss callers inside the package. The test helper would
   be needed anyway.
3. **Keep the facade.** That means no churn, and the magic stays in production.

## Consequences

- 39 test files import `repo_view as repo`. About 50 `monkeypatch.setattr(repo, …)`
  calls became `repo.patch(monkeypatch, …)`. A test that assigns
  `repo.<name> = …` directly now patches nothing in the app.
  `test_route_authz_matrix` did this and was rewritten.
- `fake_firestore.install` patches `app.repo._base` directly.
- The suite (836) and the emulator tier (24) pass unchanged in what they assert.
