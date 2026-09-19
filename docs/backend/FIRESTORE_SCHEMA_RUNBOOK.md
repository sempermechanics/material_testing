# Firestore schema migration and rollback

The application schema version is `backend/app/firestore_repo.py::SCHEMA_VERSION`.
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
2. Bump `SCHEMA_VERSION` in `backend/app/firestore_repo.py` to match. The runner
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
