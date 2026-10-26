# Readiness inventory — 2026-10-24

Run: production-readiness review, Phase 0. Repository `semperdic-app` at `875e2334`
(worktree `semperdic-readiness`, branch `claude/readiness-20261024`, engine `f072da0`
v0.6.1); fork `material_testing` at `839d4285` (synced to `968637cb`, so without
#471–#483). Read-only; every claim below was read from the code on this date.

## Screens (Android)

No Jetpack navigation graph and no Fragments: screens are Activities
(`app/src/main/AndroidManifest.xml`), plus sheets and dialogs.

| Screen | Manifest | Exported | Role |
|---|---|---|---|
| `SplashActivity` (+ `LauncherLight` / `LauncherDark` aliases) | :73, :82, :95 | yes (launcher) | Start, routing (`AccessRouter`) |
| `AuthActivity` | :108 | yes (4 App Link filters, `autoVerify`) | Sign-in, email link, reset |
| `TermsActivity` | :173 | no | Clickwrap gate |
| `PendingApprovalActivity` | :172 | no | Account awaiting approval |
| `HomeActivity` | :159 | no | Session list, **+** → `MediaPickerSheet` |
| `StaticAnalysisActivity` | :207 | no (full `configChanges`) | Analysis wizard (core flow) |
| `RoiDrawActivity` | :213 | no | ROI studio |
| `ResultViewerActivity` | :193 | no | Viewer, exports (core flow) |
| `VsgLatticeActivity` | :217 | no | VSG lattice view |
| `SaveExportActivity` | :197 | no (transparent) | Export save target |
| `SettingsActivity` | :161 | no | Account, cloud, analyses, storage, preferences, your data, help |
| `AdminActivity` | :160 | no | Staff tools |
| `SessionLimitActivity` | :175 | no | Demo / quota limit |
| `SeatRequiredActivity` | :179 | no | Floating seat needed |
| `UpdateRequiredActivity` | :183 | no | Version gate |

Source-set extras: `DebugViewerSeedActivity` (debug, exported), `BenchmarkSeedActivity`
and re-exported Settings / wizard (benchmark build type only).

Sheets: media picker, send to, settings used, share, video sampling. Dialogs: custom
scale, delete choice, sweep frame pick, determinate progress (`ui/common/dialog/`).

Theme: `Theme.Semper` (MaterialComponents Light, `values-night` overrides 30 of 59
colours); no `sw600dp` / `w840dp` resources. Locked paid features: one component,
`ui/common/dialog/LicenseLock.kt` (decisions in `data/policy/FeatureLocks.kt`).

## Operator console (Firebase Hosting, `app.sempermechanics.com`)

| Page | Entry module | Who |
|---|---|---|
| `/console/` (sign-in) | `router.js` | everyone |
| `/console/account/` | `account.js`, `claim.js` | account holder |
| `/console/institution/` | `institution.js` (+ `roster-tools.js`, `audit-log.js`) | institution IT (`adminEmails`) |
| `/console/operator/` | `operator.js` (+ edit, licences, lifecycle, mint, people, roster-card, state, test-accounts, verify) | staff |
| `/auth/finishSignIn`, `/finishReset` | `reset.js` | email-link / reset continue |
| `/privacy/`, `/terms/` | generated from `docs/legal/` | public |

## API endpoints and auth requirement

71 routes. The authoritative table is `EXPECTED` in
`backend/tests/test_route_authz_matrix.py:80-216`; the test fails if a route ships
without a row, and only `/healthz` and `/readyz` are unauthenticated.

| Tier | Meaning | Routes |
|---|---|---|
| NONE | none | `GET /healthz`, `GET /readyz` |
| TOKEN_ONLY | Firebase ID token, any account state | `GET /v1/me/erasure` |
| ANY_STATUS | ID token, before approval | `POST /v1/me/terms`, `PUT /v1/me/consents` |
| USER | ID token, approved account | `GET /v1/me`, `GET /v1/config`, `POST /v1/devices/register`, `POST /v1/challenge`, `GET /v1/sessions`, `GET /v1/sessions/{sid}/files`, `POST /v1/licenses/{activate,checkout,release}`, `GET /v1/institutions/licenses` |
| USER_STEPUP | USER + browser step-up | `GET /v1/sessions/{sid}/bundle`, `POST /v1/licenses/unbind`, `POST /v1/me/claim` |
| DEVICE | USER + device-signed nonce | `GET /v1/me/export`, `DELETE /v1/me`, `POST /v1/sessions`, `DELETE /v1/sessions/{sid}`, `GET /v1/sessions/{sid}/uploads`, `PUT /v1/sessions/{sid}/metadata`, `GET /v1/files/{id}/content`, `POST /v1/files/{id}/complete` |
| ADMIN | staff token (reads) | 12 routes: users, licences list/search/one, deleted licences, reconcile, seats list/export, summary, audit, device history, test-accounts list |
| ADMIN_STEPUP | staff + TOTP / device step-up | 14 routes: approve, revoke user, device releases, user config, mint, edit, convert, restore deleted, seat device, seats add/patch/delete, invite withdraw, seats import |
| ADMIN_STEPUP_FRESH | staff + fresh step-up | licence revoke, licence delete, test accounts create / extend / password / device release / delete |
| INSTITUTION_STEPUP | `adminEmails` member + step-up | 9 routes under `/v1/institutions/licenses/{id}/…` (seats CRUD, invite withdraw, import, export, summary, audit) |
| TASK | Cloud Tasks / Scheduler OIDC | `POST /v1/tasks/{provision-session,license-fanout,test-account-expiry}` |

Gateway allowlist: `backend/gateway/openapi.yaml`, kept equal to the app by
`test_gateway_parity.py`. No committed OpenAPI of the FastAPI app itself.

## Firestore collections (Admin SDK only; client rules deny all, `firestore.rules:7-9`)

`users`, `devices`, `sessions`, `files`, `licenses`, `licenses/{id}/seats`,
`licenseInvites`, `challenges` (TTL `expireAt`), `auth_links`, `audit_logs`,
`deleted_licenses`, `deleted_licenses/{id}/deleted_seats`. The rules tests' list
(`firestore-rules-tests/collections.mjs`) is held equal to the backend's by
`collections.test.mjs`.

## Test suites and gate commands

| Suite | Command (CI) | Floor / gate |
|---|---|---|
| Android unit (Robolectric; 386 files) incl. `.dat` / GIF oracles (`DatFieldOracleTest`, `SummaryGifOracleTest`) | `./gradlew :app:testDebugUnitTest` (Tier 1) | all pass; oracles bit-exact |
| Android static | `spotlessCheck :app:detekt :app:lintDebug` | empty baselines |
| Kover | `:app:koverLog :app:koverVerify` | lines 78, branches 60 (fork 71 / 57) |
| Release | `ciReleaseGate`; Tier 5 `assembleRelease` + `apksigner` + `scripts/check_release_apk.py --aapt2 --mapping` | not debuggable, no debug App Check, cleartext off, mapping present; pins warn-only |
| Android instrumented (21 files) | `:app:connectedDebugAndroidTest` (Tier 3; signs in to live staging) | all pass |
| Benchmarks | `benchmark/` macro (smoke) + `HotPathMicroBenchmark` A/B (`scripts/micro_ab.py`) | A/B gate on PR |
| Backend | `pytest tests/ -q --cov=app --cov-fail-under=93`; emulator tier `--cov-append`; `ruff check`; `pip-audit --strict`; hashed lock | 93 % |
| Firestore rules | `firebase emulators:exec … node --test` in `firestore-rules-tests/` | all pass |
| Console | `check_console.py`; `node --test firebase-hosting/tests/*.test.mjs` (`node:test`, fake DOM); `console_coverage.mjs --lines=96` | 96 % lines |
| Repo scripts (`legal-pages` job) | `render_legal_pages.py --check`, `check_doc_paths.py`, `check_framework_coverage.py`, `check_kotlin_file_size.py`, `check_activity_scope_work.py`, `check_swallowed_cancellation.py`, `unittest discover scripts/tests` | all pass |
| Secrets | gitleaks (`.gitleaks.toml`) | no leaks |
| Engine | not this CI: host / sanitizer / DICe suites in `semper-dic-engine` ([docs/engine/TESTING.md](../../../engine/TESTING.md)) | — |

Absent: screenshot tests, Android accessibility checks, StrictMode, Playwright / axe /
Lighthouse, Schemathesis, OpenAPI breaking-change diff, bandit / semgrep.

## External actions owed by the Admin (open on 2026-10-24)

From [TECH_DEBT.md](../../TECH_DEBT.md), [DEPLOY_CHECKLIST_2026-10.md](../../DEPLOY_CHECKLIST_2026-10.md)
and the [gate](../../PRODUCTION_READINESS_GATE.md):

| Id | Action |
|---|---|
| TD-229 | Create / link the Play developer account; add the Play app-signing SHA-256 to the Firebase Android app |
| TD-226 | App Check `off` → `monitor` once a Play build is the fleet (after TD-229) |
| TD-230 / TD-231 | Play listing and pre-launch report; rehearse the staged rollout |
| TD-232 / TD-317 | Two owner decisions, then submit the Play Data safety form; Privacy Policy omits Analytics and App Check |
| TD-215 | Create the entitlement signing key in Secret Manager and grant the runtime SA |
| TD-214 | Accept `CHECK_REVOKED_ON_STEP_UP`; `roles/firebaseauth.admin` for `indic-api@` on the Auth project |
| TD-277 | Counsel review of the Terms and Privacy Policy |
| TD-36 | Fill the 10 bracketed operator fields (name, address, Grievance Officer, region) in the legal pages |
| TD-217 | Create a notification channel and run `scripts/apply-monitoring.sh --apply` |
| TD-221–TD-225 | Liens, `Prod` tag, owners through groups; API-key restrictions; Auth providers / enumeration; budgets; Data Access audit logs |
| TD-227 | Record the production Firestore location |
| TD-252 | Crashlytics velocity alerts and routing |
| TD-309 | Resend sender and repository variable for new-device mail |
| TD-240 | Commission the MASVS-mapped penetration test |
| TD-248 / TD-209 | Separate staging project (deferred); staging shares the production Firestore |
| Checklist §6–§7 | Staff phones re-registering with TOTP, 24 h log watch, erasure check, end the staging QC account, release builds with entitlement public keys, device smoke, raise the minimum version |
