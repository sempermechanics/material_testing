#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT:?set GCP_PROJECT}"
: "${FIRESTORE_BACKUP_BUCKET:?set FIRESTORE_BACKUP_BUCKET (without gs://)}"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="gs://${FIRESTORE_BACKUP_BUCKET}/firestore/${GCP_PROJECT}/${stamp}"

database_json="$(gcloud firestore databases describe --project="${GCP_PROJECT}" --format=json)"
python -c 'import json,sys; d=json.load(sys.stdin); assert d.get("pointInTimeRecoveryEnablement") == "POINT_IN_TIME_RECOVERY_ENABLED", "PITR is not enabled"' <<<"${database_json}"

# The challenges.expireAt TTL policy is a field policy, not an index, so it is
# not covered by `firebase deploy --only firestore:indexes` and can silently be
# missing after a project rebuild. Without it, consumed and expired nonce
# documents accumulate forever. Checked here because this is the one job that
# already talks to the Firestore admin API every day.
ttl_json="$(gcloud firestore fields ttls list \
  --collection-group=challenges --project="${GCP_PROJECT}" --format=json)"
python -c 'import json,sys; f=json.load(sys.stdin); assert any(x.get("ttlConfig",{}).get("state")=="ACTIVE" and x.get("name","").endswith("/expireAt") for x in f), "challenges.expireAt has no ACTIVE TTL policy; fix with: gcloud firestore fields ttls update expireAt --collection-group=challenges --enable-ttl"' <<<"${ttl_json}"

gcloud firestore export "${destination}" \
  --project="${GCP_PROJECT}"

# Record what was in the database at export time. The restore drill compares
# against this; without it a drill can only prove the import API accepted the
# job, not that the data came back. Stored beside the export so the manifest and
# the data it describes cannot drift apart.
manifest="$(mktemp)"
trap 'rm -f "${manifest}"' EXIT
python "$(dirname "$0")/firestore_verify.py" --project="${GCP_PROJECT}" --emit="${manifest}"
gsutil cp "${manifest}" "${destination}/manifest.json"

echo "Export completed: ${destination}"
echo "Manifest: ${destination}/manifest.json"
