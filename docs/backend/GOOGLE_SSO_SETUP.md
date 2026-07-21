# Google SSO Setup (native one-tap)

The app signs in with Google using AndroidX **Credential Manager** to obtain a
Google **ID token**, which the **inDIC GCP backend** (Cloud Run) verifies
server-side — signature, audience, issuer, `email_verified`, and the corporate
hosted domain. The client code is already wired
([GoogleSignInHelper.kt](../../app/src/main/java/com/rafad/indicvisiondic/ui/auth/GoogleSignInHelper.kt),
[AuthActivity.handleGoogleSignIn](../../app/src/main/java/com/rafad/indicvisiondic/ui/auth/AuthActivity.kt),
[AuthRepository.signInWithGoogle](../../app/src/main/java/com/rafad/indicvisiondic/data/AuthRepository.kt)).
It will not function until the pieces below are configured — the "Continue with
Google" button shows a setup hint until then.

## 1. Google Cloud — OAuth clients

In [console.cloud.google.com](https://console.cloud.google.com) → **APIs & Services → Credentials**:

1. Configure the **OAuth consent screen** (Internal for a Workspace-only app, or
   External + test users while in Testing).
2. Create an **OAuth client ID → Web application**. Copy its **Client ID** —
   this is the `GOOGLE_WEB_CLIENT_ID` the app needs and the **audience** the
   backend checks. (The Web client *secret* is **not** needed — the backend only
   verifies the ID token, it never does an OAuth code exchange.)
3. Create an **OAuth client ID → Android**:
   - Package name: `com.rafad.indicvisiondic`
   - SHA-1 fingerprint of your signing key. For debug:
     ```
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
     Add your release keystore's SHA-1 too before shipping.

> The **Web** client ID (not the Android one) is what goes in the app and in the
> backend's `WEB_CLIENT_ID` — that is how the ID-token audience is validated.

## 2. Backend — accepted audience & domain

The Cloud Run service verifies every ID token. Set its env vars (see
[BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md)) so they match this app:

- `WEB_CLIENT_ID` = the **Web** client ID from step 1.2 (token audience).
- `ALLOWED_HD` = your corporate domain, e.g. `company.com` (only users from this
  Workspace hosted domain may sign in).

No provider toggle or client secret is configured anywhere — the backend is the
verifier.

## 3. App config — local.properties

Add your Web client ID and the backend URL to `local.properties` (git-ignored):

```properties
GOOGLE_WEB_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
INDIC_API_BASE_URL=https://indic-api-xxxx.a.run.app
```

They are exposed to code as `BuildConfig.GOOGLE_WEB_CLIENT_ID` and
`BuildConfig.INDIC_API_BASE_URL`. Leave `INDIC_API_BASE_URL` blank to run the app
offline-only (sign-in/sync disabled). Rebuild after changing.

## Testing notes

- Use a **Google APIs / Play Store** emulator image (plain AOSP images lack Play
  services and the credential sheet will fail with `NoCredentialException`).
- Add at least one Google account to the emulator (Settings → Accounts).
- First-time SSO users are created in Firestore with `access_status = PENDING`,
  so they land on the "Approval Pending" screen until an admin flips them to
  `APPROVED` (during a pilot you can deploy the backend with `AUTO_APPROVE=1`).

## Flow summary

```
Continue with Google
   └─ CredentialManager → Google ID token
        └─ POST /v1/me  (Authorization: Bearer <ID token>)
             └─ Cloud Run verifies signature / aud / iss / hd / email_verified
                  └─ user row ensured in Firestore (PENDING on first sign-in)
                       └─ AuthActivity routes APPROVED → Home / PENDING → Pending
```
