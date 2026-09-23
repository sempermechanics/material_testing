# Tech debt

Baselines stay empty: `app/lint-baseline.xml` and `app/detekt-baseline.xml`.
`./gradlew :app:lintDebug` fails on errors; `lintVitalRelease` is clean.

**Still open warnings** (do not baseline, do not `warningsAsErrors` until decided):

- `OldTargetApi` — disabled in `app/build.gradle.kts` lint config until a
  deliberate `targetSdk` 36→37 bump PR. Do not re-enable casually.

Inherent size/complexity in a few UI orchestration files uses targeted
`@file:Suppress` — prefer extracting over widening those lists.
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

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-3 | Architecture | `ViewerSession` extras bag (FI-1) — **write half done**: `ViewerArgs` is the one packer and `ViewerArgsTest` pins both entry points' key sets. What is left is the unpack half, across four readers | 3 | 2 | 4 | **10** | Deferred — a parsed-object read side has to keep working for an Intent already in the back stack across an update |
| TD-22 | Test | Consoles have no behavioural test — `check_console.py` reads their structure, nothing exercises a sign-in, a step-up or a revoke | 3 | 3 | 4 | **12** | Deferred — same Firebase Auth fixture blocker as auth-gated UI E2E |
| TD-24 | Architecture | No `@MainThread` on UI entry points, so `SessionStore`'s `@WorkerThread` contract is documentation rather than a gate — lint's `WrongThread` fires only when the *calling* method is annotated | 2 | 2 | 3 | **12** | Deferred — annotating ~27 Activities needs a lint run to land against an empty baseline |
| TD-25 | Test | `AuthRepository` cannot be unit-tested against a fake backend: `IndicApi` is final with a private constructor, so a defaulted constructor parameter would be a seam that admits only the real client | 2 | 2 | 2 | **16** | Deferred — needs an interface extracted from `IndicApi` and threaded through every worker and repository; that is the DI proposal CONTRIBUTING defers to its own PR |
| TD-26 | Architecture | Wizard/viewer UI state lives on the Activity as fields rather than hoisted into `AnalysisViewModel` as `StateFlow`, so a rotation reconstructs it from intent extras and `onSaveInstanceState` | 3 | 2 | 5 | **5** | Deferred — a 1.8k-line native-solve screen with bit-exact `.dat` oracles; the run itself is on `viewModelScope`, which is the part that was losing work |
| TD-27 | Ops | The API Gateway is never deployed by CI: `test_gateway_parity.py` proves `gateway/openapi.yaml` matches the routers, but moving the live gateway to a new config is a by-hand `api-configs create` + `gateways update` ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) "Redeploying the gateway"). A route can be merged, tested and deployed to Cloud Run while the gateway still 404s it | 3 | 3 | 3 | **18** | Deferred — a workflow job needs `apigateway.*` on the WIF principal and an ordering guarantee against the Cloud Run promote; the runbook covers the licensing rollout |
| TD-28 | Backend | A per-user `maxSessions` override (`PATCH /v1/admin/users/{uid}/config`) is ignored while the account is demo: `resolve_user_config` takes `DEMO_MAX_ANALYSES` for every unlicensed user, so an operator cannot lift one demo account's cap | 2 | 2 | 2 | **16** | Deferred — decide whether the override should win in demo or whether "lift the cap" means "attach a licence"; today the docs say the latter |
| TD-29 | App | Two auth continue hosts: `app.sempermechanics.com/auth/*` (current `AUTH_HOST`) and `indicvision-dic-app-auth.firebaseapp.com/*` (`LEGACY_AUTH_HOST`). The manifest carries four App Link filters, `AUTH_HOSTS` accepts both, the Hosting site rewrites both path shapes, and the Firebase Console password-reset action URL still names the legacy host because pre-`/auth` builds intercept only it | 1 | 2 | 2 | **8** | Deferred until no build declaring only the legacy host is installed (Play vitals). Then: action URL → `https://app.sempermechanics.com/auth/finishReset`, drop the two legacy filters and `LEGACY_AUTH_HOST`, drop the bare rewrites in `firebase.json` |
| TD-30 | Ops | The API Gateway (`semper-gw`, asia-northeast1) sits in a different region from Cloud Run (`semper-api`, asia-south1) because API Gateway is not offered in asia-south1: every request takes a Tokyo→Mumbai hop, and the legacy `indic-gw` / `indic-api` pair from the first deploy is still provisioned | 2 | 1 | 3 | **9** | Deferred — a regional external HTTPS load balancer with a serverless NEG in asia-south1 would replace the gateway (JWT check moves to Cloud Run / IAP); measure the hop first. Delete the legacy pair as soon as nothing resolves its host |
| TD-33 | Backend | A contended invite claim that starves out completely leaves the account on the Demo key it then mints, and `claim_pending_invite` returns early for anyone already holding a licence — so the invite stays pending until ops mints for the address again (which attaches directly) | 2 | 1 | 3 | **9** | Open — either re-check invites for an account whose only licence is a `createdByUid: system` Demo key, or have the Demo mint skip an address that still has an invite. It needs every request in one burst to exhaust the client's five ABORTED retries: seen against the emulator (#133), never in production |
| TD-34 | Console | The operator desk still carries the pre-#129 enrolment card (`enrolCard`, `enrolSecret`, and its two handlers): `requireSignIn` → `ensureDashboardMfa` enrols in-page with a QR before any desk code runs, so `hasSecondFactor(user)` is always true by the time `renderFactorState` reads it and the card never shows | 1 | 1 | 2 | **2** | Open — delete the card from `operator/index.html` and `operator.js`; keep `renderFactorState` only for displaying which factor is enrolled |

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
| `ViewerSession` unpack half | `DicKeys` is now packed in one place (`ViewerArgs`); the four readers still parse the bundle themselves |
| firebase-admin / hashed lock | Lock is regenerated from txt on each bump (`pip-compile --generate-hashes` on Python 3.12). Direct-dep versions in the lock must match `requirements.txt`. |
| Identity Platform upgrade | The consoles' second factor is Firebase MFA (TOTP), which needs the project upgraded to Identity Platform — a project-wide Auth change shared with the mobile app, and a change to the Auth pricing model. Until it is done the consoles sign in and every write fails `mfa_required`. |

## Perf / quality gates (do not loosen)

See [../engine/PERF_BASELINE_bd44af0.md](../engine/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s host; smoke/DICe floors; preserve `-O3 -ffast-math` / OpenMP / LTO
on the release pipeline.

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
