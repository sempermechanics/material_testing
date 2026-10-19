# Monitoring as code (semper-api)

Log-based metrics, the `/readyz` uptime check, the availability SLO and the alert
policies for the production Cloud Run service, applied by
[`scripts/apply-monitoring.sh`](../../scripts/apply-monitoring.sh). Readiness items
A7.1–A7.3; the signals in [§17](../../docs/backend/CLOUD_ARCHITECTURE_GCP.md) of the
architecture doc.

```bash
# Dry run (the default): renders every file and prints the commands, calls nothing.
scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <channel-id>
# Apply (needs roles/monitoring.editor + roles/logging.configWriter). Reruns update in place.
scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <channel-id> --apply
```

Create the notification channel (email, Chat or PagerDuty) first, in Cloud Console →
Monitoring → Alerting → Edit notification channels, and pass its id. Then grant the
Monitoring service agent `roles/run.invoker` on the service (the script prints the
command): `semper-api` is private, and the uptime check calls it with an OIDC token.

## Variables

Files hold `__NAME__` placeholders that the script fills; a file left with one fails
the run.

| Placeholder | Flag | Default | Meaning |
|---|---|---|---|
| `__PROJECT__`, `__PROJECT_NUMBER__` | `--project` | — | Project id; the number is looked up on `--apply` |
| `__SERVICE__`, `__REGION__` | `--service`, `--region` | `semper-api`, `asia-south1` | Cloud Run service |
| `__RUN_HOST__` | `--run-host` | looked up | The service's `*.run.app` host, for the uptime check |
| `__NOTIFICATION_CHANNEL__` | `--notification-channel` | — | Id or full `projects/…/notificationChannels/…` name |
| `__ACTIVATIONS_PER_LICENCE_PER_DAY__` | `--activations-per-licence-per-day` | 25 | **N** for the activation anomaly: successful key redemptions on one licence in 24 h. Set it above the largest class an institution onboards in a day; a personal licence never comes near it |
| `__AUTH_DENIED_PER_5MIN__` | `--auth-denied-per-5min` | 50 | AUTH_DENIED refusals in 5 min (the §17 starting point) |
| `__UPTIME_CHECK_ID__`, `__SLO_SERVICE_ID__`, `__SLO_ID__` | — | from the created objects | Wired by the script |

## Log-based metrics (`metrics/`)

All count lines from `resource.type="cloud_run_revision"` on the service. The events
are `observability.log_event` lines (`jsonPayload.event`), one JSON object per line.

| Metric | Filter | Labels |
|---|---|---|
| `semper_http_5xx` | `event="http_access"`, `status>=500` | `routeTemplate`, `status` |
| `semper_audit_write_failed` | `event="audit_write_failed"` | `action`, `errorCode` |
| `semper_activation` | `event="activation"` (licence key redemption) | `licenseId`, `outcome` (ok / refused), `errorCode`, `appId` |
| `semper_checkout` | `event="checkout"` (floating seat) | `licenseId`, `outcome`, `errorCode`, `appId` |
| `semper_devlock_drop` | `event="devlock_drop"` (licensed account resolved Demo at the device lock) | `licenseId`, `stage` (revoked / mismatch) |
| `semper_auth_denied` | `action="AUTH_DENIED"`, not an `audit_write_failed` line | `stage` |

A metric counts only from the moment it exists, and only lines a deployed backend
writes: `activation`, `checkout` and `devlock_drop` come with the licensing
observability change, `audit_write_failed` with the audit change, and
`semper_auth_denied` with the change that makes `audit.record()` log an
`audit_denied` line (with `action` and `stage`) for every DENIED row as well as
writing it to Firestore (`backend/app/audit.py:64-69`). Until the backend that
writes a line is deployed, its alert stays quiet, which is not the same as healthy.

## Alerts (`policies/`) and what to do

### uptime-readyz
`/readyz` failed from two or more of three regions (checked every 10 min; each check
costs a Firestore read and a Drive call). Read the 503 detail in the access log
(`firestore_unreachable`, `drive_unhealthy`, …): a dependency outage is not fixed by
rolling back app code. If it started with a deploy, move traffic back to the previous
revision (`gcloud run services update-traffic semper-api --to-revisions=<prev>=100`).

### http-5xx-ratio
More than 2 % of requests answered 5xx over 10 min (Cloud Run's `request_count`, so
platform 5xx such as no-instance 503s count too). At very low traffic one error can
cross 2 %: check the absolute count in `semper_http_5xx` by route first. Then recent
deploys, `/readyz`, and Error Reporting for the new error group.

### latency-p95
p95 above 2 s for 15 min. With `min-instances 0` a cold start is p95 ~6 s, so a quiet
period with sparse traffic can hold this up on cold starts alone: compare instance
count and `semper_http_5xx` before acting. Real causes: Firestore contention, a Drive
slowdown, a hot route (group by `routeTemplate` in Logs Explorer).

### auth-denied-spike
More than N refusals in 5 min. Group by `stage`: one stage across many IPs is an
attack or a broken client release (an App Check or token change); one uid is one
confused device. For an attack, tighten the API Gateway quota; for a release, roll the
backend setting back (e.g. `APP_CHECK_MODE`) before touching the app.

### audit-write-failed
An audit row was lost: the request succeeded, the compliance record has a gap.
Check Firestore health and quota, then note the time window in the incident log so an
audit export for that window is read with the gap in mind.

### activation-anomaly
One licence was redeemed more than N times in 24 h (`licenseId` label). An
institution onboarding a class can do this; a personal licence cannot. Look the
licence up in the staff console, compare with its seat count, and revoke or contact
the holder if the key has leaked. Refusals are not counted here; see
`semper_activation` with `outcome="refused"` for key guessing.

### slo-burn
SLO: 99.5 % of requests not 5xx over a rolling 28 days (`slo/availability.json`;
error budget ~3.4 h of full outage). **Fast burn** (14.4× over 1 h, 2 % of the budget
in an hour) pages: treat as an incident. **Slow burn** (6× over 6 h, 5 % of the
budget) is a fix-today ticket. Both come from the SRE workbook's multi-window
starting points; tune after a month of data.

## Not covered here

Crashlytics velocity alerts and Performance Monitoring (A7.1) are configured in the
Firebase console; Play Integrity failure ratios (A7.3) need an event the backend does
not log yet.
