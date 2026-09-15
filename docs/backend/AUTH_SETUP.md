# Sign-in setup (Firebase Authentication)

Identity is federated through **Firebase Authentication**. The app signs the
user in with Firebase, sends the resulting **Firebase ID token** as a bearer
token, and Cloud Run verifies it with `firebase-admin`.

Three providers are wired in
[`AuthRepository.kt`](../../app/src/main/java/com/indicvision/semper/data/AuthRepository.kt):

| Provider | How it signs in | Email verified? |
|---|---|---|
| **Google** | Credential Manager returns a Google ID token, exchanged for a Firebase credential ([GoogleSignInHelper.kt](../../app/src/main/java/com/indicvision/semper/ui/auth/GoogleSignInHelper.kt)) | Yes, by construction |
| **Email link** | Firebase emails a sign-in link; tapping it completes sign-in | Yes, by construction |
| **Email + password** | Standard Firebase email/password | Not until the user confirms |

Forgot a password? The sign-in screen also offers **Forgot password?**, which
emails a Firebase reset link (`sendPasswordReset` in
[AuthRepository.kt](../../app/src/main/java/com/indicvision/semper/data/AuthRepository.kt)).
It is identity-only — no backend call — and reports success even for an unknown
email so the screen can't be used to probe which addresses are registered.

> **Sign-in requires `INDIC_API_BASE_URL`.** `AuthRepository.firebaseThen`
> short-circuits when the cloud base URL is blank, so Google and email/password
> sign-in both fail without it. (Password *reset* and *sending* an email link are
> the exceptions — they are pure Firebase calls.) Once a user has been APPROVED,
> later launches fall back to an offline-approved cache, but the first sign-in
> needs the backend reachable.

## 1. Firebase project

1. In the [Firebase console](https://console.firebase.google.com), open (or
   create) the project — currently `indicvision-dic-app-auth`.
2. **Authentication → Sign-in method**: enable **Google**, **Email/Password**,
   and **Email link (passwordless sign-in)**.
3. **Project settings → Your apps → Android app** with package
   `com.indicvision.semper`. Add the **SHA-1** of every signing key you use —
   debug and release. Google sign-in fails without it.

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android
   ```

   For the **release** key (CI `KEYSTORE_BASE64` / `app/release.keystore`):

   ```bash
   keytool -list -v -keystore app/release.keystore -alias <release-alias>
   # or from a signed APK:
   apksigner verify --print-certs app-release.apk | grep -i 'SHA-1'
   ```

   The SHA-1 must appear under the Firebase Android app **before** Google SSO
   works on a release install. Asset Links SHA-256 (App Links) is separate —
   having the release cert in `assetlinks.json` does **not** register it for
   Google Sign-In.

4. Download **`google-services.json`** into `app/`. The
   `com.google.gms.google-services` Gradle plugin reads it and generates the
   `default_web_client_id` string resource that Credential Manager uses — this
   is why no OAuth client ID lives in `local.properties`.

   Release builds enable `shrinkResources`. `AuthActivity` shows the Google
   button only when that string is present in the APK, so the app must keep a
   **static** `R.string.default_web_client_id` reference (see
   [GoogleSignInHelper.kt](../../app/src/main/java/com/indicvision/semper/ui/auth/GoogleSignInHelper.kt)).
   A `getIdentifier`-only lookup looks unused to the shrinker and silently
   hides SSO on release builds.

> Adding the SHA-1 in Firebase creates the matching Android OAuth client in the
> underlying Google Cloud project automatically. You do not need to create
> OAuth clients by hand.

## 1a. Email-link App Link (required for passwordless sign-in)

The passwordless link only signs the user in if tapping it **reopens this app**.
Firebase mails a link back to the continue URL
`https://indicvision-dic-app-auth.firebaseapp.com/finishSignIn`
(`EMAIL_LINK_CONTINUE_URL` in
[AuthRepository.kt](../../app/src/main/java/com/indicvision/semper/data/AuthRepository.kt)),
and `AuthActivity` declares a matching App Link `intent-filter` for that
host + path.

**Procedure of record** (Digital Asset Links, deploy, `adb` verify): see
[`firebase-hosting/README.md`](../../firebase-hosting/README.md). That folder
holds the Hosting project for the auth domain; Firebase Hosting with
`appAssociation: AUTO` serves the live `assetlinks.json`.

Until asset links verify, email + password sign-in still works; only the
passwordless **link** flow is affected (the link opens in a browser and can't
complete). Email/password reset links are read by the user in the browser and
do **not** depend on this App Link.

**The `intent-filter` is not the security boundary.** `AuthActivity` is
`exported`, so any installed app can start it with an *explicit* intent and any
`data` URI it likes — explicit starts never consult the filter, and `autoVerify`
constrains only implicit matching. `AuthActivity.isTrustedAuthLink` therefore
re-checks scheme and host against `AUTH_HOST` before it touches `intent.data`,
and both the sign-in-link and password-reset handlers go through it. Change the
domain in `AUTH_HOST` (one constant, in
[AuthRepository.kt](../../app/src/main/java/com/indicvision/semper/data/AuthRepository.kt))
and the manifest filter together, or tapped links stop being recognised.

## 2. Backend — which project's tokens to accept

The Cloud Run service verifies that a token's audience is a Firebase project
id. Set **one** env var (see [BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md)):

- `FIREBASE_PROJECT_ID` — the Firebase project from step 1. Defaults to
  `GOOGLE_CLOUD_PROJECT`, so you only need it when Firebase Auth lives in a
  different project than the backend (e.g. org policy blocks adding Firebase to
  the main one).

There is no client secret and no OAuth code exchange anywhere — the backend is
purely a verifier.

## 3. Who gets in

Verification proves *who* someone is. Whether they may use the system is a
separate decision made in `get_or_create_user`
([firestore_repo.py](../../backend/app/firestore_repo.py)):

| Env var | Effect |
|---|---|
| `ADMIN_EMAILS` | Comma-separated. A **verified** email in this list gets `role=admin` and is always approved |
| `AUTO_APPROVE_HD` | A **verified** email at this domain is created `APPROVED` |
| `AUTO_APPROVE` | `1` = every new user is created `APPROVED`. Pilot convenience; turn off for production |

Anyone else is created `PENDING` and lands on the approval screen until an
admin approves them via `POST /v1/admin/users/{uid}/approve`. Auto-approval
always requires a verified email, so a fresh email/password signup cannot
self-approve into a privileged domain.

> **There is no domain restriction on *signing in*, deliberately.** Any account
> Firebase Auth accepts can authenticate — that is what lets an outside
> collaborator sign in and land in the approval queue. The control that holds
> is the `PENDING`/`APPROVED` status above. An `ALLOWED_HD` setting that no
> code read used to sit in `config.py`; it has been removed, so don't go
> looking for it.

## 3a. Terms acceptance (clickwrap) and the improvement consent

Signing in proves identity; it does not bind anyone to the Terms. The app
records that as a separate, affirmative act: after **every** sign-in method
(password, Google, email link) and before Pending or Home, `AccessRouter.intentFor`
routes through `TermsActivity` whenever the accepted version on the device
differs from the version in force. The Terms box starts unticked and is the
only thing that unlocks the button; the improvement-consent box is pre-ticked
(product decision) but is a separate option the user can untick; Decline (or
Back) signs the user out.

| Piece | Where |
|---|---|
| Version in force | `backend/app/legal.py::TERMS_VERSION` = the `**Version:**` line of [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md); `tests/test_terms_and_consent.py` fails if they drift. `LegalTerms.TERMS_VERSION` in the app is the offline / PENDING fallback |
| `GET /v1/me` | adds `terms: {required_version, accepted_version, terms_url, privacy_url}` and `improvement_consent: bool \| null` |
| `POST /v1/me/terms` `{version}` | records `users/{uid}.termsAccepted`; **409 `terms_version_mismatch`** if the app sends a version the server does not serve (outdated app). Audit `TERMS_ACCEPTED` |
| `PUT /v1/me/consents` `{improvement}` | records `users/{uid}.improvementConsent`; the optional "use my data to improve Semper" choice, withdrawable in Settings → Your data. Audit `CONSENT_CHANGED` |
| Dependency | both use `any_status_user`: a **PENDING** account may accept (the gate runs before approval); SUSPENDED and unauthenticated are refused as before. `/v1/me` itself still needs `current_user` (APPROVED) |
| Export | `GET /v1/me/export` includes both records |

Bumping the Terms: edit the document, set the new date in `**Version:**`,
`backend/app/legal.py` and `LegalTerms.kt`, and regenerate the hosted pages.
Every user is re-gated on their next `/v1/me` because `required_version`
changes; the app stores the server value and compares it locally.

Acceptance is local-first: the gate opens as soon as the device has recorded
the choice, and an unsynced acceptance is re-sent by `AuthRepository.resolveStatus`
on the next successful `/v1/me`.

## 4. App config — local.properties

```properties
INDIC_API_BASE_URL=https://indic-api-xxxx.a.run.app
```

That is the only key the app needs, exposed as
`BuildConfig.INDIC_API_BASE_URL`. Leave it blank to run fully offline with
cloud sync disabled. Rebuild after changing it.

## Testing notes

- Use a **Google APIs / Play Store** emulator image. Plain AOSP images have no
  Play services and the credential sheet fails with `NoCredentialException`.
- Add at least one Google account to the emulator (Settings → Accounts).
- A first-time user is `PENDING` unless one of the rules in §3 applies, so
  expect the "Approval Pending" screen.

## Flow summary

```
Sign in (Google / email link / password)
   └─ Firebase Auth → Firebase ID token
        └─ GET /v1/me  (Authorization: Bearer <Firebase ID token>)
             └─ firebase-admin verifies signature, exp, iss, aud == project id
                  └─ user row ensured in Firestore (PENDING on first sign-in)
                       └─ Terms gate (once per Terms version; Decline = sign out)
                            └─ APPROVED → Home · PENDING → Approval Pending screen
```
