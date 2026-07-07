# Google SSO Setup (native one-tap)

The app signs in with Google using AndroidX **Credential Manager** to obtain a
Google **ID token**, which **Supabase** verifies server-side. The client code is
already wired ([GoogleSignInHelper.kt](../app/src/main/java/com/rafad/indicvisiondic/ui/GoogleSignInHelper.kt),
[AuthActivity.handleGoogleSignIn](../app/src/main/java/com/rafad/indicvisiondic/AuthActivity.kt),
[AuthRepository.loginWithGoogle](../app/src/main/java/com/rafad/indicvisiondic/AuthRepository.kt)).
It will not function until the three external pieces below are configured — the
"Continue with Google" button shows a setup hint until then.

## 1. Google Cloud — OAuth clients

In [console.cloud.google.com](https://console.cloud.google.com) → **APIs & Services → Credentials**:

1. Configure the **OAuth consent screen** (External, add your test users while in Testing).
2. Create an **OAuth client ID → Web application**. Copy its **Client ID** and
   **Client secret** (used by Supabase in step 2). This is the
   `GOOGLE_WEB_CLIENT_ID` the app needs.
3. Create an **OAuth client ID → Android**:
   - Package name: `com.rafad.indicvisiondic`
   - SHA-1 fingerprint of your signing key. For debug:
     ```
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
     Add your release keystore's SHA-1 too before shipping.

> The **Web** client ID (not the Android one) is what goes in the app and in
> Supabase — that is how the ID-token audience is validated.

## 2. Supabase — enable Google provider

Supabase dashboard → **Authentication → Providers → Google**:

1. Toggle **Enable**.
2. Paste the **Web** client ID and client secret from step 1.
3. Under **Authorized Client IDs**, also add the same Web client ID (this lets
   Supabase accept ID tokens minted for the native Android flow).
4. Save.

## 3. App config — local.properties

Add your Web client ID to `local.properties` (already git-ignored):

```properties
GOOGLE_WEB_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
```

It is exposed to code as `BuildConfig.GOOGLE_WEB_CLIENT_ID`. Rebuild after adding.

## Testing notes

- Use a **Google APIs / Play Store** emulator image (plain AOSP images lack Play
  services and the credential sheet will fail with `NoCredentialException`).
- Add at least one Google account to the emulator (Settings → Accounts).
- First-time SSO users are created in `auth_profiles` with `access_status =
  PENDING`, so they land on the "Approval Pending" screen until an admin flips
  them to `APPROVED` — identical to email registration.

## Flow summary

```
Continue with Google
   └─ CredentialManager → Google ID token
        └─ Supabase signInWith(IDToken, provider = Google)   ← validates audience
             └─ ensure auth_profiles row (PENDING on first login)
                  └─ SplashActivity gatekeeper routes APPROVED / PENDING
```
