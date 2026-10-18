#!/usr/bin/env bash
# Apply ops/monitoring/ to a GCP project: log-based metrics, the /readyz uptime
# check, the 99.5% availability SLO, the alert policies (readiness A7.1-A7.3)
# and the dashboards.
#
# Dry run by default: renders every file and prints the commands it would run,
# calling nothing. --apply makes the changes; each step looks the object up
# first and updates it if it exists, so a rerun converges instead of
# duplicating. Run by someone with roles/monitoring.editor and
# roles/logging.configWriter on the project:
#
#   scripts/apply-monitoring.sh --project indicvision-dic-app \
#     --notification-channel projects/indicvision-dic-app/notificationChannels/123
#   scripts/apply-monitoring.sh ... --apply
#
# Options:
#   --project ID                  GCP project (required)
#   --notification-channel NAME   channel id or full resource name (required)
#   --service NAME                Cloud Run service (default semper-api)
#   --staging-service NAME        staging service the service-health dashboard
#                                 shows beside --service (default semper-api-staging)
#   --region REGION               Cloud Run region (default asia-south1)
#   --run-host HOST               the service's run.app host (default: looked up
#                                 with `gcloud run services describe` on --apply)
#   --activations-per-licence-per-day N   activation anomaly threshold (default 25)
#   --auth-denied-per-5min N      AUTH_DENIED spike threshold (default 50)
#   --out DIR                     keep the rendered files in DIR
#   --apply                       make the changes (default: dry run)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../ops/monitoring"
API=https://monitoring.googleapis.com/v3

PROJECT=""
CHANNEL=""
SERVICE=semper-api
STAGING_SERVICE=semper-api-staging
REGION=asia-south1
RUN_HOST=""
ACTIVATIONS=25
AUTH_DENIED=50
OUT=""
DRY_RUN=1
SLO_ID=availability-995

usage() {
  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="${2:?--project needs an id}"; shift 2 ;;
    --notification-channel) CHANNEL="${2:?--notification-channel needs a channel}"; shift 2 ;;
    --service) SERVICE="${2:?--service needs a name}"; shift 2 ;;
    --staging-service) STAGING_SERVICE="${2:?--staging-service needs a name}"; shift 2 ;;
    --region) REGION="${2:?--region needs a region}"; shift 2 ;;
    --run-host) RUN_HOST="${2:?--run-host needs a host}"; shift 2 ;;
    --activations-per-licence-per-day) ACTIVATIONS="${2:?needs a number}"; shift 2 ;;
    --auth-denied-per-5min) AUTH_DENIED="${2:?needs a number}"; shift 2 ;;
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --apply) DRY_RUN=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done

if [ -z "$PROJECT" ] || [ -z "$CHANNEL" ]; then
  echo "--project and --notification-channel are required" >&2
  usage 2
fi
if ! [[ "$PROJECT" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]]; then
  echo "not a project id: $PROJECT" >&2
  exit 2
fi
for n in "$ACTIVATIONS" "$AUTH_DENIED"; do
  if ! [[ "$n" =~ ^[0-9]+$ ]]; then
    echo "thresholds must be whole numbers: $n" >&2
    exit 2
  fi
done
case "$CHANNEL" in
  projects/*/notificationChannels/*) ;;
  */*) echo "not a notification channel: $CHANNEL" >&2; exit 2 ;;
  *) CHANNEL="projects/$PROJECT/notificationChannels/$CHANNEL" ;;
esac

if [ -z "$OUT" ]; then
  OUT=$(mktemp -d)
  trap 'rm -rf "$OUT"' EXIT
fi
mkdir -p "$OUT"

step() { printf '\n== %s\n' "$*"; }
say() { printf '   %s\n' "$*"; }

# Values only --apply can look up; a dry run shows a marker in their place.
PROJECT_NUMBER="<project-number>"
UPTIME_ID="<uptime-check-id>"
if [ "$DRY_RUN" = 0 ]; then
  PROJECT_NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
  if [ -z "$RUN_HOST" ]; then
    url=$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" \
      --format='value(status.url)')
    RUN_HOST=${url#https://}
  fi
fi
RUN_HOST=${RUN_HOST:-<run-host>}

# render SRC DEST: fill the placeholders. A quoted numeric placeholder becomes
# a bare number so the JSON stays typed.
render() {
  mkdir -p "$(dirname "$2")"
  sed \
    -e "s|\"__ACTIVATIONS_PER_LICENCE_PER_DAY__\"|$ACTIVATIONS|g" \
    -e "s|__ACTIVATIONS_PER_LICENCE_PER_DAY__|$ACTIVATIONS|g" \
    -e "s|\"__AUTH_DENIED_PER_5MIN__\"|$AUTH_DENIED|g" \
    -e "s|__AUTH_DENIED_PER_5MIN__|$AUTH_DENIED|g" \
    -e "s|__PROJECT_NUMBER__|$PROJECT_NUMBER|g" \
    -e "s|__PROJECT__|$PROJECT|g" \
    -e "s|__STAGING_SERVICE__|$STAGING_SERVICE|g" \
    -e "s|__SERVICE__|$SERVICE|g" \
    -e "s|__REGION__|$REGION|g" \
    -e "s|__RUN_HOST__|$RUN_HOST|g" \
    -e "s|__NOTIFICATION_CHANNEL__|$CHANNEL|g" \
    -e "s|__UPTIME_CHECK_ID__|$UPTIME_ID|g" \
    -e "s|__SLO_SERVICE_ID__|$SERVICE|g" \
    -e "s|__SLO_ID__|$SLO_ID|g" \
    "$1" > "$2"
  if grep -q '__[A-Z_]*__' "$2"; then
    echo "unfilled placeholder in $2:" >&2
    grep -o '__[A-Z_]*__' "$2" | sort -u >&2
    exit 1
  fi
}

# api METHOD URL [BODY_FILE]: one Monitoring REST call. Prints the HTTP status;
# the body lands in $OUT/response.json.
api() {
  local method=$1 url=$2 body=${3:-}
  local args=(-sS -o "$OUT/response.json" -w '%{http_code}' -X "$method"
    -H "Authorization: Bearer $(gcloud auth print-access-token)"
    -H "x-goog-user-project: $PROJECT")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  fi
  curl "${args[@]}" "$url"
}

# expect_2xx STATUS WHAT
expect_2xx() {
  case "$1" in
    2??) say "$2: ok ($1)" ;;
    *) echo "$2 failed ($1):" >&2; cat "$OUT/response.json" >&2; exit 1 ;;
  esac
}

json_field() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1" "$2"
}

echo "Project $PROJECT, service $SERVICE ($REGION), channel $CHANNEL"
if [ "$DRY_RUN" = 1 ]; then
  echo "DRY RUN: nothing is changed. Pass --apply to apply."
fi

step "Log-based metrics"
for src in "$SRC"/metrics/*.yaml; do
  name=$(basename "$src" .yaml)
  dest="$OUT/metrics/$name.yaml"
  render "$src" "$dest"
  if [ "$DRY_RUN" = 1 ]; then
    say "would run: gcloud logging metrics create|update $name --project $PROJECT --config-from-file=$dest"
  elif gcloud logging metrics describe "$name" --project "$PROJECT" >/dev/null 2>&1; then
    gcloud logging metrics update "$name" --project "$PROJECT" --config-from-file="$dest" --quiet
    say "updated $name"
  else
    gcloud logging metrics create "$name" --project "$PROJECT" --config-from-file="$dest" --quiet
    say "created $name"
  fi
done

step "Uptime check on /readyz"
render "$SRC/uptime/readyz.json" "$OUT/uptime/readyz.json"
uptime_name="$SERVICE /readyz"
if [ "$DRY_RUN" = 1 ]; then
  say "would run: GET $API/projects/$PROJECT/uptimeCheckConfigs (find \"$uptime_name\")"
  say "would run: POST (or PATCH the existing one) with $OUT/uptime/readyz.json"
else
  status=$(api GET "$API/projects/$PROJECT/uptimeCheckConfigs?pageSize=100")
  expect_2xx "$status" "list uptime checks"
  existing=$(python3 - "$OUT/response.json" "$uptime_name" <<'PY'
import json, sys
body = json.load(open(sys.argv[1]))
print(next((c["name"] for c in body.get("uptimeCheckConfigs", [])
            if c.get("displayName") == sys.argv[2]), ""))
PY
)
  if [ -n "$existing" ]; then
    status=$(api PATCH "$API/$existing?updateMask=displayName,httpCheck,period,timeout,selectedRegions,userLabels" \
      "$OUT/uptime/readyz.json")
    expect_2xx "$status" "update uptime check"
  else
    status=$(api POST "$API/projects/$PROJECT/uptimeCheckConfigs" "$OUT/uptime/readyz.json")
    expect_2xx "$status" "create uptime check"
  fi
  UPTIME_ID=$(basename "$(json_field "$OUT/response.json" name)")
  say "uptime check id: $UPTIME_ID"
fi

step "Service and 99.5% availability SLO"
render "$SRC/slo/service.json" "$OUT/slo/service.json"
render "$SRC/slo/availability.json" "$OUT/slo/availability.json"
svc_url="$API/projects/$PROJECT/services"
if [ "$DRY_RUN" = 1 ]; then
  say "would run: POST $svc_url?serviceId=$SERVICE (PATCH if it exists) with $OUT/slo/service.json"
  say "would run: POST $svc_url/$SERVICE/serviceLevelObjectives?serviceLevelObjectiveId=$SLO_ID (PATCH if it exists) with $OUT/slo/availability.json"
else
  status=$(api GET "$svc_url/$SERVICE")
  if [ "$status" = 404 ]; then
    status=$(api POST "$svc_url?serviceId=$SERVICE" "$OUT/slo/service.json")
  else
    expect_2xx "$status" "read service"
    status=$(api PATCH "$svc_url/$SERVICE?updateMask=displayName,userLabels" "$OUT/slo/service.json")
  fi
  expect_2xx "$status" "service $SERVICE"
  slo_url="$svc_url/$SERVICE/serviceLevelObjectives"
  status=$(api GET "$slo_url/$SLO_ID")
  if [ "$status" = 404 ]; then
    status=$(api POST "$slo_url?serviceLevelObjectiveId=$SLO_ID" "$OUT/slo/availability.json")
  else
    expect_2xx "$status" "read SLO"
    status=$(api PATCH "$slo_url/$SLO_ID?updateMask=displayName,goal,rollingPeriod,serviceLevelIndicator,userLabels" \
      "$OUT/slo/availability.json")
  fi
  expect_2xx "$status" "SLO $SLO_ID"
fi

step "Alert policies"
for src in "$SRC"/policies/*.json; do
  name=$(basename "$src" .json)
  dest="$OUT/policies/$name.json"
  render "$src" "$dest"
  display=$(json_field "$dest" displayName)
  if [ "$DRY_RUN" = 1 ]; then
    say "would run: gcloud alpha monitoring policies create|update ($display) --project $PROJECT --policy-from-file=$dest"
    continue
  fi
  existing=$(gcloud alpha monitoring policies list --project "$PROJECT" \
    --filter="displayName=\"$display\"" --format='value(name)' | head -1)
  if [ -n "$existing" ]; then
    gcloud alpha monitoring policies update "$existing" --project "$PROJECT" --policy-from-file="$dest" --quiet
    say "updated: $display"
  else
    gcloud alpha monitoring policies create --project "$PROJECT" --policy-from-file="$dest" --quiet
    say "created: $display"
  fi
done

step "Dashboards"
# Found by displayName. An update sends back the etag the list returned, so a
# dashboard edited in the console after that read is refused (ABORTED) rather
# than overwritten unseen; rerun to take the new etag.
for src in "$SRC"/dashboards/*.json; do
  name=$(basename "$src" .json)
  dest="$OUT/dashboards/$name.json"
  render "$src" "$dest"
  display=$(json_field "$dest" displayName)
  if [ "$DRY_RUN" = 1 ]; then
    say "would run: gcloud monitoring dashboards create|update ($display) --project $PROJECT --config-from-file=$dest"
    continue
  fi
  gcloud monitoring dashboards list --project "$PROJECT" \
    --filter="displayName=\"$display\"" --format=json > "$OUT/dashboards-list.json"
  found=$(python3 - "$OUT/dashboards-list.json" "$display" <<'PY'
import json, sys
same = [d for d in json.load(open(sys.argv[1])) if d.get("displayName") == sys.argv[2]]
if len(same) > 1:
    print(f"more than one dashboard named {sys.argv[2]!r}; updating {same[0]['name']}", file=sys.stderr)
print(f"{same[0]['name']} {same[0].get('etag', '')}" if same else "")
PY
)
  if [ -n "$found" ]; then
    read -r existing etag <<<"$found"
    update="$OUT/dashboard-updates/$name.json"
    mkdir -p "$(dirname "$update")"
    python3 - "$dest" "$etag" "$update" <<'PY'
import json, sys
body = json.load(open(sys.argv[1]))
if sys.argv[2]:
    body["etag"] = sys.argv[2]
json.dump(body, open(sys.argv[3], "w"), indent=2)
PY
    gcloud monitoring dashboards update "$existing" --project "$PROJECT" \
      --config-from-file="$update" --quiet
    say "updated: $display"
  else
    gcloud monitoring dashboards create --project "$PROJECT" --config-from-file="$dest" --quiet
    say "created: $display"
  fi
done

step "By hand, once"
say "The uptime check calls the private service with an OIDC token minted for the"
say "Monitoring service agent, which needs run.invoker on $SERVICE:"
say "  gcloud run services add-iam-policy-binding $SERVICE --region $REGION --project $PROJECT \\"
say "    --member=serviceAccount:service-$PROJECT_NUMBER@gcp-sa-monitoring-notification.iam.gserviceaccount.com \\"
say "    --role=roles/run.invoker"
say "The log-based alerts and charts see nothing until a backend that writes their"
say "events is deployed (activation, checkout, devlock_drop, audit_write_failed,"
say "AUTH_DENIED, revocation_check_failed, auth_admin_rate_limited, app_check_*;"
say "ops/monitoring/README.md)."
if [ "$DRY_RUN" = 1 ]; then
  echo
  echo "Rendered files: $OUT"
fi
