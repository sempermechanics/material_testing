# Semper GCP Backend — Setup & Run Runbook

Get the **keyless GCP backend** (`backend/`) deployed and proven end-to-end,
then point the app at it.

**Nobody needs this to work on Semper.** The app builds and runs fully offline
without a backend; do this only if you are deploying the cloud side yourself.

- Prefer clicking to typing? → [BACKEND_SETUP_CONSOLE.md](BACKEND_SETUP_CONSOLE.md)
  covers Parts A–B in the browser, then send you back here for Part C.
- Want to know *why* it is shaped this way? → [CLOUD_ARCHITECTURE_GCP.md](CLOUD_ARCHITECTURE_GCP.md)
- Sign-in trouble specifically? → [AUTH_SETUP.md](AUTH_SETUP.md)

| Part | What you get | Needs |
|---|---|---|
| **A** | GCP project provisioned (APIs, Firestore, service account, IAM, Drive) | `gcloud` |
| **B** | Backend deployed to Cloud Run and smoke-tested with `curl` | `gcloud`, `curl`, python |
| **C** | The Android app talking to your deployment | Android Studio |

> **Read this first — what works today.**
> - ✅ The **backend** (`backend/app/*.py`) is complete: auth, device binding,
>   Firestore, keyless Drive resumable uploads. You can deploy and smoke-test it
>   with `curl` right now (Parts A + B).
> - ✅ The **Android client is implemented** — `data/net/` (IndicApi, TokenStore,
>   TokenProvider), `DeviceKeyManager`, `AuthRepository`, and
>   `DicUploadWorker` (resumable PUT straight to Drive). Part C is the
>   operational path to point the app at your deployment and test it.
> - Cloud sync stays off entirely until `INDIC_API_BASE_URL` is set (C1), so
>   the app builds and runs fully offline without any of this.
>
> Steps marked 🖐️ happen in a web console (can't be scripted). Do them in order;
> each ends with a **Check**.

---

## 0. Prerequisites

- **Google Cloud SDK** (`gcloud`) installed and `gcloud init` done.
- A **GCP project** you can create resources in (company creates it if needed).
- The **Shared Drive** `Semper-Research-Storage` (or any Shared Drive you own)
  and its **`SHARED_DRIVE_ID`**.
- A **Firebase project** with sign-in providers enabled and its
  `google-services.json` in `app/` — see [AUTH_SETUP.md](AUTH_SETUP.md). Note
  its **project id**: the backend accepts ID tokens issued by it.
- `curl` + `python` (3.12) for the smoke test.

```bash
export PROJECT=indic-prod          # your project id
export REGION=asia-south1          # pick one near you; Firestore must match
export API_SA=indic-api@$PROJECT.iam.gserviceaccount.com
gcloud config set project $PROJECT
```
> **PowerShell:** replace `export X=Y` with `$env:X="Y"`, and `$VAR` with `$env:VAR`.

---

## Part A — Provision GCP (one time)

### A1. Enable APIs
```bash
gcloud services enable \
  run.googleapis.com \
  firestore.googleapis.com \
  drive.googleapis.com \
  iamcredentials.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com
```
**Check:** `gcloud services list --enabled | grep -E 'run|firestore|drive|iamcredentials'` shows all four.

### A2. Create the Firestore database (Native mode)
```bash
gcloud firestore databases create --location=$REGION --type=firestore-native
```
**Check:** `gcloud firestore databases list` shows one `(default)` database.

### A2a. Enable the TTL policy on auth challenges
Each `/v1/challenge` writes a `challenges/{nonce}` doc with an `expireAt`
timestamp. Consumed nonces are deleted immediately, but *abandoned* ones only
disappear if Firestore is told `expireAt` is a TTL field — otherwise they
accumulate forever. Set the policy once:
```bash
gcloud firestore fields ttls update expireAt \
  --collection-group=challenges --enable-ttl
```
**Check:** `gcloud firestore fields ttls list --collection-group=challenges`
shows `expireAt` in state `ACTIVE` (may take a few minutes to apply).

### A3. Create the runtime service account
```bash
gcloud iam service-accounts create indic-api \
  --display-name="Semper Cloud Run runtime"
```
**Check:** `gcloud iam service-accounts list | grep indic-api`.

### A4. Grant IAM roles (least privilege + keyless Drive)
```bash
# Firestore read/write
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/datastore.user"

# THE KEYLESS-DRIVE GRANT: SA may impersonate ITSELF to add the Drive scope
gcloud iam service-accounts add-iam-policy-binding $API_SA \
  --member="serviceAccount:$API_SA" --role="roles/iam.serviceAccountTokenCreator"

# Log writer
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/logging.logWriter"
```
**Check:** `gcloud iam service-accounts get-iam-policy $API_SA` lists the SA itself as `serviceAccountTokenCreator`.

### A5. 🖐️ Add the SA to the Shared Drive
In **Google Drive → `Semper-Research-Storage` → Manage members**, add
`indic-api@indic-prod.iam.gserviceaccount.com` as **Manager**.
  > Use **Manager**, not Content manager. Content manager can upload but
  > **cannot permanently delete**: `files.delete` needs *organizer* rights on
  > the parent, so GDPR erasure silently fails without it.
- If Workspace blocks adding a service-account principal, a Workspace **admin**
  must one-time-allow it (Admin console → Apps → Google Workspace → Drive and
  Docs → Sharing → permit members outside the org / allow-list).
- **No JSON key and no domain-wide delegation are involved.**

**Check:** the SA appears as **Manager** on the Shared Drive.

---

## Part B — Deploy & smoke-test the backend

### B1. Deploy to Cloud Run from source
```bash
gcloud run deploy indic-api \
  --source backend \
  --region $REGION \
  --service-account "$API_SA" \
  --allow-unauthenticated \
  --min-instances 0 --max-instances 10 \
  --concurrency 40 --cpu 1 --memory 512Mi --timeout 120 \
  --set-env-vars "SERVICE_ACCOUNT_EMAIL=$API_SA,SHARED_DRIVE_ID=$SHARED_DRIVE_ID,GOOGLE_CLOUD_PROJECT=$PROJECT,FIREBASE_PROJECT_ID=$FIREBASE_PROJECT,AUTO_APPROVE_HD=yourdomain.com,ADMIN_EMAILS=you@yourdomain.com" \
  --set-env-vars "SUPPORT_EMAIL=support@indicvision.com,NOTIFY_FROM=Semper <noreply@yourdomain.com>" \
  --set-secrets "RESEND_API_KEY=resend-api-key:latest"
```
> `FIREBASE_PROJECT_ID` can be omitted when Firebase Auth lives in the same
> project as the backend — it defaults to `GOOGLE_CLOUD_PROJECT`. See
> [AUTH_SETUP.md](AUTH_SETUP.md) §3 for what `AUTO_APPROVE_HD` and
> `ADMIN_EMAILS` do.

> `NOTIFY_FROM` / `RESEND_API_KEY` drive the "a new user is waiting for
> approval" mail to `SUPPORT_EMAIL` (see B1a). Leave both unset and the backend
> simply sends nothing — no error, no failed sign-ins.

### B1a. Access-request notification mail (optional)

When a new account is created `PENDING`, the backend mails `SUPPORT_EMAIL` with
the account, the uid, and how to approve it. Without this, a pending user is
only discovered by opening **Settings → Access requests** in the app.

Sending goes through [Resend](https://resend.com). Verify your sending domain
there first — an unverified `NOTIFY_FROM` is rejected and only shows up as a
warning in the logs.

`RESEND_API_KEY` is the one secret this service holds, so it goes in Secret
Manager rather than `--set-env-vars`:

```bash
printf '%s' 're_your_key_here' | gcloud secrets create resend-api-key --data-file=-
```

```bash
gcloud secrets add-iam-policy-binding resend-api-key --member "serviceAccount:$API_SA" --role roles/secretmanager.secretAccessor
```

Then deploy with the `--set-secrets` flag shown in B1. Verify by signing in with
a fresh non-admin, non-domain account: mail should land in support@ within
seconds, and `gcloud run services logs read indic-api --region $REGION` should
carry no `access-request mail` warning.

> `--allow-unauthenticated` is correct here: the service is public at the network
> layer, and **auth is enforced in the app layer** (Firebase ID token + device
> signature). Nothing sensitive is reachable without a valid token.

Grab the URL:
```bash
export URL=$(gcloud run services describe indic-api --region $REGION --format='value(status.url)')
echo $URL
```
**Check:** `curl -s $URL/healthz` → `{"ok":true}`.

### B2. Redeploy in **insecure dev mode** to smoke-test Drive + Firestore

This bypasses the ID-token + device-signature checks (see `DEV_INSECURE_AUTH`
in [config.py](../../backend/app/config.py)) so you can prove the storage path with
plain curl, before any Android work. **Never leave this on.**
```bash
gcloud run services update indic-api --region $REGION \
  --update-env-vars "DEV_INSECURE_AUTH=1,INSECURE_AUTH_I_ACCEPT_THE_RISK=1,AUTO_APPROVE=1"
```

**B2a. Create a session** (declare one tiny file):
```bash
SHA=$(printf 'hello dic' | sha256sum | cut -d' ' -f1)
RESP=$(curl -s -X POST "$URL/v1/sessions" -H "content-type: application/json" -d "{
  \"specimen\":\"smoke-test\",
  \"files\":[{\"name\":\"note.txt\",\"role\":\"metadata\",\"bytes\":9,\"sha256\":\"$SHA\"}]
}")
echo "$RESP"
SID=$(echo "$RESP" | python -c "import sys,json;print(json.load(sys.stdin)['sessionId'])")
UP=$(echo "$RESP"  | python -c "import sys,json;print(json.load(sys.stdin)['uploads'][0]['uploadUrl'])")
FID=$(echo "$RESP" | python -c "import sys,json;print(json.load(sys.stdin)['uploads'][0]['fileId'])")
```

**B2b. Upload the 9 bytes directly to Drive** (single-shot; bytes never touch Cloud Run):
```bash
DRIVE=$(printf 'hello dic' | curl -s -X PUT "$UP" \
  -H "content-type: application/octet-stream" \
  -H "Content-Range: bytes 0-8/9" --data-binary @-)
echo "$DRIVE"
DRIVE_ID=$(echo "$DRIVE" | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
```

**B2c. Complete the file:**
```bash
curl -s -X POST "$URL/v1/files/$FID/complete" -H "content-type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"driveFileId\":\"$DRIVE_ID\",\"bytes\":9}"
```

**Check (the payoff):**
- `Semper-Research-Storage/Research Storage/user/dev-user/session/$SID/metadata/note.txt` exists in Drive.
- Firestore → `sessions/$SID` shows `status: COMPLETED`; `files/$FID` shows `driveFileId`.
- Cloud Run logs (`gcloud run services logs read indic-api --region $REGION`) show the requests, no errors.

### B3. Turn dev mode OFF
```bash
gcloud run services update indic-api --region $REGION \
  --remove-env-vars "DEV_INSECURE_AUTH,INSECURE_AUTH_I_ACCEPT_THE_RISK,AUTO_APPROVE"
```
**Check:** `curl -s $URL/v1/me` (no token) → `401 missing_bearer`.

### (Optional) Run locally instead of Cloud Run
```bash
cd backend
python -m venv .venv && source .venv/Scripts/activate   # Git Bash on Windows
pip install -r requirements.txt
gcloud auth application-default login                    # user ADC (no key)
# let your user mint Drive tokens for the SA:
gcloud iam service-accounts add-iam-policy-binding $API_SA \
  --member="user:$(gcloud config get-value account)" --role="roles/iam.serviceAccountTokenCreator"
export GOOGLE_CLOUD_PROJECT=$PROJECT FIREBASE_PROJECT_ID=$FIREBASE_PROJECT \
       SERVICE_ACCOUNT_EMAIL=$API_SA SHARED_DRIVE_ID=$SHARED_DRIVE_ID \
       DEV_INSECURE_AUTH=1 AUTO_APPROVE=1
uvicorn app.main:app --reload --port 8080
```
Then use `URL=http://localhost:8080` in the B2 steps.

---

## Part C — Connect & test the Android app

> **The client code is implemented.** `data/net/` (IndicApi, TokenStore,
> TokenProvider, ApiDtos), the EC-P256 `DeviceKeyManager` (challenge-response),
> `AuthRepository` (Google, email/password, or email-link sign-in) and `DicUploadWorker` (resumable PUT
> direct to Drive) are all in the app. This part is the **operational** steps
> to point the app at your live backend and test it.

### C0. Make the service reachable by the app (**do this first**)

The app authenticates users with a **Firebase ID token**, which is *not* a Cloud
Run **invoker** token. So a **private** Cloud Run service is unreachable by the
app — every call gets Google's HTML `403 Forbidden`. It needs a public front
door.

**Simplest — if your org allows it:** make Cloud Run public (app-layer auth then
guards it):
```bash
gcloud run services add-iam-policy-binding indic-api --region asia-south1 \
  --member="allUsers" --role="roles/run.invoker"
```
If this **fails** with `iam.allowedPolicyMemberDomains` / Domain Restricted
Sharing, your org forbids public Cloud Run and forbids `allUsers`. Ask an org
admin for a project-scoped exception (one change, $0, no code) — otherwise use
the API Gateway workaround below.

> **IAP is not an option if you need external (non-domain) users:** IAP
> authorizes via IAM, which the same DRS policy restricts — so IAP can only admit
> your own Workspace domain. For external collaborators, use API Gateway.

**Workaround (no policy change, supports external users) — API Gateway:**
A managed **public** endpoint whose reachability is *not* granted via `allUsers`
IAM, so DRS doesn't block it. The gateway invokes Cloud Run using **its own
service account** (an org-internal principal DRS permits); Cloud Run stays
private. Your FastAPI app-layer auth (Firebase ID token + approval + device
signature) runs unchanged — the client token arrives as
`X-Forwarded-Authorization` (already handled in `deps.py`).

```bash
# Reuse the same $PROJECT you set in Part A — do not reassign it here. Set
# $REGION to wherever you deployed Cloud Run.
REGION=asia-south1
RUN_URL=$(gcloud run services describe indic-api --region $REGION --format='value(status.url)')

# 1. APIs
gcloud services enable apigateway.googleapis.com servicemanagement.googleapis.com \
  servicecontrol.googleapis.com

# 2. Gateway service account, granted invoker on the private Cloud Run service
gcloud iam service-accounts create indic-gw
GW_SA=indic-gw@$PROJECT.iam.gserviceaccount.com
gcloud run services add-iam-policy-binding indic-api --region $REGION \
  --member="serviceAccount:$GW_SA" --role="roles/run.invoker"

# 3. Spec is committed at backend/gateway/openapi.yaml (covers all current
#    routes). Inject your Cloud Run URL into a working copy:
sed "s#https://REPLACE_WITH_CLOUD_RUN_URL#$RUN_URL#" \
  backend/gateway/openapi.yaml > /tmp/openapi.yaml

# 4. Create the API, config (with backend-auth SA), and gateway
gcloud api-gateway apis create indic-api
gcloud api-gateway api-configs create v1 --api=indic-api \
  --openapi-spec=/tmp/openapi.yaml --backend-auth-service-account=$GW_SA
gcloud api-gateway gateways create indic-gw --api=indic-api \
  --api-config=v1 --location=$REGION

# 5. The public gateway URL → this is what the app talks to (C1)
gcloud api-gateway gateways describe indic-gw --location $REGION \
  --format='value(defaultHostname)'
```
Leave Cloud Run **ingress at its default** (`all`) — the gateway calls the
`run.app` URL and only its SA has `run.invoker`, so direct calls still 403; only
the gateway gets through. In **C1**, set `INDIC_API_BASE_URL` to
`https://<gateway defaultHostname>` (not the `run.app` URL).

### C1. Point the app at the backend

In `local.properties`:
```properties
INDIC_API_BASE_URL=https://indic-api-xxxx.a.run.app
```
Blank `INDIC_API_BASE_URL` = offline-only (cloud disabled). Rebuild after editing.

### C2. Make sure Google sign-in works on-device

In the **Firebase console** → Project settings → your Android app, confirm the
**SHA-1** of the keystore you are building with is registered (debug keystore
for a debug build), and that `app/google-services.json` is current. Firebase
creates the matching Android OAuth client for you. Full steps in
[AUTH_SETUP.md](AUTH_SETUP.md).

### C3. Configure the access model

```bash
gcloud run services update indic-api --region asia-south1 \
  --update-env-vars AUTO_APPROVE_HD=indicvision.com \
  --remove-env-vars DEV_INSECURE_AUTH,INSECURE_AUTH_I_ACCEPT_THE_RISK,AUTO_APPROVE
```
- `DEV_INSECURE_AUTH` **off** → real device auth (after this, curl smoke tests no
  longer work; test via the app).
- `AUTO_APPROVE_HD=indicvision.com` → `@indicvision.com` accounts are **APPROVED**
  on first sign-in.
- Any account may sign in regardless — there is no domain gate on
  authentication, deliberately. Non-domain accounts land **PENDING** and use
  the in-app **Request access** button (emails `support@indicvision.com`), then
  an admin approves them individually.
- `AUTO_APPROVE` (blanket approve-everyone) **removed**.

Designate admins with `ADMIN_EMAILS` (comma-separated) — they're always
approved and can call the admin API:
```bash
gcloud run services update indic-api --region asia-south1 \
  --update-env-vars ADMIN_EMAILS=support@indicvision.com
```

**Approve / revoke via the admin API** (ID-token + admin role; no device
signature — works from an in-app admin screen, or from curl while
`DEV_INSECURE_AUTH=1`):
```
GET  /v1/admin/users?status=PENDING     # who's waiting
POST /v1/admin/users/{uid}/approve      # → APPROVED
POST /v1/admin/users/{uid}/revoke       # → SUSPENDED
```
Or, without the API, set `access_status: "APPROVED"` on the `users/{uid}` doc in
Firestore directly.

### C4. Build, install, sign in

- Build a **debug** APK and install on a **Google-Play** emulator or a real
  device (plain AOSP images can't do Google Sign-In).
  - The debug auth-skip now only applies when **no** backend is configured. With
    `INDIC_API_BASE_URL` set (C1), even a debug build runs the **real** sign-in
    flow — so device testing exercises the full path.
- Sign in with a Google account:
  - **`@indicvision.com`** → auto-APPROVED → straight into the app.
  - **Any other account** → **PENDING** screen → **Request access** (emails
    support) → an admin approves them → they tap **Check now** and get in.
- First APPROVED sign-in registers the device public key
  (`/v1/devices/register`) — one user ⇄ one device.

### C5. Run an analysis and verify the upload

- Ensure **Save to cloud** is enabled in settings, run an analysis.
- `DicUploadWorker` fires (network-gated). Verify in Drive:
  ```
  session/<sid>/
  ├── raw/       Reference.png, Deformed_*.png
  ├── csv/       Data_<frame>.csv
  ├── reports/   Master_Report_*.pdf
  └── metadata/  metadata_<frame>.json   (device/time/engine info)
  ```
- Firestore: `sessions/<sid>` → `COMPLETED`; `files/*` have `driveFileId`.
- Backend logs for a run: `gcloud run services logs read indic-api --region asia-south1 --limit 50`.

### C6. Approving outside collaborators

Non-`indicvision.com` users land PENDING. Approve them from the **in-app admin
screen**: sign in with an `ADMIN_EMAILS` account → **Home ▸ settings ▸ Pending
access requests** → **Approve**/**Deny** each. (The button only appears for admin
accounts.) The approved user taps **Check now** and is let in.

### C7. (post-pilot) tighten

- Add Cloud Armor / rate-limiting on the public endpoint.
- Consider minting a backend session JWT via IAM `signJwt` if per-request ID
  token verification becomes a latency concern (see
  [CLOUD_ARCHITECTURE_GCP.md](CLOUD_ARCHITECTURE_GCP.md) §2).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/v1/sessions` 500, Drive `404` on folder create | SA not a member of the Shared Drive, or wrong `SHARED_DRIVE_ID`. |
| Deletion reports success but files remain in Drive | SA is **Content manager**, not **Manager**. `files.delete` needs organizer rights; Drive answers 404 rather than 403. Promote the SA to Manager. |
| Drive `403 storageQuotaExceeded` | Writing to the SA's personal 15 GB, not the Shared Drive — `driveId`/membership wrong. Must be a Workspace **Shared** Drive. |
| `403 PERMISSION_DENIED` minting Drive token | SA missing `serviceAccountTokenCreator` **on itself** (A4), or `iamcredentials` API not enabled (A1). |
| `iam.serviceAccounts.getAccessToken` denied locally | Your user lacks `tokenCreator` on the SA — see the local-run block. |
| Firestore `NOT_FOUND` / `PermissionDenied` | Firestore DB not created (A2) or `datastore.user` not granted (A4). |
| `401 invalid_token` from the app | Token audience is not the project in `FIREBASE_PROJECT_ID` (defaults to `GOOGLE_CLOUD_PROJECT`), or the token expired. Check the deployed env vars. |
| `403 not_approved` | User is `PENDING`; set `access_status: APPROVED` in Firestore, or deploy with `AUTO_APPROVE=1` during pilot. |
| `409 device_conflict` on register | User already has an active device — needs admin rebind (revoke old device in Firestore). |
