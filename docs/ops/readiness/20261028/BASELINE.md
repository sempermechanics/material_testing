# Readiness baseline — 2026-10-28

Phase 0 of the second readiness run ([INVENTORY.md](INVENTORY.md)). Every existing gate
command, run unchanged on a fresh worktree of `semperdic-app` `046d54c5` (engine `f072da0`,
v0.6.1). The fork `material_testing` was not run: whether it is in scope is the Admin's
first question. Failures here are findings, not blockers. Full logs are in the session
scratchpad (`baseline/{gradle,backend,console,repo}/`); the excerpts below are what the
gates printed. The first run's baseline is [20261024/BASELINE.md](../20261024/BASELINE.md).

Host: Windows 11, JDK Temurin 21.0.11, Python **3.12.10** (CI's runtime; the first run
used 3.13), Node 24.18.1. CI is ubuntu-24.04, Python 3.12, Node 22, emulator API 34; each
deviation is noted where it mattered.

## Result

| Gate | Command | Result |
|---|---|---|
| Release gate | `gradlew ciReleaseGate` | **PASS** (652 s, cold native build) |
| Unit tests (in the gate) | `:app:testDebugUnitTest` | 2852 run, 0 failed, 5 skipped (2729 on 2026-10-24) |
| `.dat` / GIF oracles | `DatFieldOracleTest`, `SummaryGifOracleTest` | 1 + 2 passed |
| Lint (in the gate) | `:app:lintDebug` | pass; 0 errors, 7 warnings (IconXmlAndPng, NotShrinkingResources, PluralsCandidate ×2, RtlSymmetry, UseKtx ×2) |
| Kover | `:app:koverLog :app:koverVerify` (+ `koverXmlReport`) | **PASS**: lines 82.24 % (floor 78), branches 63.45 % (floor 60) |
| Compiles | `:app:compileDebugAndroidTestKotlin :benchmark:compileBenchmarkKotlin :app:compileReleaseKotlin` | PASS |
| Release APK check | `scripts/check_release_apk.py app-release-unsigned.apk --aapt2 <build-tools 36.1.0> --mapping …` | **PASS** on default Windows Python (the first run's codec failure is fixed). `cert-pins` WARN, `entitlement-pins` WARN (empty at a local build, TD-10), `signing-key` SKIP (unsigned) |
| Instrumented | `:app:connectedDebugAndroidTest` on `Semper_API30` (API 30), live-service classes excluded (below) | **FAIL**: 58 run, 54 passed, 4 failed, all in `VideoFrameExtractionDeviceTest` (open as TD-258) |
| Backend tests | `pytest tests/ -q --cov=app --cov-fail-under=93` | **FAIL**: 2297 passed, **1 failed**, 86 skipped; coverage 96.87 % |
| Backend emulator tier | `firebase-tools@15.31.0 emulators:exec … pytest tests/test_firestore_emulator_integration.py --cov-append --cov-fail-under=93` | PASS: 28 passed, 96.88 % |
| ruff | `ruff check app/ tests/ scripts/ ../scripts/` (0.16.9) | PASS, 0 findings |
| pip-audit | `pip-audit -r requirements.lock --require-hashes --disable-pip --strict` (2.10.1) | **FAIL**: `msgpack 1.2.1  CVE-2026-114613  fix 1.2.3` |
| Hashed lock | `pip install --require-hashes -r requirements.lock` | FAIL on Windows only: `uvloop does not support Windows` (as on 2026-10-24) |
| Lock coverage | ci.yml inline check | PASS: "lock covers all 9 direct dependencies at matching versions" |
| `bash -n` | ops scripts | PASS |
| shellcheck | ci.yml step | NOT RUN (not installed); green in CI |
| Firestore rules | `npm ci`; `firebase emulators:exec --only firestore "node --test"` | PASS: 61/61 |
| Legal pages | `render_legal_pages.py --check` | PASS |
| Engine pin | `check_engine_pin.py` | PASS: `f072da030b43 (v0.6.1)` is a tagged engine release; unchanged |
| Repo checks | `check_doc_paths`, `check_framework_coverage`, `check_kotlin_file_size`, `check_activity_scope_work`, `check_swallowed_cancellation`, `unittest discover scripts/tests` | PASS |
| Console | `check_console.py`; `node --test firebase-hosting/tests/*.test.mjs` with `NODE_V8_COVERAGE`; `console_coverage.mjs --lines=96` | **PASS**: 465/465, 98.76 % lines |
| Console a11y | `npm ci`; `npx playwright install chromium`; `npm run test:a11y` | **PASS**: 20/20 (axe at 360 / 768 / 1280 px) |
| Engine host ASan + UBSan | `engine-host-sanitized` job | NOT RUN (Linux + libopencv); green in CI on `main` `e6fe6086` and `facd3ae2` (2026-10-27) |
| Secrets | `gitleaks detect --config .gitleaks.toml` | NOT RUN (not installed); green in CI on `046d54c5` (run 40491151096) |
| GCP posture | `scripts/check_gcp_posture.sh --app-project indicvision-dic-app --auth-project indicvision-dic-app-auth --api-sa indic-api@… --backup-bucket indicvision-dic-app-firestore-backups` (read-only; run after the Admin re-logged gcloud) | **FAIL 17**, UNKNOWN 0: the same items, statuses and scopes as 2026-10-24 (lien, tag, owners, groups on both projects; unrestricted API keys; `APP_CHECK_MODE=off` and no `ENTITLEMENT_SIGNING_KEY` on both services; delete protection off; Data Access audit logs) |

CI on `main`: run 40491151096 (`046d54c5`, 2026-10-27) is green; Tier 3, Tier 5, the
sanitizer job and benchmarks were skipped by the path filter there. Their last runs on
`main` were green at `facd3ae2` (2026-10-27, after #489).

## Failures, in detail

1. **pip-audit — `msgpack 1.2.1`, CVE-2026-114613, fixed in 1.2.3.** Transitive, through
   `CacheControl` (`backend/requirements.lock:533`). Tier 4 passed on `046d54c5` on
   2026-10-27, so the advisory is newer than that run; the next backend change, or the
   weekly full run, fails Tier 4 here. The fix is a lock regeneration with
   `pip-compile --generate-hashes` on Python 3.12 (CLAUDE.md guardrail). To assess in TE-5.
2. **Backend — `tests/test_seat_reconciliation.py::test_the_holders_next_request_confirms_the_revoke_despite_the_throttle`**:
   `assert ('revokedStillRunning', 'no_checkin_since_revoke') == ('revokedConfirmed', 'checked_in')`.
   Fails 3 of 3 times alone too. Cause: on Windows, Python 3.12's wall clock is
   `GetSystemTimeAsFileTime()`, resolution 15.6 ms (3.13 uses the precise clock, 1e-7 s;
   the first run used 3.13 and passed). `backend/tests/fake_firestore.py:96,172` resolves
   `SERVER_TIMESTAMP` to `datetime.now()`, so the revoke's checkpoint and the next
   request's `lastSeenAt` get the same instant, and `_seen_since` asks
   `last_seen > revoked_at` (`backend/app/repo/reconcile.py`). Linux CI and real Firestore give
   distinct instants. A test-fixture defect (the fake clock can collide), not a product
   one. To assess in TE-3.
3. **Instrumented, API 30 — `VideoFrameExtractionDeviceTest`, 4 tests**, the same four as
   on 2026-10-24 and still open as TD-258 (`docs/ops/TECH_DEBT.md:539`):
   `keyframeModeExtractsEachIFrame` and `portraitClipComesOutUpright`
   (`expected:<[0, 30, 60]> but was:<[0, 30, 60, 89]>`);
   `keyframeModeOnAnIndexedH264AviExtractsEachIFrame` and
   `keyframeModeOnTheRetrieverRungExtractsEachIFrame` (every 6th frame, 0…84, 89). API 30 is
   minSdk; Tier 3 runs API 34 only and was green on `facd3ae2`.
4. **Hashed lock on Windows**: `uvloop==0.22.1` has no platform marker; Linux unaffected.
   Unchanged since the first run.

## How the instrumented run was kept off live services

Staging shares the production Firestore (TD-7), and `~/.gradle/gradle.properties`
supplies the local test account and a staging URL to every instrumented run. The first
run's `notClass` list let `StagingRoundTripDeviceTest` through (only the first comma entry
takes effect). This run:

- excluded `auth.FirebaseAuthIntegrationTest` and `data.cloud.StagingRoundTripDeviceTest`
  with `notTestFile=/data/local/tmp/live_excludes.txt`, plus `notPackage=…benchmark`;
- overrode `STAGING_API_BASE_URL` with `http://127.0.0.1:9` as a second guard;
- proved the filter first with a `log=true` dry run: 58 tests in 16 classes, neither
  excluded class nor the benchmark package among them. The real run executed the same 58.

The `Pixel_5` AVD was booted first and then left untouched: it holds an installed build at
versionCode 101 with session data, and a debug build (versionCode 1) would hit the
downgrade path that made AGP uninstall the app on 2026-10-24. The emulator already running
(`emulator-5554`, `Pixel_10_2`) was not used.

Skips worth knowing: the backend's Windows-only skips (bash- and shebang-dependent script
tests) run in CI.

## Kover floors are below the ratchet

Measured lines 82.24 % / branches 63.45 % against floors 78 / 60
(`app/build.gradle.kts:525,528`). The repository rule is floors at measured − 2
(CLAUDE.md, TESTING.md), which would be 80 / 61. To assess in TE-1.

## Not available to this session

- Python 3.12 is installed now; gitleaks, shellcheck and a Linux toolchain are not.
- One physical device is attached (Galaxy S22+, `g0q`) and was **not used**: it is the
  benchmark phone, and no run here needs it yet.
- No Play Console; no signed-in console session (signing in to staging touches the
  production Firestore, TD-7).
- The posture run needed an interactive `gcloud auth login`, which the Admin did.
