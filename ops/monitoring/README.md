# Monitoring as code (semper-api)

Log-based metrics, the `/readyz` uptime check, the availability SLO, the alert
policies and four dashboards for the production Cloud Run service, applied by
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
| `__STAGING_SERVICE__` | `--staging-service` | `semper-api-staging` | Shown beside `__SERVICE__` on the service-health dashboard (same project) |
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
| `semper_activation` | `event="activation"` (licence key redemption) | `licenseId`, `outcome` (ok / refused), `errorCode`, `appId`, `appVersion` |
| `semper_checkout` | `event="checkout"` (floating seat) | `licenseId`, `outcome`, `errorCode`, `appId`, `appVersion` |
| `semper_devlock_drop` | `event="devlock_drop"` (licensed account resolved Demo at the device lock) | `licenseId`, `stage` (revoked / mismatch), `appId`, `appVersion` |
| `semper_auth_denied` | `action="AUTH_DENIED"`, not an `audit_write_failed` line | `stage` |
| `semper_revocation_check_failed` | `event="revocation_check_failed"` (step-up revocation check could not answer; refused, fail closed) | `errorCode` (exception class), `appId` |
| `semper_auth_admin_rate_limited` | `event="auth_admin_rate_limited"` (a Firebase Auth admin call was throttled) | none |
| `semper_app_check_failed` | `event` is `app_check_missing` or `app_check_invalid` (`APP_CHECK_MODE` monitor or enforce) | `verdict` (missing / invalid), `appId`, `appVersion` |

`appId` and `appVersion` are the caller's `X-App-Id` / `X-App-Version` (versionCode),
which `observability.event_payload` adds to every event line. `licenseId` stays a
label only where the activation-anomaly alert needs it; no chart splits by it.
The `appVersion` / `appId` labels on the three licensing metrics are additions to
metrics that may already exist: a rerun updates them in place (`gcloud logging
metrics update`), and the new labels count only from then on.

A metric counts only from the moment it exists, and only lines a deployed backend
writes: `activation`, `checkout` and `devlock_drop` come with the licensing
observability change, `audit_write_failed` with the audit change, and
`semper_auth_denied` with the change that makes `audit.record()` log an
`audit_denied` line (with `action` and `stage`) for every DENIED row as well as
writing it to Firestore (`backend/app/audit.py:64-69`), and
`semper_auth_admin_rate_limited` with the change that logs `auth_admin_rate_limited`
from `backend/app/google_auth.py`. Until the backend that writes a line is deployed,
its alert stays quiet and its chart flat, which is not the same as healthy.

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

## Dashboards (`dashboards/`)

Cloud Monitoring dashboards (`mosaicLayout`), found by `displayName`: a rerun lists
the one with that name and updates it with the etag it read, so a dashboard edited
in the console since is refused rather than overwritten unseen (rerun to take the
new etag). Console edits are lost on the next apply; change the file instead.

### semper-service-health
Cloud Run's own metrics for `__SERVICE__` and `__STAGING_SERVICE__`, one series per
service: requests/s by `response_code_class`; the 5xx ratio over 10 min (the
http-5xx-ratio alert's 2 % drawn in); request latency p50 / p95 / p99 (the 2 s p95
alert drawn in); instances by state (active / idle) against `--max-instances` 10;
container CPU and memory utilisation, p50 and p99 across instances. Last, the 5xx the
app itself answered, by route (`semper_http_5xx`, production only): the first place
to look when the ratio moves.

### semper-licensing
Hourly counts on production: activations, seat checkouts and device-lock drops, each
by outcome (or stage, for drops) and by app and build (`appId`, `appVersion`);
activation and checkout refusals by `errorCode`. A new build whose refusals climb
shows here before support hears of it. Per-licence counts are the activation-anomaly
alert's, not a chart's.

### semper-security
Per 5 min on production: AUTH_DENIED by `stage` (the auth-denied-spike threshold
drawn in, against the stacked total); audit write failures by `action`; revocation
check failures by exception class, beside Firebase Auth admin rate limits (a burst of
both is throttling, failures alone a lost `roles/firebaseauth.admin`); App Check
failures by verdict and by app and build. App Check lines are written in `monitor`
mode as well as `enforce`, and the line does not say which (its `enforcing` field is
not on the logging allowlist): in enforce mode the same requests are also AUTH_DENIED
with stage `app_check`. A passing App Check token is not logged, so there is no
pass rate.

### semper-slo
The 99.5 % availability SLO (`slo/availability.json`): scorecards for the SLI (good /
all) and the remaining error budget, the budget remaining over time
(`select_slo_budget_fraction`), the burn rate over 1 h and 6 h
(`select_slo_burn_rate`, with the fast-burn 14.4 and slow-burn 6 thresholds the alerts
use) and the SLI over time (`select_slo_health`).

## Not covered here

Crashlytics velocity alerts and Performance Monitoring (A7.1) are configured in the
Firebase console; Play Integrity failure ratios (A7.3) need an event the backend does
not log yet.
