# ADR-010: Device binding per app — one phone per app, not one phone per account

**Status:** Proposed. Phase 0 (a lock mismatch is no longer stored) is built;
phases 1–3 are not.
**Date:** 2026-09-28
**Deciders:** product owner, backend owner

## Context

Material Testing became its own Android app on 2026-09-28
(`com.indicvision.semper.materialtesting`, material_testing ADR-009) and
shares this backend and Firebase project. Android scopes `ANDROID_ID` to the
signing key, so Semper and Material Testing on the same phone report two
different device ids (`and-{ANDROID_ID}`, `DeviceKeyManager`).

The backend binds an account to **one** device: `users.activeDeviceId`
(`repo/devices.py:88`), the licence or seat `deviceIdLock`, and `_may_bind`
(`repo/devlock.py:184`). A second app on a licensed phone is therefore a
second device:

1. Signing in to Material Testing is refused as already linked.
2. Worse, every request it sent went through `revalidate_device_lock`, which
   stored `mode: demo` on the account on a lock mismatch. Revalidation
   returns early for an account already on Demo, so the demotion stuck: the
   licensed phone was Demo too, while the console still showed the licence
   as live. That is what happened to the first account to try it
   (2026-09-28).

## Decision

**Phase 0 (built, this PR).** A lock mismatch is Demo for the mismatched
device's requests only; nothing is written (`repo/devlock.py:270`). A revoked
licence or a revoked/disabled seat still stores Demo, since that is the
account's state and not one device's. `check_device_lock` refuses both
(`_LOCK_REFUSED`, `repo/devlock.py:44`). The clear's mode re-stamp
(`_settle_holder`) stays, for accounts demoted before this change.

**Phases 1–3 (proposed).** Bind one device **per app**:

1. **App.** Both apps send `X-App-Id` (their `applicationId`). A request
   without it is Semper, so every build in the field keeps working.
2. **Backend.** `activeDeviceId`, the release hold and `deviceIdLock` become
   per-app slots (`activeDevices.{app}`, `releasedDevices.{app}`,
   `deviceIdLocks.{app}`), read with a fallback to today's single fields for
   the Semper slot; an unknown app id is refused. The self-service device
   change cooldown (`SELF_DEVICE_CHANGE_COOLDOWN_DAYS`) counts per app.
3. **Consoles and docs.** The operator and IT consoles show and clear each
   slot; the operating manual and `CLOUD_ARCHITECTURE_GCP.md` describe two
   devices per account, one per app.

## Options

| Option | Why not |
|--------|---------|
| Keep one device per account | Semper and Material Testing cannot both be used on one phone by one person, which is the normal case |
| Share one device id across both apps (same signing key, or an id both apps derive) | Undoes material_testing ADR-009's separate signing key, and a shared id is spoofable across apps |
| Trust the App Check `app_id` claim instead of a header | App Check is off by default (`APP_CHECK_MODE`) and not registered for Material Testing; the header works now and can be checked against the claim once it is |
| Raise the device limit to two, any apps | Lets one licence run on two phones of the same app |

## Trade-offs

- An account can be licensed on two phones at once, if each runs a different
  app. Accepted: the apps do different work, and each still has one phone.
- `X-App-Id` is a claim until App Check enforces it. A modified Semper build
  can call itself Material Testing and take that slot, which gives it no more
  than the one extra phone above.
- Phase 0 alone does not let Material Testing sign in; it stops that attempt
  from costing the licensed phone its licence.

## Consequences

- Phase 0 deploys with the backend and needs no app release.
- Accounts demoted before phase 0 stay Demo until an operator re-stamps
  `mode`/`plan` or clears the device.
- Phase 2 must migrate nothing: the Semper slot reads the existing fields.

## Action items

- [x] Phase 0: request-scoped mismatch (this PR); TD-137.
- [ ] Phase 1: `X-App-Id` in both apps; TD-138.
- [ ] Phase 2: per-app slots and cooldown in `repo/devlock.py`,
      `repo/devices.py`, `repo/seats.py`; TD-138.
- [ ] Phase 3: consoles, operating manual, architecture doc; TD-138.
- [ ] Register Material Testing for App Check (Play Integrity) and check the
      header against the token's `app_id`.
