#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT:?set GCP_PROJECT}"
: "${FIRESTORE_BACKUP_BUCKET:?set FIRESTORE_BACKUP_BUCKET (without gs://)}"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="gs://${FIRESTORE_BACKUP_BUCKET}/firestore/${GCP_PROJECT}/${stamp}"

database_json="$(gcloud firestore databases describe --project="${GCP_PROJECT}" --format=json)"
python -c 'import json,sys; d=json.load(sys.stdin); assert d.get("pointInTimeRecoveryEnablement") == "POINT_IN_TIME_RECOVERY_ENABLED", "PITR is not enabled"' <<<"${database_json}"

gcloud firestore export "${destination}" \
  --project="${GCP_PROJECT}"

echo "Export completed: ${destination}"
