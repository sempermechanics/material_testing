# ADR-033: A licence per app, explicit activation per phone, a device count instead of a cooldown, upgrade by issue

**Status:** Accepted 2026-10-29; in progress. Step 1 built: each app reads its own
licence off the user (`apps.licence_view`), with no change in behaviour. Every later
step is listed under Action items.
**Date:** 2026-10-29
**Deciders:** product owner
**Supersedes in part:** [ADR-007](ADR-007-licence-lifecycle.md) (one licence per person becomes one per person per app; conversion becomes issue then activate),
[ADR-010](ADR-010-device-binding-per-app.md) (one bound phone per app becomes N activated phones per licence; the self-service cooldown goes),
[ADR-014](ADR-014-session-app-tag.md) decision 4 (the analysis quota becomes per app)
**Amends:** [ADR-027](ADR-027-test-accounts.md) (QC test accounts run the activation model; CI accounts keep theirs)
**Keeps:** [ADR-024](ADR-024-account-anchored-entitlements.md) (nothing is typed or sold in the app), [ADR-023](ADR-023-signed-offline-entitlement.md), [ADR-030](ADR-030-semper-classroom-app-identity.md) (new per-app fields go through `apps.written_fields` / `read_field` while TD-330 is open)

## Context

A licence attaches to the account on the server (ADR-024) and binds itself to a phone
with no step by the user:

- `revalidate_device_lock` runs on every authenticated request. On an empty lock, the
  first registered phone to ask takes it (`backend/app/repo/devlock.py`, the `UNBOUND`
  branch).
- One licence covers both apps. The licence has no `app`; the user holds one
  `licenseId` and one set of mirrored terms; each app has its own `deviceIdLock*`
  field (ADR-010).
- The holder can move their licence to another phone themselves, at most once every
  `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (30) (`repo/seats.clear_device_lock`, self path).
- Moving from individual to institution converts in place and seats the holder
  without asking (`repo/upgrade.convert_to_institution`).

The product owner's decisions (2026-10-29):

1. **Binding is explicit.** A phone is licensed only after the user taps **Activate**
   on it. The licence still reaches the account by mint, roster add or web claim
   code, and nothing is typed in the app.
2. **A device count replaces the cooldown.** Each licence has `maxDevices` (default 1).
   - Every activated phone counts, whoever activated it.
   - Any other phone of the holder runs as Demo.
   - For an institution licence the count is a pool across the whole licence.
3. **A licence belongs to one app.** Existing licences are split into a Semper licence
   and a Semper Classroom licence with the same terms, each keeping its phone. The
   analysis quota counts per app.
4. **Upgrade is a new licence.** Staff issue it; the old licence works until the
   holder taps Activate on the new one, which revokes the old one in the same
   transaction.
5. **Who may deactivate follows the role.**
   - The holder: their own phones on their own licence.
   - IT: phones of members in their domain, on licences they administer.
   - Staff: anyone; staff may also add an activation.
6. **Licence changes keep the phone's files.** Demo → licensed unlocks at once, and
   nothing local is deleted on any change. A holder's activated phones sync their
   analyses through the existing backup and restore flow.

## Decision

### Data

- **Licence:** `licenses/{id}` gains `app` (`apps.stored_name`), `maxDevices`, a
  transactional `devicesActive` counter and `supersedes`.
  - A licence **without** `app` keeps today's lock code unchanged. The licence decides
    its model, so the backend ships before the migration.
  - Demo keys stay app-less and are not split.
- **Activations:** one document per activated phone,
  `licenses/{id}/activations/{deviceId}` with `{uid, app, status, activatedAt/By,
  deactivatedAt/By, model}`.
  - A deactivated phone is marked, not deleted, so it keeps its history.
  - Revalidation reads the licence, the activation and (institution) the seat in one
    batched get.
- **Per-app user mirror:** follows ADR-010's flat suffixed fields, not a map.
  - Semper's mirror is the unsuffixed fields every account has. Another app's is the
    same names suffixed (`apps.LICENCE_MIRROR_FIELDS`).
  - Readers go through `apps.licence_view(user, app)`; writers through
    `apps.licence_patch(patch, app)`.
  - An account with no `licenseApp` is from before the split, and its one licence
    holds for both apps until the migration stamps it.
- **Pending upgrade:** `pendingLicenseId[app]` on the user names an issued upgrade
  until it is activated or revoked.

### Behaviour

- **Activate:** `POST /v1/me/devices/activate`, signed by the phone, app from
  `X-App-Id`. One transaction checks:
  - the phone is registered to the account for the app;
  - the licence is live and the seat active;
  - the licence is not floating;
  - `devicesActive < maxDevices`, else `409 device_limit_reached`.

  The answer carries the fresh `/v1/config`, so locks lift without a restart.
- **Deactivate:** by role, each scope enforced in the repository, not only by route.
  - The holder: their own activations, from the app or the web account page with a
    second factor.
  - IT: `adminEmails` plus `domainLock`.
  - Staff: any; staff also add.

  An IT or staff deactivation also releases the phone's registration (with the 24 h
  hold); a holder's does not.
- **No cooldown.** The count is the limit.
- **Several phones per account per app may register.** Real accounts, as ADR-027
  already allows test accounts. Up to `MAX_PHONES_PER_APP`; the oldest phone that is
  not activated makes room.
- **Floating:** the lease is the activation. `maxSeats` is the pool, and only the phone
  that checked the lease out is licensed.
- **Upgrade:** `issue_upgrade` mints the new licence with `supersedes`.
  `activate(upgrade=True)` revokes the old licence, moves the app's mirror, seats the
  holder (institution), activates the phone and clears the pointer, all in one
  transaction.
- **Quota per app:** `count_user_sessions(uid, app)`; the Demo per-device cap stays.
- **Compatibility:** installed builds (Semper 1036, Classroom v1.4-beta.2) never tap
  Activate.
  - New builds send `X-Activation: explicit`.
  - While `IMPLICIT_ACTIVATION=legacy`, a request without it auto-activates the
    holder's first registered phone on a licence with an `app`, if a slot is free.
  - `off` ends that once adoption allows.

## Options considered

- **Keep one licence for both apps with a per-app device count:** rejected. The owner
  wants a licence, its terms and its count per app.
- **A per-app map on the user (`licences.{app}`):** rejected for the reasons ADR-010
  gave. Every single-field update, `DELETE_FIELD`, query and the test double would
  change shape, and it would sit outside the TD-330 helpers.
- **Mirror activated device ids onto the user (no extra read):** rejected. IT and pool
  deactivations would write members' user documents, and the licence read is needed
  for revoke anyway.
- **A version threshold instead of a capability header:** rejected. Debug, emulator
  and CI builds report versionCode 1, and the installed builds send no version.
- **Count phone changes, not active phones:** rejected by the owner in favour of a
  count of active phones, which reads like the vendors' activation limits.

## Trade-offs

- **Licence passing:** without a cooldown, a licence can be passed round a lab by
  deactivating one phone and activating another. The device count bounds it at N
  concurrent phones. Every activation and deactivation is audited, and churn is
  visible in phone history and the `event=activation` log.
- **Offline phones:** a phone deactivated while offline keeps its last config until it
  next reaches the backend, or its token's grace ends once TD-215 provisions signing.
- **Doubled Demo allowance:** a Demo account gets the Demo analysis allowance per app,
  so up to twice the stored analyses across both apps. The per-device cap still holds
  for each app.
- **User documents grow:** about 14 mirror fields per extra app, three names each while
  TD-330 is open.

## Consequences

- **Backend:** `apps.licence_view` / `licence_patch` / `LICENCE_MIRROR_FIELDS`;
  `user_config` takes `app`. Still to come: `repo/activations.py`,
  `repo/licence_upgrade.py`, `routers/activations.py`, a migration script that
  splits existing licences per app, an index override on
  `activations.uid`, and the gateway and contract snapshot.
- **App:** an Activate step, a phones screen with Deactivate, instant unlock, a sync
  toggle. Semper Classroom gets it by fork sync.
- **Consoles:** mint per app with `maxDevices`, per-phone Deactivate, staff Add phone,
  Issue upgrade, the institution pool, per-app account sections; the cooldown text
  goes.

## Action items

- [x] Step 1: each app reads its own licence (`apps.licence_view`), `user_config`
      takes `app`; no change in behaviour (`backend/tests/test_licence_view.py`).
- [ ] Several phones per account per app; `_device_is_bound`; the release hold for
      staff and IT releases only.
- [ ] The activation model, routes, `/v1/config` and `/v1/me` blocks, role-scoped
      deactivation, cooldown removal, floating lease device.
- [ ] A licence per app: one per person per app, per-app invites, mint `app` and
      `maxDevices`, per-app quota, issue and apply upgrade, QC test accounts on the
      activation model.
- [ ] Migration to split existing licences, dry run first, run by the owner.
- [ ] App and consoles; fork sync.
- [ ] Set `IMPLICIT_ACTIVATION=off` once adoption allows, then remove the legacy
      paths (TD-363).
