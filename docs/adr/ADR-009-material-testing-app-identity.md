# ADR-009: Material Testing is its own Android app on Semper's backend

**Status:** Accepted, built (App Check owed)
**Date:** 2026-09-28
**Deciders:** app owner

## Context

material_testing kept Semper's `applicationId` (`com.indicvision.semper`) when
it forked from `semperdic-app`. A lab build therefore replaced Semper on a phone
and took over its analyses, sign-in, licence state and upload queue (TD-133).
It also could not ship: a release has to be signed with Semper's key to install
over Semper, and Android refuses it anyway, because this repo's workflow run
number (the `versionCode`) is far below Semper's, which makes it a downgrade.

## Decision

1. **Own package.** `applicationId = "com.indicvision.semper.materialtesting"`
   (`app/build.gradle.kts`). `namespace` stays `com.indicvision.semper`, so no
   source package, `R` or `BuildConfig` import moves. The test APK follows as
   `com.indicvision.semper.materialtesting.test`.
2. **Own release key.** This repo's `KEYSTORE_BASE64` / `KEY_ALIAS` /
   `KEY_PASSWORD` / `STORE_PASSWORD` hold a key made for Material Testing, not
   Semper's.
3. **Same backend and accounts.** A second Android app in the same Firebase
   project (`indicvision-dic-app-auth`). ID tokens carry the project as their
   audience, so the backend accepts both apps unchanged; it has no package
   check. `INDIC_API_BASE_URL` is Semper's gateway.
4. **App Links for both.** `assetlinks.json` lists both packages, each with its
   own certificates. Hosting deploys from `semperdic-app` only, so the entry
   lands there too.

## Options considered

- **Keep the shared identity, sign with Semper's key.** Still replaces Semper,
  and the lower `versionCode` blocks the install. Rejected.
- **Separate Firebase project.** Separate accounts, licences and backend.
  Rejected: the point is one sign-in for both.
- **Build flavour in `semperdic-app`.** The cleaner end state, but it merges two
  repos that now differ in the wizard. Not now.

## Trade-off analysis

A phone can hold both apps, but Android scopes `ANDROID_ID` to the signing
key, so each app has its own device id (`and-{ANDROID_ID}`, `DeviceKeyManager`)
and the backend sees two devices. Until the backend binds per app, the whole
account has one registered device and one licence or seat lock: whichever app
signs in first takes the phone, and the other is refused at sign-in with
`device_conflict` ("already linked"). Moving to the other app is a device
change under `SELF_DEVICE_CHANGE_COOLDOWN_DAYS`, or an IT/staff unbind.
[Corrected 2026-09-28: this section first said only the licence or seat
locked to one device. The account's registered device does too, and that is
what sign-in hit.]

[ADR-010](ADR-010-device-binding-per-app.md) (semperdic-app #279, #280,
deployed 2026-09-28) binds one phone per app. Each app sends `X-App-Id` (its
`applicationId`, `data/net/AppIdHeader.kt`), and has its own registered device,
release hold, lock and self-service cooldown; a staff or IT clear moves both.
It came here in the 2026-09-28 sync. A Material Testing build from before it
sends no header, so the backend still reads it as Semper.

When both apps are installed and signed in, the sign-in and reset email links match two
verified apps, and Android asks which one opens the link; the other app cannot
finish that link, because the pending email lives in the app that sent it.

## Consequences

- `google-services.json` here holds both clients (Firebase app
  `1:171818100029:android:52451e04a07d4bbe161154` for Material Testing), with
  one shared web OAuth client. A merge from `semperdic-app` must keep this
  repo's copy, and keep `applicationId`.
- Existing lab installs under `com.indicvision.semper` stay as they are; the new
  app installs beside them. Uninstall a lab build that replaced Semper and
  reinstall Semper to get Semper's data path back.
- `benchmark/`, `scripts/startup_ab.py` and CI's micro-benchmark step target the
  new package.

## Action items

- [x] `applicationId`, benchmark targets, CI instrument/pull paths, docs.
- [x] Firebase Android app with the release SHA-1/SHA-256 and the two debug
      SHA-1s Semper already lists; its `google-services.json` (2026-09-28).
- [x] Release key (alias `materialtesting`, SHA-256 `F5:DC:B8:…:46:37`) and
      `INDIC_API_BASE_URL`.
- [x] Release SHA-256 in `assetlinks.json` here and in `semperdic-app`
      (semperdic-app #278); Hosting deployed (2026-09-28). Released as
      `v1.2-beta.1` from `2d28c3fc` (#82).
- [x] One phone per app: semperdic-app #279 and #280 merged and deployed,
      and synced here (2026-09-28).
- [x] Cut a build with `AppIdHeader`: `v1.2-beta.2` from `fc1aaa4e`; it signed
      in licensed on a Pixel 6 (2026-09-29).
- [ ] Sign in on a phone where Semper is signed in too.
- [ ] App Check: register the app with Play Integrity before
      `APP_CHECK_MODE=enforce`.
