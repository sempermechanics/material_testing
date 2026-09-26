# Backend cost

On 2026-09-26 the backend's whole bill was one idle Cloud Run instance. `semper-api`
ran with `minScale=1`, which keeps an instance billed around the clock whether or not
anyone calls it. Everything else fitted in a free tier. This file records the
measurement, the change made (scale to zero, clean up build storage), and the latency
options deliberately left for later.

Evidence labels: **[Measured]** means read from the live project `indicvision-dic-app`
here; **[Estimated]** means a measured quantity × list price. The project has no billing
export, so no rupee figure below is read from an invoice.

## Usage, 30 days to 2026-09-26

| Item | Measured | Cost at this volume |
|---|---|---|
| `semper-api` billable instance time | 244,010 s in the ~3 days since the service was created, ≈ 94 % of wall-clock **[Measured]** | ≈ $10–14 (₹800–1,150) a month for one idle 1 vCPU / 512 MiB minimum instance **[Estimated]**, over the ₹500/month budget `semper-pilot-cap` |
| Cloud Run requests, all services | ~1,170 **[Measured]** | free tier |
| API Gateway calls | 1,492 production + 108 staging **[Measured]** | free (under 2 M/month) |
| Firestore | 20.4k reads, 2.9k writes, 348 deletes **[Measured]** | free tier (50k reads a **day**) |
| Cloud Run egress | 150 MB **[Measured]** | ≈ free |
| Cloud Logging ingested | 13.5 MB **[Measured]** | free (50 GiB) |
| GCS (backups + run-sources) | ~10 MB **[Measured]** | ≈ ₹0 |
| Artifact Registry `cloud-run-source-deploy` | 1.24 GB, 32 image versions of ~80 MB, no cleanup policy **[Measured]** | ≈ ₹6/month above the 0.5 GB free tier, growing ~80 MB per deploy **[Estimated]** |
| Cloud Build | 36 builds **[Measured]** | free tier |

**Method.** Cloud Monitoring time series over the window, summed:
`run.googleapis.com/container/billable_instance_time`, `run.googleapis.com/request_count`,
`run.googleapis.com/container/network/sent_bytes_count` (resource
`cloud_run_revision`); `serviceruntime.googleapis.com/api/request_count` (API Gateway);
`firestore.googleapis.com/document/{read,write,delete}_count`;
`logging.googleapis.com/billing/bytes_ingested`; `storage.googleapis.com/storage/total_bytes`.
Registry size is `sizeBytes` from `gcloud artifacts repositories describe`; the build
count is from `gcloud builds list`. Live service settings are from
`gcloud run services describe`.

## Latency at the time (7 days, `semper-api` access log) [Measured]

| Route | p50 |
|---|--:|
| `POST /v1/sessions` | 2.5 s (6.1 s on 2026-09-22, before `43eeb641` / `122acd74`) |
| `POST /v1/files/{id}/complete` | 0.66 s |
| `GET /v1/sessions` | 127 ms |
| `GET /v1/config` | 53 ms |
| `GET /v1/me` | 49 ms |

Cold start (`run.googleapis.com/container/startup_latencies`): **p50 3.9 s, p95 6.0 s**.
`/v1/challenge` saw only 26 calls, which shows the client nonces are in use.

## Change 1: scale production to zero

`deploy-backend.yml` now defaults `--min-instances` to `0` in both environments. Before,
production got `1` and staging `0`. The `MIN_INSTANCES` repository variable is still the
switch back ([ENVIRONMENTS.md](../ops/ENVIRONMENTS.md)).

**Trade.** The first call after about 15 minutes idle waits for a cold start: p50 3.9 s,
p95 6.0 s. What already ships softens that without removing it. Home opens from cache,
and the app's first calls already wait 1.3–1.8 s on App Check
([request-volume.md](request-volume.md) Pass 3), which overlaps the start.

**Not taken.**
- Precompiling `app/` in the Dockerfile. Compiling all 52 modules takes 53 ms, under 5 %
  of a 3.9 s start, the floor this folder uses.
- Trimming Firestore or Drive calls: they are in the free tier.
- Moving restore from Drive to GCS: restore traffic is ~150 MB a month.
- Removing API Gateway: see below.

## Change 2: build storage cleanup

The registry and the `run-sources-…` bucket gain a copy of the service on every source
deploy. Both now delete anything more than 15 days old. The registry keeps the image
tagged `latest` (serving), any image with a tag starting `rollback` (hand-pinned rollback
images, added 2026-09-26), and each package's five newest versions. So a serving revision
can always start a new instance and the rollbacks stay available. Artifact Registry records
no last-pull time, so age is the only usable signal for "unused". The policy files are in
[`backend/deploy/`](../../backend/deploy/) and the apply steps are in
[BACKEND_SETUP_GCP.md A7](../backend/BACKEND_SETUP_GCP.md#a7-storage-hygiene). Both were
applied on 2026-09-26, with the registry policy enforcing. On that day no image was old
enough to qualify (1.35 GB, 35 versions, oldest 2026-09-23), so the first deletions fall
on about 2026-10-08. After that, check that the registry size has levelled off and that
both services still cold-start.

## Deferred: latency

API Gateway costs nothing at this volume. Its hop to Tokyo (asia-northeast1) and back
to Mumbai stays accepted as TD-30 ([TECH_DEBT.md](../ops/TECH_DEBT.md)). Two options
remain for the day latency is worth money. The trigger is a latency complaint or a
public launch.

1. **Call Cloud Run directly** from new app builds, and retire the gateway once its
   traffic reaches about zero. This costs no money, but the gateway's jobs move into the
   service or are dropped: its Firebase-token check at the edge, its per-consumer quotas
   and per-route deadlines (`backend/gateway/openapi.yaml`). Cloud Run would also have to
   accept unauthenticated ingress, since the app layer does the authentication.
2. **Global HTTPS load balancer with a serverless NEG** in front of Cloud Run, about
   $20–25 a month **[Estimated]**.

Both need an app release, because the gateway host is compiled into shipped builds.
Keeping one instance warm (`MIN_INSTANCES=1`) is the cheapest way to remove the
cold start alone.

## Re-measuring

Run the same metrics 24–48 h after a deploy. `billable_instance_time` for `semper-api`
should fall from ~86,400 s a day to roughly the time spent serving requests. The
`startup_latencies` count goes up, which is expected, with p50 near 4 s. At month end,
Billing → Reports should show no Cloud Run "Idle Min-Instance" line and total spend
under ₹500.
