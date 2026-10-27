# Readiness baseline — 2026-10-24

Phase 0 of the readiness run ([INVENTORY.md](INVENTORY.md)). Every existing gate command,
run unchanged on fresh worktrees: **S** = `semperdic-app` `875e2334` (engine `f072da0`),
**M** = `material_testing` `839d4285`. Failures here are findings, not blockers. Full
logs were kept in the session scratchpad (`baseline/{gradle,backend,console,posture}/`);
the excerpts below are what the gate printed.

Host: Windows 11, JDK Temurin 21.0.11, Python 3.13.11, Node 24.18.1. CI is ubuntu-24.04,
Python 3.12, Node 22, emulator API 34; each deviation is noted where it mattered.

## Result

| Gate | Command | S | M |
|---|---|---|---|
| Release gate | `gradlew ciReleaseGate` | **PASS** (495 s) | **PASS** (535 s) |
| Unit tests (in the gate) | `:app:testDebugUnitTest` | 2729 run, 0 failed, 5 skipped | 3010 run, 0 failed, 5 skipped |
| `.dat` / GIF oracles | `DatFieldOracleTest`, `SummaryGifOracleTest` | 1 + 2 passed | 1 + 2 passed |
| Lint (in the gate) | `:app:lintDebug` | pass; 3 warnings (IconXmlAndPng, RtlSymmetry, UseKtx) | pass; 5 warnings (+ InflateParams, PluralsCandidate) |
| Kover | `:app:koverLog :app:koverVerify` | **PASS**: lines 80.53 % (floor 78), branches 62.79 % (60) | **PASS**: lines 76.94 % (71), branches 61.46 % (57) |
| androidTest compile | `:app:compileDebugAndroidTestKotlin` | PASS | PASS |
| Release APK check | `scripts/check_release_apk.py <unsigned apk> --aapt2 … --mapping …` | **FAIL on default Windows Python** (`UnicodeDecodeError: 'charmap'`, then `AttributeError … splitlines` at `check_release_apk.py:183`); **PASS** with `PYTHONUTF8=1` (CI's locale); `cert-pins` WARN (no pin in a local build) | same |
| Instrumented | `:app:connectedDebugAndroidTest` (Semper_API30, API 30) | **FAIL**: 59 run, 54 passed, 5 failed | **FAIL**: 68 run, 63 passed, 5 failed |
| Backend tests | `pytest tests/ -q --cov=app --cov-fail-under=93` | **PASS**: 2051 passed, 59 skipped, 96.71 % | **FAIL**: 2049 passed, 1 failed, 59 skipped, 96.71 % |
| Backend emulator tier | `pytest tests/test_firestore_emulator_integration.py … --cov-append --cov-fail-under=93` | PASS: 28 passed, 96.76 % | PASS: 28 passed, 96.72 % |
| ruff | `ruff check app/ tests/ scripts/ ../scripts/` (0.16.9) | PASS, 0 findings | PASS, 0 findings |
| pip-audit | `pip-audit -r requirements.txt --strict` | PASS, no vulnerabilities | PASS |
| Hashed lock | `pip install --require-hashes -r requirements.lock` | FAIL on Windows only: `uvloop does not support Windows`; the rest of the lock installed with hashes, all 50 pins matching | same |
| Lock coverage, `bash -n` | ci.yml steps | PASS | PASS |
| shellcheck | ci.yml step | NOT RUN (not installed) | NOT RUN |
| Firestore rules | `npm ci`; `firebase emulators:exec --only firestore "node --test"` | PASS: 61/61 | PASS: 61/61 |
| Legal pages | `render_legal_pages.py --check` | PASS | PASS |
| Repo checks | `check_doc_paths`, `check_framework_coverage`, `check_kotlin_file_size`, `check_activity_scope_work`, `check_swallowed_cancellation`, `unittest discover scripts/tests` | PASS (52 tests) | PASS (52 tests) |
| Console | `check_console.py`; `node --test firebase-hosting/tests/*.test.mjs`; `console_coverage.mjs --lines=96` | **PASS**: 403/403, 98.64 % | **PASS**: 402/402, 98.63 % |
| Secrets | `gitleaks detect --config .gitleaks.toml` | NOT RUN (not installed; green in CI) | NOT RUN |
| GCP posture | `scripts/check_gcp_posture.sh --app-project indicvision-dic-app --auth-project indicvision-dic-app-auth --api-sa indic-api@… --backup-bucket indicvision-dic-app-firestore-backups` | NOT RUN: refused by this session's permission classifier before any call; the script was read and every call in it is read-only. Owed to the Admin | — |

## Failures, in detail

1. **Instrumented, S and M — `VideoFrameExtractionDeviceTest`, 4 tests**
   (`keyframeModeExtractsEachIFrame`, `keyframeModeOnAnIndexedH264AviExtractsEachIFrame`,
   `keyframeModeOnTheRetrieverRungExtractsEachIFrame`, `portraitClipComesOutUpright`):
   `expected:<[0, 30, 60]> but was:<[0, 30, 60, 89]>`, or every 6th frame. API 30 is
   minSdk; CI runs API 34. To assess in TE-1.
2. **Instrumented, S — `StagingRoundTripDeviceTest`**: ran although excluded (see
   "Incident" below); `DeviceConflictException: device_conflict` (409) at `registerDevice`.
3. **Instrumented, M — `LabWorkflowDeviceTest.theViewerScrubsZoomsPansAndProbes`**:
   "pinch-close went below the rest scale".
4. **Backend, M — `tests/test_audit_append_only.py::test_no_module_but_the_audit_repo_names_the_collection`**:
   `assert ['repo\\audit_log.py'] == ['repo/audit_log.py']`. Windows-only; S already uses
   `.as_posix()` (line 47), M has not synced it.
5. **`check_release_apk.py` on Windows**: reads aapt2 output with the platform codec;
   passes under UTF-8 mode, as on CI.
6. **Hashed lock on Windows**: `uvloop==0.22.1` has no platform marker and cannot build
   on Windows; Linux CI is unaffected.

Skips worth knowing: 26 backend tests skip on Windows only (24 in `test_script_ops_gcp.py`
need bash, 2 in `test_script_check_release_apk.py` use a shebang fake aapt2); they run in CI.

## False passes seen while running the baseline

- An AVD holding `com.sempermechanics.semper` at versionCode 101 made the install fail with
  `INSTALL_FAILED_VERSION_DOWNGRADE`; AGP then uninstalled the app (its data on the
  Semper_API30 AVD is gone) and reported success with **0 tests**.
- Blank credential overrides made AGP drop the arguments: `No instrumentation found`,
  **0 tests**, BUILD SUCCESSFUL.

Both were re-run; the counts above are from the re-runs.

## Incident: one instrumented test reached live staging

The baseline was meant to exclude every test that signs in to live services, because
staging shares the production Firestore (TD-7/TD-36). Through AGP only the **first**
entry of a comma-separated `notClass` argument takes effect (confirmed with `log=true`
dry runs), so in S `StagingRoundTripDeviceTest` ran with the local test account that
`~/.gradle/gradle.properties` supplies. It signed in to Firebase Auth, called
`GET /v1/config` on staging, and was refused `device_conflict` at `POST /v1/devices/register`,
whose refusal is raised before any write in its transaction
(`backend/app/repo/devices.py:204-206`). No session was created, uploaded or deleted. M's
run used a `notTestFile` exclusion list, verified by dry run, and reached nothing. The 409
itself matches the known stale-staging symptom (staging serves
`semper-api-staging-39948110403-1`, 2026-10-22, behind `main`).

## Not available to this session

No physical device; no Play Console; no gitleaks or shellcheck; no Python 3.12; no
signed-in console session (signing in to staging touches production Firestore); the
posture run needs the Admin.
