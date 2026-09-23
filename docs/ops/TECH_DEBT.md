# Tech debt

Baselines stay empty: `app/lint-baseline.xml` and `app/detekt-baseline.xml`.
`./gradlew :app:lintDebug` fails on errors; `lintVitalRelease` is clean.

**Still open warnings** (do not baseline, do not `warningsAsErrors` until decided):

- `OldTargetApi` — disabled in `app/build.gradle.kts` lint config until a
  deliberate `targetSdk` 36→37 bump PR. Do not re-enable casually.

Size and complexity findings are silenced with targeted `@file:Suppress`
rather than a baseline — prefer extracting over widening those lists. As of
2026-09-23 that is not "a few files": 81 main files carry `@file:Suppress`
(most often `MagicNumber` 55, `ReturnCount` 29, `TooManyFunctions` 27,
`LongParameterList` 21, `CyclomaticComplexMethod` 19, `LongMethod` 18) and 17
carry `@file:SuppressLint`. Since the baselines are empty, suppression is the
only thing keeping those findings quiet.
Catalog version-availability lint IDs are disabled; bump deps in deliberate PRs.

`UnclosedTrace`, `PluralsCandidate`, `UseKtx`, `TooManyViews` on
`activity_settings` / `wizard_step_settings`, and the 2026-08 lint warning
set are fixed (content extracted behind `SettingsScrollContentView` /
`WizardStepSettingsContentView`). The architecture extracts that had missed
`main` (#65 / #67, re-landed as #69 / #70) are on `origin/main` as of
2026-08-16.

Orphaned strings the 2026-08-18 workflow audit found are listed in
[../app/WORKFLOWS.md](../app/WORKFLOWS.md) §11 — none of them fail a gate, so they
are removed opportunistically rather than in a sweep.

## Proposed improvements live next door

Forward-looking items are in [FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md).
This file stays the record of what is *owed* and what is deliberately deferred.

## Open register

Priority = (Impact + Risk) × (6 − Effort).

**Re-verified against the code on 2026-09-23.** Every row below was checked at
`main` @ `4d04c28`, not copied from the previous register; several of the old
rows' numbers were wrong and are corrected here. Evidence is `path:line`.
Architecture decisions for the structural rows are in [../adr/](../adr/README.md);
the remediation order is fourteen PRs, tracked in the Status column.

### Carried over (re-scored)

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-3 | Architecture | The viewer's read side: `ViewerArgs` (`ui/viewer/ViewerArgs.kt`) is the one writer, but four readers (`ResultViewerActivity` 25 `DicKeys` refs, `VsgLatticeActivity` 21, `ViewerSettingsSheet` 12, `ViewerReportFactory` 8) parse the bundle themselves with **different silent defaults** (subset 41 vs 0, step 5 vs 1, ROI image-size vs 0). The lattice→viewer hop copies the whole bundle (`VsgLatticeActivity.kt:757-764`) | 3 | 3 | 3 | **18** | [ADR-003](../adr/ADR-003-viewerargs-read-side.md) — `ViewerArgs.from(intent, record)` |
| TD-22 | Test | Consoles have no behavioural test; `scripts/check_console.py` reads structure only | 3 | 3 | 4 | **12** | **Partly fixed** (console PR): DOM-free `console/util.js` + `node --test` in the `console-pages` job. Sign-in/step-up/revoke E2E still needs Firebase fixtures |
| TD-24 | Architecture | No `@MainThread` on UI entry points (0 in `app/src/main`), so `SessionStore`'s 13 `@WorkerThread` annotations are not a gate. **14** Activities (the old row said ~27) | 2 | 2 | 2 | **16** | **Fixed** (app-reuse PR): all 14 Activities carry a class-level `@MainThread`, so lint's `WrongThread` now checks every `SessionStore` `@WorkerThread` call made from them. It found one site, `SettingsActivity.enqueueRestoreWithStub`, which already ran on the IO dispatcher and is now annotated `@WorkerThread`. A helper that runs off the main thread has to say so with `@WorkerThread` or `@AnyThread` |
| TD-25 | Test | `AuthRepository` cannot be unit-tested: `IndicApi` is `class IndicApi private constructor` (`data/net/IndicApi.kt:44`), reached by 21 `IndicApi.get(` calls in 10 files; no mocking library in the test stack | 2 | 2 | 2 | **16** | **Fixed** (cloud-api-seam PR, [ADR-002](../adr/ADR-002-cloudapi-seam.md)): `IndicApi` implements `CloudApi`; `AuthRepository`, `SeatLease`, `CloudSync` and `CloudAccountExport` take it as a defaulted parameter. `AuthRepositoryTest` (14) and `CloudSeamTest` (13) run on `FakeCloudApi` |
| TD-26 | Architecture | **Reframed.** Rotation does not recreate the wizard (full `configChanges`, `AndroidManifest.xml:147-149`). **Process death** loses everything: no `SavedStateHandle` anywhere, and `CacheJanitor` deletes `temp_deformed` on start (`data/CacheJanitor.kt:132`). A re-run after a kill loses `workingLocalId` and creates a second Home row | 3 | 3 | 3 | **18** | [ADR-005](../adr/ADR-005-wizard-process-death.md) — wizard draft |
| TD-27 | Ops | The API Gateway is never deployed by CI; the runbook ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) "Redeploying the gateway") also had three faults (unset `FIREBASE_PROJECT_ID`, same-day config-id collision, inverted `grep && exit 1`) | 3 | 3 | 3 | **18** | [ADR-006](../adr/ADR-006-gateway-deploy-job.md); runbook faults fixed in the docs PR. Closes after the owner's first `apply` dispatch (needs `roles/apigateway.admin` + `roles/iam.serviceAccountUser` on `indic-gw@`) |
| TD-28 | Backend | A per-user `maxSessions` override is ignored while the account is demo (`backend/app/firestore_repo.py:537-538`) | 2 | 2 | 2 | 16 | **Closed as decided (2026-09-23):** "lift a demo cap" means attach a licence. `BACKEND_SETUP_GCP.md` described it wrongly and is corrected |
| TD-29 | App | Two auth continue hosts (`AUTH_HOST` / `LEGACY_AUTH_HOST`, `data/AuthRepository.kt:47,55`; four App Link hosts in the manifest; `AuthHostsTest.kt:17` pins the legacy host) | 1 | 2 | 2 | **8** | Deferred until Play vitals show no legacy-only build installed |
| TD-30 | Ops | API Gateway in asia-northeast1 vs Cloud Run in asia-south1. Whether the legacy `indic-gw` / `indic-api` pair still exists **cannot be verified from the repo**, and CONTEXT previously said it was deleted: check with `gcloud api-gateway gateways list` and `gcloud run services list` | 2 | 1 | 3 | **9** | Deferred — measure the hop first |
| TD-33 | Backend | A contended invite claim that starves out leaves the account on the Demo key it then mints; `claim_pending_invite` returns early on `licenseId` (`firestore_repo.py:2192`), so the invite stays pending. The client retries **10** times (`_TX_ATTEMPTS`, `firestore_repo.py:49`), not five as previously written | 2 | 1 | 3 | **9** | **Fixed** (backend quick-wins PR): `ensure_entitlement` skips the Demo mint when the claim failed with `_CONTENDED`, so the invite stays claimable and the next request takes it; a non-transient failure (seats exhausted) still mints Demo. `test_a_starved_invite_claim_mints_no_demo_key` fails on the old code |
| TD-34 | Console | Dead pre-#129 enrolment card on the operator desk (`operator/index.html:44-73`, `operator.js:14,54-90`) **and** the account page (`account/index.html:47-77`, `account.js:19,30-63`); `auth.js:472` awaits `ensureDashboardMfa()` before any page code runs, so it never shows | 1 | 2 | 2 | **12** | **Fixed** (console PR): both enrolment cards and their handlers are gone; the pages show a fixed "2FA on" pill, since `ensureDashboardMfa` has already enrolled and confirmed a factor before page code runs. `check_console.py` now passes on Windows too (it fed node cp1252) |

### New (found 2026-09-23)

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-35 | Infra / privacy | The monthly restore drill purges only `users devices sessions files audit_logs challenges _migrations` (`.github/workflows/firestore-restore-drill.yml:96`), so `licenses`, their `seats`, `licenseInvites` and `auth_links` — production PII — stay in the weaker drill project; `scripts/firestore_verify.py` does not check them either | 3 | 4 | 1 | **35** | **Fixed** (CI/ops PR): drill purges `licenses seats licenseInvites auth_links` too; `firestore_verify.py` counts them (`seats` as a collection group) |
| TD-36 | Legal | 10 bracketed operator fields in `docs/legal/PRIVACY_POLICY.md` (:5 ×2, :7 ×2, :18, :153) and `TERMS_OF_SERVICE.md` (:6 ×2, :438, :439) are published as literal text on the hosted pages | 2 | 5 | 1 | **35** | Operator task (values); listed in [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md) |
| TD-61 | **Accuracy** | The viewer's metadata depends on how it was opened. A fresh run passes the **requested** ROI (`ui/analysis/AnalysisNavHelper.kt:113-116`) but the session saves the **resolved, inset** ROI the engine ran on (`ui/analysis/DicBatchRunner.kt:340-343`), so the report, ⓘ sheet, CSV header and lattice line cut change when the same session is reopened from Home. Also `SESSION_ID` (`Pending_Cloud_Sync_…` vs the local id), sweep subset/strain window (slider vs first combination), and `lastPlannedFrames` / `lastStep` left stale by an all-failed sweep | 3 | 4 | 2 | **28** | [ADR-004](../adr/ADR-004-runspec.md) + [ADR-003](../adr/ADR-003-viewerargs-read-side.md) |
| TD-37 | Build | No `ndkVersion` (`app/build.gradle.kts`): the bit-exact `-ffast-math` engine silently follows AGP's default NDK | 2 | 3 | 1 | **25** | **Fixed** (CI/ops PR): `ndkVersion = "28.2.13676358"`, the NDK every local `.cxx` build already used |
| TD-38 | Test | The Kover floor is configured (`minBound(27)`, `app/build.gradle.kts:389-395`) but **never enforced**: CI runs `koverLog` only (`ci.yml:294-295`) and `ciReleaseGate` omits `koverVerify`. Measured 31.9 % locally | 2 | 3 | 1 | **25** | **Fixed** (CI/ops PR): `koverVerify` in tier 1 and `ciReleaseGate`; 32.1 % measured |
| TD-39 | Test | `DicResultCsvTest.kt:33` pins `DicResult.CSV_POINT_HEADER` (`DicResult.kt:177`), but production CSV uses its own copy (`report/AnalysisCsvWriter.kt:49`) | 2 | 3 | 1 | **25** | **Fixed** (app-correctness PR): `AnalysisCsvWriter` uses `DicResult.CSV_POINT_HEADER`; one definition |
| TD-40 | Infra | R8 `mapping.txt` is a 90-day workflow artifact, deliberately not attached to the release (`release.yml:231-240`), and Crashlytics mapping upload is off: field crashes become unreadable after 90 days | 2 | 3 | 1 | **25** | **Fixed** (CI/ops PR): release builds pass `-PuploadCrashlyticsMapping=true`, so Crashlytics keeps the mapping privately; the artifact stays as a 90-day fallback. First real upload happens on the next release |
| TD-41 | Code | `runCatching` around a suspend call swallows `CancellationException` at **21** sites (`AuthRepository` ×5, `CloudRestore:563`, `CloudSync:153,268`, `DicUploadWorker` ×7, `SeatLease:64,86,103`, `SeatRequiredActivity:69`, `SettingsYourDataSection:116`, `AuthActivity:428`). Visible bug: cancelling the cloud account export shows "export failed" and logs `EXPORT_FAILED` | 3 | 3 | 2 | **24** | **Fixed** (app-correctness PR): `util.suspendRunCatching` rethrows cancellation at all 21 sites. The local export also swallowed cancellation (`SessionEverythingExporter` caught every `Exception` and never checked between sessions) and kept packing after Cancel; `LocalExportCancelTest` failed on the old code (3 sessions after a cancel) and passes now. `SessionUploadBundler` now checks per frame and per GIF, so Cancel on the emulator clears the banner in about 2 s instead of 4–6 s |
| TD-42 | CI / security | Dispatch inputs interpolated into shell (`deploy-backend.yml:74,76`; `backend-lock.yml:99,127`); no top-level `permissions:` in `ci.yml`; no `refs/heads/main` guard on the deploy; `reactivecircus/android-emulator-runner@v2` unpinned at `ci.yml:668` (SHA-pinned at `:357`); `pip-audit` / `pip-tools` unpinned; gitleaks downloaded by curl with no checksum (`ci.yml:57-59`) | 2 | 3 | 2 | **20** | **Fixed** (CI/ops PR): inputs via `env:`, `ci.yml` read-only default, production deploy only from `main`, runner SHA-pinned, `pip-audit==2.10.1` / `pip-tools==7.6.1`, gitleaks tarball checked against the release checksums |
| TD-43 | CI | The backend gate differs across three workflows: `deploy-backend.yml:61-62` runs no pip-audit, coverage gate, emulator tier or ruff on scripts; `release.yml:65` runs no lint/detekt/spotless before signing | 2 | 3 | 2 | **20** | CI composites PR |
| TD-44 | Backend / observability | `observability.classify_route` (`backend/app/observability.py:24,37-41`) rewrites any unlisted segment of 8+ characters to `{id}`: `/v1/licenses/activate` logs as `/v1/{id}/{id}` and every licence and institution route is opClass `other`. Blocks the telemetry that shim retirement needs | 3 | 2 | 2 | **20** | **Fixed** (backend quick-wins PR): `main.py` registers every router's declared `path_format`; `classify_route` matches against those (params → `{id}`) and falls back to the id regex only for undeclared paths. New opClasses `license` and `institution`; `/v1/me/consents` and `/v1/me/terms` are `account`. `test_every_declared_route_has_a_class` fails on a route left as `other` |
| TD-45 | Backend | Compat shims: nine in the code, retirement order documented for two (CLOUD_ARCHITECTURE_GCP §20.5). The `/v1/campus/*` invite-revoke alias (`routers/institutions.py:176`, `gateway/openapi.yaml:816`) was added on 2026-09-09 (`4100955`), **after** the rename (`90485b4`, 2026-09-07), so no caller can exist. `plan` is still written into the audit detail (`routers/licenses.py:38`); `PRO_MAX_SESSIONS_PER_USER` is still read as a default (`config.py:50-53`) | 2 | 2 | 2 | **16** | **Partly fixed** (backend quick-wins PR): the never-called `/v1/campus/*` invite-revoke alias is deleted from the router, gateway spec and authz matrix; `LICENSE_ACTIVATE` audits `mode`, not `plan`; all nine remaining shims have a retirement row and signal in CLOUD_ARCHITECTURE_GCP §20.5. The retirements themselves wait on those signals |
| TD-46 | Dependencies | Dependabot (`.github/dependabot.yml`) has no `docker` (the digest-pinned `backend/Dockerfile`), `gitsubmodule` (`native`) or `.github/actions/*` entries; CI uses Node 20 (EOL April 2026) and `firebase-tools@13`; the Firestore workflows pin `google-cloud-firestore==2.21.0` against the backend's 2.29.0 | 2 | 2 | 1 | **20** | **Fixed** (CI/ops PR): Dependabot `docker` (`/backend`) and `/.github/actions/*`; Node 22; `firebase-tools@13.35.1` exact; Firestore workflows on 2.29.0. No `gitsubmodule`, deliberately: an engine pin move is a reviewed oracle change |
| TD-47 | Security | `.gitleaks.toml` allowlists every `local.properties` (:14), two paths that no longer exist (:15-16) and four whole commits (:18-23) | 1 | 3 | 1 | **20** | **Fixed** (CI/ops PR): the `local.properties` and dead website-sync path rules are gone. A full-history scan (764 commits, CLI 8.24.3 and 8.28.0) then showed the `SupabaseManager.kt` path and commits `9a3623a` and `46dbc60` suppress nothing the remaining rules miss; they are removed. Left: the `google-services.json` path and commits `b9e9065` (deleted Supabase client key) and `46e50fd` (one historical `local.properties`) |
| TD-48 | Privacy | A personal Gmail address was committed in this public repo (`PRODUCTION_READINESS_GATE.md:211`) | 1 | 3 | 1 | **20** | Docs PR (git history keeps it) |
| TD-49 | Docs | `CONTEXT.md` was 83 % changelog (lines 134–780) | 3 | 2 | 2 | **20** | Docs PR — history moved to [CHANGELOG.md](CHANGELOG.md) |
| TD-50 | Console | `seatRow` / `inviteRow` exist in both `operator.js:661/678` and `institution.js:115/141` and have drifted: the operator desk shows an expired lease as "until …" | 2 | 2 | 2 | **16** | **Fixed** (console PR): `console/util.js` `seatCells` / `inviteCells` render the four shared columns for both pages (operator table reordered to Member · Status · Seat · Device); an expired lease reads "—". `firebase-hosting/tests/util.test.mjs` |
| TD-51 | Code | Dead code: `CloudRestore.listRestorable` / `listRestorableSessions` and their 60 s cache (`data/CloudRestore.kt:209-277`, no caller); six `SessionStore.*Async` wrappers; `FrameOrderHelper.loadMeta/sortMeta`; a doc comment + `@Suppress` orphaned above the wrong function (`CloudRestore.kt:527-532`); worker keys `KEY_DONE/KEY_TOTAL/KEY_LOCAL_ID` never read; `SettingsActivity.kt:530` reads `KEY_CLOUD_SESSION_ID` from progress no worker writes; `AnalysisViewModel.hasCompletedAnalysis` write-only | 2 | 1 | 1 | **15** | **Fixed** (app-correctness PR), except `hasCompletedAnalysis`, which goes with [ADR-004](../adr/ADR-004-runspec.md): the dead listing, its cache and invalidation, six wrappers, `loadMeta` / `sortMeta` plus the now-unused URI date probes, the orphaned doc, unread worker keys and the `SettingsActivity` progress read |
| TD-52 | Backend | `notify._retry_delay` honours `Retry-After` with no upper limit (`backend/app/notify.py:92-100`): one 429 can park the single notify worker | 1 | 2 | 1 | **15** | **Fixed** (backend quick-wins PR): `app/backoff.retry_delay` caps `Retry-After` at 16 s (the exponential ceiling) for both notify and Drive; Drive's copy, on the request path, had the same unbounded wait |
| TD-53 | Backend | The contention block `try tx / except Exception: if not _lost_to_contention(exc): raise` is repeated ten times in `firestore_repo.py` | 2 | 2 | 2 | **16** | **Fixed** (backend refactor PR): one `_run_tx(body, *, on_contended)` in `firestore_repo.py` runs every transactional body; each of the ten sites passes what a contention loss means there (`_CONTENDED`, `False`, a re-read, …). `bind_device_lock` keeps its outer re-read loop around it. Unblocks [ADR-001](../adr/ADR-001-firestore-repo-package.md) |
| TD-54 | Backend | Router duplication: `raise HTTPException(429, …)` ×35 at audit; #154 moved the 25 signed routes to the `deps.rate_limited` dependency (so a 429 no longer spends the nonce), leaving **14** in-handler raises (`admin` ×4, `devices` ×2, `health` ×2, `institutions` ×1, `licenses` ×3, `sessions` ×2) that send no `Retry-After`; `routers/sessions.py` ownership guard ×4, page-size clamp ×3 with two ceilings, `page` dict ×3; cursor loop copied (`firestore_repo.py:3147,3175`); 15 raw status strings bypass `statuses.py`; two `_retry_delay`s (merged into `backend/app/backoff.py` in the quick-wins PR); `sessions.py:21` imports from another router | 2 | 1 | 2 | **12** | **Fixed** (backend refactor PR): `rate_limit.enforce(bucket, key)` raises the 429 with `Retry-After`, and `deps.rate_limited` uses it too, so the 14 unsigned routes now say when to retry (`test_an_unsigned_429_says_when_to_retry`; the signed-route guard also catches `enforce` in a handler). `routers/_shared.py` holds `json_dumps`, `clamp_page_size` and `page_block` (used by `sessions` and `admin`), and `sessions._owned_session` is the one ownership guard. `_cursor_page` and `_session_file_docs` replace six copied paging loops in `firestore_repo.py`. The 13 raw status literals use `statuses.*` (the `validation.AccessStatus` `Literal` stays literal) |
| TD-55 | Test | Emulator re-race logic in three variants (`test_firestore_emulator_integration.py:541-570, 600-620, 706-721`); the `store` fixture defined in 10 files; `test_licenses.py` is 2,518 lines | 1 | 2 | 2 | **12** | **Fixed** (backend refactor PR): `_race_until(race, settled)` in the emulator tier; each test's per-round assertions live in its `settled`, and the device-lock test re-races a fully starved round like the other two. `store` and `audited` live in `tests/conftest.py`, and the six modules that need more override `store` by taking it. `test_licenses.py` is seven files by section, plus `tests/license_helpers.py` for the four helpers they share; an `ast.dump` comparison showed every test body moved unchanged |
| TD-56 | CI | Copy-paste across workflows (java+gradle setup ×6, KVM ×2, ccache env ×3, prune ×3, arm64 `.so` check ×2); `.github/actions/dic-submodules` referenced nowhere; six jobs without `timeout-minutes` | 2 | 1 | 2 | **12** | CI composites PR |
| TD-57 | Test | 14 main classes over 300 lines with no test at all (`ShareCenter` 759, `SweepSetupHelper` 822, `DicBatchRunner.kt` 413, `SessionUploadBundler` 397, `PdfReportGenerator` 304, `VsgPlotView`, `StudioOverlayView`, `TouchImageView`, `RoiDrawActivity`, `VsgLatticeView`, `SessionSelectionController`, `MediaPickerSheet`, `ViewerSettingsSheet`, `SessionListAdapter`); `HotPathMicroBenchmark` never runs in CI (`ci.yml:371`) | 3 | 3 | 4 | **12** | Coverage PR |
| TD-58 | Architecture | `backend/app/firestore_repo.py` is 3,432 lines / 138 functions, two thirds licensing | 3 | 2 | 4 | **10** | **Fixed** ([ADR-001](../adr/ADR-001-firestore-repo-package.md) PR): eleven modules under `backend/app/repo/` (largest `licensing.py`, about 1,300 lines); `firestore_repo.py` is a facade whose assignments propagate into the package, so no test changed. An `ast.dump` comparison showed all 159 definitions unchanged apart from `firestore` → `_base.firestore`; `tests/test_repo_facade.py` pins the propagation, the one-way imports and the facade's public names |
| TD-59 | Hygiene | Seven merged remote branches (`beta-v0.1`, `damodar`, `beta-v1.2.0`, `video-decoding`, `chore/semper-names`, `docs/perf-142-deployed`, `fix/appcheck-real-phones`) and the dead `fix/capture-dic-good-practice-gates` | 1 | 1 | 1 | **10** | Close-out PR, each deletion confirmed |
| TD-60 | Build | Java 11 source/target (`app/build.gradle.kts:255-256`) vs JDK 17 in CI and detekt `jvmTarget = "17"` (`:366`); plugin versions outside the catalog; ktlint `1.5.0` hard-coded twice; `localProperty()` re-implemented for two keys | 1 | 1 | 2 | **8** | **Fixed** (app-reuse PR): Java 17 source/target in `app` and `benchmark`; detekt, Kover, Spotless, google-services and Crashlytics resolve through `gradle/libs.versions.toml` `[plugins]`; ktlint is `libs.versions.ktlint`; `INDIC_API_BASE_URL` and `INDIC_API_CERT_PINS` read through `localProperty()` |
| TD-62 | Misc | `StudioOverlayView` suppresses `DrawAllocation` over real `RectF` allocations in `onDraw` (:497, :500); `Thread.sleep` in the `RetryOnTransient` interceptor; `create_session` drops the next-page token (`routers/sessions.py:401,469`); `TIMEOUT_US` declared twice; `reconcile_institution_seats` reads one user per seat serially | 1 | 1 | 1 | **10** | **Fixed.** Backend in the backend quick-wins PR: `create_session` returns `nextPageToken` (one page is the whole manifest while `MAX_FILES_PER_SESSION` ≤ `_LIST_SOFT_LIMIT`, now a test), and `reconcile_institution_seats` reads holders with one `get_all`. App in the app-reuse PR: `StudioOverlayView` reuses a scratch `RectF` and no longer suppresses `DrawAllocation`; `RetryOnTransient` waits in 100 ms slices and ends the wait when the OkHttp call is cancelled (`RetryOnTransientTest`). Coroutine cancellation still does not reach it, because `IndicApi` runs a blocking `execute()` (the ADR-002 seam). `HardwareVideoDecoder.TIMEOUT_US` is the one declaration and `AviCodecDecoder` uses it |
| TD-63 | Code | `PendingApprovalActivity:53` and `SessionLimitActivity:69` constructed `DeviceKeyManager` — which loads the AndroidKeyStore and may generate a key — only to show or mail the device id, which needs neither; a Keystore fault crashed the screen that tells the user how to reach support | 2 | 2 | 1 | **20** | **Fixed** (app-correctness PR): Keystore-free `DeviceKeyManager.deviceId(context)` at every id-only site |
| TD-64 | Architecture | `backend/app/repo/licensing.py` is about 1,300 lines after the ADR-001 split (mint, claims, invites, entitlement, licence admin), past the ~1,000 the ADR set for splitting it again | 2 | 1 | 3 | **9** | Split mint / claims / invites along the existing one-way edges; `tests/test_repo_facade.py` keeps the order honest |

### Cannot be verified from the repository

Checked by hand in the console or with `gcloud`, not by reading code: whether
the legacy `indic-gw` / `indic-api` pair exists (TD-30); branch protection
requiring `CI OK`; whether the deny-all `firestore.rules` is deployed; API-key
restrictions; GitHub Environment branch rules on `production`. The open
checkboxes are in [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).

### Closed as obsolete, 2026-09-14

TD-4, TD-5, TD-18, TD-20 and TD-21 all described the in-app camera: the
capture orchestrators, their missing instrumented tier, `GrayPngEncoder`,
`LockedCameraSession`'s duplicated bodies, and the per-capture `ImageReader`
re-registration. `449c9da` removed the feature, so `ui/capture/` is gone from
both this branch and `main` and none of those symbols resolves anywhere in
`app/src`. They are struck rather than carried as permanently deferred — a
register row that cannot be worked is noise. TD-23 is closed by work, not by
deletion: the revoke now stamps a check-in checkpoint the `lastSeenAt` throttle
honours.

**TD-32 is closed by `chore/prune-cand-tags`.** The promote now routes
`--to-latest` (refusing if the latest ready revision is not the candidate) and
removes every `cand-*` tag in the same `update-traffic` call, so tags no longer
pile up (one per revision per deploy) and a hand-run `gcloud run services
update` no longer creates a revision that serves 0 %.

**TD-31 is closed by #133.** The flaky emulator invite race was not asserting a
real guarantee: a round the emulator starves grants nothing, and with no grant
there is no grant for a loser to stamp a Demo key over. `_race_entitlement`
re-races such a round instead of failing on it — 32 consecutive runs, the retry
path firing in four of them. What that starvation costs an account outlives the
test and is now TD-33.

## External / deferred (not blocked on code alone)

| Item | Why deferred |
|------|----------------|
| Auth-gated UI E2E | Needs Firebase secrets / fixtures in CI |
| `ViewerArgs` read half | Now TD-3 / [ADR-003](../adr/ADR-003-viewerargs-read-side.md) |
| firebase-admin / hashed lock | Lock is regenerated from txt on each bump (`pip-compile --generate-hashes` on Python 3.12). Direct-dep versions in the lock must match `requirements.txt`. |
| Identity Platform upgrade | The consoles' second factor is Firebase MFA (TOTP), which needs the project upgraded to Identity Platform — a project-wide Auth change shared with the mobile app, and a change to the Auth pricing model. Until it is done the consoles sign in and every write fails `mfa_required`. |

## Perf / quality gates (do not loosen)

See [../engine/PERF_BASELINE_bd44af0.md](../engine/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s host; smoke/DICe floors; preserve `-O3 -ffast-math` / OpenMP / LTO
on the release pipeline. **No CI job in this repository enforces the solve-rate
floor** (that document says so at line 10): it is checked by hand in the engine
repo when the `native` pin moves. The Kover floor was report-only until TD-38;
`koverVerify` now runs in CI tier 1 and `ciReleaseGate`.

Macrobenchmark CI (`tier-benchmark`) is emulator **smoke**: it suppresses
`EMULATOR,LOW-BATTERY,UNLOCKED` and does not assert numeric thresholds. Keep API 34
(API 37 `dumpsys gfxinfo framestats` is empty). Dispatch with `run_benchmark` or
the `benchmark` label.

## User-facing consent copy (fixed 2026-08-24)

**Send crash reports** understated its scope: the same flag
(`DicSettings.diagnosticsEnabled`) gates `analytics/SemperAnalytics` as well as
Crashlytics. The toggle now reads **Send crash reports and usage data**, and its
subtitle and the first-run prompt name the usage events explicitly alongside what
is never sent. `docs/legal/PRIVACY_POLICY.md` §2.4 names the new label and the
hosted pages were regenerated. Nothing about *what* is collected changed — the
events were, and remain, PII-free buckets.

## 2026-08-12 result-viewer / report memory & latency program

Two rounds cutting per-point duplication and per-frame main-thread work in the viewer,
report and export paths. Every change was representation / allocation / threading /
resolution only — computed numbers are bit-identical (GIF bytes byte-identical), pinned
by 0-delta parity tests. Measured before/after, including the drawbacks, is in
[../perf/round2-main-vs-branch.md](../perf/round2-main-vs-branch.md).

**Fixed**

- Report/summary/heatmap collectors de-boxed onto reused primitive buffers; the GIF LZW
  string table is a flat `IntArray` instead of a per-pixel-boxing `HashMap`. Measured
  −99.6 % to −100 % allocations on those paths.
- `.dat` decode is memory-mapped (`decodeDatFile`); export sites no longer read the whole
  file into a second `ByteArray` first.
- Stats-strip and Max/Min extrema moved off the main thread and memoised per (frame, field).
- Viewer look-ahead is now byte-bounded and filled by a single serialized worker, and the
  whole-batch summary scan starts on demand — see the invariants in
  [../app/ARCHITECTURE.md](../app/ARCHITECTURE.md#memory--failure-invariants). Max heap
  during a 150-frame scrub went 165 MB → 24.5 MB and is now flat in workload size.

**Open / deliberately not done**

| Item | Why |
|---|---|
| `StartupTimingMetric` benchmarks on an API 37 emulator | `startActivityAndWait` confirms launches via `dumpsys gfxinfo framestats`, which returns empty there for every activity. `StartupBenchmark`/`ScreenBenchmark` need a physical device or an older image; `ViewerScrubBenchmark` avoids the API and runs |
| Float16 / ZNSSD quantisation for field data | Would change reported numbers; rejected under the bit-exactness requirement |
| In-memory X/Y compaction (derive coords from the grid) | Loss-less and worth ~25 %, but a larger change that also touches the native writer |

Do not split VisualizationEngine loops, GifEncoder LZW, ReportBuilder fusion,
`DicResult.decodeDatFile`, `DicUploadWorker.doWork`, `prefetchAround` /
`ScrubFrameCache`, or `PointSpatialIndex.build`.

## 2026-08-05 transparency / robustness audit

A whole-app + backend pass for silent failures, crashes, security and tech debt.
The branch was already strong; findings and fixes were small:

- **Crash guards (fixed).** The JNI full-field solve reads its direct output buffer
  back by the engine's returned point count. Added a bound: a count exceeding the
  ROI-grid buffer capacity is now treated as an engine failure instead of reading
  past the buffer (`DicFieldIo` / `VsgStudyRunner` once #70 lands; still inline on
  `main` until then). Closes the residual "JNI buffer" concern from the earlier
  Tier-0 list.
- **Fragile null-asserts (fixed).** `ShareCenter` replaced nine `snap!!` sites with
  a checked `requireSnapshot()` that fails the share job cleanly (snackbar) rather
  than NPE-crashing.
- **Silent restore failure (fixed).** A background restore that failed terminally
  was never surfaced; `SettingsActivity` now observes the `restore` work tag and
  shows the reason, mirroring the existing upload-failure surfacing on Home.
- **Verified clean (no change):** cleartext disabled, no local token storage, no
  hardcoded secrets, release signing wired to an env keystore, backend Drive query
  escaping + `parents` confused-deputy guard, and no PII in Crashlytics WARN/ERROR
  breadcrumbs (`CrashReportingTree`).

## History

Earlier burn-down (CI path filters, Hilt removal, engine Path A–C split, OkHttp 5,
FastAPI train, Analysis helpers, Kover floor 15→27, UseKtx/Plurals/Overdraw, capture
maintenance pass on PR #101, etc.) is in git history — do not re-open closed items
without new evidence.
