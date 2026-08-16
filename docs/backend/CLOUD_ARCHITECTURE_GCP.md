# Semper — Production Cloud Architecture (Pure GCP, Keyless)

**You probably don't need this document.** Analysis is fully offline and the
cloud is off unless someone builds with `INDIC_API_BASE_URL` set. Read it if
you are changing `backend/` or the sync path in `app/.../data/`.

| If you want to… | Go to |
|---|---|
| Deploy it and see it work | [BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md) |
| Understand why Drive and not GCS | [§0](#0-the-one-hard-truth-up-front-drive-is-not-gcs) |
| Follow a request end to end | [§2 auth](#2-authentication-flow) → [§4 upload](#4-upload-sequence-5-gb-resumable-keyless) |
| Find the code for a concept | [§7 backend map](#7-implementation-map) · [§8 Android map](#8-android-client-map) |
| Know the data shape | [§5 Firestore](#5-firestore-schema) · [§6 Drive layout](#6-google-drive-folder-hierarchy) |
| Fix sign-in | [AUTH_SETUP.md](AUTH_SETUP.md) |

> **Status / scope.** This document specifies the **GCP-native** backend:
> Firebase Auth → Cloud Run (FastAPI) → Firestore → Google Drive, with **no
> service-account JSON keys anywhere** and **no image processing in the cloud**.
> This is the only backend. The on-device engine
> ([ARCHITECTURE.md](../engine/ARCHITECTURE.md)) is unchanged — the cloud only does
> identity, metadata, orchestration, and audit.

**Non-negotiables baked into this design**

| Constraint | How it is honored |
|---|---|
| No JSON service-account keys (Workspace policy) | Cloud Run runs *as* a service account; Drive tokens are minted via **IAM Credentials `generateAccessToken`** (self-impersonation). Zero key material at rest. |
| Developer is not a Workspace admin | The only admin-gated step is a one-time "allow adding a service account to a Shared Drive" toggle. Everything else is a normal-user or Cloud-project action. |
| Storage stays in company Google Drive (5 TB) | The Cloud Run SA is a **Manager** of a company **Shared Drive**; writes count against the org pool. (Manager, not Content manager: `files.delete` requires organizer rights, so erasure fails otherwise.) |
| Cloud never processes images | Cloud Run only creates folders, initiates resumable sessions, and records metadata. **Bytes never transit Cloud Run** — the client PUTs directly to Drive's resumable session URI. |
| No secrets in Android | Android holds only public config (`google-services.json`, which ships in every APK by design). The device private key lives in Android Keystore and never leaves the device. |
| $0 during pilot | Scale-to-zero Cloud Run, Firestore free tier, no egress through the backend. |

---

## 0. The one hard truth up front: Drive is not GCS

This comparison comes first because it shapes every downstream decision.

| Capability | Google Cloud Storage | Google Drive (your constraint) |
|---|---|---|
| Direct client **upload** without backend touching bytes | ✅ V4 **signed URL** (fully anonymous, per-object, ≤7-day TTL) | ⚠️ **Resumable session URI** only — broker must initiate it; client then PUTs directly. Works, but the URI is minted by the SA, not signed offline. |
| Direct client **download** without backend touching bytes | ✅ signed URL | ❌ **No anonymous signed download.** Requires an OAuth token or making the file link-shared. Downloads must be **brokered** or link-shared (privacy hit). |
| Per-object access control | ✅ IAM / signed policy | ❌ Coarse: file/Shared-Drive membership only. |
| Object size | 5 TiB | 5 TB file OK, but **750 GB/user/day** upload ceiling and **400,000 items/Shared Drive**. |
| Throughput / quota | Effectively unlimited | Drive API **12,000 queries/min**/project; upload throughput fine but item/day caps bite at scale. |
| Cost model | Pay per GB + egress | **Free** (already-paid 5 TB Workspace pool) — this is the whole reason to use it. |

**Conclusion.** Drive is the right choice *now* purely because the 5 TB is
already paid for and must stay in-org. It is a **capacity and download-ergonomics
liability at scale**. The architecture is therefore built so that **the Android
client API is storage-agnostic** — migrating Drive→GCS later is a backend-only
change (see §19).

---

## 1. Production architecture diagram

```
                         ┌───────────────────────────────────────────┐
                         │             Android device                 │
                         │  Android Views UI (XML + findViewById)     │
                         │  ├─ Firebase Auth (Firebase ID token)      │
                         │  ├─ Android Keystore (device private key)  │
                         │  ├─ WorkManager CoroutineWorker            │
                         │  └─ OkHttp streaming (chunked resumable)   │
                         │  ── DIC / OpenCV / PDF: 100% on-device ──  │
                         └───────────────┬───────────────────────────┘
                                         │ HTTPS (ID token + device signature)
                                         ▼
              ┌────────────────────── Google Cloud project ──────────────────────┐
              │                                                                   │
              │   ┌──────────────┐   verify ID token (firebase-admin: certs,     │
              │   │  Cloud Run   │◀── aud, iss, exp) + verify device signature   │
              │   │  FastAPI     │                                               │
              │   │  (scale→0)   │──▶ Firestore (users, devices, sessions,       │
              │   │  runs as SA  │        files, audit_logs)                     │
              │   │ indic-api@…  │                                               │
              │   └──────┬───────┘                                               │
              │          │ IAM Credentials generateAccessToken                   │
              │          │ (Drive scope, keyless self-impersonation)             │
              │          ▼                                                       │
              │   ┌──────────────┐   create folders, init resumable session     │
              │   │ Drive API    │   (metadata only — NO bytes)                  │
              │   └──────┬───────┘                                               │
              │          │ returns resumable session URI                        │
              │  Cloud Logging / Monitoring / Error Reporting  ◀── structured    │
              └──────────┼────────────────────────────────────────────logs──────┘
                         │ session URI handed back to device
                         ▼
              ┌───────────────────────────────────────────────┐
              │  Company Google Workspace — Shared Drive       │
              │  "Semper-Research-Storage" (5 TB pool)          │
              │  SA is Manager. Device PUTs bytes here        │
              │  DIRECTLY (never through Cloud Run).           │
              └───────────────────────────────────────────────┘
```

**Trust boundaries.** (1) Device↔Cloud Run: mutually authenticated (the
Firebase ID token proves *user*; the Keystore signature proves *device*). (2) Cloud
Run↔Google APIs: keyless, via the metadata server + IAM Credentials. (3)
Device↔Drive: capability-scoped — the resumable session URI authorizes writes
to *exactly one file*, nothing else.

---

## 2. Authentication flow

Identity is federated through **Firebase Authentication** — Google, email link,
or email/password, all producing one **Firebase ID token** (a JWT, ~1 hour).
The backend **re-verifies on every request** with `firebase-admin`: stateless,
with zero server-side key management. The client refreshes silently through the
Firebase SDK.

```mermaid
sequenceDiagram
    participant A as Android
    participant F as Firebase Auth
    participant R as Cloud Run (FastAPI)
    A->>F: sign in (Google / email link / password)
    F-->>A: Firebase ID token (aud = firebase project id)
    A->>R: GET /v1/me  (Authorization: Bearer <ID token>)
    R->>F: fetch Google public certs (cached, rotating)
    R->>R: firebase_admin.verify_id_token: signature, exp, iss, aud
    R->>R: get_or_create_user(claims) → role + access_status
    R->>R: reject unless access_status == APPROVED
    R-->>A: 200 {user profile, access_status}
    Note over R: verify failure → 401 · not approved → 403 · both audit-logged
```

Claims consumed downstream: `sub` (the stable Firebase uid, identical across
providers for one account), `email`, `email_verified`, `name`, and
`firebase.sign_in_provider`.

**Why re-verify vs. minting our own session JWT.** Re-verifying needs **no
signing secret** — perfectly aligned with "no secrets." If per-request cert
verification ever becomes a latency concern, mint a short-lived backend session
JWT using **IAM Credentials `signJwt`** (Google signs it; verifiable via the
SA's public JWKS) — still keyless. Not needed at pilot scale.

**Access control is a separate decision from authentication.** Verification
proves identity; it does not grant entry. `get_or_create_user`
([firestore_repo.py](../../backend/app/firestore_repo.py)) assigns:

1. `role = admin` if a **verified** email is in `ADMIN_EMAILS`;
2. `access_status = APPROVED` if admin, or `AUTO_APPROVE=1`, or a **verified**
   email at `AUTO_APPROVE_HD`;
3. otherwise `PENDING` — authenticated but refused with `403 not_approved`
   until an admin approves them.

Auto-approval always requires `email_verified`, so a fresh email/password
signup cannot claim a privileged domain it does not own.

> **There is no hosted-domain gate on sign-in, by design.** Any account
> Firebase Auth accepts can authenticate; the `PENDING`/`APPROVED` status is
> the control that holds. This is what makes the "outside collaborator requests
> access" flow work. (Earlier revisions carried an `ALLOWED_HD` setting that no
> code read — it has been removed rather than left to imply a gate that was
> never there.)

---

## 3. Device registration & challenge-response

Device identity = an EC P-256 key pair generated **inside** Android Keystore
(`StrongBox` when available). The private key is non-exportable; only the
public key and a stable `deviceId` (a random UUID, *not* IMEI/serial/MAC) leave
the device.

```mermaid
sequenceDiagram
    participant A as Android (Keystore)
    participant R as Cloud Run
    participant F as Firestore
    A->>A: generateKeyPair(EC P-256, StrongBox, user-auth optional)
    A->>R: POST /v1/devices/register {deviceId, publicKeyPem, model, osVer}\n(Authorization: ID token)
    R->>F: users/{uid} exists? one active device already bound?
    alt no active device
        R->>F: devices/{deviceId} = {uid, publicKeyPem, status: ACTIVE}
        R-->>A: 201 registered
    else already bound to another device
        R-->>A: 409 device_conflict (needs admin re-bind)
    end

    Note over A,R: Every subsequent mutating request:
    A->>R: POST /v1/challenge (ID token) 
    R->>F: store nonce (TTL 120s) bound to uid+deviceId
    R-->>A: {nonce}
    A->>A: sig = Keystore.sign(nonce || method || path || bodySHA256)
    A->>R: POST /v1/sessions ... headers: X-Device-Id, X-Nonce, X-Signature
    R->>F: load devices/{deviceId}.publicKeyPem; verify sig; consume nonce
    R-->>A: 200 (or 401 bad_signature / 409 nonce_replay)
```

**One-user-one-device binding.** `devices/{deviceId}.uid` is unique per active
device. Re-registering the *same* `deviceId` for its owner is idempotent — it refreshes
the stored public key and returns `201` with `healed: true` (see
`register_device` in `backend/app/routers/devices.py`). Registering a *different* second device for a
`uid` that already has one returns `409 device_conflict`; moving to genuinely
new hardware means an admin revokes the prior device first. (There is no
`:rebind` endpoint — that was a design idea, not something implemented.)

**Device records are settled, not orphaned.** Both transitions now write the old
record rather than leaving it `ACTIVE` and unreachable:

| Event | What happens to the device docs |
|---|---|
| Admin revokes or suspends a user | **Every** device of that uid moves to `REVOKED` with a `revokedAt`, and `users/{uid}.activeDeviceId` is cleared (`set_user_status` in `firestore_repo.py`) |
| A new device is registered after that | The previous device moves to `SUPERSEDED` with a `revokedAt`, so history shows *why* it stopped being usable rather than just vanishing |

Admin revoke itself requires a `verified_device` caller — an admin cannot revoke
from an unattested session.

**Latency note.** A per-request challenge round-trip doubles RTT. For hot paths
you may fold it into a **signed-timestamp assertion** (client signs
`timestamp||method||path||bodyHash`; server accepts a ±120 s skew window and
caches used signatures to block replay) — same security property, one fewer
round-trip. The challenge endpoint remains for registration and sensitive
admin actions.

---

## 4. Upload sequence (5 GB, resumable, keyless)

### 4.1 Provisioning is asynchronous

Opening one Drive resumable session per file used to happen **inline** inside
`POST /v1/sessions`. At the 600-file ceiling that is roughly 1200 sequential
round-trips inside a 60-second Cloud Run request budget, so a large analysis
simply could not be uploaded. Provisioning now runs in a **Cloud Task** and the
request only reserves the session.

```mermaid
sequenceDiagram
    participant W as WorkManager Worker (OkHttp)
    participant R as Cloud Run
    participant T as Cloud Tasks
    participant D as Google Drive (Shared Drive)
    W->>R: POST /v1/sessions {specimen, files:[…]} (ID token + device sig)
    R->>Firestore: sessions/{sid} = PROVISIONING, files/{fid} = PENDING
    R->>T: create task provision-{sid} (OIDC, deterministic name)
    R-->>W: 200 {sessionId, status:"PROVISIONING", uploads:[]}
    T->>R: POST /v1/tasks/provision-session {sessionId} (OIDC token)
    R->>D: create folder tree, then open resumable sessions (bounded fan-out)
    R->>Firestore: store session URIs, sessions/{sid} = UPLOADING
    loop until status is UPLOADING
        W->>R: GET /v1/sessions/{sid}/uploads
        R-->>W: 202 still PROVISIONING, or 200 {uploads:[{fileId, uploadUrl, chunkSize}]}
    end
```

Three properties worth knowing before you change this path:

- **The task name is deterministic** (`provision-{sid}`), so Cloud Tasks
  de-duplicates. A retried `create_session` for the same session cannot
  double-provision; an `AlreadyExists` on enqueue is treated as success.
- **Enqueue failure is not request failure.** `enqueue_provision` returns `False`
  when the queue is unconfigured or the call fails, and the caller provisions
  **inline** instead. Leaving `TASKS_QUEUE` empty is therefore a supported
  configuration — local dev and the test suite run that way, and the client
  cannot tell the difference beyond latency.
- **A failed provision is recorded, not silent.** The task marks the session
  `PROVISION_FAILED` with an error code so Cloud Tasks can retry and a polling
  client is told to stop waiting.

`/v1/tasks/provision-session` is authenticated by `tasks.tasks_caller`, not by
anything in `deps.py`: the caller is Google, so there is no uid, no device and no
`access_status`. It verifies the OIDC token against the configured audience
**and** requires the token's email to equal `TASKS_INVOKER_SA` — the audience
check alone is not authentication, because any Google account can mint a token
for a public audience.

### 4.2 Transfer and completion

```mermaid
sequenceDiagram
    participant W as WorkManager Worker (OkHttp)
    participant R as Cloud Run
    participant IAM as IAM Credentials
    participant D as Google Drive (Shared Drive)
    Note over R,IAM: token = generateAccessToken(SA, scope=drive) — NO json key
    loop each file, 32 MiB chunks
        W->>D: PUT uploadUrl  Content-Range: bytes a-b/total  (direct, no backend)
        D-->>W: 308 Resume Incomplete (Range: bytes=0-b)
    end
    W->>D: PUT final chunk
    D-->>W: 200/201 {id, md5Checksum, size}
    W->>R: POST /v1/files/{fileId}/complete {driveFileId, md5, bytes}
    R->>R: verify size + (client md5 vs Drive md5Checksum)
    R->>Firestore: files/{fid}=COMPLETED; if all done → sessions/{sid}=COMPLETED
    R->>Firestore: audit_logs += UPLOAD_COMPLETED
    R-->>W: 200
```

**Resume after crash / network loss.** The resumable session URI survives ~1
week. On restart the worker issues `PUT uploadUrl` with
`Content-Range: bytes */TOTAL` (zero-length body) → Drive replies `308` with the
last-received byte in the `Range` header → worker resumes from there. No bytes
re-sent. WorkManager's `BackoffPolicy.EXPONENTIAL` + network constraint handles
retry/offline.

**Integrity.** At `:complete` the backend asks Drive for the object's real
`size`/`md5Checksum` and rejects a mismatch with `422` (`size_mismatch` /
`checksum_mismatch`), leaving the file `PENDING` rather than marking it
`COMPLETED` — see `complete_file` in `backend/app/routers/files.py`. It does **not** currently mark
`FAILED`, delete the Drive object, or auto-re-enqueue; the client retries the
completion. Whenever Drive reports an `md5Checksum` (always for our binary
blobs), the client **must** supply a matching `md5`; omitting it is treated as
`checksum_mismatch`. The check is skipped only when Drive itself has no md5
(Docs-native types we never store). The Android client computes the local file
MD5 during upload so `:complete` does not depend on Drive's completion JSON
including `md5Checksum`.

**Session binding (confused-deputy guard).** `:complete` does not trust the
client's claim that a `driveFileId` belongs to its session. `get_file_meta` reads
the object's `parents` from Drive and the backend rejects any file whose parent is
not the caller's own session folder — so a valid token cannot bind an arbitrary
Drive object (or another user's) into its session record. This is why the upload
init requests `fields=id,md5Checksum,size` and completion re-fetches
`size,md5Checksum,parents` rather than believing the PUT response.

**Upload targets require attestation.**
`GET /v1/sessions/{sid}/uploads` hands out Drive upload capability URLs, so it is
gated by `deps.device_or_legacy_reader` rather than a plain ID-token dependency.
While `REQUIRE_ATTESTED_UPLOADS` is unset it accepts either a full device
signature *or* an ID token alone (startup warning). **Production keeps
`REQUIRE_ATTESTED_UPLOADS=1`**; `deploy-backend.yml` pins that from the GitHub
var of the same name — an empty var clears the flag on redeploy. Every other
write and mint path already requires a device signature.

**A Drive outage must never erase session metadata.** `GET /v1/sessions?verify=true`
probes whether each session's blobs still exist, and purges Firestore metadata
for ones that are gone. The probe returns three states, not two:

| Probe result | Meaning | What the backend does |
|---|---|---|
| `ALIVE` | Drive confirmed the object | Nothing |
| `MISSING` | Drive confirmed it is gone | Purge the session metadata |
| `UNKNOWN` | The probe raised — Drive 5xx, timeout, expired token | **Nothing.** Counted into an `indeterminate` tally |

The response carries `{requested, purged, indeterminate}` so the client knows the
verification was incomplete, and a non-zero `indeterminate` is logged at WARNING
with `dependency="drive"`. Collapsing `UNKNOWN` into `MISSING` would turn a
transient outage into permanent data loss for the user, which is exactly the bug
this shape exists to prevent — see the `MISSING` / `UNKNOWN` constants in
`drive.py`.

**Resumable restore download.** Restore streams bytes back through Cloud Run
(there is no anonymous signed download URL). Drive honors HTTP `Range` on
`alt=media`, so `open_download` forwards a `Range` header. The Android client
requests **bounded 1 MiB windows** (`bytes=N-M`) so each GET finishes inside the
API Gateway deadline; a truncated chunk resumes from the `.part` file length
(`206` + `Content-Range`) instead of restarting the whole Session.zip. After
changing `gateway/openapi.yaml` deadlines, recreate the API Gateway **api-config**
(Cloud Run deploy alone does not update the gateway).

**One Session.zip per session (current), not per-file objects.** The product now
uploads a single `bundle`-role `Session.zip` holding `raw/`, `dat/`, `csv/` and
the report archives (plus a small `metadata.json` at the session root), so a
session costs ~1–2 Firestore file docs instead of 3F+4. This trades in-Drive
browsability of individual frames for far fewer resumable inits and Firestore
writes. The `raw/processed/reports/metadata` subfolder tree below is the older
per-file layout, kept for reference.

---

## 5. Firestore schema

Native mode, `nam5`/regional to match Cloud Run region. Collections:

```
users/{uid}                       (uid = Google 'sub')
  email, displayName              (note: `hd`/hostedDomain is NOT persisted today —
                                   get_or_create_user does not write it, so
                                   /v1/me/export returns hostedDomain: null)
  role: "user" | "admin"
  access_status: "PENDING" | "APPROVED" | "SUSPENDED"
  activeDeviceId: string | null
  driveFolderId                   (…/user/{uid} folder)
  maxSessions, maxFilesPerSession, maxFrames   (optional per-user quota overrides)
  schemaVersion                   (stamped by backend/scripts/migrate_schema.py)
  createdAt, updatedAt, lastSeenAt (Timestamp)

devices/{deviceId}                (deviceId = client UUID)
  uid, publicKeyPem
  status: "ACTIVE" | "REVOKED" | "SUPERSEDED"
  revokedAt                       (set on REVOKED and SUPERSEDED)
  model, osVersion, appVersion
  registeredAt, lastAssertionAt

challenges/{nonce}                (short-lived, TTL-deleted)
  uid, deviceId, createdAt, expireAt (Timestamp, TTL policy)

sessions/{sessionId}
  uid, deviceId
  specimen: string
  status: "PENDING" | "PROVISIONING" | "PROVISION_FAILED"
        | "UPLOADING" | "COMPLETED" | "FAILED"
        (PROVISIONING and UPLOADING are IN_FLIGHT_STATUSES — both still
         expect more bytes and both count against the session quota)
  driveFolderId                   (…/session/{sid} folder)
  totalBytes, fileCount, completedCount
  metrics: { pointsConverged, avgIcgnIters, execMs }  // small, from device
  createdAt, updatedAt, completedAt

files/{fileId}                    (fileId = deterministic sid_role_name)
  sessionId, uid
  role: "raw" | "processed" | "reports" | "metadata" | "csv" | "dat" | "bundle"
        (see Role in models.py; "bundle" is the Session.zip the app now ships)
  name, sizeBytes, sha256
  status: "PENDING" | "UPLOADING" | "COMPLETED" | "FAILED"
  uploadUrl                       (resumable session URI, cleared on complete)
  driveFileId, driveMd5
  createdAt, updatedAt

audit_logs/{autoId}               (append-only)
  ts (Timestamp), uid, deviceId, ip, ua
  action: "LOGIN" | "DEVICE_REGISTER" | "DEVICE_REBIND" |
          "SESSION_CREATE" | "UPLOAD_COMPLETE" | "AUTH_DENIED" | ...
  target: { type, id }
  outcome: "OK" | "DENIED" | "ERROR"
  detail: map
```

**Indexing.**
- `backend/firestore.indexes.json` is the source of truth and defines **four
  composite indexes**: `sessions(uid, localSessionId, status)`,
  `sessions(uid, __name__)`, `users(access_status, __name__)` and
  `files(sessionId, __name__)`. The paginated listing and the admin pending-user
  query both need one. Deploy them with
  `firebase deploy --only firestore:indexes` — a missing index shows up as a
  `FAILED_PRECONDITION` at runtime, not at deploy time.
- **Exempt** large/opaque fields from indexing (`publicKeyPem`, `uploadUrl`,
  `sha256`) to cut index cost and stay off the 40 KB/1500-field limits.
- **TTL policy** on `challenges.expireAt` is a *field* policy, not an index, so it
  cannot live in that file. Enable it as part of operator setup — step A2a of
  [BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md). `consume_nonce` deletes a nonce on
  use; TTL reclaims the ones that are never consumed. An optional retention TTL on
  `audit_logs.ts` (e.g. 400 days) is still just a suggestion.

Security: Firestore is **written only by the Cloud Run SA** (server-side). No
Android SDK writes → Firestore Security Rules can be `allow read, write: if
false` (deny all client access); all access mediated by FastAPI. This removes a
whole class of rules-bypass risk.

---

## 6. Google Drive folder hierarchy

```
Shared Drive: Semper-Research-Storage/        (SA = Manager/organizer)
└── Research Storage/
    └── user/{uid}/
        └── session/{sessionId}/
            ├── raw/          Reference.png, Deformed_0001.png, …
            ├── processed/    heatmaps, overlays
            ├── reports/      Master_Report_0001.pdf
            └── metadata/     session.json  (device-authored, verbatim)
```

`{uid}` = Google `sub` (stable, opaque, not PII-leaking like email in a path).
Folder IDs are cached in `sessions.driveFolderId` / a `folders` map to avoid
re-resolving by name (name lookups are slow and race-prone).

---

## 7. Implementation map

The code is the specification — these modules are implemented and deployed, so
read them rather than a sketch. Sections 2–6 above explain *why* each behaves
the way it does.

| Concern | File | Notes |
|---|---|---|
| FastAPI app, middleware, lifespan | [`backend/app/main.py`](../../backend/app/main.py) | App factory; includes routers below |
| Routes by prefix | [`backend/app/routers/`](../../backend/app/routers/) | `health`, `account`, `devices`, `sessions`, `files`, `provision_tasks`, `admin` |
| Session provision / purge | [`backend/app/session_provision.py`](../../backend/app/session_provision.py) | `provision_session` / `purge_session` |
| Auth + device dependencies | [`backend/app/deps.py`](../../backend/app/deps.py) | Bearer verify, device-signature check, `device_or_legacy_reader` (§4) |
| ID-token verify, keyless Drive token | [`backend/app/google_auth.py`](../../backend/app/google_auth.py) | Self-impersonation to add the Drive scope (§2) |
| Drive folders, resumable init, blob probe | [`backend/app/drive.py`](../../backend/app/drive.py) | Returns the opaque upload URI, and `ALIVE`/`MISSING`/`UNKNOWN` (§4) |
| Async provisioning | [`backend/app/tasks.py`](../../backend/app/tasks.py) | Cloud Tasks enqueue + OIDC callback auth (§4.1) |
| Firestore access | [`backend/app/firestore_repo.py`](../../backend/app/firestore_repo.py) | Schema in §5; contention retries; device settlement (§3) |
| Input validation | [`backend/app/validation.py`](../../backend/app/validation.py) | Page cursors and document ids — see below |
| Rate limiting | [`backend/app/rate_limit.py`](../../backend/app/rate_limit.py) | Per-uid token buckets (§12) |
| Structured logging | [`backend/app/observability.py`](../../backend/app/observability.py) | JSON log records with request correlation (§17) |
| Outbound mail | [`backend/app/notify.py`](../../backend/app/notify.py) | Resend, fire-and-forget |
| Pydantic models | [`backend/app/models.py`](../../backend/app/models.py) | |
| Audit trail | [`backend/app/audit.py`](../../backend/app/audit.py) | |
| Config / env vars | [`backend/app/config.py`](../../backend/app/config.py) | Includes `DEV_INSECURE_AUTH`, `REQUIRE_ATTESTED_UPLOADS`, `TASKS_*` |
| Schema migrations | [`backend/scripts/migrate_schema.py`](../../backend/scripts/migrate_schema.py) | Versioned steps in `backend/scripts/migrations/` — see [FIRESTORE_SCHEMA_RUNBOOK.md](FIRESTORE_SCHEMA_RUNBOOK.md) |
| Container | [`backend/Dockerfile`](../../backend/Dockerfile) | Installs from `requirements.lock` with `--require-hashes`. `requirements.txt` is the pin list; lock versions of direct deps must match txt. |
| API Gateway spec | [`backend/gateway/openapi.yaml`](../../backend/gateway/openapi.yaml) | Covers all current routes; `__CLOUD_RUN_URL__` is substituted at deploy |

**Cursor validation.** Every paginated listing takes a `pageToken`, which becomes
a Firestore cursor. `validation.py` checks tokens and document ids before they
reach the query, so a crafted token cannot smuggle a path separator into a
collection reference and read across the collection tree. Treat any new
client-supplied identifier that reaches Firestore as needing the same treatment.

**Per-user quota overrides.** `resolve_user_config` merges the fleet defaults
(`MAX_SESSIONS_PER_USER`, `MAX_FILES_PER_SESSION`, `MAX_FRAMES_PER_ANALYSIS`)
with optional per-user values on `users/{uid}`, so one tester can be raised
without redeploying. Admins set them via
`PATCH /v1/admin/users/{uid}/config`, and the app reads the resolved numbers
rather than hardcoding its own.

**Firestore contention is retried, not returned.** Concurrent writes to the same
session document used to surface as a `500`. `firestore_repo.py` now retries the
contended transaction, so a burst of `:complete` calls for one session settles
instead of failing the client.

---

## 8. Android client map

| Concern | File |
|---|---|
| Google sign-in, session state | [`data/AuthRepository.kt`](../../app/src/main/java/com/indicvision/semper/data/AuthRepository.kt) |
| Credential Manager helper | [`ui/auth/GoogleSignInHelper.kt`](../../app/src/main/java/com/indicvision/semper/ui/auth/GoogleSignInHelper.kt) |
| EC P-256 Keystore device key | [`data/DeviceKeyManager.kt`](../../app/src/main/java/com/indicvision/semper/data/DeviceKeyManager.kt) |
| Backend HTTP client | [`data/net/IndicApi.kt`](../../app/src/main/java/com/indicvision/semper/data/net/IndicApi.kt) |
| Token storage / refresh | [`data/net/TokenStore.kt`](../../app/src/main/java/com/indicvision/semper/data/net/TokenStore.kt) · [`TokenProvider.kt`](../../app/src/main/java/com/indicvision/semper/data/net/TokenProvider.kt) |
| Resumable upload worker | [`data/DicUploadWorker.kt`](../../app/src/main/java/com/indicvision/semper/data/DicUploadWorker.kt) |
| Restore / download | [`data/DicRestoreWorker.kt`](../../app/src/main/java/com/indicvision/semper/data/DicRestoreWorker.kt) · [`CloudRestore.kt`](../../app/src/main/java/com/indicvision/semper/data/CloudRestore.kt) |

The upload worker speaks the resumable protocol from §4: `PUT` with a
`Content-Range` header, `308` means keep going, `200`/`201` means the file
landed. It runs under WorkManager with a network constraint and exponential
backoff, so an upload survives losing connectivity or the app being killed.

Cloud sync stays off entirely unless `INDIC_API_BASE_URL` is set at build time
— see [BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md) step C1.
---

## 9. IAM configuration

Two service accounts, least-privilege:

```bash
PROJECT=indic-prod   # placeholder GCP project id — replace with yours
API_SA=indic-api@$PROJECT.iam.gserviceaccount.com
DEPLOY_SA=indic-deployer@$PROJECT.iam.gserviceaccount.com
```
Firebase Auth may use a different project id (`indicvision-dic-app-auth`); see AUTH_SETUP.md and set `FIREBASE_PROJECT_ID` when they differ.

```bash
# Runtime SA — what Cloud Run runs AS
gcloud iam service-accounts create indic-api --project $PROJECT

# Firestore access
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/datastore.user"

# Keyless Drive tokens: SA may impersonate ITSELF to add the Drive scope
gcloud iam service-accounts add-iam-policy-binding $API_SA \
  --member="serviceAccount:$API_SA" --role="roles/iam.serviceAccountTokenCreator"

# Logging / monitoring
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/logging.logWriter"

# Deployer SA (used by GitHub Actions via Workload Identity Federation — NO KEY)
gcloud iam service-accounts create indic-deployer --project $PROJECT
for R in run.admin artifactregistry.writer iam.serviceAccountUser cloudbuild.builds.editor; do
  gcloud projects add-iam-policy-binding $PROJECT \
    --member="serviceAccount:$DEPLOY_SA" --role="roles/$R"; done
```

**Drive membership (the only Workspace-side step, done by you, not the SA):**
add `indic-api@indic-prod.iam.gserviceaccount.com` as **Manager** of the
`Semper-Research-Storage` Shared Drive. If Workspace blocks adding a
service-account principal, a Workspace admin must one-time-allow it (Admin
console → Drive & Docs → Sharing → allow members outside org / add to the
allow-list). **No JSON key, no domain-wide delegation required.**

---

## 10. Cloud Run deployment

Container: [`backend/Dockerfile`](../../backend/Dockerfile) (python:3.12-slim,
uvicorn, 2 workers). The exact deploy command with all flags is step B1 of
[BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md) — it is a runbook step, not
something to retype from here.

The shape it deploys into, and why:

| Setting | Value | Reason |
|---|---|---|
| Min instances | 0 | Scale to zero — $0 when idle |
| Max instances | 10 | Pilot-sized ceiling |
| CPU / memory | 1 / 512Mi | Upload bytes bypass Cloud Run (device→Drive); restore still proxies Session.zip through `/content` |
| Timeout | 300 s | Matches the `/v1/files/{id}/content` API Gateway deadline (300 s) so Session.zip restore can finish; other JSON routes keep a 60 s gateway deadline. Session provisioning still runs as a Cloud Task. |
| Public endpoint | yes | Auth is enforced in the app layer (ID token + device signature), not the network layer |

No mounted secrets. All identity comes from the attached SA + metadata server.
HTTPS-only is the default; consider Cloud Armor / a WAF once public.

---

## 11. Application Default Credentials (ADC)

- **In Cloud Run:** `google.auth.default()` returns metadata-server
  credentials for `$API_SA`. **Nothing to configure, no file.** Drive scope is
  layered on via `impersonated_credentials` (§7.1).
- **Local dev:** `gcloud auth application-default login` mints a user ADC file
  under your own identity; grant your user `serviceAccountTokenCreator` on
  `$API_SA` to exercise the Drive path locally. Never download a key.
- **Never** set `GOOGLE_APPLICATION_CREDENTIALS` to a JSON key — that's exactly
  what the Workspace policy (and this design) forbids.

---

## 12. Security best practices (checklist)

- **Keyless everywhere:** ADC + IAM Credentials impersonation; WIF for CI. Zero
  long-lived key material.
- **Defense in depth on identity:** signature + `aud` + `iss` + `email_verified`
  + Firestore allow-list (`access_status`). Note there is deliberately **no `hd`
  sign-in gate** — any account Firebase accepts may authenticate; what it may
  *use* is decided by `access_status` (config.py spells this out). Domain only
  affects auto-approval via `AUTO_APPROVE_HD`, not admission.
- **Device binding:** non-exportable Keystore key, challenge/nonce with replay
  cache, admin-gated revoke that settles every device record (§3). Nonce TTL is a
  Firestore field policy the operator enables at setup — see §5.
- **Least privilege IAM:** `datastore.user` + self-`tokenCreator` only; deployer
  SA separate.
- **Firestore locked to the server:** client rules deny-all; all writes via API.
- **No PII in URLs/logs:** log `uid` (opaque `sub`), never tokens; scrub
  `Authorization`, `uploadUrl`, `X-Signature` from logs.
- **Capability-scoped uploads:** resumable URI authorizes one file only; expires.
- **Transport:** TLS only; HSTS; reject non-HTTPS.
- **Input validation:** pydantic models; cap `files[]` length and per-file bytes
  (reject >5 GB); sanitize filenames before Drive; validate page cursors and
  document ids in `validation.py` before they reach a Firestore query (§7).
- **Rate limiting (implemented in-process; distributed layer still open):**
  `rate_limit.py` applies per-uid token buckets to challenge, session create,
  download, export, erase, admin, listing, health, file-complete and
  session-verify. Because the buckets live in the process, they bound one Cloud
  Run instance rather than the fleet — the cross-instance layer is API Gateway
  quotas in `openapi.yaml`, with Cloud Armor still to come when the service is
  public. [PRODUCTION_READINESS_GATE.md](../ops/PRODUCTION_READINESS_GATE.md)
  tracks this as PARTIAL for that reason.
- **Attested upload targets:** `/uploads` hands out capability URLs and is gated
  by `device_or_legacy_reader`; production keeps `REQUIRE_ATTESTED_UPLOADS=1`
  (§4).
- **Audit everything security-relevant**, append-only, with retention.
- **Privacy prerequisites for public launch:** privacy policy + account-deletion
  path (raw specimen images + email are personal data).

---

## 13. Failure recovery strategy

| Failure | Detection | Recovery |
|---|---|---|
| Network drop mid-chunk | OkHttp IOException | WorkManager retry; resume via `bytes */total` → 308 offset |
| App killed during upload | Worker re-created by WM | Reload plan from local store; resume each incomplete file |
| Resumable URI expired (~1 wk) | 404/410 on PUT | **Planned:** a `:reinit` broker endpoint to mint a fresh session URI. Not implemented today; the client re-creates the session. |
| Chunk checksum mismatch | Drive size/md5 checked at `:complete` | reject with `422`, leave the file `PENDING`; client retries the completion (no auto-delete/re-enqueue today) |
| Session stuck `UPLOADING` | janitor query `(status, updatedAt)` | after N hours → notify user / reinit / mark FAILED |
| Firestore write fails after Drive success | try/except around commit | idempotent `:complete` (deterministic fileId) → safe re-POST |
| Drive `403 storageQuotaExceeded` | broker init error | means writing to SA's personal 15 GB, not the Shared Drive → config alarm |
| Drive `429`/quota | broker/PUT 429 | exponential backoff + jitter; surface "try later" |
| Provisioning task fails | task marks `PROVISION_FAILED` | Cloud Tasks retries the task; a polling client sees the status and stops waiting instead of hanging on `PROVISIONING` |
| Cloud Tasks unavailable / unconfigured | `enqueue_provision` returns `False` | Provision inline in the request — slower, same result (§4.1) |
| Drive unreachable during a verifying refresh | probe yields `UNKNOWN` | **Purge nothing.** Report an `indeterminate` count and log at WARNING (§4) |
| Concurrent writes to one session doc | Firestore contention | Retried inside `firestore_repo.py` rather than returned as `500` |

**Idempotency** is the backbone: deterministic `fileId` / `sessionId` mean every
mutation is safely retryable.

---

## 14. Cost analysis

**Pilot (10–50 users), target $0/month.**

| Service | Free tier | Pilot usage | Cost |
|---|---|---|---|
| Cloud Run | 2M req, 360k GiB-s, 180k vCPU-s / mo | tiny JSON reqs, scale-to-zero | **$0** |
| Firestore | 1 GiB, 50k reads / 20k writes / 20k deletes per day | metadata only, low volume | **$0** |
| IAM Credentials / metadata tokens | free | per-request token (cached 1 h) | **$0** |
| Drive storage | 5 TB already-paid Workspace pool | ~5 sessions/user × 1 GB | **$0 marginal** |
| Egress | uploads go **device→Drive**, not via Cloud Run | ~0 GB through GCP | **$0** |
| Cloud Logging | 50 GiB/mo free | structured logs | **$0** |
| Artifact Registry | 0.5 GB free | one image | **~$0** |

The killer design win: **bytes never transit Cloud Run**, so the usual
egress/compute blowup for 1–5 GB uploads simply doesn't exist.

**As users scale (hundreds→thousands):**
- *Cloud Run:* still cents/month — requests are small and infrequent (one burst
  per session). Even 10k sessions/day ≈ well within paid tier at ~$1–5/mo.
- *Firestore:* watch the **per-day free quotas**; thousands of users generating
  sessions + audit logs can exceed 20k writes/day → single-digit $/mo. Batch
  writes, TTL-expire old audit logs.
- *Network:* remains ~$0 (byte path bypasses GCP).
- *Drive capacity:* **this is what breaks first** (see §15).

---

## 15. Scalability analysis — when Drive becomes the ceiling

Drive limits, in the order they bite:

1. **5 TB pool.** At ~1 GB/session, ~5,000 sessions total. With retention (auto-
   delete raw after N months) you stretch it, but **capacity is the #1 wall.**
2. **750 GB / user / day upload.** The SA is the "user"; ~750 sessions/day is
   the hard ingest ceiling across *all* users. A busy field day with hundreds of
   testers can hit this.
3. **400,000 items / Shared Drive.** Many small files per session erode this;
   bundle `raw/` if sessions get file-heavy.
4. **Download ergonomics.** No anonymous signed download → every download either
   brokers bytes through Cloud Run (reintroduces egress/compute cost) or
   link-shares files (privacy regression). At dashboard scale this is painful.
5. **API query quota** (12k/min): fine unless you fan out huge per-file loops.

**Migrate to GCS when any of:** you approach ~4 TB, sustained ingest nears
750 GB/day, you need real per-object access or performant in-app downloads, or
Drive rate-limits start showing in logs. GCS removes all five ceilings at the
cost of GB-month + egress billing (~$0.02/GB-mo storage, ~$0.12/GB egress).

---

## 16. CI/CD pipeline

> **Status: in use.** `.github/workflows/deploy-backend.yml` deploys the backend
> to Cloud Run, running ruff + pytest first. It is `workflow_dispatch` (manual)
> and still needs `GCP_WIF_PROVIDER` and `GCP_DEPLOY_SA` configured for the
> target environment. `.github/workflows/ci.yml` builds and tests the app
> ([CI.md](../ops/CI.md)).

**Traffic is shifted only after the new revision answers.** The deploy does not
replace the serving revision and hope:

1. Deploy tagging the revision `cand-<run_id>-<run_attempt>`. When the service
   **already exists**, use `no_traffic: true` so the previous revision keeps
   serving. **First create** must omit `no_traffic` (Cloud Run rejects it on
   create).
2. Smoke the **tagged candidate URL** at `/readyz`, using an ID token minted with
   the *service URL* as its audience (production runs
   `--no-allow-unauthenticated`, so an unauthenticated probe would only ever
   prove that the gateway rejects it).
3. Promote the candidate to 100% traffic only if the smoke passes (update path).

On an update deploy, if the smoke fails there is nothing to roll back — the
candidate never carried traffic. Rollback is only relevant if a later step fails
after promotion. `REQUIRE_ATTESTED_UPLOADS` is passed from the GitHub var on
every deploy — keep production at `1`.

The revision suffix carries the **run attempt** as well as the run id, so
re-running a failed job cannot collide with the revision name the first attempt
already created.

It authenticates with **Workload Identity Federation** rather than a stored key:
a pool trusting GitHub's OIDC, mapped to a deployer service account and
restricted to this repo, with `permissions: id-token: write`. That keeps the
no-JSON-keys rule (§0) intact all the way through delivery. The signing keystore
for Android release builds stays in GitHub encrypted secrets or Play App
Signing.

---

## 17. Monitoring and logging

**Implemented:**
- **Liveness** `GET /healthz` — process up; no dependency probes (safe for
  restart loops).
- **Readiness** `GET /readyz` — bounded Firestore + Drive probes; stable 503
  detail codes (`firestore_unreachable`, `drive_unhealthy`, …).
- **Structured JSON access log** — UTC `timestamp`, `requestId`, `method`,
  `path`, `status`, `latencyMs`, `outcome`, `uid` / `deviceId` when resolved,
  `opClass` / `routeTemplate` for usage rollups, optional `fileCount` /
  `frameCount` on session create, and `errorCode` on failures
  (`app/observability.py` + middleware). Never logs tokens/signatures/URIs.
  Structured access logs include `opClass` / `routeTemplate` for ops dashboards.
  Client 500 bodies stay opaque (`internal_error`) on
  Cloud Run.
- **Audit trail** in Firestore `audit_logs` — the compliance record (Cloud
  Logging is the operational one).
- **Async Resend notify** — access-request mail is enqueued off the request
  path with Idempotency-Key + bounded retry (`app/notify.py`).

### Cloud Error Reporting and log-based alerts (operator setup)

Configure in the GCP project that hosts Cloud Run. Ownership: **backend on-call**
(default: the deployer listed in `ADMIN_EMAILS` / support mailbox) until a
dedicated rotation exists.

| Signal | How to wire | Threshold (starting point) | Action |
|---|---|---|---|
| Unhandled / reported errors | Error Reporting ingests `@type` ReportedErrorEvent lines and Cloud Run stderr | New error group or >5 events / 5 min | Page on-call; check `/readyz` and recent deploys |
| `outcome=server_error` access lines | Log-based metric on `jsonPayload.outcome="server_error"` | >10 / 5 min | Investigate revision; consider traffic rollback |
| `errorCode=firestore_unreachable` or `drive_*` | Log-based metric on `jsonPayload.errorCode` | Any sustained >2 min | Dependency outage — do not roll app code first |
| `AUTH_DENIED` audit / auth warnings | Metric on audit action or `invalid_token` spike | >50 / 5 min from many IPs | Possible attack; tighten gateway quota |
| Drive `429` / `notify_rejected` | Metric on dependency=drive/resend warnings | Sustained | Quota / Resend misconfig |
| Uptime | Cloud Monitoring uptime check on `/readyz` (not only `/healthz`) | 2 failed regions | Page; run staging rollback drill if deploy-correlated |

Document the notification channel (email / PagerDuty / Chat) in the project’s
ops runbook when the channel is created. Until external consoles are verified,
treat alert wiring as **UNKNOWN** in the completion gate.

**Also useful (optional dashboards):** Cloud Run request count / p95 latency /
error rate / instance count; Drive init failure rate; Firestore write count vs
free quota; sessions stuck `UPLOADING`.

---

## 18. Disaster recovery

> **Status: partly implemented.** Offline-first (Identity row) is real, partial
> IaC exists (`backend/firestore.indexes.json`, `deploy-backend.yml`), and the
> **scheduled Firestore export now runs**: `.github/workflows/firestore-backup.yml`
> exports daily at 02:17 UTC, and `.github/workflows/firestore-restore-drill.yml`
> proves the export can actually be restored rather than assuming it. Both are
> documented in [FIRESTORE_DATA_PROTECTION.md](FIRESTORE_DATA_PROTECTION.md).
> Still outstanding: console proof that PITR and the bucket lifecycle are wired,
> nightly Drive↔Firestore reconciliation, and full Terraform. The RPO/RTO figures
> describe the target.

| Asset | Risk | Mitigation |
|---|---|---|
| Firestore metadata | corruption/deletion | **Scheduled export** to a GCS bucket (`gcloud firestore export`) daily via Cloud Scheduler; PITR (7-day) enabled. |
| Drive blobs | accidental delete | Shared Drive trash retention; restrict delete to admins; nightly manifest reconciliation (Firestore `files` ↔ Drive listing). |
| Cloud Run service | region outage | Stateless + container in Artifact Registry → redeploy to another region in minutes; multi-region if RTO demands. |
| Identity | Google outage | App is **offline-first** — analysis/reports keep working; uploads queue in WorkManager until service returns. |
| Config drift | — | IaC (Terraform) for project/IAM/Run so the whole stack is reproducible. |

**RPO** ≈ 24 h (metadata) / 0 (blobs, WAL-like resumable). **RTO** ≈ minutes
(redeploy). Offline-first design means user-facing RTO for core work is
effectively zero.

---

## 19. Migration path: Drive → GCS with **zero Android change**

The client only ever sees an **opaque resumable upload URL** and speaks the
**resumable PUT + `Content-Range` + 308** protocol. **GCS resumable uploads use
the identical protocol.** So the storage swap is entirely inside the broker:

**Client API contract (frozen):**
```
POST /v1/sessions  → { sessionId, uploads:[{ fileId, uploadUrl, chunkSize }] }
PUT  <uploadUrl>   (Content-Range, 308 resume)   ← Drive OR GCS, indistinguishable
POST /v1/files/{id}/complete
```

**Backend change only:**
- Introduce a `StorageBackend` interface with `ensure_folders()`,
  `init_resumable() → uploadUrl`, `verify_complete()`.
- `DriveBackend` = §7.4. `GcsBackend` = start a GCS resumable session
  (`POST .../o?uploadType=resumable`) and return its session URL — same shape.
- Flip a `STORAGE_BACKEND=gcs` env var (or route per-user for phased cutover).
- **Migration of existing data:** background job copies Drive→GCS (Cloud Run
  job or Storage Transfer), rewrites `files.driveFileId` → `gcsObject`. Clients
  never notice.

GCS then also unlocks **V4 signed URLs** for uploads *and* downloads — at which
point you can even drop the resumable-init broker call for uploads if you want,
though keeping the broker preserves audit/authorization centrally. Downloads
become direct signed URLs, eliminating the Drive download-brokering problem.

**Design payoff:** the abstraction that makes Drive tolerable today
(opaque upload URL + standard resumable protocol) is the *same* abstraction that
makes the GCS migration a config flag tomorrow.

