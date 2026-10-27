# ADR-029: The backend's OpenAPI document is a committed snapshot

**Status:** Accepted (the Admin's readiness triage, 2026-10-24: "Fix now: snapshot only"). Built
on `claude/readiness-20261024-backend`; not merged.
**Date:** 2026-10-24
**Deciders:** Admin (readiness run 2026-10-24, finding TE3-006)
**Register:** — (TE3-007, schema-driven fuzzing, is deferred: no new test dependency approved)

Paths are from the repository root.

## Context

- `backend/app/main.py` serves `/openapi.json`, `/docs` and `/redoc` only off Cloud Run, so the
  deployed API has no published description and nothing in CI kept one.
- `backend/gateway/openapi.yaml` is the ESPv2 allow-list. `backend/tests/test_gateway_parity.py` compares
  it with the app by (method, path) with parameter names erased; it says nothing about
  bodies or parameters.
- The repository-root `contracts/*.json` (checked by `backend/tests/test_wire_contracts.py` here and
  `ApiDtosContractTest` in the app) pin 15 bodies for 13 of the routes the Android app
  calls. The other app routes and the ~45 routes the operator, institution and account
  consoles read have no pinned shape.
- No route declares a `response_model`, so `app.openapi()` (71 operations) types request
  bodies and parameters but not responses.

## Decision

1. `backend/contracts/app-openapi.json` is `app.openapi()` written with sorted keys, two-space
   indent, LF and a final newline. It is committed.
2. `backend/tests/test_openapi_snapshot.py` regenerates it on every backend run (tier 1 and the
   backend gate) and fails on any byte of difference, printing the diff.
3. A deliberate change rewrites it in the same PR:
   `UPDATE_OPENAPI_SNAPSHOT=1 python -m pytest tests/test_openapi_snapshot.py`. The PR says
   whether the diff breaks a shipped client. Breaking means: a path or method removed; a
   request field renamed or removed while a shipped app or console still sends it; a new
   required request field or parameter; a narrower type or enum on a request field. A
   breaking change needs the client change shipped first, or a compatibility window.
4. Scope is the snapshot only. No `response_model` is added: FastAPI filters an outgoing
   body through it, so an incomplete model would silently drop a field a console reads.

## Options

| Option | Why not (now) |
|---|---|
| Snapshot + automated breaking-change diff (oasdiff, or a Python check against `origin/main`'s copy) in CI | A new tool or a check of its own; the reviewed diff covers the same ground at this size. Revisit if the snapshot diff is waved through |
| `response_model` on every route, so responses are typed too | Large, and risky for the reason in decision 4; route by route later, behind the wire tests |
| Schemathesis against the local app | New test dependencies not approved (TE3-007) |
| Rely on `backend/gateway/openapi.yaml` | It lists paths, not shapes |

## Trade-offs

- Any edit to a model's docstring, a `Field` description or a route summary also shows in
  the diff. That is noise, but it is reviewed noise and small.
- The snapshot does not cover responses until routes declare models; `test_wire_contracts`
  remains the response check for what the Android app reads.

## Consequences

- An unintended change to the API surface fails CI in the PR that makes it.
- The committed file is the description to hand a client author; production still serves
  none.

## Action items

- [x] Commit `backend/contracts/app-openapi.json` and `backend/tests/test_openapi_snapshot.py`.
- [ ] Response models, route by route, starting with the console desk reads (TE3-006 part 2,
      not approved in this run).
