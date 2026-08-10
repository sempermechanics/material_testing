# Alpha usage metering

How to measure cloud API usage during private alpha testing on a real device
(Pixel / sideloaded beta APK). DIC **analysis does not hit the API** — cloud
cost is identity, backup/sync/restore, and (for restore) Cloud Run egress.

Labeled access logs require a backend revision that emits `opClass` (see
`backend/app/observability.py`). Deploy staging/production **before** metering
runs that expect those fields.

Related: [RELEASING.md](RELEASING.md) (alpha APK), [CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §17.

## Operation classes (`opClass`)

| opClass | Typical routes |
|---|---|
| `health` | `/healthz`, `/readyz` |
| `login` | `GET /v1/me`, `POST /v1/devices/register` |
| `config` | `GET /v1/config` |
| `attest` | `POST /v1/challenge` (shared by every signed call — do not fold into login/backup) |
| `backup` | `POST /v1/sessions`, `GET …/uploads`, `POST …/complete`, `DELETE /v1/sessions/{id}`, provision task |
| `sync` | `GET /v1/sessions` |
| `restore` | `GET …/files`, `GET /v1/files/{id}/content` |
| `admin` | `/v1/admin/*` |
| `account` | export / erase account |
| `other` | unmatched |

Session create also logs PII-safe ints when present: `fileCount`, `frameCount`
(from the client `metrics` map). Those appear on the same `http_access` line and
in `audit_logs` `SESSION_CREATE.detail`.

## Expected call recipes (current client)

Sources: `DicUploadWorker`, `CloudSync`, `CloudRestore`, `IndicApi`.

### Login (cold, after Firebase Auth)

1. `GET /v1/me` → `login`
2. `GET /v1/config` → `config`
3. `POST /v1/challenge` + `POST /v1/devices/register` → `attest` + `login`

Firebase Auth itself is **not** Cloud Run.

### Home pull-to-sync (throttled ~5 min)

1. Often `GET /v1/config` when quota unknown → `config`
2. Paginated `GET /v1/sessions` → `sync` (each page; deep `verify=true` adds Drive probes server-side)
3. Attested paths also burn `attest` challenges

### Backup after one analysis (2-file Session.zip model)

1. `attest` + `POST /v1/sessions` → `backup` (access log may include `frameCount` / `fileCount`)
2. Poll `GET …/uploads` while `PROVISIONING` → `backup` (+ `attest` each)
3. Device **PUT bytes to Drive** (not Cloud Run)
4. Two × (`attest` + `POST …/complete`) → `backup`
5. Server task `POST /v1/tasks/provision-session` → `backup` (Cloud Tasks → Cloud Run)

Single vs sweep differ on-device; cloud mainly sees metrics (`frameCount`, `isSweep`).

### Restore one session

1. `GET /v1/sessions` (list) → `sync` if list not cached
2. `GET …/files` → `restore`
3. One or more `GET /v1/files/{id}/content` → `restore` (**bytes proxied through Cloud Run**)

### Delete one cloud backup

1. `attest` + `DELETE /v1/sessions/{id}` → `backup`

## Alpha device matrix

Hard gate: **do not start** until the tester confirms they are logged in on Home.

| # | Scenario | App actions | Meter |
|---|---|---|---|
| 1 | Warm Home sync | Pull-to-sync | `sync`, `config`, `attest` + RAM/ROM baseline |
| 2 | Single (small) | Few frames → Compute → wait upload badge clear | `backup` + counts + peak PSS / ROM Δ |
| 3 | Sweep (small) | Small ranges → lattice → View → upload | `backup` + metrics + peak PSS / ROM Δ |
| 4 | Restore | Restore one cloud-only / freed session | `restore` (+ egress) + ROM Δ after download |
| 5 | Delete | Delete one cloud-backed row | `DELETE` session + ROM Δ |
| 6 | Heavy | `AAA5083_H111 - PLC band` full import → single analysis → upload | `backup`, frames/files, Drive size, **peak PSS**, **ROM peak/Δ** |

Keep light sets small so the heavy PLC run dominates image-related cost.

## Device RAM and ROM (on-phone)

Cloud Logging does not see phone memory. During each matrix scenario also capture:

| Signal | Meaning | How |
|---|---|---|
| **PSS** (`pss_kb`) | Process RAM (shared pages proportionally) | App `AlphaMeter` samples + `adb dumpsys meminfo` |
| **Java / native heap** | Runtime + native allocations | `AlphaMeter` CSV |
| **ROM** (`rom_bytes`) | App code + private data + cache (`StorageStats`) | `AlphaMeter` CSV |
| **sessions_bytes** | Local analysis tree under app files | `AlphaMeter` CSV |
| **cache_dir_bytes** | Clearable temp cache | `AlphaMeter` CSV |

The app appends rows to:

`/sdcard/Android/data/com.indicvision.semper/files/alpha_meter/samples.csv`

and prints the same snapshot to logcat tag **`AlphaMeter`** (works on release betas).

Labels include: `cold_start`, `import_done_nN`, `analysis_saved_*`, `viewer_open_fN`,
`viewer_close`, `backup_prepare_start`, `backup_zip_*`, `backup_done`,
`restore_done`, `cache_cleared`.

### WSL / laptop commands (USB debugging)

```bash
# While a scenario runs — PSS every 5s
./scripts/meter_alpha_device_memory.sh watch

# After done:N — pull app CSV + summarize peaks
./scripts/meter_alpha_device_memory.sh pull
./scripts/meter_alpha_device_memory.sh summarize

# Optional: live AlphaMeter lines
adb logcat -s AlphaMeter:I
```

Fill the report’s **Peak PSS** / **ROM Δ** columns from `summarize` (or the CSV).
For scenario deltas, note ROM at `start:` vs `done:` (or `cold_start` vs
`backup_done` / `viewer_open_*`).

## How to meter one scenario

1. Note **UTC start**, tester **uid**, scenario name.
2. Start `./scripts/meter_alpha_device_memory.sh watch` (optional but preferred for peak PSS).
3. Run the scenario; wait until UI settles (upload complete / restore done).
4. Note **UTC end**; stop watch; `pull` + `summarize`.
5. Query Cloud Logging (project that hosts Cloud Run), e.g.:

```text
resource.type="cloud_run_revision"
jsonPayload.event="http_access"
jsonPayload.uid="USER_UID"
timestamp>="START_RFC3339"
timestamp<"END_RFC3339"
```

Group / count by `jsonPayload.opClass` and `jsonPayload.routeTemplate`.

gcloud example:

```bash
PROJECT_ID=... USER_UID=... FRESHNESS=2h ./scripts/meter_alpha_usage.sh
```

6. Cross-check Firestore `audit_logs` for the same window (`SESSION_CREATE`,
   `UPLOAD_COMPLETE` / complete actions, `FILE_DOWNLOAD`, session delete).
7. Separate **Drive upload bytes** (Drive admin / API metrics; device→Drive) from
   **Cloud Run restore egress** (`GET …/content`).
8. Record **peak PSS** and **ROM Δ** from the device meter summarize step.

## Interpreting results

- High `attest` with low `backup` usually means polling / signed GETs, not more uploads.
- `frameCount` on session create tracks analysis size; Cloud Run CPU for backup
  create is mostly metadata — byte cost is Drive until restore.
- Restore cost scales with bundle size through Cloud Run; prefer measuring one
  restore of the heavy PLC session if you need restore-vs-frames data.

## Human-driven checklist (cloud agent cannot see laptop USB)

When the Pixel is attached only to a laptop:

1. Install the private GitHub Release **beta** APK.
2. Copy `AAA5083_H111 - PLC band` onto the phone (or ensure Files picker sees it).
3. Sign in; message the agent **“logged in”**.
4. For each matrix row: message **“start: \<scenario\>”**, start
   `meter_alpha_device_memory.sh watch`, perform actions, then **“done: \<scenario\>”**
   with approximate local time or UTC; `pull` + `summarize` device CSV.
5. Agent runs Logging / audit queries and fills the usage report table
   (including Peak PSS / ROM Δ when CSV is pasted).
