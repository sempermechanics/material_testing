# Firebase Hosting — auth domain (`indicvision-dic-app-auth`)

This site serves two things on `https://indicvision-dic-app-auth.firebaseapp.com`
that the passwordless **email-link sign-in** depends on:

| Path | Purpose |
|---|---|
| `/.well-known/assetlinks.json` | Digital Asset Links — lets Android verify the App Link and route the sign-in link to the app instead of a browser. |
| `/finishSignIn` | The email link's continue URL (`EMAIL_LINK_CONTINUE_URL` in [`AuthRepository.kt`](../app/src/main/java/com/rafad/indicvisiondic/data/AuthRepository.kt)). On-device the app's App Link intercepts it; in a plain browser it shows a "finish on your phone" page. |

Firebase Auth is configured to use **direct continue-URL handlers** (not the
retired Dynamic Links), so the emailed link lands on `/finishSignIn` carrying
the `oobCode`/`mode=signIn` params, and `AuthActivity` completes sign-in.

## Before you ship: add the release fingerprint

`public/.well-known/assetlinks.json` currently lists the **debug** signing key
plus a `REPLACE_WITH_RELEASE_SHA256_BEFORE_SHIPPING` placeholder. Replace that
placeholder with the SHA-256 of the **release** signing key (or the Play App
Signing key from the Play Console → *Setup → App signing*). Get a keystore's
SHA-256 with:

```bash
keytool -list -v -keystore <path-to-release.keystore> -alias <alias>
```

Both fingerprints (debug + release) can coexist in the array.

## Deploy

Requires the Firebase CLI (`npm i -g firebase-tools`) and access to the
`indicvision-dic-app-auth` project.

```bash
cd firebase-hosting
firebase deploy --only hosting
```

## Verify the App Link

```bash
# Confirm the file is live and served as JSON
curl -s https://indicvision-dic-app-auth.firebaseapp.com/.well-known/assetlinks.json

# On a connected device/emulator (Android 12+):
adb shell pm verify-app-links --re-verify com.rafad.indicvisiondic
adb shell pm get-app-links com.rafad.indicvisiondic   # expect: verified
```

Until the file is live and verified, the email **link** flow falls back to a
browser and can't complete; email **+ password** sign-in and password **reset**
are unaffected (reset links are read in the browser by design).
