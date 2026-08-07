#!/usr/bin/env bash
# Fetch recent http_access lines for alpha metering.
# Usage:
#   PROJECT_ID=... USER_UID=... ./scripts/meter_alpha_usage.sh
# Optional: FRESHNESS=2h LIMIT=500
set -euo pipefail
: "${PROJECT_ID:?set PROJECT_ID}"
: "${USER_UID:?set USER_UID}"
FRESHNESS="${FRESHNESS:-2h}"
LIMIT="${LIMIT:-500}"
FILTER='resource.type="cloud_run_revision" AND jsonPayload.event="http_access" AND jsonPayload.uid="'"${USER_UID}"'"'
gcloud logging read "${FILTER}" \
  --project="${PROJECT_ID}" \
  --freshness="${FRESHNESS}" \
  --limit="${LIMIT}" \
  --format='csv(timestamp,jsonPayload.opClass,jsonPayload.routeTemplate,jsonPayload.method,jsonPayload.status,jsonPayload.latencyMs,jsonPayload.fileCount,jsonPayload.frameCount)'
