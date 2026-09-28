# ADR-009: Material Testing is its own Android app on Semper's backend

**Status:** Accepted, built (Hosting deploy of the Asset Links entry owed)
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

A phone can hold both apps, but each has its own device key
(`DeviceKeyManager`), so the backend sees two devices. A licence or seat locks
to one device (`POST /v1/licenses/unbind`, `routers/licenses.py`): whichever
app signs in first holds it, and moving it to the other app is a device change
under `SELF_DEVICE_CHANGE_COOLDOWN_DAYS`, or an IT/staff unbind. When both apps
are installed and signed in, the sign-in and reset email links match two
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
- [ ] Release SHA-256 in `assetlinks.json` here (done) and in `semperdic-app`;
      deploy Hosting from `semperdic-app`.
- [ ] App Check: register the app with Play Integrity before
      `APP_CHECK_MODE=enforce`.
