# Google Play Data safety — draft answers (2026-10-18)

Draft for readiness item 8.4 ([READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md)),
derived from the SDK inventory in `app/build.gradle.kts` and from what the app sends
to the backend. **Not submitted** (TD-232); the operator answers the form in the
Play Console and owns the final text. This file is **not rendered** to Hosting
(`scripts/render_legal_pages.py` renders only the Privacy Policy and the Terms).
The underlying inventory is [DATA_MAP.md](DATA_MAP.md).

## SDK inventory (`app/build.gradle.kts:354-418`, Firebase BoM 34.19.0)

| SDK | Line | What it sends off the device |
|---|---|---|
| Firebase Analytics | `:361` | Usage events with enum and bucket parameters, app instance id, device and app metadata. **Only with consent**: collection is off in the manifest (`app/src/main/AndroidManifest.xml:28-38`) |
| Firebase Authentication | `:363` | Email, display name, sign-in provider, password (to Google) |
| Firebase App Check (Play Integrity) | `:371` | Integrity request to Google Play; App Check token to the backend |
| Firebase Crashlytics | `:377` | Crash stack traces, device model, OS and app version, breadcrumbs. **Only with consent** |
| Credential Manager, Google ID (`androidx.credentials`, `googleid`) | `:405-407` | Google sign-in token exchange |
| OkHttp, kotlinx.serialization, WorkManager, Timber and AndroidX UI libraries | `:410-417` | Nothing of their own; OkHttp carries the app's own calls |

To confirm on the release APK before submitting: `aapt2 dump permissions` for
`com.google.android.gms.permission.AD_ID`, which Firebase Analytics declares unless
the manifest removes it.

## Data the app sends to Semper's backend

| Data | Endpoint | When |
|---|---|---|
| Firebase ID token (email, uid inside) | every call | signed in |
| Device id `and-{ANDROID_ID}`, device public key, model, OS and app version | `POST /v1/devices/register` | first sign-in on a phone |
| `X-App-Id`, `X-App-Version` | every call | always |
| Analysis name, specimen file name, small run metrics | `POST /v1/sessions` | each analysis recorded to the cloud |
| Images, results, reports, `metadata.json` | Drive resumable upload | each analysis recorded to the cloud; on Demo this is automatic, with no toggle ([PRODUCTION_READINESS_GATE.md](../ops/PRODUCTION_READINESS_GATE.md), licensing rollout) |
| Terms acceptance and improvement consent | `POST /v1/me/terms`, `PUT /v1/me/consents` | sign-in gate; Settings |

## Draft answers

**Does the app collect or share any of the required user data types?** Yes.

**Is all user data encrypted in transit?** Yes: cleartext is refused app-wide
(`app/src/main/res/xml/network_security_config.xml`) and release builds require an
HTTPS API URL.

**Do you provide a way for users to request that their data is deleted?** Yes: in
the app (Settings, **Delete account**, which calls `DELETE /v1/me`) and by email to
support.

| Play category / type | Collected | Shared | Optional | Purposes |
|---|---|---|---|---|
| Personal info / Email address | Yes | No | No (required for an account) | App functionality, Account management, Fraud prevention and security |
| Personal info / Name | Yes (display name from the identity provider) | No | Yes | Account management |
| Personal info / User IDs | Yes (Firebase uid) | No | No | App functionality, Account management |
| Photos and videos / Photos | Yes (analysis images uploaded to cloud backup) | No | Licensed: the user chooses backup. Demo: recorded automatically. Answer "No" (not optional) unless that changes | App functionality |
| Photos and videos / Videos | No: frames extracted on the device are uploaded as images | — | — | — |
| Files and docs | Yes (results, reports, metadata) | No | as for photos | App functionality |
| App activity / App interactions | Yes (Analytics, opt-in) | No | Yes | Analytics |
| App info and performance / Crash logs, Diagnostics | Yes (Crashlytics, opt-in) | No | Yes | Analytics |
| Device or other IDs | Yes (`ANDROID_ID`-derived device id, app instance id) | No | No | App functionality, Fraud prevention and security |
| Location, Financial info, Health, Messages, Contacts, Calendar, Audio, Web history | No | — | — | — |

"Shared" follows Play's definition: transfers to service providers processing on
Semper's behalf (Google Cloud and Firebase, Resend for the support mail) are not
sharing.

**Independent security review:** No, until TD-240.

## Gate items this answers

[PRODUCTION_READINESS_GATE.md](../ops/PRODUCTION_READINESS_GATE.md): "Update the
Google Play Data safety form" (consent and terms records, improvement use) and
"Google Play Data safety form: demo analysis content".
