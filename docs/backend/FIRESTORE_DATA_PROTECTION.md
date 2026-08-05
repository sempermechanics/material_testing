# Firestore backup, PITR, retention, and restore drills

These commands mutate cloud resources and require an authorized operator. They
were not run while preparing this repository.

## One-time controls

1. Create a dedicated backup bucket in a second region or dual-region and
   enable uniform bucket-level access and object versioning.
2. Grant the Firestore service agent permission to write exports to the bucket.
   Grant the GitHub backup service account only the Firestore export and bucket
   permissions it needs; use Workload Identity Federation, not a JSON key.
3. Enable PITR and verify its state:

   `gcloud firestore databases update --database='(default)' --enable-pitr --project=PROJECT_ID`

   `gcloud firestore databases describe --database='(default)' --project=PROJECT_ID --format='value(pointInTimeRecoveryEnablement,earliestVersionTime)'`

4. Apply a reviewed bucket lifecycle policy retaining daily exports for the
   approved period (recommended baseline: delete after 35 days, with a separate
   monthly archive policy if regulation requires it). Example policy:

   `{"rule":[{"action":{"type":"Delete"},"condition":{"age":35,"matchesPrefix":["firestore/"]}}]}`

   Apply with `gcloud storage buckets update gs://BUCKET --lifecycle-file=FILE`.
   Retention duration is a business/legal decision; do not shorten it during an
   incident.
5. Configure the `production-backup` GitHub environment variables used by
   `.github/workflows/firestore-backup.yml`: `GCP_PROJECT`,
   `FIRESTORE_BACKUP_BUCKET`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, and
   `FIRESTORE_BACKUP_SERVICE_ACCOUNT`.

## Scheduled exports

The workflow runs daily at 02:17 UTC. `scripts/firestore-export.sh` refuses to
export unless PITR is enabled and waits for export completion. Alert on workflow
failure and review bucket objects weekly. API Gateway/in-process application
rate limits are unrelated to these Admin API operations.

## Quarterly restore drill

1. Select a completed export and a non-production recovery project.
2. Ensure its Firestore database/location and indexes are compatible.
3. Run:

   `PRODUCTION_PROJECT=prod RESTORE_DRILL_PROJECT=recovery FIRESTORE_EXPORT_URI=gs://... bash scripts/firestore-restore-drill.sh`

   The script refuses to target the named production project.
4. Wait for the import operation to complete. Compare collection/document
   counts, inspect `schemaVersion`, run backend smoke tests against recovery,
   and sample user/session/file relationships. Audit logs must be present.
5. Record recovery point, elapsed restore time, verification evidence, and any
   RPO/RTO gap. Delete the drill project under the approved cleanup process.

PITR complements exports; it does not replace independently retained exports or
restore drills.

## Account-erasure audit policy

Account erasure deletes users, devices, sessions, files, and Drive artifacts.
`audit_logs` is deliberately excluded so security and deletion events remain
available for incident response and proof of erasure. Audit records must not
contain uploaded content, filenames, specimen names, email addresses, tokens,
keys, or resumable URLs. They may retain the opaque uid/device id and event
metadata only. Access is server/admin-only under the deny-all client rules.
Legal/privacy owners must set and document the retention period and configure a
Firestore TTL field before production launch; backups inherit the same approved
retention/legal-hold policy.
