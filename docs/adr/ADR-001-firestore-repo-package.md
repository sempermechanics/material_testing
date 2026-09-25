# ADR-001: Split `firestore_repo.py` into a package behind a facade

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** backend owner

## Context

`backend/app/firestore_repo.py` is 3,432 lines and 138 functions. Roughly two
thirds of it is licensing (mint, claims, invites, leases, seats, device lock,
reconciliation), written as the licensing work landed. Every router imports it
as `from .. import firestore_repo as repo` and calls about 70 public names
through that alias; tests do the same and patch 16 distinct names on it.

The same file repeats one transaction shape ten times — build a transaction,
run a `@firestore.transactional` body, `except Exception: if not
_lost_to_contention(exc): raise`, then a per-site contention answer — which is
where most of its 29 broad `except` suppressions come from (TD-53).

Forces:

- The authz surface has three tiers and 464 backend tests; a move that changes
  behaviour is unacceptable and must be provably a move.
- Tests patch module globals that are resolved *inside* the repo at call time:
  `_DB` (`fake_firestore.install`), the whole `firestore` module, `_now`,
  `_BIND_BACKOFF_S`, `_drop_user_to_demo_if_licensed`. A plain re-export facade
  would leave those patches pointing at a name nothing reads any more.
- The function-level call graph is acyclic, but a naive split by noun
  (users / licensing / leases / seats / sessions) creates four module cycles
  (users↔devices, licensing↔device lock, licensing↔invites,
  users→invites→licensing→users).

## Decision

1. First (separate PR) extract `_run_tx(body, *, on_contended)` and use it at
   the ten sites. `on_contended` is a value or a zero-argument callable (for
   the re-read answers); `bind_device_lock` keeps its own outer re-read loop
   and uses a marker mode. The helper looks up `firestore.transactional` at
   call time.
2. Then move the code into `backend/app/repo/`:

   ```
   repo/_base.py      _DB, db(), firestore binding, _now, _TX_ATTEMPTS,
                      _lost_to_contention, _run_tx, _CONTENDED,
                      shared pure helpers (_load_user, get_device, _seat_ref,
                      _invite_ref, get_license, find_user_by_email,
                      _mode_patch, _license_mode, _license_public,
                      _license_mirror_patch, _*_member_patch, _apply_patch,
                      _emails_match, _email_domain, _license_past_grace,
                      _seat_lease_live, _lease_clear_patch, _invite_public,
                      _delete_query_until_empty, _delete_refs)
   repo/users.py      get_or_create_user, list_users, set_user_status, …
   repo/config.py     resolve_user_config, effective_mode, set_user_config, …
   repo/licensing.py  mint, claims, invites, licence admin, institution queries
                      (split again under TD-64; see "Licensing split")
   repo/devlock.py    bind/check/revalidate_device_lock
   repo/leases.py     checkout/release_lease, sweep
   repo/seats.py      clear_device_lock, set_seat_enabled, revoke seat
   repo/reconcile.py  reconcile_institution_seats
   repo/devices.py    devices + nonces
   repo/account.py    user folder, terms, consents, erase
   repo/sessions.py   sessions and files
   ```

   Module edges run one way: users, devices → licensing → devlock, config;
   seats → licensing; leases, reconcile → config. Every module reads
   `_base.db()` and `_base.firestore` **by attribute**, never `from _base
   import db`, so `fake_firestore.install` still patches one place.
3. `firestore_repo.py` becomes a facade that re-exports every public name (and
   the private names tests read: `_BIND_ROUNDS`, `_CONTENDED`,
   `_auto_approved`, `_individual_member_patch`, `_is_admin_email`,
   `_public_claim_error`, `_seat_ref`, `_now`). Routers keep importing it.

## Options considered

### A: Keep the monolith; extract helpers only

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Cost | Small |
| Scalability | The file keeps growing with every licensing change |
| Team familiarity | Unchanged |

**Pros:** no test churn. **Cons:** navigation and review cost stay; licensing
changes keep landing in one 140 KB file.

### B: Package + facade (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium: one mechanical move, ~6 test patch targets updated |
| Cost | Two PRs |
| Scalability | New licensing code lands in a 500-line module |
| Team familiarity | Same call sites; `repo.X` unchanged |

**Pros:** domain modules small enough to review; acyclic by construction.
**Cons:** patches of internals must target the submodule.

### C: Repository classes per aggregate

| Dimension | Assessment |
|-----------|------------|
| Complexity | High |
| Cost | Rewrites every call site and most tests |
| Scalability | Good |
| Team familiarity | New pattern for this codebase |

**Cons:** too much churn on a three-tier authz surface for no behaviour gain.

## Trade-off analysis

B buys the readability of C at the cost of A. The only risk is behavioural
drift during the move, which is checkable mechanically: `ast.dump` of every
function body before and after must match, with only its module changing.

## Consequences

- Easier: reviewing licensing changes; finding the one place a rule lives.
- Harder: a name now has two bindings (the facade's and the submodule's), which
  the facade hides from tests; see "As built".
- Revisit: if `licensing.py` itself passes ~1,000 lines, split mint / claims /
  invites along the same acyclic edges. It was about 1,300 as built, and was
  split under TD-64 (see "Licensing split"); the largest licensing module is
  now `mint.py`, under 400 lines.
- A licensing change now lands in the module for its concern. A new repo
  module has to join `PACKAGE` in import order or the facade tests fail; a
  new licensing module also belongs in `LICENSING_IMPORTS` in
  `tests/test_repo_facade.py`.

## As built (2026-09-23)

Three departures from the decision above, each smaller than what it replaced:

- **`repo/user_config.py`, not `repo/config.py`.** Inside the package
  `from ..config import settings` and `from .config import …` would sit one
  dot apart and mean different modules.
- **`_base` holds only what two or more modules read.** Helpers with one
  consumer (`_license_public`, `_invite_ref`, `find_user_by_email`, the member
  patches, …) live in that consumer; `get_device` lives in `devices`. The
  edges are users → devices → licensing → devlock → user_config, with seats →
  licensing and leases / reconcile → user_config; no cycle.
- **No test was retargeted.** After the split a caller inside `licensing`
  holds its own binding of `claim_seat`, so `monkeypatch.setattr(repo,
  "claim_seat", …)` on a plain re-export would reach the router but not
  `ensure_entitlement` — and the same test file needs the router path
  elsewhere (`list_user_sessions`). Instead the facade's module class
  propagates an assignment to every package module holding the same object,
  and a module `__getattr__` reads through for names it does not list
  (`_DB`, `firestore`). That is the monolith's meaning of a patch, for the
  526 tests written against it. Production never assigns to the module.

Proof: an `ast.dump` of every top-level definition before and after is equal
for all 159, after undoing only `firestore` → `_base.firestore` and one
function-local `from . import audit` → `from .. import audit`.
`tests/test_repo_facade.py` pins the propagation, the one-way imports, the
facade's public names, and that only `_base` binds `firestore`.

## Licensing split (TD-64, 2026-09-24)

`repo/licensing.py` (1,298 lines) is cut along its call graph into seven
modules. Code moved unchanged; `licensing.py` now only re-exports the 44 names
it used to define, so `from app.repo.licensing import …` still works.

```
repo/claims.py             claim_seat, claim_individual_license,
                           _drop_superseded_demo, and what a claim writes or
                           answers: _license_mirror_patch, the two member
                           patches, _emails_match, _public_claim_error
repo/invites.py            invites (write, list, revoke, delete on revoke),
                           find_user_by_email
repo/mint.py               _write_license, ensure_demo_license, the two ops
                           mints, _attach_to_existing_holder, _license_public
repo/activation.py         activate_license (a typed key) and its two branches
repo/entitlement.py        ensure_entitlement, claim_pending_invite
repo/license_admin.py      list_licenses, update_license (renewal fan-out),
                           revoke_license, _drop_user_to_demo_if_licensed
repo/institution_admin.py  IT self-service: add member, roster, summary,
                           licences an address administers
```

Edges (all toward `_base` omitted): mint → claims, invites; activation →
claims, devlock, user_config; entitlement → claims, invites, mint;
license_admin → claims, invites, mint; institution_admin → claims, invites,
mint. Outside the licensing modules, users and devices → entitlement and
seats → license_admin, where they used to import `licensing`. Claims and
invites are leaves. Every module still reads `_base.db()` and
`_base.firestore` by attribute, and each is in the facade's `PACKAGE`, so a
patch through the facade still reaches every caller: `claim_seat` is bound in
claims, activation, entitlement, institution_admin and the re-export.

A helper with several consumers lives in a licensing module below all of them
(`_license_public` in mint, `find_user_by_email` in invites), not in `_base`:
there `_base.firestore` would have become `firestore` in its body, and the
move would no longer be textually exact.

Proof: `ast.dump` and the source text of all 44 definitions are equal before
and after, and every global each one reads (nested transaction bodies
included) resolves to the same object, or, for a moved function, to its new
definition. `tests/test_repo_facade.py` adds `LICENSING_IMPORTS`, the imports
each licensing module may make, and a test that `licensing.py` defines
nothing.

## Action items

1. [x] `_run_tx` at the ten sites; the ten `noqa: BLE001` go with it (TD-53).
2. [x] Move into `repo/` with the facade; AST-equality check (see As built).
3. [x] ~~Update `fake_firestore.install`, the emulator fixture, and the tests
       patching `_now`, `_BIND_BACKOFF_S`, `_drop_user_to_demo_if_licensed`.~~
       Not needed: the facade propagates patches (As built).
4. [x] Full pytest (532 with the facade tests), emulator tier (23),
       `test_gateway_parity`, `test_route_authz_matrix`.
5. [x] Split `licensing.py` (TD-64); see "Licensing split".
