# ADR-026: A licence-wide fan-out is recorded on the licence and finished by a Cloud Task

**Status:** Accepted, built on #457, merged 2026-10-20 and deployed 2026-10-22 from `b940cc29` ([CHANGELOG](../ops/CHANGELOG.md)); the task rides the provisioning queue (`TASKS_QUEUE`)
**Date:** 2026-10-19
**Deciders:** backend owner
**Register:** TD-231

## Context

Two staff actions change a licence and then write every document that copies
it (`backend/app/repo/license_admin.py`):

- `revoke_license` writes the licence `revoked`, then drops every holder to
  Demo, revokes every seat, zeroes the counters and deletes the invites.
- `update_license` writes the new terms, then re-stamps the terms each holder
  carries (`_license_mirror_patch`), and clears the leases when the licence
  leaves floating seating.

The holders' copies are what the request path reads ([§20.6](../backend/CLOUD_ARCHITECTURE_GCP.md)),
so a call that dies after the licence write and before the fan-out leaves
holders licensed on a revoked licence, or on the old terms, until staff
repeat the action or reconciliation reports it. Neither call fits one
transaction: a roster can pass the 500 writes a transaction may hold.

`set_seat_enabled`, the third such write, touches one holder and was moved
into the seat's transaction (TD-231, `e61eb09`).

## Decision

1. **The licence records what is left to do, in the licence's own write.**
   The write that revokes the licence or changes its terms also writes
   `fanoutId` (a fresh id), `fanoutOps` (what the fan-out must do: `revoke`,
   `terms`, `leases`) and `fanoutAt`. A single-document write is atomic, so
   the licence never changes without its marker. A second action before the
   first finished adds its op to the ones already pending.
2. **The fan-out reads the licence as it now stands.** `finish_license_fanout`
   reads the licence, runs each pending op from the stored document (the
   revoke as before; the terms from `_license_mirror_patch` of the current
   terms; the lease clear only while the licence is not floating), and then
   clears the marker in a transaction, only if `fanoutId` is still the one it
   read. Every op is idempotent, so running one twice is harmless.
3. **A marker that changed under the fan-out means run again.** If another
   action wrote a new marker (or finished and cleared it) while this fan-out
   ran, the copies this one wrote may be older than the licence. The fan-out
   re-reads the licence and runs again, the ops it ran included, up to three
   passes. The last writer is then always the current licence's terms.
4. **The follow-up is scheduled before the licence is written.** Before the
   write, the call enqueues a Cloud Task `license-fanout-{fanoutId}` on the
   existing queue (`TASKS_QUEUE`), due `FANOUT_TASK_DELAY_S` (360 s, past the
   Cloud Run request timeout of 300 s) later. The task's callback,
   `POST /v1/tasks/license-fanout`, authenticated as the provisioning callback
   is (`tasks.tasks_caller`) and absent from the gateway, calls
   `finish_license_fanout`. In the ordinary case the call has already
   finished and the task finds nothing pending. Enqueueing first means no
   crash point leaves a written marker with no task behind it; a task that
   runs before its write (it cannot, short of a 6-minute request) finds
   nothing and returns.
5. **The call itself still runs the fan-out inline**, as it did, and answers
   with the result. Re-running the action remains a repair.
6. **A pending fan-out is visible.** Reconciliation reports the ops still
   pending (`fanoutPending`), and a task that finds work logs
   `license_fanout_finished_late`, the sign that a call died part-way.

## Options considered

1. **Marker on the licence plus a delayed task scheduled first (chosen).** One
   extra licence field set, one task per action, no new collection, no sweep
   job. The task can be lost only when the enqueue itself fails, which is
   logged (`license_fanout_enqueue_failed`); the call then still runs the
   fan-out inline, so a lost task needs a second fault to matter.
2. **Marker plus a scheduled sweep** (Cloud Scheduler calling a route that
   finishes every pending marker). Needs an index on `fanoutId`, a scheduler
   job and its IAM, and finishes only at the next tick.
3. **Finish from reconciliation.** Reconciliation is a read on demand, run by
   IT or staff, and only for institution licences: an individual licence
   would never be finished, and a GET that writes is a surprise.
4. **An outbox collection.** A new collection, rules entry, erasure path and
   index, for what one licence's own fields can hold.

## Consequences

- `licenses/{id}` may carry `fanoutId`, `fanoutOps`, `fanoutAt` while a
  fan-out is pending; `_license_public` does not answer them.
- A licence action costs one Cloud Task when the queue is configured
  (`settings.tasks_enabled`); without it (local, tests) there is no task and
  the inline run is the only one.
- The task uses the provisioning queue; its retry policy (Cloud Tasks retries
  a non-2xx) also retries a fan-out that fails.
- Tests: `backend/tests/test_license_fanout.py` kills each call between the
  licence write and the holder writes and shows the task completing it.
