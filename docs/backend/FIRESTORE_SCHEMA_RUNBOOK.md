# Firestore schema migration and rollback

The application schema version is `backend/app/repo/_base.py::SCHEMA_VERSION`.
Every newly written server-owned document includes `schemaVersion`. Firestore is
schemaless, so migrations must tolerate mixed versions while they run.

## Optional fields that need no migration

Additive, server-owned fields on `users/{uid}` whose absence has a defined
meaning do not bump `SCHEMA_VERSION`:

| Field | Written by | Absent means |
|---|---|---|
| `termsAccepted: {version, acceptedAt, deviceId, source}` | `POST /v1/me/terms` | the Terms have never been accepted — the app shows the gate |
| `improvementConsent: {granted, version, at, deviceId, source}` | `PUT /v1/me/consents` | no consent given (same as `granted: false`) |

`source` is `app` when the request carried `X-Device-Id`, else `console`. Both
records are returned by `GET /v1/me/export` and deleted with the user document.

### `sessions/{sid}.app`, and its backfill

`app` (`semper`, or `materialtesting` for Semper Classroom until TD-56:
`apps.stored_name`, below) is written by `POST /v1/sessions` from
`X-App-Id` ([ADR-014](../adr/ADR-014-session-app-tag.md)). Absent means
`semper` (`repo/sessions.session_app`), so it needs no version bump either, but
a Semper Classroom session left untagged drops out of Semper Classroom's list
and restore. `backend/scripts/tag_session_apps.py` tags every session without
`app` from its device (`devices/{deviceId}.app`, Semper Classroom only when the
device says so, under the same stored name). A tag is compared by its name
today (`apps.canonical`, `apps.same_app`), so a `materialtesting` tag (below) is
Semper Classroom's. It is not a chain migration: it does not stamp
`schemaVersion` and keeps no ledger, because it is idempotent by construction
(a tagged session is skipped).

1. With Application Default Credentials that can read and write Firestore,
   dry-run: `python backend/scripts/tag_session_apps.py --project PROJECT_ID`.
   It prints the sessions scanned, already tagged, and how many would be tagged
   each way, plus those with no device document (read as `semper`).
2. Apply: the same command with `--apply`.
3. Deploy the account console (it asks `?app=all`), then the backend that
   writes and filters on the tag.
4. Run the dry-run again, then `--apply` for any session the old backend wrote
   between step 2 and its replacement. A second dry-run must report zero to tag.

Staging and production share one database, so there is one run.

### Material Testing → Semper Classroom (ADR-030)

[ADR-030](../adr/ADR-030-semper-classroom-app-identity.md) renamed the app's
short name `materialtesting` → `semperclassroom` and its field suffix
`MaterialTesting` → `SemperClassroom`. A backend from before the rename knows
only the old names, so until TD-56 they stay the stored truth: tags are
stored as `materialtesting` (`apps.stored_name`) and read as `semperclassroom`
(`apps.canonical`); per-app fields are read from the old field only
(`apps.read_field`) and written to both (`apps.written_fields`). So no
`schemaVersion` bump and no chain migration.
`backend/scripts/rename_classroom_app.py` makes each new field on `users/`,
`licenses/` and `licenses/{id}/seats/` equal to the old one: it copies
`<name>MaterialTesting` to `<name>SemperClassroom` where the new field is
absent or differs, and deletes a `<name>SemperClassroom` whose old twin is
absent (a previous backend deleted the old field and left the new one stale).
It never modifies or deletes an old field and never touches audit or event
rows. `--retag` also rewrites the `app` tag on `devices/` and `sessions/` to
`semperclassroom`; that is TD-56's first step, not part of the rollout.

Every run is the owner's: staging and production share one database.

1. After the backend is deployed, Hosting and the repo variables are set and
   Semper Classroom is released (ADR-030, Rollout order), dry-run:
   `python backend/scripts/rename_classroom_app.py --project PROJECT_ID`.
   It prints per-collection counts and lists any document whose new field
   differs from the old one or has no old one.
2. Apply, once the new backend serves all traffic and rolling back past it is
   ruled out: the same command with `--apply`. A dry-run must then report
   nothing to change.
3. TD-56 only, once no backend from before the rename can serve and no build
   sends the old id: `--apply` again, then `--apply --retag`, before reads and
   tags move to the new names.

### A licence per app: the split (ADR-033)

[ADR-033](../adr/ADR-033-licence-per-app-explicit-activation.md) gives each app
its own licence. A licence from before it names no `app`, holds for both apps
and locks one phone per app; the backend runs whichever model the licence
names, so both kinds work side by side and there is no `schemaVersion` bump.
`backend/scripts/split_licences_per_app.py` moves every licence to the new
model (CLOUD_ARCHITECTURE_GCP §20.19):

- the licence is stamped `app: semper`, with `devicesActive`, `maxDevices` and
  `splitInto`; its locks stay, so a backend from before ADR-033 still reads it;
- a Semper Classroom twin is minted with the same terms, a fresh key nobody is
  shown, and `splitFrom`; an institution's roster (not a revoked seat, not a
  lease) is copied to it;
- each locked phone is activated on the licence of its app
  (`activatedBy: migration`), so nobody taps Activate;
- each holder's account gets `licenseApp: semper` and the twin in Semper
  Classroom's mirror; each pending or held invite is copied under Semper
  Classroom's key;
- one `LICENSE_SPLIT` audit row per split (`audit_logs/licence-split-{id}`).

Left alone: a licence that names its app, a Demo key, a CI test account's
licence (Tier 3 rotates emulators through one licence for every app,
ADR-027), and a deleted one (restored, it is app-less again, and the next run
splits it). A revoked licence is only stamped `app: semper`. A holder who
already holds a live Semper Classroom licence keeps it: an individual licence
gets no twin, an institution member is left off the twin's roster, and the
run lists each case under `conflicts`, with any invite whose Semper Classroom
key already promises another live licence.

It resumes and re-runs safely: the twin's id is reserved on the licence
(`splitPending`) before anything is written, the twin is built before any
account or invite points at it, every later write is skipped where it is
already in place, and the final stamp is conditional on the licence being
unchanged (a licence that changed is read again and redone). A phone a legacy
build binds while its licence is being split can be missed; its holder taps
Activate, or the legacy build activates it itself (`IMPLICIT_ACTIVATION=legacy`).

Every run is the owner's: staging and production share one database.

1. After the backend with ADR-033 step 5 serves all traffic, dry-run:
   `python backend/scripts/split_licences_per_app.py --project PROJECT_ID`.
   It prints the licences to split and why others are left, the twins, seats,
   activations, accounts and invites it would write, and every conflict.
2. Rehearse on the QC domain:
   `... --only-domain qa.sempermechanics.com --apply`, then check a QC
   holder's phones on both apps and the twin in the staff console.
3. At a quiet hour, apply everything: the same command with `--apply` and no
   `--only-domain`. A dry run must then report nothing to split.
4. Resolve each conflict by hand: a holder who keeps their own Semper
   Classroom licence needs nothing; an invite conflict needs staff to decide
   which licence the address gets.

Rollback: a backend from before ADR-033 reads the original licence, both of
its locks and the unsuffixed mirror, and ignores the twins; nothing to undo.

## How migrations are defined

Migrations live in `backend/scripts/migrations/` as `NNN_slug.py`, each exporting:

| Name | Meaning |
|---|---|
| `from_version` | version the migration upgrades from |
| `to_version` | version documents carry afterwards (must be `from_version + 1`) |
| `collections` | collections to walk |
| `transform(doc) -> dict \| None` | field updates, or `None` to skip this document |

`transform` never writes `schemaVersion` — the runner stamps it. The chain is
validated on load: a gap, a duplicate target, or a jump of more than one version
is a hard error, because a partial chain applied in order strands documents at an
intermediate version.

Applied migrations are recorded in the `_migrations` collection, one document per
migration name, with `startedAt`/`completedAt` and scanned/changed counts.
`completedAt` is written only after every collection succeeded, so a run
interrupted halfway is retried rather than assumed done.

### Adding one

1. Create `backend/scripts/migrations/00N_what_it_does.py` with
   `from_version = SCHEMA_VERSION` (the current value) and `to_version = N`.
2. Bump `SCHEMA_VERSION` in `backend/app/repo/_base.py` to match. The runner
   refuses to start if the code version and the end of the chain disagree —
   otherwise the app would write documents at a version no migration produces.
3. `pytest tests/test_migrations.py` covers chain validity automatically.

## Running one

1. Complete and verify a named Firestore export (see `FIRESTORE_DATA_PROTECTION.md`).
2. Deploy code that can read both the old and target versions.
3. With Application Default Credentials that can read Firestore, dry-run:
   `python backend/scripts/migrate_schema.py --project PROJECT_ID`.
4. Review per-collection counts, then run:
   `python backend/scripts/migrate_schema.py --project PROJECT_ID --apply`.
5. Run the dry-run again; it must report zero changes and list the migration as
   already applied. Verify representative user, device, session, file, challenge,
   audit, **license and seat** documents. License documents entered the chain
   only at migration 002 — 001's collection list omits them — so an environment
   whose last migration is 001 has never had them walked.
6. Only then deploy code that requires the new version.

The runner reads and writes in pages of 400, is idempotent at both the migration
and document level, and never changes a document already at or above the target
version — so an interrupted run resumes instead of restarting.

## Rollback

Do not write synthetic SQL-style "down migrations." Firestore has no atomic
database-wide rollback, and reverse field transforms can destroy data.

1. Stop writes or route traffic to maintenance mode.
2. Roll back the application to the last version compatible with the export.
3. Restore the pre-migration export to a dedicated recovery project and verify
   counts and application reads.
4. For a production replacement, follow the incident change process and import
   the verified export into the intended database. Record the export URI,
   operation IDs, approvers, and validation results.
5. Re-enable traffic only after integrity checks pass.
