# ADR-030: Material Testing becomes Semper Classroom: app id, short name and field suffix

**Status:** Accepted, in progress (backend code written; nothing merged, deployed or released)
**Date:** 2026-10-26
**Deciders:** app owner
**Relates to:** [ADR-010](ADR-010-device-binding-per-app.md) (one phone per app),
[ADR-014](ADR-014-session-app-tag.md) (sessions tagged by app),
[ADR-019](ADR-019-sempermechanics-app-id.md) (the last app id move),
[ADR-023](ADR-023-signed-offline-entitlement.md) (the token's `app` claim),
[FORK_SYNC](../ops/FORK_SYNC.md)

## Context

The material_testing fork ships the app "Material Testing"
(`com.sempermechanics.materialtesting`, ADR-019). The owner renames it **Semper
Classroom** everywhere: the app name, its id, the name the backend stores for it,
and the repo.

The old name is not only a label. It is stored:

- the short name `materialtesting` on `devices/{id}.app` (ADR-010) and
  `sessions/{sid}.app` (ADR-014), in the consoles' `?app=` values, and in the
  `app` claim of the signed entitlement (ADR-023), which a build checks against
  the name it pins (`EntitlementApp.shortId`,
  `data/account/entitlement/EntitlementPins.kt:70-81`);
- the field suffix `MaterialTesting` on every per-app field of users, licences and
  seats: `activeDeviceIdMaterialTesting`, `deviceIdLockMaterialTesting`,
  `deviceChangedAtMaterialTesting`, `previousDeviceIdMaterialTesting`,
  `releasedDeviceIdMaterialTesting`, `releasedAtMaterialTesting`;
- the release gate variables `MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING` /
  `RECOMMENDED_VERSION_CODE_MATERIAL_TESTING` (`settings.release_gate(app)`,
  `backend/app/config.py`).

An Android `applicationId` cannot change in place: a new id is a new app, with a
new Firebase Android app, new App Links entries and empty on-device storage
(ADR-019, Context). Installed builds keep sending the old id, pinning the old
short name and reading the old fields for as long as testers keep them.

A backend from before the rename knows only the old names, and one can serve
beside the new one while a deploy shifts traffic, or again after a rollback.

## Decision

- **App id** `com.sempermechanics.semperclassroom`. The namespace and Kotlin
  package stay `com.sempermechanics.semper` (shared code, ADR-019). It is signed
  with the same release key as Material Testing (alias `materialtesting`), so its
  SHA-256 fingerprints are the same. It is a new Firebase Android app in
  `indicvision-dic-app-auth`, with App Check (Play Integrity) registered, and
  `assetlinks.json` lists the new package with the same fingerprints.
- **Backend short name** `semperclassroom` (was `materialtesting`) and **field
  suffix** `SemperClassroom` (was `MaterialTesting`), in `backend/app/apps.py`.
  Until TD-330 the old names stay the stored truth, because every backend,
  this one and one from before the rename, reads and writes them:
  - **Tags are stored under the old name.** `apps.stored_name` gives
    `materialtesting` for Semper Classroom, and it is what `devices/{id}.app`
    (`backend/app/repo/devices.py:171`) and `sessions/{sid}.app`
    (`backend/app/repo/sessions.py:537`) are written with, as
    `backend/scripts/tag_session_apps.py` does. A stored or sent name is read as the app
    it is today by `apps.canonical` (`_LEGACY_NAMES`), compared through
    `apps.same_app`, never with `==`, and answered as `semperclassroom`.
  - **`apps.read_field` reads the old field only.** Every backend writes and
    deletes it, so it is always current. The new field is not read: a backend
    from before the rename that deletes the old field leaves the new one
    behind, stale.
  - **`apps.written_fields` writes both**, the new field and the old one as a
    mirror, the way migration `002_rename_campus_to_institution` kept `plan`
    beside `mode`. A Firestore sentinel (`DELETE_FIELD`, `SERVER_TIMESTAMP`)
    goes to both. So the new fields fill as accounts change, ready for TD-330
    to read.
  - **Responses and audit details spread both keys** (`apps.spread`, through
    `written_fields`), so a console from before the rename still finds
    `releasedDeviceIdMaterialTesting`.
  - **The roster CSV** keeps the column `deviceIdLockMaterialTesting`
    (`roster_io.ROSTER_COLUMNS`, from `apps.legacy_field`), and **the account
    console** names the app `?app=materialtesting` on `/v1/licenses/unbind`
    (`console/account/account.js`), which old and new backends both accept
    (`apps.from_name`).
- **The entitlement's `app` claim is per build** (`apps.token_claim`,
  `_TOKEN_CLAIM_BY_APPLICATION_ID`). The old ids get `materialtesting`, the new
  id `semperclassroom`: a build refuses any other claim as `WRONG_APP`
  (`data/account/entitlement/EntitlementVerifier.kt:88`), so a build from before
  the rename would lose its offline entitlement if it were sent the new name.
- **`com.sempermechanics.materialtesting` joins `apps.RETIRED`.** Semper
  Classroom is signed with its key, so Android gives both the same `ANDROID_ID`
  and the same device id; the old app registering beside the new one would
  replace its key on `devices/{id}`, and every signed call from the new app
  would fail `bad_signature`, as on 2026-10-10 with the `com.indicvision.*` ids
  (TD-209). A retired id is refused only at `POST /v1/devices/register`
  (`410 app_retired`); its other calls are served as before, and the access log
  names it (`apps.logged_application_id`), so the log shows when no build sends
  it any more.
- **Release gate.** The backend reads `MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM`
  / `RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM` for Semper Classroom builds and,
  where one is unset, the `*_MATERIAL_TESTING` variable it replaces
  (`_env_int_renamed`, `backend/app/config.py:18`, used at `:321-326`), so until
  the new variables are set the values set for Material Testing builds gate
  Semper Classroom's too.
- **Script** `backend/scripts/rename_classroom_app.py`: a dry run by default,
  `--apply` to write. On users, licences and seats it makes each
  `*SemperClassroom` field equal to its `*MaterialTesting` twin: it copies the
  old value where the new field is absent or differs, and deletes a new field
  whose old twin is absent (the prune). Documents whose new field differed or
  was deleted are counted and listed. The old fields are never modified or
  deleted, and audit and event rows are never touched. `--retag` also rewrites
  the `app` tag on `devices/` and `sessions/` from `materialtesting` to
  `semperclassroom`; it is TD-330's first step, not this rename's. The backend
  is correct without the script; it makes the new fields complete, so that
  TD-330 can move reads to them. Re-running it is a no-op.
- **Repo.** `sempermechanics/material_testing` is renamed
  `sempermechanics/semper_classroom` (an owner step on GitHub, which redirects the
  old URL, so remotes and links keep working).
- **What keeps the old name, and why:**
  - audit and event rows: they record what happened, under the name of the time,
    and are never rewritten;
  - the stored tags, the old fields as the read truth and the mirror, the roster
    column, the console's `?app=materialtesting`, the `materialtesting` alias,
    the old ids and the `*_MATERIAL_TESTING` fallback, until TD-330 removes them;
  - history: the CHANGELOG, dated checklists and earlier ADRs keep the names they
    were decided under.

### Review findings

- **H1. A previous backend leaves the new field stale.** A backend from before
  the rename, serving during a deploy shift or after a rollback, deletes only
  `*MaterialTesting` (a device release, a lock clear) and leaves
  `*SemperClassroom` holding the old value. Reading the new field first, or at
  all, would read that stale value and bind or refuse the wrong phone. Handled:
  `read_field` reads only the old field until TD-330, and the script's prune
  deletes a new field whose old twin is gone.
- **H2. A previous backend does not know `semperclassroom`.** A device tagged
  `semperclassroom` reads there as an unknown app (`bad_signature` on every
  signed call from that phone), and a session so tagged is hidden from its list.
  Handled: tags are stored as `materialtesting` (`stored_name`) and turned into
  `semperclassroom` only on read, compare and response; the retag moved behind
  `--retag`, as TD-330's first step.

## Options considered

| Option | Why not |
|--------|---------|
| Keep `materialtesting` as an internal tag and rename only what users see (ADR-019 kept `indic.*` wire formats this way) | Rejected by the owner, who wants the name gone from stored data. Unlike a wire format, the tag and the suffix can be rewritten by a script, and the alias makes that safe to do late (TD-330). |
| A hard cutover: rename the fields and the tag in one migration, no dual read | Old builds and unmigrated accounts break. Between the deploy and the script, a backend that reads only the new field sees an empty slot and binds a second phone; a backend instance still on the old code, during a rollout or a rollback, misses every change; a console from before the rename shows every Semper Classroom device as missing; and a build pinning `materialtesting` refuses a token that names `semperclassroom`. |
| Read the new field, with the old one as a fallback, and store `semperclassroom` at once | A previous backend serving beside this one leaves the new field stale (H1) and reads the new tag as an unknown app (H2), so a mixed or rolled-back fleet disagrees. |
| A new suffix with no copy (new writes only) | When TD-330 moves reads to the new field, every account not written since the deploy would read it empty, which is the hard cutover for reads, and one a previous backend touched would read it stale. The script makes the new fields equal the old ones before that. |

## Trade-offs

- Every per-app write for Semper Classroom is two fields until TD-330. It is the
  same Firestore write, so it costs no extra operation, only a few bytes.
- The guarantee is that every backend, before and after the rename, reads and
  writes the old names, so a mixed or rolled-back fleet stays consistent. It is
  not that the new fields are current: a previous backend can leave one stale
  (H1), and only the script, run once no previous backend can serve, makes them
  current. So nothing reads them until TD-330, and every Semper Classroom write
  goes through `apps.written_fields`, not a bare `apps.field` key, so the old
  field is never left behind.
- The header is still a claim until App Check enforces the token's `app_id`
  (ADR-010, Trade-offs). Nothing here changes that.

## Rollout order

Each step is outward-facing; the owner approves each one. Staging shares
production Firestore, so every run of the script is the owner's, and one run
covers both.

1. Merge the backend and docs change here; sync the fork.
2. Deploy the backend. It stores and reads the old names and writes both
   fields, so it is correct beside a previous backend and before and after the
   script.
3. Deploy Hosting (`assetlinks.json` and the consoles' Semper Classroom label)
   with `API_BASE_URL=<INDIC_API_BASE_URL repo variable> ./scripts/deploy-console.sh`
   from a clean `main`; it runs `scripts/check_live_console.py`.
4. Set the new repo variables `MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM` and
   `RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM` to the current
   `*_MATERIAL_TESTING` values and redeploy the backend (or leave them unset
   while the fallback reads the old ones; `deploy-backend.yml` passes both).
5. Release Semper Classroom from the fork (`com.sempermechanics.semperclassroom`,
   with its own `google-services.json` client).
6. Run `backend/scripts/rename_classroom_app.py` as a dry run, then with
   `--apply`, once the new backend serves all traffic and rolling back past it
   is ruled out.

`--retag`, and moving reads and tags to the new names, belong to TD-330, not to
this rollout.

## Consequences

- Testers install Semper Classroom beside Material Testing: it is a new app, not
  an upgrade. Signing in takes the same account; the account's Semper Classroom
  slot is the one Material Testing held, so the phone is already its device.
  Material Testing on the same phone can no longer register a new device key
  (`410 app_retired`).
- Backups restore into the new app: sessions tagged `materialtesting`, the name
  every Semper Classroom session is still stored under, are listed for it
  (`same_app`) and answered as `semperclassroom`. On-device analyses that were
  never backed up stay in the old app.
- Old builds keep working (sign-in, backup, restore, entitlement) except
  registering a new device, which the retired id refuses. On a phone with both
  apps, Semper Classroom's registration replaces the key on `devices/{id}`, so
  Material Testing's signed calls then fail `bad_signature` and it cannot
  register again: back up in Material Testing before installing Semper Classroom.
- A console from before the rename keeps working on the mirrored fields; the
  consoles deployed in step 3 label the app Semper Classroom and still send
  `?app=materialtesting`. The backend accepts `?app=semperclassroom` too
  (`apps.from_name`).
- TD-330 retags, moves reads and tags to the new names, and then removes the
  alias, mirrors and fallbacks, once no build sends
  `com.sempermechanics.materialtesting` and no previous backend can serve.

## Action items

- [x] `backend/app/apps.py`: short name, suffix, `canonical` / `same_app`,
      `stored_name`, `read_field`, `written_fields`, `spread`, `token_claim`, the
      new id and the retired one.
- [ ] Backend callers, consoles and `assetlinks.json` on the new names; release
      gate variables with the old fallback; tests.
- [ ] `backend/scripts/rename_classroom_app.py` (with `--retag` and the prune)
      and its tests.
- [ ] Register `com.sempermechanics.semperclassroom` in Firebase
      (`indicvision-dic-app-auth`) with the release and debug fingerprints, and
      App Check (Play Integrity) for it.
- [ ] Merge; deploy the backend; deploy Hosting and run `check_live_console.py`;
      set the repo variables.
- [ ] The fork: `applicationId`, app name, `google-services.json`, and the
      entitlement pin `semperclassroom` for the new id
      (`EntitlementPins.kt:72-78`); release it.
- [ ] Dry-run, then `--apply`, the script once the new backend serves all
      traffic and rollback past it is ruled out (owner).
- [ ] Rename the GitHub repo `material_testing` to `semper_classroom` (owner).
- [ ] TD-330: retag, move reads and tags to the new names, then remove the
      alias, the mirrors and the fallbacks.
