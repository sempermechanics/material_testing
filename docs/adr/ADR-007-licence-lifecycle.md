# ADR-007: Licence lifecycle — one per person, replace by revoke, delete into a hold

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** product owner, backend owner

## Context

The operator desk could mint and revoke a licence and extend its term, and
nothing else. Three gaps came up together (2026-09-25):

1. **A person could hold two licences.** Every grant path granted anyway: the
   staff mint reported `holder_already_licensed` / `invite_exists` after
   writing, and a typed key or an IT roster add moved the account onto the
   new licence while the old one stayed `redeemed` in their name.
2. **No upgrade.** An individual customer who became an institution needed a
   new key and a manual revoke. Seating, IT contacts, shortening and
   perpetual↔timed could not be changed.
3. **No delete.** Revoked licences stay forever as the record, and the desk
   fills with them.

The product decision: a person (an address, with a device that can be
changed) holds **one** licence; terms, seating and individual→institution are
changed in place or by conversion; a delete is a hard delete with the data
held 30 days.

## Decision

- **One licence per person.** `repo/holders.licence_held_by` is asked at every
  grant point before any write: staff mint (`409 email_already_licensed`),
  a typed key (`already_licensed`), IT adding a member
  (`member_already_licensed`). An address holds a licence through its account,
  an `emailLock`, a roster seat or a pending invite. Only a *live* licence
  counts: revoked, past grace, and the system Demo key do not.
- **A replaced licence is revoked, not a new status.** Conversion to an
  institution revokes the individual licence and sets `supersededBy`. Every
  revoked-licence path (app, reconcile, both consoles, the one-licence check)
  handles it unchanged.
- **Delete is revoke, then a tombstone with a TTL.** The licence and its seats
  are copied to `deleted_licenses/{id}` and `…/deleted_seats/{uid}` with
  `purgeAt` = now + 30 days before anything is removed. Firestore TTL policies
  on `purgeAt` remove them. Restore checks the date itself because TTL runs up
  to a day late.
- **Holders are detached on delete.** Their `licenseId` and mirrored terms are
  removed, so the next request issues a fresh Demo key. Restore re-attaches
  each holder unless they have since taken another live licence.

## Options considered

### A: Revoke + `supersededBy`; tombstone collection + TTL (chosen)

Reuses existing handling. The live `licenses` collection holds only licences
that exist, so the list query, the one-licence check and redemption need no
"deleted" filter.

### B: New `superseded` / `deleted` statuses on the licence document

Every reader of `status` in the backend (some 37 checks against
`STATUS_REVOKED` / `"revoked"`) would have to learn both, and a key typed in
the shipped app would need a new error code beside `license_revoked`, which it
branches on. A deleted licence would still be in `licenses`
and count against list pages and the one-licence rule unless every query
excluded it.

### C: Scheduled purge job instead of TTL

A Cloud Scheduler job plus a route to run it: more IAM and one more thing that
can stop without anyone noticing. TTL is already used for `challenges`.

## Trade-off analysis

A is the smallest change to what reads licences. Its costs are two TTL
policies to set up by hand per project, and a restore that cannot bring back
leases or pending roster invites (both are short-lived, and re-created by the
member or IT).

## Consequences

- A deleted key redeems as `license_not_found`, and its address can be
  licensed again at once.
- The audit log (`ADMIN_LICENSE_DELETE`, `ADMIN_LICENSE_RESTORE`,
  `ADMIN_LICENSE_CONVERT`) outlives the purge and is the permanent record.
- Without the TTL policies nothing is purged. Restore still refuses a licence
  past `purgeAt`, so the only effect is data kept longer than promised.
- The restore drill counts `deleted_licenses` and `deleted_seats` and purges
  them.

## Action items

- [x] `repo/holders.py`, `repo/upgrade.py`, `repo/deletion.py`, routes and
      gateway spec.
- [x] Create the TTL policies on `deleted_licenses.purgeAt` and
      `deleted_seats.purgeAt` (BACKEND_SETUP_CONSOLE.md §3a). Staging and
      production share `indicvision-dic-app`'s default database, so one pair
      serves both; both `ACTIVE` on 2026-09-26.
- [ ] Run `backend/scripts/find_duplicate_licences.py` against production and
      resolve anyone holding two live licences from before the rule.
