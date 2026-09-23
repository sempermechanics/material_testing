#!/usr/bin/env bash
# Substitute console placeholders, deploy Hosting, always restore templates.
#
# Usage (from repo root or firebase-hosting/):
#   API_BASE_URL=https://your-gateway-host ./scripts/deploy-console.sh
#
# There is no AUTH_DOMAIN: auth.js uses the page's own host as authDomain.
#
# Optional: FIREBASE_PROJECT=indicvision-dic-app-auth
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOSTING="${ROOT}/firebase-hosting"
CFG="${HOSTING}/public/console/config.js"
JSON="${HOSTING}/firebase.json"

: "${API_BASE_URL:?set API_BASE_URL to the API Gateway origin (no trailing slash)}"

# The first Python 3 that actually runs. On Windows `python3` (and often
# `python`) is the Microsoft Store alias: it exists on PATH but only prints an
# install hint, so probe by running each candidate rather than by lookup.
PYTHON=()
for candidate in "python3" "python" "py -3"; do
  read -r -a cmd <<< "${candidate}"
  if "${cmd[@]}" -c 'import sys; sys.exit(sys.version_info < (3,))' >/dev/null 2>&1; then
    PYTHON=("${cmd[@]}")
    break
  fi
done
if [[ ${#PYTHON[@]} -eq 0 ]]; then
  echo "No working Python 3 found (tried python3, python, py -3)." >&2
  exit 1
fi

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
  "${PYTHON[@]}" - "$file" "$from" "$to" <<'PY'
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

(
  cd "${HOSTING}"
  firebase deploy --only hosting "${PROJECT_FLAG[@]}"
)

echo "Hosting deploy finished; placeholders restored under firebase-hosting/."
