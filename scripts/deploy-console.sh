#!/usr/bin/env bash
# Substitute console placeholders, deploy Hosting, always restore templates.
#
# Usage (from repo root or firebase-hosting/):
#   API_BASE_URL=https://your-gateway-host \
#   AUTH_DOMAIN=your-project.firebaseapp.com \
#   ./scripts/deploy-console.sh
#
# Optional: FIREBASE_PROJECT=indicvision-dic-app-auth
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOSTING="${ROOT}/firebase-hosting"
CFG="${HOSTING}/public/console/config.js"
JSON="${HOSTING}/firebase.json"

: "${API_BASE_URL:?set API_BASE_URL to the API Gateway origin (no trailing slash)}"
: "${AUTH_DOMAIN:?set AUTH_DOMAIN to the Firebase Auth domain, e.g. project.firebaseapp.com}"

PROJECT_FLAG=()
if [[ -n "${FIREBASE_PROJECT:-}" ]]; then
  PROJECT_FLAG=(--project "${FIREBASE_PROJECT}")
fi

restore() {
  # Always put placeholders back so a live hostname is never committed.
  if [[ -f "${CFG}.bak" ]]; then
    mv -f "${CFG}.bak" "${CFG}"
  fi
  if [[ -f "${JSON}.bak" ]]; then
    mv -f "${JSON}.bak" "${JSON}"
  fi
}
trap restore EXIT

cp "${CFG}" "${CFG}.bak"
cp "${JSON}" "${JSON}.bak"

# Portable in-place substitute (GNU and BSD sed differ on -i).
subst() {
  local file="$1" from="$2" to="$3"
  python3 - "$file" "$from" "$to" <<'PY'
import pathlib, sys
path, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text(encoding="utf-8")
if old not in text:
    raise SystemExit(f"{path}: placeholder {old!r} not found")
path.write_text(text.replace(old, new), encoding="utf-8")
PY
}

subst "${CFG}" "__API_BASE_URL__" "${API_BASE_URL}"
subst "${JSON}" "__API_ORIGIN__" "${API_BASE_URL}"
subst "${JSON}" "__AUTH_DOMAIN__" "${AUTH_DOMAIN}"

(
  cd "${HOSTING}"
  firebase deploy --only hosting "${PROJECT_FLAG[@]}"
)

echo "Hosting deploy finished; placeholders restored under firebase-hosting/."
