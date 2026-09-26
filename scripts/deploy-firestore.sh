#!/usr/bin/env bash
# Deploy the Firestore security rules and/or composite indexes.
#
# Usage (from anywhere; Git Bash on Windows):
#   ./scripts/deploy-firestore.sh            # rules and indexes
#   ./scripts/deploy-firestore.sh rules      # firestore.rules only
#   ./scripts/deploy-firestore.sh indexes    # backend/firestore.indexes.json only
#
# Optional: PROJECT=<id> (default indicvision-dic-app, the backend project that
# holds Firestore; not firebase-hosting/'s default, the Auth project).
#
# Needs a Firebase CLI running on Node >= 20. From PowerShell, `bash` may
# resolve to WSL (Node 12 there); run this from Git Bash instead.
#
# The Firebase CLI refuses any file outside the directory holding its
# firebase.json, and neither file lives under one, so both are staged in a
# scratch directory with a firebase.json of their own.
#
# The indexes file also declares the TTL policies (challenges.expireAt,
# deleted_licenses.purgeAt, deleted_seats.purgeAt) as fieldOverrides: the CLI
# counts each live TTL policy as a field override and refuses, in
# non-interactive mode, to leave one that the file omits. Never add --force
# here; it deletes every live override the file does not declare, TTL included.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${PROJECT:-indicvision-dic-app}"
case "${1:-all}" in
  rules) ONLY="firestore:rules" ;;
  indexes) ONLY="firestore:indexes" ;;
  all) ONLY="firestore" ;;
  *) echo "usage: $0 [rules|indexes]" >&2; exit 2 ;;
esac

# Every client read and write is refused; the backend's server SDK bypasses
# rules. Refuse to publish anything else by accident.
if ! grep -q 'allow read, write: if false;' "${ROOT}/firestore.rules"; then
  echo "firestore.rules is not deny-all; not deploying it" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT
cp "${ROOT}/firestore.rules" "${STAGE}/firestore.rules"
cp "${ROOT}/backend/firestore.indexes.json" "${STAGE}/firestore.indexes.json"
printf '{"firestore":{"rules":"firestore.rules","indexes":"firestore.indexes.json"}}\n' \
  > "${STAGE}/firebase.json"

echo "Deploying ${ONLY} to ${PROJECT}"
(cd "${STAGE}" && firebase deploy --only "${ONLY}" --project "${PROJECT}" --non-interactive)
