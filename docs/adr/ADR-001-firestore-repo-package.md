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
- Harder: a test that patches an internal now names the submodule.
- Revisit: if `licensing.py` itself passes ~1,000 lines, split mint / claims /
  invites along the same acyclic edges.

## Action items

1. [ ] `_run_tx` at the ten sites; the ten `noqa: BLE001` go with it (TD-53).
2. [ ] Move into `repo/` with the facade; AST-equality script in the PR.
3. [ ] Update `fake_firestore.install`, the emulator fixture, and the tests
       patching `_now`, `_BIND_BACKOFF_S`, `_drop_user_to_demo_if_licensed`.
4. [ ] Full pytest, emulator tier, `test_gateway_parity`, `test_route_authz_matrix`.
