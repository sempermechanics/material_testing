#!/usr/bin/env bash
set -euo pipefail

: "${RESTORE_DRILL_PROJECT:?set a non-production RESTORE_DRILL_PROJECT}"
: "${FIRESTORE_EXPORT_URI:?set the exact gs://... export URI}"
: "${PRODUCTION_PROJECT:?set PRODUCTION_PROJECT as a safety check}"

if [[ "${RESTORE_DRILL_PROJECT}" == "${PRODUCTION_PROJECT}" ]]; then
  echo "Refusing to import a drill into the production project" >&2
  exit 2
fi

gcloud firestore import "${FIRESTORE_EXPORT_URI}" \
  --project="${RESTORE_DRILL_PROJECT}" \
  --async

echo "Restore drill submitted to ${RESTORE_DRILL_PROJECT}."
echo "Wait for completion, then run application smoke/read-count checks from the runbook."
