#!/usr/bin/env bash
# Read-only check of the GCP / Firebase posture the production-readiness gate
# asks for, one PASS / FAIL / UNKNOWN line per framework item:
#
#   2.1  project lien, a Prod tag, two or more owners, owners through groups
#   2.4  every API key restricted to named APIs and to an app / site / server
#   2.5  anonymous auth off (and which providers are on) on the Auth project
#   2.6  a billing budget on each project; Cloud Run max-instances set (the cap)
#   2.7  Firestore PITR, delete protection, and backups (2.7-backup): a daily
#        managed backup schedule, or exports from the daily export workflow
#        (.github/workflows/firestore-backup.yml) in --backup-bucket
#   3.1  APP_CHECK_MODE=enforce on each Cloud Run service
#   3.4  CHECK_REVOKED_ON_STEP_UP=true on each service; the API service
#        account holds a role on the Auth project that can revoke and delete
#        users (firebaseauth.users.update and .delete: roles/firebaseauth.admin,
#        roles/firebase.admin, or a custom role with both) (3.4-iam)
#   3.5  ENTITLEMENT_SIGNING_KEY reaches each service as a Secret Manager ref
#   3.7  Data Access audit logs on for Firebase, rules, App Check, Firestore
#
# Only describe / list / ls / get-iam-policy calls and REST GETs (Identity
# Toolkit config) are made; a wrapper refuses any other gcloud verb. UNKNOWN
# means the call failed (permission, API off) and says why.
#
#   scripts/check_gcp_posture.sh --app-project indicvision-dic-app \
#     --auth-project indicvision-dic-app-auth \
#     --api-sa indic-api@indicvision-dic-app.iam.gserviceaccount.com
#
# Options: --region (default asia-south1), --services "semper-api semper-api-staging",
# --backup-bucket NAME (the FIRESTORE_BACKUP_BUCKET repo variable, no gs://).
# Exit 1 when any line is FAIL, else 0.
set -euo pipefail

APP_PROJECT=""
AUTH_PROJECT=""
API_SA=""
BACKUP_BUCKET=""
REGION=asia-south1
SERVICES="semper-api semper-api-staging"

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --app-project) APP_PROJECT="${2:?--app-project needs an id}"; shift 2 ;;
    --auth-project) AUTH_PROJECT="${2:?--auth-project needs an id}"; shift 2 ;;
    --api-sa) API_SA="${2:?--api-sa needs an email}"; shift 2 ;;
    --region) REGION="${2:?--region needs a region}"; shift 2 ;;
    --services) SERVICES="${2:?--services needs names}"; shift 2 ;;
    --backup-bucket) BACKUP_BUCKET="${2:?--backup-bucket needs a bucket name}"; BACKUP_BUCKET="${BACKUP_BUCKET#gs://}"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done
if [ -z "$APP_PROJECT" ] || [ -z "$AUTH_PROJECT" ] || [ -z "$API_SA" ]; then
  echo "--app-project, --auth-project and --api-sa are required" >&2
  usage 2
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILS=0
UNKNOWNS=0

# gc ARGS...: gcloud, read-only verbs only. Output to stdout; on failure the
# first stderr line is left in $WORK/err.
gc() {
  local verb=""
  for a in "$@"; do
    case "$a" in
      describe|list|ls|get-iam-policy|print-access-token) verb=$a ;;
    esac
  done
  if [ -z "$verb" ]; then
    echo "refusing non-read-only gcloud call: gcloud $*" >&2
    exit 3
  fi
  gcloud "$@" 2>"$WORK/err"
}

why() { head -c 200 "$WORK/err" | tr '\n' ' '; }

report() {
  local id=$1 status=$2 scope=$3 detail=$4
  printf '%-22s %-8s %-28s %s\n' "$id" "$status" "$scope" "$detail"
  case "$status" in
    FAIL) FAILS=$((FAILS + 1)) ;;
    UNKNOWN) UNKNOWNS=$((UNKNOWNS + 1)) ;;
  esac
}

# py FILE SCRIPT [ARGS...]: run a short Python reading the JSON in FILE (as
# `d`), printing "STATUS|detail".
py() {
  local file=$1 script=$2
  shift 2
  python3 -c "import json,sys
d=json.load(open(sys.argv[1]))
args=sys.argv[2:]
$script" "$file" "$@"
}

printf '%-22s %-8s %-28s %s\n' "item" "status" "scope" "detail"
printf '%-22s %-8s %-28s %s\n' "----" "------" "-----" "------"

# number PROJECT: the project number read below, or empty.
number() { cat "$WORK/number-$1" 2>/dev/null || true; }
for p in "$APP_PROJECT" "$AUTH_PROJECT"; do
  if gc projects describe "$p" --format=json >"$WORK/proj.json"; then
    py "$WORK/proj.json" 'print(d.get("projectNumber",""))' >"$WORK/number-$p"
  else
    report "2.1-project" UNKNOWN "$p" "projects describe failed: $(why)"
  fi
done

# --- 2.1 project protection --------------------------------------------------
for p in "$APP_PROJECT" "$AUTH_PROJECT"; do
  if gc alpha resource-manager liens list --project "$p" --format=json >"$WORK/liens.json"; then
    IFS='|' read -r st detail < <(py "$WORK/liens.json" '
dels=[l for l in d if "resourcemanager.projects.delete" in (l.get("restrictions") or [])]
print(("PASS|%d lien(s) block project deletion" % len(dels)) if dels else "FAIL|no lien restricting resourcemanager.projects.delete")')
    report "2.1-lien" "$st" "$p" "$detail"
  else
    report "2.1-lien" UNKNOWN "$p" "liens list failed: $(why)"
  fi

  if [ -n "$(number "$p")" ] && gc resource-manager tags bindings list \
      --parent="//cloudresourcemanager.googleapis.com/projects/$(number "$p")" --effective \
      --format=json >"$WORK/tags.json"; then
    IFS='|' read -r st detail < <(py "$WORK/tags.json" '
vals=[str(t.get("namespacedTagValue") or t.get("tagValue") or "") for t in d]
prod=[v for v in vals if v.lower().rsplit("/",1)[-1] in ("prod","production")]
print(("PASS|tagged " + ", ".join(prod)) if prod else ("FAIL|no Prod tag (effective tags: %s)" % (", ".join(vals) or "none")))')
    report "2.1-tag" "$st" "$p" "$detail"
  else
    report "2.1-tag" UNKNOWN "$p" "tags bindings list failed: $(why)"
  fi

  if gc projects get-iam-policy "$p" --format=json >"$WORK/iam-$p.json"; then
    IFS='|' read -r st detail < <(py "$WORK/iam-$p.json" '
owners=[m for b in d.get("bindings",[]) if b.get("role")=="roles/owner" for m in b.get("members",[])]
people=[m for m in owners if m.startswith("user:")]
groups=[m for m in owners if m.startswith("group:")]
print(("PASS|%d owners (%d group, %d user)" if len(owners)>=2 else "FAIL|%d owner(s) (%d group, %d user); want 2 or more") % (len(owners),len(groups),len(people)))')
    report "2.1-owners" "$st" "$p" "$detail"
    IFS='|' read -r st detail < <(py "$WORK/iam-$p.json" '
owners=[m for b in d.get("bindings",[]) if b.get("role")=="roles/owner" for m in b.get("members",[])]
users=[m.split(":",1)[1] for m in owners if m.startswith("user:")]
groups=[m for m in owners if m.startswith("group:")]
print("PASS|owner granted through %d group(s)" % len(groups) if groups and not users else
      "FAIL|owner granted to users directly: %s" % ", ".join(users) if users else "FAIL|no owner group")')
    report "2.1-groups" "$st" "$p" "$detail"
  else
    report "2.1-owners" UNKNOWN "$p" "get-iam-policy failed: $(why)"
  fi
done

# --- 2.4 API keys ------------------------------------------------------------
for p in "$APP_PROJECT" "$AUTH_PROJECT"; do
  if gc services api-keys list --project "$p" --format=json >"$WORK/keys.json"; then
    IFS='|' read -r st detail < <(py "$WORK/keys.json" '
bad=[]
for k in d:
    r=k.get("restrictions") or {}
    app=any(r.get(x) for x in ("androidKeyRestrictions","iosKeyRestrictions","browserKeyRestrictions","serverKeyRestrictions"))
    if not r.get("apiTargets") or not app:
        why=[] if r.get("apiTargets") else ["no API restriction"]
        if not app: why.append("no app/site/server restriction")
        bad.append("%s (%s)" % (k.get("displayName") or k.get("uid") or k.get("name","?").rsplit("/",1)[-1], ", ".join(why)))
if not d: print("PASS|no API keys")
elif bad: print("FAIL|" + "; ".join(bad))
else: print("PASS|%d key(s), all restricted by API and caller" % len(d))')
    report "2.4-api-keys" "$st" "$p" "$detail"
  else
    report "2.4-api-keys" UNKNOWN "$p" "api-keys list failed: $(why)"
  fi
done

# --- 2.5 auth providers (Identity Toolkit admin config, REST GET) -----------
if TOKEN=$(gc auth print-access-token); then
  if curl -sS --fail -o "$WORK/idp.json" \
      -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: $AUTH_PROJECT" \
      "https://identitytoolkit.googleapis.com/admin/v2/projects/$AUTH_PROJECT/config" 2>"$WORK/err"; then
    IFS='|' read -r st detail < <(py "$WORK/idp.json" '
s=d.get("signIn") or {}
on=[n for n,k in (("email",s.get("email")),("phone",s.get("phoneNumber")),("anonymous",s.get("anonymous"))) if (k or {}).get("enabled")]
if (s.get("anonymous") or {}).get("enabled"): print("FAIL|anonymous sign-in is enabled (on: %s)" % ", ".join(on))
else: print("PASS|anonymous off; built-in providers on: %s" % (", ".join(on) or "none"))')
    report "2.5-auth-providers" "$st" "$AUTH_PROJECT" "$detail"
  else
    report "2.5-auth-providers" UNKNOWN "$AUTH_PROJECT" "identitytoolkit config GET failed: $(why)"
  fi
  if curl -sS --fail -o "$WORK/idps.json" \
      -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: $AUTH_PROJECT" \
      "https://identitytoolkit.googleapis.com/admin/v2/projects/$AUTH_PROJECT/defaultSupportedIdpConfigs" 2>"$WORK/err"; then
    IFS='|' read -r st detail < <(py "$WORK/idps.json" '
on=[c.get("name","").rsplit("/",1)[-1] for c in d.get("defaultSupportedIdpConfigs",[]) if c.get("enabled")]
print("PASS|federated providers on: %s (check each is in use)" % (", ".join(on) or "none"))')
    report "2.5-idp-providers" "$st" "$AUTH_PROJECT" "$detail"
  else
    report "2.5-idp-providers" UNKNOWN "$AUTH_PROJECT" "defaultSupportedIdpConfigs GET failed: $(why)"
  fi
else
  report "2.5-auth-providers" UNKNOWN "$AUTH_PROJECT" "no access token: $(why)"
fi

# --- 2.6 budgets -------------------------------------------------------------
for p in "$APP_PROJECT" "$AUTH_PROJECT"; do
  if ! gc billing projects describe "$p" --format=json >"$WORK/billing.json"; then
    report "2.6-budget" UNKNOWN "$p" "billing projects describe failed: $(why)"
    continue
  fi
  account=$(py "$WORK/billing.json" 'print((d.get("billingAccountName") or "").rsplit("/",1)[-1] if d.get("billingEnabled") else "")')
  if [ -z "$account" ]; then
    report "2.6-budget" FAIL "$p" "billing is not enabled"
    continue
  fi
  if gc billing budgets list --billing-account="$account" --format=json >"$WORK/budgets.json"; then
    IFS='|' read -r st detail < <(py "$WORK/budgets.json" '
want="projects/"+args[0]
hit=[b for b in d if not ((b.get("budgetFilter") or {}).get("projects")) or want in b["budgetFilter"]["projects"]]
def amt(b):
    s=(b.get("amount") or {}).get("specifiedAmount") or {}
    return "%s %s" % (s.get("units","last-period"), s.get("currencyCode","")) if s else "last period"
print(("PASS|" + "; ".join("%s (%s, %d threshold(s))" % (b.get("displayName","?"), amt(b), len(b.get("thresholdRules",[]))) for b in hit)) if hit else "FAIL|no budget covers this project")' "$(number "$p")")
    report "2.6-budget" "$st" "$p" "$detail"
  else
    report "2.6-budget" UNKNOWN "$p" "billing budgets list failed: $(why)"
  fi
done

# --- Cloud Run services: 2.6 cap, 3.1, 3.4, 3.5 -----------------------------
for s in $SERVICES; do
  if ! gc run services describe "$s" --region "$REGION" --project "$APP_PROJECT" --format=json >"$WORK/run.json"; then
    report "3.1-app-check" UNKNOWN "$s" "run services describe failed: $(why)"
    continue
  fi
  while IFS='|' read -r id st detail; do
    report "$id" "$st" "$s" "$detail"
  done < <(py "$WORK/run.json" '
t=(d.get("spec") or {}).get("template") or {}
ann=(t.get("metadata") or {}).get("annotations") or {}
env={}
for c in (t.get("spec") or {}).get("containers") or []:
    for e in c.get("env") or []:
        env[e.get("name")]=e
def val(n): return (env.get(n) or {}).get("value")
mx=ann.get("autoscaling.knative.dev/maxScale")
print("2.6-max-instances|%s" % ("PASS|max-instances %s caps spend" % mx if mx else "FAIL|no max-instances: nothing caps scale-out"))
mode=val("APP_CHECK_MODE") or "unset"
print("3.1-app-check|%s|APP_CHECK_MODE=%s" % ("PASS" if mode=="enforce" else "FAIL", mode))
rev=(val("CHECK_REVOKED_ON_STEP_UP") or "unset")
print("3.4-check-revoked|%s|CHECK_REVOKED_ON_STEP_UP=%s" % ("PASS" if rev.lower() in ("true","1","yes") else "FAIL", rev))
k=env.get("ENTITLEMENT_SIGNING_KEY")
ref=((k or {}).get("valueFrom") or {}).get("secretKeyRef")
if ref: print("3.5-entitlement-key|PASS|secret %s:%s" % (ref.get("name"), ref.get("key","latest")))
elif k: print("3.5-entitlement-key|FAIL|set as a plain env value, not a Secret Manager ref")
else: print("3.5-entitlement-key|FAIL|ENTITLEMENT_SIGNING_KEY not set")')
done

# --- 2.7 Firestore -----------------------------------------------------------
if gc firestore databases describe --database='(default)' --project "$APP_PROJECT" --format=json >"$WORK/fs.json"; then
  IFS='|' read -r st detail < <(py "$WORK/fs.json" '
v=d.get("pointInTimeRecoveryEnablement","")
print(("PASS|" if v=="POINT_IN_TIME_RECOVERY_ENABLED" else "FAIL|") + (v or "unset") + ", versionRetentionPeriod " + str(d.get("versionRetentionPeriod","?")))')
  report "2.7-pitr" "$st" "$APP_PROJECT" "$detail"
  IFS='|' read -r st detail < <(py "$WORK/fs.json" '
v=d.get("deleteProtectionState","")
print(("PASS|" if v=="DELETE_PROTECTION_ENABLED" else "FAIL|") + (v or "unset"))')
  report "2.7-delete-protection" "$st" "$APP_PROJECT" "$detail"
else
  report "2.7-pitr" UNKNOWN "$APP_PROJECT" "firestore databases describe failed: $(why)"
fi
# Backups: a daily managed schedule, or the daily export workflow's exports
# (scripts/firestore-export.sh writes gs://BUCKET/firestore/PROJECT/STAMP/).
# PITR is its own line above. What cannot be read is UNKNOWN, not FAIL.
EXPORT_HINT=".github/workflows/firestore-backup.yml exports daily to FIRESTORE_BACKUP_BUCKET"
sched="unread"
if gc firestore backups schedules list --database='(default)' --project "$APP_PROJECT" --format=json >"$WORK/sched.json"; then
  sched=$(py "$WORK/sched.json" '
daily=[s for s in d if "dailyRecurrence" in s]
weekly=[s for s in d if "weeklyRecurrence" in s]
print("daily schedule, retention %s" % daily[0].get("retention","?") if daily else
      "weekly schedule only" if weekly else "no schedule")')
else
  sched="schedules list failed: $(why)"
fi
case "$sched" in
  daily*)
    report "2.7-backup" PASS "$APP_PROJECT" "$sched" ;;
  *)
    if [ -z "$BACKUP_BUCKET" ]; then
      report "2.7-backup" UNKNOWN "$APP_PROJECT" "managed backups: $sched; exports not checked (pass --backup-bucket; $EXPORT_HINT)"
    elif gc storage ls "gs://$BACKUP_BUCKET/firestore/$APP_PROJECT/" >"$WORK/exports.txt"; then
      latest=$(sed -n 's#.*/firestore/[^/]*/\([0-9]\{8\}T[0-9]\{6\}Z\)/\{0,1\}$#\1#p' "$WORK/exports.txt" | sort | tail -1)
      if [ -n "$latest" ]; then
        age_h=$(python3 -c 'import datetime as t,sys
s=t.datetime.strptime(sys.argv[1],"%Y%m%dT%H%M%SZ").replace(tzinfo=t.timezone.utc)
print(int((t.datetime.now(t.timezone.utc)-s).total_seconds()//3600))' "$latest")
        if [ "$age_h" -le 48 ]; then
          report "2.7-backup" PASS "$APP_PROJECT" "export gs://$BACKUP_BUCKET/firestore/$APP_PROJECT/$latest, ${age_h} h old; managed backups: $sched"
        else
          report "2.7-backup" FAIL "$APP_PROJECT" "latest export $latest is ${age_h} h old; want one a day ($EXPORT_HINT)"
        fi
      else
        report "2.7-backup" FAIL "$APP_PROJECT" "no export under gs://$BACKUP_BUCKET/firestore/$APP_PROJECT/ and $sched ($EXPORT_HINT)"
      fi
    else
      report "2.7-backup" UNKNOWN "$APP_PROJECT" "managed backups: $sched; export listing failed: $(why) ($EXPORT_HINT)"
    fi ;;
esac

# --- 3.4 API service account on the Auth project ----------------------------
# revoke_sessions and delete_auth_user (backend/app/google_auth.py) need
# firebaseauth.users.update and .delete; a viewer role only reads users, which
# the revocation check needs but revoke and delete do not get by on.
if [ -s "$WORK/iam-$AUTH_PROJECT.json" ]; then
  roles=$(py "$WORK/iam-$AUTH_PROJECT.json" '
m="serviceAccount:"+args[0]
print(" ".join(sorted({b["role"] for b in d.get("bindings",[]) if m in b.get("members",[])})))' "$API_SA")
  ok=""
  unread=""
  for r in $roles; do
    case "$r" in
      roles/firebaseauth.admin|roles/firebase.admin) ok="$ok $r" ;;
      projects/*/roles/*|organizations/*/roles/*)
        scope_flag="--project"
        case "$r" in organizations/*) scope_flag="--organization" ;; esac
        parent=$(echo "$r" | cut -d/ -f2)
        if gc iam roles describe "${r##*/}" "$scope_flag" "$parent" --format=json >"$WORK/role.json"; then
          if py "$WORK/role.json" '
p=set(d.get("includedPermissions") or [])
sys.exit(0 if {"firebaseauth.users.update","firebaseauth.users.delete"} <= p else 1)'; then
            ok="$ok $r"
          fi
        else
          unread="$unread $r"
        fi ;;
    esac
  done
  if [ -n "$ok" ]; then
    report "3.4-iam" PASS "$AUTH_PROJECT" "${ok# } (can revoke and delete users)"
  elif [ -n "$unread" ]; then
    report "3.4-iam" UNKNOWN "$AUTH_PROJECT" "custom role(s)${unread} not readable: $(why)"
  else
    report "3.4-iam" FAIL "$AUTH_PROJECT" "no role with firebaseauth.users.update and .delete (has: ${roles:-nothing}); grant roles/firebaseauth.admin"
  fi
else
  report "3.4-iam" UNKNOWN "$AUTH_PROJECT" "no IAM policy read for the Auth project"
fi

# --- 3.7 Data Access audit logs ---------------------------------------------
for p in "$APP_PROJECT" "$AUTH_PROJECT"; do
  if [ ! -s "$WORK/iam-$p.json" ]; then
    report "3.7-audit-logs" UNKNOWN "$p" "no IAM policy read"
    continue
  fi
  IFS='|' read -r st detail < <(py "$WORK/iam-$p.json" '
want=["firebase.googleapis.com","firebaserules.googleapis.com","firebaseappcheck.googleapis.com","firestore.googleapis.com"]
cfg={c.get("service"):{l.get("logType") for l in c.get("auditLogConfigs",[])} for c in d.get("auditConfigs",[])}
def on(svc):
    have=cfg.get(svc,set())|cfg.get("allServices",set())
    return {"DATA_READ","DATA_WRITE"}<=have
missing=[s for s in want if not on(s)]
print(("FAIL|no DATA_READ+DATA_WRITE for: " + ", ".join(missing)) if missing else "PASS|Data Access logs on for Firebase, rules, App Check, Firestore")')
  report "3.7-audit-logs" "$st" "$p" "$detail"
done

echo
echo "FAIL: $FAILS  UNKNOWN: $UNKNOWNS"
if [ "$FAILS" -gt 0 ]; then
  exit 1
fi
