#!/usr/bin/env bash
# Read-only: does the runtime service account hold access to a secret?
#
# Usage: check_secret_access.sh <project> <secret> <service-account-email>
#
# deploy-backend.yml runs this before mounting ENTITLEMENT_SIGNING_KEY. A
# revision whose runtime SA cannot read a mounted secret never starts: the
# deploy stops, traffic stays on the previous revision, and every later deploy
# fails the same way until the grant is made (TD-215). Failing here names the
# missing grant instead.
#
# Reads the secret's IAM policy, then the project's (a project-level grant
# reaches every secret). Exit 0 when either grants an access role to the SA,
# or when a policy could not be read (a warning: the deploy SA may lack
# getIamPolicy, and the revision's own start remains the check); exit 1 when
# both were read and neither grants it; exit 2 on bad arguments. Never writes.
set -euo pipefail

if [ "$#" -ne 3 ] || [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
  echo "usage: $0 <project> <secret> <service-account-email>" >&2
  exit 2
fi
PROJECT="$1"
SECRET="$2"
MEMBER="serviceAccount:$3"

# Roles that include secretmanager.versions.access.
ROLES="roles/secretmanager.secretAccessor roles/secretmanager.admin roles/owner"

grants() {
  # stdin: an IAM policy as JSON. Exit 0 when a binding gives MEMBER one of ROLES.
  python3 -c '
import json, sys
member, roles = sys.argv[1], set(sys.argv[2].split())
policy = json.load(sys.stdin) or {}
sys.exit(0 if any(b.get("role") in roles and member in b.get("members", [])
                  for b in policy.get("bindings", [])) else 1)
' "$MEMBER" "$ROLES"
}

unread=""
if policy=$(gcloud secrets get-iam-policy "$SECRET" --project "$PROJECT" --format=json 2>/tmp/secret-iam-err); then
  if printf '%s' "$policy" | grants; then
    echo "OK: $MEMBER can read secret $SECRET (secret policy)."
    exit 0
  fi
else
  unread="secret $SECRET ($(head -c 200 /tmp/secret-iam-err | tr '\n' ' '))"
fi

if policy=$(gcloud projects get-iam-policy "$PROJECT" --format=json 2>/tmp/secret-iam-err); then
  if printf '%s' "$policy" | grants; then
    echo "OK: $MEMBER can read secret $SECRET (project policy)."
    exit 0
  fi
else
  unread="${unread:+$unread; }project $PROJECT ($(head -c 200 /tmp/secret-iam-err | tr '\n' ' '))"
fi

if [ -n "$unread" ]; then
  echo "WARN: could not read the IAM policy of $unread; not checked whether $MEMBER can read secret $SECRET."
  exit 0
fi
echo "FAIL: $MEMBER has no access to secret $SECRET in $PROJECT. Grant it before deploying:" \
  "gcloud secrets add-iam-policy-binding $SECRET --project $PROJECT" \
  "--member=$MEMBER --role=roles/secretmanager.secretAccessor"
exit 1
