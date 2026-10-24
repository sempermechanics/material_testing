# OWASP MASVS v2 self-assessment (2026-10-18)

A self-assessment of the Android app and the backend it calls against the eight
MASVS v2 control groups, made for readiness item 3.6
([READINESS_FRAMEWORK_2026-10.md](READINESS_FRAMEWORK_2026-10.md)). It is **not**
a substitute for the third-party, MASTG-based penetration test the framework
requires (TD-240; scope in [PENTEST_SCOPE.md](PENTEST_SCOPE.md)). It records what
the code does, with evidence, so the tester starts from a map rather than a
blank page.

Status: **Met** (the control is in the code, with evidence), **Partial**,
**Gap** (owned by the TD id named), **N-A** (with the reason). App paths are
from `app/src/main/java/com/sempermechanics/semper/` unless they start at the
repository root. Written 2026-10-18 on `claude/new-session-jhfhj9`; every line
citation was re-checked on 2026-10-24 against `main` @ `875e2334` (readiness TE5-009),
and each names its symbol so a later shift can be followed. The Android half was not
compiled where it was written (TD-219).

## MASVS-STORAGE

| Control | Status | Evidence |
|---|---|---|
| STORAGE-1: sensitive data stored securely | Met | The device signing key is a non-exportable P-256 key in the Android Keystore (`data/account/DeviceKeys.kt:75-86`). The entitlement token and its clock state are sealed with an HMAC under a Keystore key (`data/account/entitlement/SealedEntitlement.kt:37-63`, `data/account/entitlement/EntitlementStore.kt:186-226`; write order in `SealedRecordStore.kt`). Firebase Auth holds its own session. Analyses are app-private files (`SessionStore`, `SessionPaths`) |
| STORAGE-2: no leakage of sensitive data | Met | Backup and device transfer exclude shared preferences (session, device id) and the session files (`app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`). Breadcrumbs and non-fatals reach Crashlytics only with diagnostics consent (`diagnostics/CrashReportingTree.kt:23-41`). Shared files go through a non-exported `FileProvider` with per-grant URIs (`app/src/main/AndroidManifest.xml:163-167`) |

## MASVS-CRYPTO

| Control | Status | Evidence |
|---|---|---|
| CRYPTO-1: current, strong cryptography | Met | ECDSA P-256 / SHA-256 for device request signatures (`verified_device`, `backend/app/deps.py:356`; the ECDSA check in `_signature_ok`, `:443-451`) and for the entitlement token (`backend/app/entitlement_token.py:115-124`; app `data/account/entitlement/EntitlementVerifier.kt:96-106`); HMAC-SHA256 for the sealed state. No custom primitive. Strict unpadded base64url and raw `r‖s` signatures on both sides (`entitlement_token.py:87-95`, `:161-166`) |
| CRYPTO-2: key management per best practice | Partial | Device keys never leave the Keystore. The entitlement signing key is in Secret Manager and reaches the service only as a secret reference (step `runtime_secrets`, `.github/workflows/deploy-backend.yml:266-286`); rotation by `kid` with several pinned keys ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). The private key is in process memory, not in KMS (TD-236); the key is not yet provisioned (TD-215) |

## MASVS-AUTH

| Control | Status | Evidence |
|---|---|---|
| AUTH-1: secure authentication and authorization protocols | Met | Every route verifies a Firebase ID token server-side (`verify_id_token`, `backend/app/google_auth.py:65`) and, for state changes from the phone, a device signature over a single-use nonce (`verified_device`, `backend/app/deps.py:356-440`; replay refused, `test_nonce_replay_rejected` and `test_client_nonce_replay_rejected`, `backend/tests/test_device_auth.py:131`, `:201`). Each route's tier is asserted in `backend/tests/test_route_authz_matrix.py`. Firestore denies every client (`firestore.rules:7-9`; proven on the emulator, `firestore-rules-tests/rules.test.mjs`). Authorization is decided on the server; the entitlement token only narrows what the app shows offline. Paid entitlements are attached to the account by the server, never entered in the app; every cloud route re-checks them per request, kill switches included ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)). The exception is share, a device-only feature with no route: the app's cached licence decides it, which a token can only narrow (`LicenseEntitlements.shareLicensed` via `FeatureLocks.share`, `ui/viewer/share/ShareCenter.kt:210`), so a modified build can share without a licence (TD-274, accepted) |
| AUTH-2: secure local authentication | N-A | The app has no local (biometric or PIN) authentication; access is the Firebase session and the server's answer |
| AUTH-3: additional authentication for sensitive operations | Partial | Console state changes need a completed TOTP second factor on a recent sign-in (`step_up_check`, `backend/app/step_up.py:117`); whole-licence revoke and delete need a fresher one (`attested_or_mfa_admin_fresh`). The revocation check on step-up is built but off until the Auth IAM grant (TD-214) |

## MASVS-NETWORK

| Control | Status | Evidence |
|---|---|---|
| NETWORK-1: secure traffic | Met | Cleartext is refused app-wide (`app/src/main/res/xml/network_security_config.xml`); release builds require an HTTPS API URL (`-PrequireCloudApi=true`, [RELEASING.md](RELEASING.md)); the APK inspection fails on cleartext (`scripts/check_release_apk.py`). Backend sends HSTS behind HTTPS (`_HSTS`, `backend/app/main.py:120`, set at `:161`) |
| NETWORK-2: identity pinning | Partial | `CertificatePinner` from `SEMPER_API_CERT_PINS` (`certificatePins`, `data/net/SemperApiClients.kt:55-68`; read from the environment, `app/build.gradle.kts:80-81`). The release secret is empty, so release builds ship system trust only (TD-218) |

## MASVS-PLATFORM

| Control | Status | Evidence |
|---|---|---|
| PLATFORM-1: IPC used securely | Met | Exported components are `SplashActivity`, the two launcher aliases and `AuthActivity`, whose App Link filters carry `autoVerify` (`app/src/main/AndroidManifest.xml:72-158`). The other activities, the `FileProvider` and the retry receiver say `exported="false"` (`:159-235`), except `ResultViewerActivity` (`:192-194`) and `RoiDrawActivity` (`:212-214`), which carry no `exported` attribute: they have no intent filter, so they are not exported, but the attribute is not stated. `AuthActivity` shows its `ROUTING_ERROR` extra as a message without checking where the intent came from (`ui/auth/AuthActivity.kt:105`; TE5-008). Managed configuration is read through `RestrictionsManager`, never from intents (`read`, `data/policy/ManagedConfigReader.kt:54-58`) |
| PLATFORM-2: WebViews used securely | N-A | The app has no WebView (no use in `app/src/main/java`) |
| PLATFORM-3: user interface used securely | Partial | No sensitive values are shown beyond the account's own email and licence prefix. `FLAG_SECURE` is not set on any screen; analyses are not secret by product decision |

## MASVS-CODE

| Control | Status | Evidence |
|---|---|---|
| CODE-1: up-to-date platform version | Met | minSdk 30, targetSdk 36 (`app/build.gradle.kts:151-152`); the targetSdk bump is a deliberate PR (CLAUDE.md) |
| CODE-2: mechanism to enforce updates | Met | `minSupportedVersionCode` / `recommendedVersionCode` on `/v1/config`, each app its own pair (`backend/app/routers/account.py:171-175`; `settings.release_gate`, `backend/app/config.py:314-321`); the app blocks new runs and cloud calls below the minimum and keeps the viewer and exports (`data/policy/FeatureGates.kt:34-40`, `ui/limit/UpdateRequiredActivity.kt:23`). Play in-app updates are TD-233 |
| CODE-3: no components with known vulnerabilities | Partial | `pip-audit --strict` over `requirements.lock` in every backend gate, so the hashed lock the image installs is what is audited (the `pip-audit` step of `.github/actions/backend-gate/action.yml`; until readiness TE5-001 it audited `requirements.txt`); Dependabot for pip, Gradle, Actions, Docker and npm (`.github/dependabot.yml`). No dependency-vulnerability scan of the Android dependency graph runs in CI |
| CODE-4: untrusted input validated | Met | Request bodies are Pydantic models with `extra="forbid"` and bounded identifiers (`backend/app/models.py`, `backend/app/validation.py`); CSV import refuses whole files before touching a row (`backend/app/roster_io.py`). The token parser rejects padded base64url (`Base64Url.decode`, `data/account/entitlement/Jws.kt:25-29`), any other `alg`, a `crit` header and an unknown `kid` (`headerProblem`, `EntitlementVerifier.kt:72-82`) |

## MASVS-RESILIENCE

| Control | Status | Evidence |
|---|---|---|
| RESILIENCE-1: platform integrity | Partial | App Check with the Play Integrity provider (`firebase.appcheck.playintegrity`, `app/build.gradle.kts:416`), enforced for device callers only when `APP_CHECK_MODE=enforce`, which is off (TD-226). No root detection and no server-side Integrity verdict (TD-234) |
| RESILIENCE-2: anti-tampering | Partial | Release APK inspected for debuggable, test-only, debug classes and the debug App Check provider (`scripts/check_release_apk.py`); the token is bound to the device key (`dkt`) and its stored state is HMAC-sealed. No signature or installer check at runtime (TD-235). Deleting both sealed state and HMAC key on a rooted phone falls back to the cached config (TD-261, accepted). A repackaged build that skips the licence check can use share, which has no server route (TD-274, accepted); it gains no cloud access |
| RESILIENCE-3: anti-static analysis | Partial | R8 minification and obfuscation on release (`isMinifyEnabled`, `app/build.gradle.kts:258`; the release check fails when R8's mapping renames no app class); the mapping is not published ([RELEASING.md](RELEASING.md)). Native engine built `-O3` with LTO. No string encryption |
| RESILIENCE-4: anti-dynamic analysis | Gap | No debugger, hook or Frida detection (TD-235) |

## MASVS-PRIVACY

| Control | Status | Evidence |
|---|---|---|
| PRIVACY-1: minimal access to sensitive data | Met | Permissions: media read (scoped by API level), internet, notifications, foreground data sync (`app/src/main/AndroidManifest.xml:6-14`); no location, contacts, camera or phone state. The device id is the app-scoped `ANDROID_ID` (`data/account/DeviceKeys.kt:54-62`) |
| PRIVACY-2: prevents identification | Partial | Analytics events carry enums and buckets only (CONTEXT.md, Invariants). Structured logs hash the one email they handled (`short_hash`, `backend/app/observability.py:228-235`; `backend/app/tasks.py:188`). The staff search sends its address in a POST body (`POST /v1/admin/licenses/search`, `backend/app/routers/admin.py:177-185`), and audit rows hash addresses (`address_tag`, `backend/app/audit.py:27-38`). A licence edit's free-text `note` is recorded by length only (`backend/app/routers/admin.py:29-40`). Left: the GET's `q` for one release (TD-273) |
| PRIVACY-3: transparent about data collection | Partial | [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) §2 and the data map [DATA_MAP.md](../legal/DATA_MAP.md); first-run diagnostics notice. The Play Data safety form is a draft (TD-232); policy operator fields are placeholders (TD-36) |
| PRIVACY-4: user control over their data | Met | Diagnostics are opt-in, off in the manifest (`app/src/main/AndroidManifest.xml:42-52`), withdrawable in Settings (`AppSettings.diagnosticsEnabled`, `data/prefs/AppSettings.kt:61`); `GET /v1/me/export` and `DELETE /v1/me` (`backend/app/routers/account.py:203`, `:252`). Erasure also deletes `auth_links` and blanks seat addresses (`delete_all_user_data` and `_blank_seat_addresses`, `backend/app/repo/account.py:188-245`), complete once its index is deployed (TD-271) |
