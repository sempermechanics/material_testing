# Backend setup — web console path (no terminal)

The same provisioning and deploy as [BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md),
done entirely in the browser. Pick this path if you would rather not install
`gcloud`; pick the CLI runbook if you want it scripted and repeatable.

**Requires:** the `backend/` folder pushed to a GitHub repo (GitHub Desktop or
your IDE is fine) — Cloud Run builds from GitHub, so no local Docker or
`gcloud` is needed.

Steps marked with a number are console clicks; each ends with a **Check**.
When you are done here, continue with **Part C** of the CLI runbook to point
the app at your deployment.

---

## 1. Select / create the project
1. Open <https://console.cloud.google.com>.
2. Top bar → **project picker** → **New Project** (or pick an existing one).
   Name it e.g. `indic-prod`. Note the **Project ID**.

## 2. Enable the APIs
1. Left menu (☰) → **APIs & Services → Enabled APIs & services**.
2. Click **+ Enable APIs and services**. Search for and **Enable** each of these
   (one at a time):
   - Cloud Run Admin API
   - Cloud Firestore API
   - Google Drive API
   - IAM Service Account Credentials API
   - Artifact Registry API
   - Cloud Build API

## 3. Create the Firestore database
1. ☰ → **Firestore**.
2. **Create database** → **Native mode** → choose a location (e.g.
   `asia-south1`; remember it — Cloud Run should use the same region).
3. **Create**.

## 4. Create the runtime service account
1. ☰ → **IAM & Admin → Service Accounts**.
2. **+ Create service account**. Name: `indic-api`. **Create and continue**.
3. On "Grant this service account access": add role **Cloud Datastore User**,
   then **+ Add another role** → **Logs Writer**. **Continue → Done**.
4. Copy its email — `indic-api@<project-id>.iam.gserviceaccount.com`.

## 5. Let the SA impersonate itself (the keyless-Drive grant)
1. Still in **Service Accounts**, click the **indic-api** account.
2. Open the **Permissions** tab → **Grant access**.
3. **New principals:** paste the **same** `indic-api@…` email.
4. **Role:** **Service Account Token Creator**. **Save**.

> This is what lets Cloud Run mint a Drive-scoped token with no JSON key.

## 6. Add the SA to the Shared Drive
1. Open <https://drive.google.com> → left menu → **Shared drives** →
   `Semper-Research-Storage` (create it if needed: **New**).
2. **Manage members** (top-right people icon) → paste the `indic-api@…` email →
   role **Manager** → **Send/Share**. (Manager — not Content manager — or
   account/analysis deletion cannot erase the files; see A5.)
   - If Drive refuses because it's outside your org, a **Workspace admin** must
     allow it once (Admin console → Apps → Google Workspace → Drive and Docs →
     Sharing → allow adding members outside the organization).
3. Get the **Shared Drive ID**: open the Shared Drive; the browser URL is
   `https://drive.google.com/drive/folders/<THIS_IS_THE_ID>`. Copy it.

## 7. Note your Firebase project id
Sign-in runs on Firebase Authentication, and the backend accepts ID tokens
issued by that project. Open the [Firebase console](https://console.firebase.google.com)
→ your project → **Project settings**, and copy the **Project ID**. If Firebase
Auth lives in the same project as this backend, you can skip the
`FIREBASE_PROJECT_ID` variable below entirely. Setup details:
[AUTH_SETUP.md](AUTH_SETUP.md).

## 8. Deploy to Cloud Run from GitHub
1. ☰ → **Cloud Run** → **Create service**.
2. Select **Continuously deploy from a repository (source or function)** →
   **Set up with Cloud Build**.
3. **Repository provider: GitHub** → authorize / install the Google Cloud Build
   app for your repo → pick the **repository** and **branch** (e.g. `main`).
4. **Build configuration:**
   - **Build type: Dockerfile**
   - **Source location / Dockerfile path:** `/backend/Dockerfile`
   - If asked for a **build context directory**, set it to `/backend`.
   - **Save**.
5. **Service settings:**
   - **Region:** the same as Firestore (e.g. `asia-south1`).
   - **Authentication:** **Allow unauthenticated invocations** (auth is enforced
     in-app by the ID token + device signature, not by the network layer).
6. Expand **Container(s), Volumes, Networking, Security**:
   - **Security tab → Service account:** select **indic-api**.
   - **Container → Variables & Secrets → + Add variable**, add each:

     | Name | Value |
     |---|---|
     | `SERVICE_ACCOUNT_EMAIL` | `indic-api@<project-id>.iam.gserviceaccount.com` |
     | `SHARED_DRIVE_ID` | the ID from step 6 |
     | `GOOGLE_CLOUD_PROJECT` | your Project ID |
     | `FIREBASE_PROJECT_ID` | the Firebase project id from step 7 — omit if it is the same as above |
     | `AUTO_APPROVE_HD` | your domain, e.g. `yourdomain.com` — verified emails there are approved on first sign-in |
     | `ADMIN_EMAILS` | comma-separated admin addresses |
     | `SUPPORT_EMAIL` | where "a new user is waiting for approval" mail goes — defaults to `support@indicvision.com` |
     | `NOTIFY_FROM` | verified Resend sender, e.g. `Semper <noreply@yourdomain.com>` — leave unset to disable notification mail |

   - **Container → Variables & Secrets → + Reference a secret** for the API key
     (it must not be a plain variable): name `RESEND_API_KEY`, secret
     `resend-api-key`, version `latest`, exposed as an environment variable.
     Create the secret first in **Security → Secret Manager**, and grant
     **indic-api** the *Secret Manager Secret Accessor* role on it. Skip this and
     the backend just sends no notification mail — nothing breaks.

   - (Resources) CPU 1, Memory 512 MiB, Min instances 0, Max 10.
7. **Create.** Wait for the build+deploy to finish; copy the service **URL**
   (looks like `https://indic-api-xxxx.a.run.app`).

## 9. Verify in the browser
1. Visit `https://<your-url>/healthz` → you should see
   `{"ok":true}`.
2. Visit `https://<your-url>/docs` → the interactive API page loads. (Calls will
   return 401 until dev mode is on — next step.)

## 10. Prove Drive + Firestore work (browser only, no curl)
Temporarily enable dev mode so you can call the API without a signed request:
1. **Cloud Run** → click **indic-api** → **Edit & deploy new revision**.
2. **Variables & Secrets** → add `DEV_INSECURE_AUTH` = `1`,
   `INSECURE_AUTH_I_ACCEPT_THE_RISK` = `1`, and `AUTO_APPROVE` = `1` →
   **Deploy**. The second variable is required on a deployed service: without
   it the container refuses to start, so nobody bypasses auth by accident.
3. Open `https://<your-url>/docs` → expand **POST /v1/sessions** → **Try it out**
   → paste this body → **Execute**:
   ```json
   {
     "specimen": "smoke-test",
     "files": [
       { "name": "note.txt", "role": "metadata", "bytes": 9,
         "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
     ]
   }
   ```
   You should get **200** with a `sessionId` and an `uploadUrl`.
4. **Check Drive:** in `Semper-Research-Storage` a tree now exists —
   `Research Storage/user/dev-user/session/<sessionId>/` with `raw`, `processed`,
   `reports`, `metadata` subfolders. ✅ keyless Drive access works.
5. **Check Firestore:** ☰ → **Firestore → Data** → collection `sessions` has your
   `<sessionId>` (status `UPLOADING`), and `files` has a matching doc. ✅ Firestore
   works.

> The actual file-bytes upload (resumable `PUT` straight to `uploadUrl`) can't be
> driven from a plain browser — it needs the app (Part C) or an HTTP client like
> Postman. Steps 3–5 already prove the two things that were risky: **keyless Drive
> writes** and **Firestore writes**.

## 11. Turn dev mode OFF (important)
Cloud Run → **indic-api** → **Edit & deploy new revision** → **Variables &
Secrets** → delete `DEV_INSECURE_AUTH`, `INSECURE_AUTH_I_ACCEPT_THE_RISK` and
`AUTO_APPROVE` → **Deploy**.
Confirm `https://<your-url>/v1/me` now returns **401**.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/v1/sessions` 500, Drive `404` on folder create | SA not a member of the Shared Drive, or wrong `SHARED_DRIVE_ID`. |
| Deletion reports success but files remain in Drive | SA is **Content manager**, not **Manager**. `files.delete` needs organizer rights; Drive answers 404 rather than 403. Promote the SA to Manager. |
| Drive `403 storageQuotaExceeded` | Writing to the SA's personal 15 GB, not the Shared Drive — `driveId`/membership wrong. Must be a Workspace **Shared** Drive. |
| `403 PERMISSION_DENIED` minting Drive token | SA missing `serviceAccountTokenCreator` **on itself** (step 5), or `iamcredentials` API not enabled (step 2). |
| `iam.serviceAccounts.getAccessToken` denied locally | Your user lacks `tokenCreator` on the SA — see the local-run block. |
| Firestore `NOT_FOUND` / `PermissionDenied` | Firestore DB not created (step 3) or `datastore.user` not granted (step 4). |
| `401 invalid_token` from the app | Token audience is not the project in `FIREBASE_PROJECT_ID` (defaults to `GOOGLE_CLOUD_PROJECT`), or the token expired. Check the deployed env vars. |
| `403 not_approved` | User is `PENDING`; set `access_status: APPROVED` in Firestore, or deploy with `AUTO_APPROVE=1` during pilot. |
| `409 device_conflict` on register | User already has an active device — needs admin rebind (revoke old device in Firestore). |
