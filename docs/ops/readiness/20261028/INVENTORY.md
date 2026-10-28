# Readiness inventory — 2026-10-28

Run: production-readiness review (second run), Phase 0. Repository `semperdic-app` at
`046d54c5` (worktree `semperdic-readiness-1028`, branch `claude/readiness-20261028`, engine
`f072da0` v0.6.1). The first run's inventory is [20261024/INVENTORY.md](../20261024/INVENTORY.md)
(at `875e2334`); this one restates it at the new head and marks what changed. Since #489
(`6d2bde6c`, the first run's merge) nothing changed in `app/src/main`, `backend/app` or the
route matrix; 15 later PRs (#485, #488, #490–#502) touched the console, CI and benchmarks.
Read-only; every claim was checked against the code on this date.

## Screens (Android)

No Jetpack navigation graph and no Fragments: screens are Activities
(`app/src/main/AndroidManifest.xml`), plus sheets and dialogs.

| Screen | Manifest | Exported | Role |
|---|---|---|---|
| `SplashActivity` (+ `LauncherLight` / `LauncherDark` aliases) | :74, :84, :97 | yes (launcher) | Start, routing (`AccessRouter`) |
| `AuthActivity` | :109 | yes (4 App Link filters, `autoVerify`) | Sign-in, email link, reset |
| `TermsActivity` | :173 | no | Clickwrap gate |
| `PendingApprovalActivity` | :172 | no | Account awaiting approval |
| `HomeActivity` | :159 | no | Session list, **+** → `MediaPickerSheet` |
| `StaticAnalysisActivity` | :209 | no (full `configChanges`) | Analysis wizard (core flow) |
| `RoiDrawActivity` | :215 | no | ROI studio |
| `ResultViewerActivity` | :193 | no | Viewer, exports (core flow) |
| `VsgLatticeActivity` | :219 | no | VSG lattice view |
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

Theme: `Theme.Semper` (MaterialComponents Light, `values/themes.xml:6`). **Changed since
2026-10-24:** `values/colors.xml` has 81 colours (was 59) and `values-night` overrides 31
(was 30); width buckets `values-w600dp`, `values-w840dp`, `values-w1240dp` now exist
(`dimens.xml`, `content_side_inset` 48 / 120 / 320 dp); still no `sw600dp`. Locked paid
features: one component, `ui/common/dialog/LicenseLock.kt` (decisions in
`data/policy/FeatureLocks.kt`).

## Operator console (Firebase Hosting, `app.sempermechanics.com`)

| Page | Entry module | Who |
|---|---|---|
| `/console/` (sign-in) | `router.js` (`console/index.html:80`) | everyone |
| `/console/account/` | `account.js` (+ `claim.js`) | account holder |
| `/console/institution/` | `institution.js` (+ `roster-tools.js`, `audit-log.js`) | institution IT (`adminEmails`) |
| `/console/operator/` | `operator.js` (+ edit, licences, lifecycle, mint, people, roster-card, state, test-accounts, verify) | staff |
| `/finishReset` | `reset.js` (`finishReset/index.html:32`) | password reset continue |
| `/auth/finishSignIn` | static, no script (correction: the first inventory listed `reset.js`) | email-link landing |
| `/privacy/`, `/terms/` | generated from `docs/legal/` | public |

Every console page also loads `chrome.js`. No page or entry module was added or removed
since 2026-10-24; #493, #497, #500–#502 changed session expiry, MFA re-enrolment, list
toggles and the test-accounts card.

## API endpoints and auth requirement

71 routes, unchanged. The authoritative table is `EXPECTED` in
`backend/tests/test_route_authz_matrix.py:83-196` (the first inventory's ":80-216" was
wrong); the test fails if a route ships without a row, and only `/healthz` and `/readyz`
are unauthenticated.

| Tier | Meaning | Routes |
|---|---|---|
| NONE | none | `GET /healthz`, `GET /readyz` (8 s budget, `routers/health.py:17-34`) |
| TOKEN_ONLY | Firebase ID token, any account state | `GET /v1/me/erasure` |
| ANY_STATUS | ID token, before approval | `POST /v1/me/terms`, `PUT /v1/me/consents` |
| USER | ID token, approved account | `GET /v1/me`, `GET /v1/config`, `POST /v1/devices/register`, `POST /v1/challenge`, `GET /v1/sessions`, `GET /v1/sessions/{sid}/files`, `POST /v1/licenses/{activate,checkout,release}`, `GET /v1/institutions/licenses` |
| USER_STEPUP | USER + browser step-up | `GET /v1/sessions/{sid}/bundle`, `POST /v1/licenses/unbind`, `POST /v1/me/claim` |
| DEVICE | USER + device-signed nonce | `GET /v1/me/export`, `DELETE /v1/me`, `POST /v1/sessions`, `DELETE /v1/sessions/{sid}`, `GET /v1/sessions/{sid}/uploads`, `PUT /v1/sessions/{sid}/metadata`, `GET /v1/files/{id}/content`, `POST /v1/files/{id}/complete` |
| ADMIN | staff token (reads) | 12 routes |
| ADMIN_STEPUP | staff + TOTP / device step-up | 14 routes |
| ADMIN_STEPUP_FRESH | staff + fresh step-up | licence revoke / delete, test-account create / extend / password / device release / delete |
| INSTITUTION_STEPUP | `adminEmails` member + step-up | 9 routes under `/v1/institutions/licenses/{id}/…` |
| TASK | Cloud Tasks / Scheduler OIDC | `POST /v1/tasks/{provision-session,license-fanout,test-account-expiry}` |

Kill switches and the version gate have no routes of their own: `GET /v1/config` publishes
them (`routers/account.py:155`, body :172-178). Switch names `config.py:29`
(backup / share / restore / upload), enforced server-side by `deps.unless_paused`
(`deps.py:303-323`); the version gate is advisory (`account.py:164-170`, per-app
`MIN_SUPPORTED` / `RECOMMENDED_VERSION_CODE` at `config.py:319-334`).

Contracts: gateway allowlist `backend/gateway/openapi.yaml`, held equal to the app by
`test_gateway_parity.py`. **New since 2026-10-24:** the FastAPI app's own OpenAPI is
committed at `backend/contracts/app-openapi.json` (TE3-006, ADR-029); 
`tests/test_openapi_snapshot.py` fails on any difference.

## Firestore collections (Admin SDK only; client rules deny all, `firestore.rules:7-9`)

Unchanged: `users`, `devices`, `sessions`, `files`, `licenses`, `licenses/{id}/seats`,
`licenseInvites`, `challenges` (TTL `expireAt`), `auth_links`, `audit_logs`,
`deleted_licenses`, `deleted_licenses/{id}/deleted_seats`. The rules tests' list
(`firestore-rules-tests/collections.mjs`) is held equal to the backend's by
`collections.test.mjs`.

## Test suites and gate commands (`.github/workflows/ci.yml`)

| Suite | Command (CI job, line) | Floor / gate |
|---|---|---|
| Secrets | gitleaks, `.gitleaks.toml` (`secret-scan`, :49) | no leaks |
| Engine pin (**new**) | `scripts/check_engine_pin.py` (`engine-pin`, :93) | gitlink is a `v*` tag, no downgrade |
| Repo scripts | `render_legal_pages.py --check`, `check_doc_paths`, `check_framework_coverage`, `check_kotlin_file_size`, `check_activity_scope_work`, `check_swallowed_cancellation`, `unittest discover scripts/tests` (`legal-pages`, :129) | all pass |
| Console | `check_console.py`; `node --test firebase-hosting/tests/*.test.mjs` with `NODE_V8_COVERAGE`; `console_coverage.mjs --lines=96` (`console-pages`, :185) | 96 % lines |
| Console a11y (**new**) | Playwright + axe-core at 360 / 768 / 1280 px, `npm run test:a11y` (`console-a11y`, :222) | no serious / critical violation, no sideways scroll |
| Engine host (**new**) | cmake ASan + UBSan, `dic_tests` (`engine-host-sanitized`, :534; Linux + libopencv) | all pass |
| Android static (**split out**) | `spotlessCheck :app:detekt` (`tier1-static`, :592) | empty baselines |
| Android unit incl. `.dat` / GIF oracles | `:app:testDebugUnitTest :app:lintDebug :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin :benchmark:compileBenchmarkKotlin` (`tier1-app-fast`, :606) | all pass; oracles bit-exact |
| Kover | `:app:koverLog :app:koverVerify` | lines 78, branches 60 (`app/build.gradle.kts:525,528`) |
| Release (local) | `ciReleaseGate` | Spotless, detekt, lint, unit tests, R8, assembleRelease |
| Android instrumented | `:app:connectedDebugAndroidTest` (`tier3-emulator-e2e`, :673; API 34; signs in to live staging) | all pass |
| Backend | `.github/actions/backend-gate`: hashed lock, `pip-audit --strict`, `ruff check` (now with the bandit `S` rules, `backend/ruff.toml:13`), `pytest --cov-fail-under=93`; shellcheck, lock coverage, Firestore emulator tier (`tier4-backend`, :765) | 93 % |
| Firestore rules | `firebase emulators:exec … node --test` (`firestore-rules`, :875) | all pass |
| Signed release | assembleRelease, apksigner, `check_release_apk.py` (now also targetSdk ≥ 36 and 16 KB `.so` alignment, #488) (`tier5-signed-release`, :923) | not debuggable, no debug App Check, cleartext off, mapping; pins warn-only |
| Benchmarks | `launch_smoke.py`, Macrobenchmark `--require-tests` (#498), micro A/B (`tier-benchmark`, :1041) | A/B gate |
| Engine (submodule) | host / sanitizer / DICe suites in `semper-dic-engine` ([docs/engine/TESTING.md](../../../engine/TESTING.md)) | — |

On #489 the new jobs (secret-scan, engine-pin, console-a11y, engine-host-sanitized) ran
green; **Tier 3 and Tier 5 were skipped** by the path filter.

Now present that the first inventory listed as absent: StrictMode (debug, log only,
`SemperApp.kt:39,112`), bandit rules (ruff `S`), axe (console only), a committed OpenAPI
snapshot. Still absent: screenshot tests, Android accessibility checks, Lighthouse,
Schemathesis (TD-319), a semantic OpenAPI breaking-change diff, semgrep. Room is not
used, so Room migration tests do not apply.

Added later in this run's fix round (2026-10-28, on the readiness branch): Lighthouse
(`console-lighthouse`, AE2-110), semgrep (`semgrep`) and OSV-Scanner over the Android
release graph (`android-deps-osv`), both TE5-105. The list above is the state at
inventory time.

## External actions owed by the Admin (open on 2026-10-28)

No commit since #489 touches the gate, `docs/ops/readiness`, `docs/legal` or
`ops/monitoring`. From the first run's [REPORT §4](../20261024/REPORT.md) and the
[gate](../../PRODUCTION_READINESS_GATE.md):

| Id | Action | State |
|---|---|---|
| A1, A2 | Merge the readiness branch with CI OK; gitleaks green | **Done** (#489; Tier 3 / Tier 5 skipped there) |
| A3 | — | Done before (REPORT :306) |
| A4 | Deploy the backend from `main` (lock bump, kill switches) | Open: last Deploy Backend runs 2026-10-26 09:29 / 09:35, before the merge |
| A5 | Hosting deploy via `scripts/deploy-console.sh` | Open: none recorded |
| A10 | Sign or fix the five blocking P1s (TE1-001, TE5-003, TE5-004, TE6-001, TE6-011) | Open: rows still "Admin to sign" (`PRODUCTION_READINESS_GATE.md:720-724`); Sign-off empty (:757-760) |
| A6–A16 | TD-217, 215, 318, 221–225, 277, 36, 317, 340–344, 327, 330 | Open (rows present) |
| A17 | Instrumented re-run on `Semper_API30` | Not verifiable from the repo |
| A18 | Remove `posture_20261024.log` from the checkout root | Open: file still present |
| Gate A1–A14 | Gate's Admin actions table (`PRODUCTION_READINESS_GATE.md:549-562`) | All unticked |
| Owner TD rows | 209, 214, 215, 217–227, 229–232, 240, 241, 248, 252, 258, 277, 309, 319 | All open |

Stale docs found while building this: `CONTEXT.md:119,129` and
`PRODUCTION_READINESS_GATE.md:521-522` still say the readiness branch is not merged;
`docs/adr/README.md:36` lists ADR-029 as "built (not merged)".
