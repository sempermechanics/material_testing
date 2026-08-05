# Firestore schema migration and rollback

The application schema version is `backend/app/firestore_repo.py::SCHEMA_VERSION`.
Every newly written server-owned document includes `schemaVersion`. Firestore is
schemaless, so migrations must tolerate mixed versions while they run.

## Migration

1. Complete and verify a named Firestore export (see `FIRESTORE_DATA_PROTECTION.md`).
2. Deploy code that can read both the old and target versions.
3. With Application Default Credentials that can read Firestore, dry-run:
   `python backend/scripts/migrate_schema.py --project PROJECT_ID`.
4. Review per-collection counts, then run:
   `python backend/scripts/migrate_schema.py --project PROJECT_ID --apply`.
5. Run the dry-run again; it must report zero changes. Verify representative
   user, device, session, file, challenge, and audit documents.
6. Only then deploy code that requires the new version.

The script reads and writes in pages of 400, is idempotent, and never changes a
document already at or above the target version.

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
