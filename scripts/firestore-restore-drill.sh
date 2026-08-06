#!/usr/bin/env bash
# Restore an export into a throwaway project and PROVE the data came back.
#
# This used to submit the import with --async and print "wait, then run the
# checks from the runbook" — so the drill proved only that the API accepted the
# job. The failure mode that matters is an import that succeeds and produces an
# empty or partial database, which is invisible without comparing against what
# was exported. This waits for the operation and then verifies against the
# manifest written beside the export.
set -euo pipefail

: "${RESTORE_DRILL_PROJECT:?set a non-production RESTORE_DRILL_PROJECT}"
: "${FIRESTORE_EXPORT_URI:?set the exact gs://... export URI}"
: "${PRODUCTION_PROJECT:?set PRODUCTION_PROJECT as a safety check}"

if [[ "${RESTORE_DRILL_PROJECT}" == "${PRODUCTION_PROJECT}" ]]; then
  echo "Refusing to import a drill into the production project" >&2
  exit 2
fi

started="$(date -u +%s)"
echo "Importing ${FIRESTORE_EXPORT_URI} into ${RESTORE_DRILL_PROJECT}..."

# Blocking, not --async: the exit status is the first thing worth checking, and
# an async submit gives us nothing to fail on.
gcloud firestore import "${FIRESTORE_EXPORT_URI}" \
  --project="${RESTORE_DRILL_PROJECT}"

elapsed=$(( $(date -u +%s) - started ))
echo "Import finished in ${elapsed}s. Verifying contents..."

manifest="$(mktemp)"
trap 'rm -f "${manifest}"' EXIT
if ! gsutil cp "${FIRESTORE_EXPORT_URI%/}/manifest.json" "${manifest}" 2>/dev/null; then
  echo "No manifest.json beside the export — cannot verify this restore." >&2
  echo "Exports taken before manifests were introduced can only be checked by hand;" >&2
  echo "re-run the drill against a newer export." >&2
  exit 3
fi

python "$(dirname "$0")/firestore_verify.py" \
  --project="${RESTORE_DRILL_PROJECT}" \
  --verify="${manifest}"

echo
echo "Restore drill PASSED for ${FIRESTORE_EXPORT_URI}"
echo "  target project: ${RESTORE_DRILL_PROJECT}"
echo "  import duration: ${elapsed}s   <-- this is the measured RTO"
echo "Record this run against docs/ops/PRODUCTION_READINESS_GATE.md."
