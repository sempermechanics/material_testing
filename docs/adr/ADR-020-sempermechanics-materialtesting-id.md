# ADR-020: Material Testing is `com.sempermechanics.materialtesting`

**Status:** Accepted, built (Firebase app and `google-services.json` owed; not released)
**Date:** 2026-10-05
**Deciders:** app owner
**Amends:** [ADR-009](ADR-009-material-testing-app-identity.md) (its package; the rest stands)

## Context

[ADR-009](ADR-009-material-testing-app-identity.md) gave this fork its own
Android app, `com.indicvision.semper.materialtesting`, on Semper's Firebase
project and backend. semperdic-app then moved to `com.sempermechanics.semper`
and took "indic" out of its code
([ADR-019](ADR-019-sempermechanics-app-id.md)): the Kotlin package, the
`SemperApi*` client, the `SEMPER_*` build keys, the `semper_*` prefs files and
the `SemperDeviceKeyEc` Keystore alias. Its FORK_SYNC asks the fork to switch
ids in the same merge that brings that code in: an install still on the old id
would find the renamed prefs files and Keystore alias empty and lose sign-in,
settings and its device key.

## Decision

1. **App id.** `applicationId = "com.sempermechanics.materialtesting"`
   (`app/build.gradle.kts`). `namespace` and the Kotlin package follow the
   parent, `com.sempermechanics.semper`, so the two repos keep sharing source
   paths. The test APK is `com.sempermechanics.materialtesting.test`.
2. **Same release key, same backend.** The key ADR-009 made for Material
   Testing signs the new id too. `backend/app/apps.py` already maps
   `com.sempermechanics.materialtesting` to `materialtesting`, the slot the old
   id used, so device binding (ADR-010) and app-tagged backups (ADR-014) carry
   over; `assetlinks.json` already lists it (semperdic-app #333, deployed
   2026-10-05).
3. **A new Firebase Android app.** Registered in `indicvision-dic-app-auth`
   with the release and debug fingerprints the old one has, with App Check
   (Play Integrity). This repo's `app/google-services.json` gets its client
   beside Semper's; until it does, a local build needs a client for the new
   id in a copy of the file that is not committed.
4. **Rides the 2026-10-05 sync** (`sync/semperdic-dfc28e1`): the merge of
   semperdic-app `dfc28e13`, then the rename pass over this repo's own files.

## Options considered

- **Keep `com.indicvision.semper.materialtesting` and take the parent's code.**
  Rejected: an upgrade in place reads the renamed prefs and Keystore alias as
  empty (ADR-019), and the id would keep "indic" that the parent removed.
- **Keep the old id and hold the merge.** Rejected: every later sync would
  conflict over the whole tree.

## Consequences

- A new app: testers install it beside the old Material Testing build; backups
  restore into it (same `materialtesting` tag). Analyses never backed up stay in
  the old app.
- `ANDROID_ID` is scoped to the signing key, not the package, so with the same
  key the phone keeps its device id; the backend reads both ids as
  `materialtesting`, so it keeps its device slot.
- Persisted formats are the parent's: prefs files and keys, Intent extras,
  WorkManager names, `index.json` and `metadata.json` fields. This fork's own
  additions keep their names (`DicKeys.TEST_TYPE` and the other lab extras, the
  wizard Bundle's lab keys, the `test` object and `loadN` of metadata schema
  `indic.session.metadata/6`, the `lab_pdf` share kind).
- The old id's fallbacks (old ids in `apps.py` and `assetlinks.json`) stay
  until no build sends it: semperdic-app's TD-176.

## Action items

- [x] `applicationId`, test runner, docs (2026-10-05 sync).
- [ ] Register `com.sempermechanics.materialtesting` in Firebase with the
      release and debug fingerprints and App Check, and commit its client in
      `app/google-services.json`.
- [ ] Phone check: install beside the old build, sign in, restore a backup,
      open an App Link.
- [ ] Release it (`v1.2-beta.4` or later) and tell testers it is a new app.
