#!/usr/bin/env bash
# Create the CI Firebase test account and store it as the two repo secrets
# FirebaseAuthIntegrationTest reads on Tier 3 (TD-188).
#
# The password is generated here (32 letters and digits: no comma, which Gradle
# would cut an instrumentation argument at, TD-106), sent to Firebase and to
# GitHub over stdin, and never printed. Run it again with --rotate to give the
# existing account a new password and update the secret.
#
# Needs: gcloud signed in as an owner of the auth project, gh with admin on the
# repo, python. Usage:
#   bash scripts/create_ci_firebase_account.sh [--rotate] [email]
#
# The account this makes is a Firebase sign-in only: it has no users doc and
# no licence, so it cannot call the backend. The CI account that can is a
# staff test account (ADR-027), made in the operator console (or
# POST /v1/admin/test-accounts) with purpose "ci", on an address in
# TEST_ACCOUNT_DOMAINS; the console shows its password once. Store that pair
# as the same two secrets with:
#   bash scripts/create_ci_firebase_account.sh --store <email>
# which reads the password from the terminal (not echoed) or stdin and sends
# it to gh on stdin, so it never reaches a command line or shell history. A new
# password is the console's "New password", then --store again. The runbook is
# docs/ops/OPERATING_MANUAL.md, "Test accounts (QC and CI)". An address that
# already has a Firebase user (one this script made) is refused there with
# email_in_use: delete that user in the Firebase console first.
set -euo pipefail

PROJECT=indicvision-dic-app-auth
REPO=sempermechanics/semperdic-app
ROTATE=false
if [ "${1:-}" = "--store" ]; then
  EMAIL=${2:?usage: --store <email>}
  if [ -t 0 ]; then
    read -r -s -p "Password for $EMAIL (from the console): " PASSWORD; echo
  else
    read -r PASSWORD
  fi
  [ -n "$PASSWORD" ] || { echo "no password given" >&2; exit 1; }
  printf '%s' "$EMAIL" | gh secret set FIREBASE_TEST_EMAIL -R "$REPO"
  printf '%s' "$PASSWORD" | gh secret set FIREBASE_TEST_PASSWORD -R "$REPO"
  echo "Secrets FIREBASE_TEST_EMAIL and FIREBASE_TEST_PASSWORD set on $REPO."
  exit 0
fi
if [ "${1:-}" = "--rotate" ]; then ROTATE=true; shift; fi
EMAIL=${1:-ci-firebase-test@sempermechanics.com}
API=https://identitytoolkit.googleapis.com/v1/projects/$PROJECT

PASSWORD=$(python -c "import secrets, string; a = string.ascii_letters + string.digits; print(''.join(secrets.choice(a) for _ in range(32)))")
TOKEN=$(gcloud auth print-access-token)
RESPONSE=$(mktemp)
trap 'rm -f "$RESPONSE"' EXIT

call() {  # call <path>; the JSON body comes on stdin
  curl -sS -o "$RESPONSE" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: $PROJECT" \
    -H "Content-Type: application/json" --data @- "$API/$1"
}

body() {  # the request body, built in python so the password is never on a command line
  EMAIL="$EMAIL" PASSWORD="$PASSWORD" LOCAL_ID="${1:-}" python -c "
import json, os
body = {'password': os.environ['PASSWORD'], 'emailVerified': True}
body.update({'localId': os.environ['LOCAL_ID']} if os.environ['LOCAL_ID'] else {'email': os.environ['EMAIL']})
print(json.dumps(body))"
}

if [ "$ROTATE" = "true" ]; then
  status=$(printf '{"email": ["%s"]}' "$EMAIL" | call accounts:lookup)
  LOCAL_ID=$(python -c "import json, sys; print(json.load(open(sys.argv[1])).get('users', [{}])[0].get('localId', ''))" "$RESPONSE")
  if [ "$status" != 200 ] || [ -z "$LOCAL_ID" ]; then
    echo "no account $EMAIL to rotate (HTTP $status)" >&2; exit 1
  fi
  status=$(body "$LOCAL_ID" | call accounts:update)
else
  status=$(body | call accounts)
fi
if [ "$status" != 200 ]; then
  echo "Firebase refused (HTTP $status):" >&2
  python -c "import json, sys; print(json.load(open(sys.argv[1])).get('error', {}).get('message', '?'))" "$RESPONSE" >&2
  echo "An existing account: run again with --rotate." >&2
  exit 1
fi
echo "Firebase account $EMAIL ready in $PROJECT."

printf '%s' "$EMAIL" | gh secret set FIREBASE_TEST_EMAIL -R "$REPO"
printf '%s' "$PASSWORD" | gh secret set FIREBASE_TEST_PASSWORD -R "$REPO"
echo "Secrets FIREBASE_TEST_EMAIL and FIREBASE_TEST_PASSWORD set on $REPO."
