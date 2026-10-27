#!/usr/bin/env bash
# Create the local Firebase test account and write it to ~/.gradle/gradle.properties
# as the two arguments FirebaseAuthIntegrationTest reads, so a local
# connectedDebugAndroidTest signs in without flags (docs/app/TESTING.md).
#
# The account is for testing only and kept apart: an address of the form
# test-*@sempermechanics.com (anything else is refused), a "TEST ONLY" display name,
# and a Firebase sign-in only, with no users doc and no licence, so the backend
# refuses it. It is not the CI account and the FIREBASE_TEST_* repo secrets are
# not touched (that is scripts/create_ci_firebase_account.sh).
#
# The password is generated here (32 letters and digits: no comma, which Gradle
# would cut an instrumentation argument at, TD-106), sent to Firebase over stdin and
# written to the properties file, and never printed. Run it again for a new
# password: an existing account is updated, not duplicated.
#
# Needs: gcloud signed in as an owner of the auth project, python, curl. On Windows
# run it with Git Bash ("C:\Program Files\Git\bin\bash.exe"); the bash.exe on PATH
# is WSL's, which sees neither the Windows path nor gcloud. Usage:
#   bash scripts/create_local_firebase_test_account.sh [email]
set -euo pipefail

PROJECT=indicvision-dic-app-auth
EMAIL=${1:-test-local-instrumented@sempermechanics.com}
case "$EMAIL" in
  test-*@sempermechanics.com) ;;
  *) echo "refused: a test sign-in must be test-*@sempermechanics.com, not $EMAIL" >&2; exit 1 ;;
esac
API=https://identitytoolkit.googleapis.com/v1/projects/$PROJECT
PROPS="$HOME/.gradle/gradle.properties"
KEY=android.testInstrumentationRunnerArguments

PASSWORD=$(python -c "import secrets, string; a = string.ascii_letters + string.digits; print(''.join(secrets.choice(a) for _ in range(32)))")
TOKEN=$(gcloud auth print-access-token)
RESPONSE=$(mktemp)
trap 'rm -f "$RESPONSE" "$PROPS.tmp"' EXIT

call() {  # call <path>; the JSON body comes on stdin
  curl -sS -o "$RESPONSE" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: $PROJECT" \
    -H "Content-Type: application/json" --data @- "$API/$1"
}

body() {  # the request body, built in python so the password is never on a command line
  EMAIL="$EMAIL" PASSWORD="$PASSWORD" LOCAL_ID="${1:-}" python -c "
import json, os
body = {'password': os.environ['PASSWORD'], 'emailVerified': True,
        'displayName': 'TEST ONLY - local instrumented tests (no users doc, no licence)'}
body.update({'localId': os.environ['LOCAL_ID']} if os.environ['LOCAL_ID'] else {'email': os.environ['EMAIL']})
print(json.dumps(body))"
}

status=$(printf '{"email": ["%s"]}' "$EMAIL" | call accounts:lookup)
LOCAL_ID=$(python -c "import json, sys; print(json.load(open(sys.argv[1])).get('users', [{}])[0].get('localId', ''))" "$RESPONSE")
if [ -n "$LOCAL_ID" ]; then
  status=$(body "$LOCAL_ID" | call accounts:update); what="new password for"
else
  status=$(body | call accounts); what="created"
fi
if [ "$status" != 200 ]; then
  echo "Firebase refused (HTTP $status):" >&2
  python -c "import json, sys; print(json.load(open(sys.argv[1])).get('error', {}).get('message', '?'))" "$RESPONSE" >&2
  exit 1
fi
echo "Firebase account $EMAIL: $what, in $PROJECT."

mkdir -p "$(dirname "$PROPS")"
touch "$PROPS"
grep -v "^$KEY\.FIREBASE_TEST_\(EMAIL\|PASSWORD\)=" "$PROPS" > "$PROPS.tmp" || true
printf '%s.FIREBASE_TEST_EMAIL=%s\n%s.FIREBASE_TEST_PASSWORD=%s\n' "$KEY" "$EMAIL" "$KEY" "$PASSWORD" >> "$PROPS.tmp"
mv "$PROPS.tmp" "$PROPS"
chmod 600 "$PROPS"  # it now holds a password (no-op on Windows)
echo "Wrote both test arguments to $PROPS (password not shown)."
