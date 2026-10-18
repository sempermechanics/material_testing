# Data map — records of processing (2026-10-18)

Internal working document for readiness item 8.1
([READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md)): what
personal data Semper holds, where, why, and for how long, **derived from the
code**, not from the policy. It is the input to a DPIA and to the Play Data
safety answers ([PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md)); the public text
remains [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

This file is **not rendered** to Hosting: `scripts/render_legal_pages.py` renders
only the Privacy Policy and the Terms (its `PAGES` table), so adding it changes
nothing that `render_legal_pages.py --check` compares. Where this map and the
policy disagree, the map describes the code and the disagreement is a finding.

Paths are from the repository root; app paths from
`app/src/main/java/com/sempermechanics/semper/`. Brought up to the branch
`claude/readiness-followups-jhfhj9` at `75bac5f` (2026-10-18): hashed addresses
in audit rows, the wider erasure and the POST licence search are in code there,
not yet deployed.

## Categories of data subject

- **Account holders**: anyone who signs in to the app or a console.
- **Invitees**: addresses an institution or staff added to a licence before the
  person has an account (`licenseInvites`).
- **Institution IT contacts**: addresses in a licence's `adminEmails`.
- **Semper staff**: `role: admin` accounts and `ADMIN_EMAILS`.

No location, contacts, camera, phone-state or advertising data is read by the app
(`app/src/main/AndroidManifest.xml:6-14`). Whether the merged manifest carries
Firebase Analytics' `AD_ID` permission is unchecked (TD-232).

## Stores

### Firebase Authentication (project `indicvision-dic-app-auth`)

Email address, display name, sign-in provider, password hash (password accounts),
TOTP enrolment (staff and console users). Purpose: identity. Retention: until the
account is erased; `DELETE /v1/me` deletes the Auth user once the runtime SA holds
Firebase Auth admin (`backend/app/routers/account.py:350`; TD-214). Both apps sign in to this one Auth project, so deleting the account removes the sign-in for both, consistent with the account-wide erase ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md), decision note).

### Firestore (project `indicvision-dic-app`; location unrecorded, TD-227)

Every collection the backend touches is listed in
`firestore-rules-tests/collections.mjs` (a CI guard keeps the list complete), and
no client can read or write any of them (`firestore.rules:7-9`).

| Collection | Personal data | Purpose | Retention |
|---|---|---|---|
| `users/{uid}` | `email`, `emailVerified`, `displayName`, `signInProvider`, `role`, `access_status`, `claimedDeviceId`, per-app `activeDeviceId`, `linkedAuthUids`, licence mirror (`licenseId`, mode, terms), `termsAccepted` and `improvementConsent` records (each with a device id and source), `createdAt`, `lastSeenAt` (`backend/app/repo/users.py:200-217`; schema `docs/backend/CLOUD_ARCHITECTURE_GCP.md:552-587`) | Identity, access, licensing, consent records | Until erasure (`backend/app/repo/account.py:206-244`) |
| `auth_links/{firebaseSub}` | the canonical uid a Firebase subject maps to (`backend/app/repo/users.py:93`) | Merging sign-in methods | Until erasure: every row whose `uid` is the account is deleted (`backend/app/repo/account.py:237-239`). Rows left by erasures before that change stay until a sweep (TD-271) |
| `devices/{deviceId}` | `uid`, `app`, `publicKeyPem`, `model`, `osVersion`, `appVersion`, `status`, registration and last-assertion times (`backend/app/repo/devices.py:80-92`) | Device binding, request signatures | Until erasure |
| `challenges/{nonce}` | `uid`, `deviceId` | Replay protection | TTL on `expireAt` (`backend/firestore.indexes.json:104-122`); deleted on use |
| `sessions/{sid}` | `uid`, `deviceId`, `app`, `specimen` (the reference image's file name), `name` (the analysis name), small run metrics, Drive folder id (`backend/app/repo/sessions.py:477-502`) | Cloud backup index | Until the session or account is erased |
| `files/{fileId}` | `uid`, `sessionId`, file `name`, size, `sha256`, Drive ids | Upload tracking | Until the session or account is erased |
| `licenses/{id}` | `emailLock` and `redeemedByUid` (individual); `adminEmails`, `domainLock` (institution); per-app `deviceIdLock`; `createdByUid` | Licensing | Until deleted; then 30 days in `deleted_licenses` |
| `licenses/{id}/seats/{uid}` | `uid`, `email`, per-app `deviceIdLock`, lease fields (`backend/app/repo/claims.py:246-254`) | Institution roster | A revoked seat keeps `email` until its holder's account is erased, which blanks it on every seat the account held, live seats revoked first (`_blank_seat_addresses`, `backend/app/repo/account.py:186-203`). Seats on other licences are found by a collection-group query whose index must be deployed first (TD-271) |
| `licenseInvites/{id}` | `email`, `licenseId`, `invitedByUid` (`backend/app/repo/invites.py:155-163`) | Promise of a seat before sign-up | Until redeemed or withdrawn; an individual licence is re-invited to its address after its holder's erasure (`backend/app/repo/account.py:146-148`) |
| `deleted_licenses/{id}`, `…/deleted_seats/{uid}` | copies of the above | 30-day hold for restore | TTL on `purgeAt` (`backend/firestore.indexes.json:123-160`); a held seat's `email` is blanked on its holder's erasure |
| `audit_logs/{autoId}` | `uid`, `deviceId`, `action`, `target`, `detail`, `licenseId` (`backend/app/audit.py:64-78`). An `ip` field exists but no caller sets it. `detail` holds no address: mint, convert and an `adminEmails` edit record hashes (`emailLockHash`, `adminEmailHashes`, `adminEmailCount`; `audit.address_tag`, `backend/app/audit.py:35-47`). A licence edit records its free-text `note` as typed (TD-270) | Security and accountability; customer-visible per licence | **Kept after erasure, no TTL** (TD-237) |

### Google Drive (Shared Drive, runtime SA as Manager)

Per user and session folders holding what the app uploads: reference and deformed
images, `.dat` results, reports, CSV, `metadata.json` (analysis name, specimen,
parameters). Purpose: backup and restore. Retention: deleted with the session or
account (`DELETE /v1/me` removes the user folder first,
`backend/app/routers/account.py:294-362`); Shared Drive trash per Workspace policy.

### Cloud Logging (project `indicvision-dic-app`)

- Structured lines from `backend/app/observability.py`: `requestId`, `uid`,
  `deviceId`, `appId`, `appVersion`, route, status, latency, error code, and on
  licensing events `licenseId` (allowed fields, `observability.py:24-33`). An email
  that must be logged is a 12-hex hash (`short_hash`, `:189-196`).
- uvicorn's access line and the Cloud Run / API Gateway request logs carry the full
  URL. The staff licence search sends its address in a POST body
  (`POST /v1/admin/licenses/search`, `backend/app/routers/admin.py:166-174`); the
  GET's `?q=` still answers for one release and would put an address in the URL
  (TD-273).
- Retention: the project's log bucket setting, unverified (policy §4 lists it
  UNKNOWN).

### Firebase Crashlytics and Analytics (opt-in)

Off in the manifest (`app/src/main/AndroidManifest.xml:28-38`) until the user
agrees (`AppSettings.diagnosticsEnabled`, `data/prefs/AppSettings.kt:61`).
Crash reports with device and app version; breadcrumbs and non-fatals only with
consent (`diagnostics/CrashReportingTree.kt:23-41`); Analytics events with enum and
bucket parameters only (CONTEXT.md, Invariants). Retention: Firebase project
settings, unverified (policy §4).

### Resend (email)

On a new PENDING account, one mail to the support address with the applicant's
email, display name, sign-in provider and uid (`backend/app/notify.py:172-195`).
Retention: Resend's, unverified.

### On the device

Analyses (images, `.dat`, reports) in app-private storage; the Firebase session; the
device key in the Android Keystore; the sealed entitlement token. Backup and device
transfer exclude preferences and session files
(`app/src/main/res/xml/data_extraction_rules.xml`).

## Identifiers

- **Device id**: `and-{ANDROID_ID}` (`data/account/DeviceKeys.kt:54-62`), unique
  per app-signing key, user and device on Android 8+; a legacy `dev-{uuid}` is kept
  when already stored. Changes on factory reset.
- **Device key thumbprint** (`dkt`): SHA-256 of the device public key, inside the
  entitlement token (`backend/app/entitlement_token.py:102-111`).
- **uid**: the Firebase Auth uid.

## Retention summary

| Data | Retention | Mechanism |
|---|---|---|
| Account, devices, sessions, files, Drive content | Until erasure | `DELETE /v1/me`; per session `DELETE /v1/sessions/{sid}` |
| Nonces | Minutes | TTL `challenges.expireAt`, delete on use |
| Deleted licences and seats | 30 days | TTL `purgeAt` |
| Audit rows | Indefinite (no TTL set) | TD-237 |
| Firestore exports | Bucket lifecycle (35 days recommended, `docs/backend/FIRESTORE_DATA_PROTECTION.md:19-26`; applied state unverified) | GCS lifecycle |
| Logs, crash reports, email | Vendor settings, unverified | Gate "Confirm Crashlytics / Cloud Logging / Resend retention" |

## Findings recorded elsewhere

TD-237 (audit retention and lock), TD-270 (free-text `note` in audit rows), TD-271
(erasure's seat index; erasures before the change), TD-273 (the deprecated search
query), TD-227 (location), TD-232
(Data safety, `AD_ID`), TD-36 (policy placeholders). `docs/backend/CLOUD_ARCHITECTURE_GCP.md`
§5 lists `ua` on `audit_logs`; the code writes none.
