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
repository root. Evidence is at the branch `claude/new-session-jhfhj9` as of
2026-10-18; the Android half was not compiled where it was written (TD-219).

## MASVS-STORAGE

| Control | Status | Evidence |
|---|---|---|
| STORAGE-1: sensitive data stored securely | Met | The device signing key is a non-exportable P-256 key in the Android Keystore (`data/account/DeviceKeys.kt:75-86`). The entitlement token and its clock state are sealed with an HMAC under a Keystore key (`data/account/entitlement/SealedEntitlement.kt:37-63`, `data/account/entitlement/EntitlementStore.kt:186-226`; write order in `SealedRecordStore.kt`). Firebase Auth holds its own session. Analyses are app-private files (`SessionStore`, `SessionPaths`) |
| STORAGE-2: no leakage of sensitive data | Met | Backup and device transfer exclude shared preferences (session, device id) and the session files (`app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`). Breadcrumbs and non-fatals reach Crashlytics only with diagnostics consent (`diagnostics/CrashReportingTree.kt:23-41`). Shared files go through a non-exported `FileProvider` with per-grant URIs (`app/src/main/AndroidManifest.xml:150-154`) |

## MASVS-CRYPTO

| Control | Status | Evidence |
|---|---|---|
| CRYPTO-1: current, strong cryptography | Met | ECDSA P-256 / SHA-256 for device request signatures (`backend/app/deps.py:304`, `verified_device`) and for the entitlement token (`backend/app/entitlement_token.py:115-124`; app `data/account/entitlement/EntitlementVerifier.kt:96-106`); HMAC-SHA256 for the sealed state. No custom primitive. Strict unpadded base64url and raw `r‖s` signatures on both sides (`entitlement_token.py:87-95`, `:161-166`) |
| CRYPTO-2: key management per best practice | Partial | Device keys never leave the Keystore. The entitlement signing key is in Secret Manager and reaches the service only as a secret reference (`.github/workflows/deploy-backend.yml:258-278`); rotation by `kid` with several pinned keys ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). The private key is in process memory, not in KMS (TD-236); the key is not yet provisioned (TD-215) |

## MASVS-AUTH

| Control | Status | Evidence |
|---|---|---|
| AUTH-1: secure authentication and authorization protocols | Met | Every route verifies a Firebase ID token server-side (`backend/app/google_auth.py:64`) and, for state changes from the phone, a device signature over a single-use nonce (`backend/app/deps.py:304-340`; replay refused, `backend/tests/test_device_auth.py:130`, `:172`). Each route's tier is asserted in `backend/tests/test_route_authz_matrix.py`. Firestore denies every client (`firestore.rules:7-9`; proven on the emulator, `firestore-rules-tests/rules.test.mjs`). Authorization is decided on the server; the entitlement token only narrows what the app shows offline |
| AUTH-2: secure local authentication | N-A | The app has no local (biometric or PIN) authentication; access is the Firebase session and the server's answer |
| AUTH-3: additional authentication for sensitive operations | Partial | Console state changes need a completed TOTP second factor on a recent sign-in (`backend/app/deps.py:474`, `step_up_check`); whole-licence revoke and delete need a fresher one (`attested_or_mfa_admin_fresh`). The revocation check on step-up is built but off until the Auth IAM grant (TD-214) |

## MASVS-NETWORK

| Control | Status | Evidence |
|---|---|---|
| NETWORK-1: secure traffic | Met | Cleartext is refused app-wide (`app/src/main/res/xml/network_security_config.xml`); release builds require an HTTPS API URL (`-PrequireCloudApi=true`, [RELEASING.md](RELEASING.md)); the APK inspection fails on cleartext (`scripts/check_release_apk.py`). Backend sends HSTS behind HTTPS (`backend/app/main.py:110-160`) |
| NETWORK-2: identity pinning | Partial | `CertificatePinner` from `SEMPER_API_CERT_PINS` (`data/net/SemperApiClients.kt:56-68`; read from the environment, `app/build.gradle.kts:80-81`). The release secret is empty, so release builds ship system trust only (TD-218) |

## MASVS-PLATFORM

| Control | Status | Evidence |
|---|---|---|
| PLATFORM-1: IPC used securely | Met | Exported components are the launcher and the verified App Link handlers (`app/src/main/AndroidManifest.xml:54-92`); every other activity and the `FileProvider` are `exported="false"` (`:141-222`). Managed configuration is read through `RestrictionsManager`, never from intents (`data/policy/ManagedConfigReader.kt:48-70`) |
| PLATFORM-2: WebViews used securely | N-A | The app has no WebView (no use in `app/src/main/java`) |
| PLATFORM-3: user interface used securely | Partial | No sensitive values are shown beyond the account's own email and licence prefix. `FLAG_SECURE` is not set on any screen; analyses are not secret by product decision |

## MASVS-CODE

| Control | Status | Evidence |
|---|---|---|
| CODE-1: up-to-date platform version | Met | minSdk 30, targetSdk 36 (`app/build.gradle.kts:130-131`); the targetSdk bump is a deliberate PR (CLAUDE.md) |
| CODE-2: mechanism to enforce updates | Met | `minSupportedVersionCode` / `recommendedVersionCode` on `/v1/config`, each app its own pair (`backend/app/routers/account.py:163-170`; `backend/app/config.py:293-300`); the app blocks new runs and cloud calls below the minimum and keeps the viewer and exports (`data/policy/FeatureGates.kt:34-40`, `ui/limit/UpdateRequiredActivity.kt:23`). Play in-app updates are TD-233 |
| CODE-3: no components with known vulnerabilities | Partial | `pip-audit --strict` on the backend lock in every backend gate (`.github/actions/backend-gate/action.yml:35-36`); Dependabot for pip, Gradle, Actions, Docker and npm (`.github/dependabot.yml`). No dependency-vulnerability scan of the Android dependency graph runs in CI |
| CODE-4: untrusted input validated | Met | Request bodies are Pydantic models with `extra="forbid"` and bounded identifiers (`backend/app/models.py`, `backend/app/validation.py`); CSV import refuses whole files before touching a row (`backend/app/roster_io.py`). The token parser rejects padded base64url (`data/account/entitlement/Jws.kt:25`), any other `alg`, a `crit` header and an unknown `kid` (`EntitlementVerifier.kt:72-82`) |

## MASVS-RESILIENCE

| Control | Status | Evidence |
|---|---|---|
| RESILIENCE-1: platform integrity | Partial | App Check with the Play Integrity provider (`app/build.gradle.kts:371`), enforced for device callers only when `APP_CHECK_MODE=enforce`, which is off (TD-226). No root detection and no server-side Integrity verdict (TD-234) |
| RESILIENCE-2: anti-tampering | Partial | Release APK inspected for debuggable, test-only, debug classes and the debug App Check provider (`scripts/check_release_apk.py`); the token is bound to the device key (`dkt`) and its stored state is HMAC-sealed. No signature or installer check at runtime (TD-235). Deleting both sealed state and HMAC key on a rooted phone falls back to the cached config (TD-261, accepted) |
| RESILIENCE-3: anti-static analysis | Partial | R8 minification and obfuscation on release (`app/build.gradle.kts:236`); the mapping is not published ([RELEASING.md](RELEASING.md)). Native engine built `-O3` with LTO. No string encryption |
| RESILIENCE-4: anti-dynamic analysis | Gap | No debugger, hook or Frida detection (TD-235) |

## MASVS-PRIVACY

| Control | Status | Evidence |
|---|---|---|
| PRIVACY-1: minimal access to sensitive data | Met | Permissions: media read (scoped by API level), internet, notifications, foreground data sync (`app/src/main/AndroidManifest.xml:6-14`); no location, contacts, camera or phone state. The device id is the app-scoped `ANDROID_ID` (`data/account/DeviceKeys.kt:54-62`) |
| PRIVACY-2: prevents identification | Partial | Analytics events carry enums and buckets only (CONTEXT.md, Invariants). Structured logs hash the one email they handled (`backend/app/observability.py:189-196`, `backend/app/tasks.py:132`). The staff search sends its address in a POST body, and audit rows hash addresses (`backend/app/routers/admin.py:166-174`, `backend/app/audit.py:35-47`; on `claude/readiness-followups-jhfhj9`). Left: the GET's `q` for one release (TD-273) and a licence edit's free-text `note` (TD-270) |
| PRIVACY-3: transparent about data collection | Partial | [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) §2 and the data map [DATA_MAP.md](../legal/DATA_MAP.md); first-run diagnostics notice. The Play Data safety form is a draft (TD-232); policy operator fields are placeholders (TD-36) |
| PRIVACY-4: user control over their data | Met | Diagnostics are opt-in, off in the manifest (`app/src/main/AndroidManifest.xml:28-38`), withdrawable in Settings (`AppSettings.diagnosticsEnabled`, `data/prefs/AppSettings.kt:61`); `GET /v1/me/export` and `DELETE /v1/me` (`backend/app/routers/account.py:194`, `:294`). Erasure also deletes `auth_links` and blanks seat addresses (`backend/app/repo/account.py:156-244`, on `claude/readiness-followups-jhfhj9`), complete once its index is deployed (TD-271) |
