# Tech debt

Baselines stay empty: `app/lint-baseline.xml` and `app/detekt-baseline.xml`.
`./gradlew :app:lintDebug` fails on errors; `lintVitalRelease` is clean.

**Still open warnings** (do not baseline, do not `warningsAsErrors` until decided):

- `OldTargetApi` — `compileSdk` 37 vs `targetSdk` 36. Inventory only; do not bump
  `targetSdk` in a drive-by.
- `TooManyViews` on `activity_settings.xml`.

Inherent size/complexity in a few UI orchestration files uses targeted
`@file:Suppress` — prefer extracting over widening those lists.
Catalog version-availability lint IDs are disabled; bump deps in deliberate PRs.

`UnclosedTrace`, `PluralsCandidate`, and `UseKtx` from the 2026-08-16 pass are
fixed (#59 / #60). The architecture extracts that had missed `main` (#65 / #67,
re-landed as #69 / #70) are on `origin/main` as of 2026-08-16.

Orphaned strings the 2026-08-18 workflow audit found are listed in
[../app/WORKFLOWS.md](../app/WORKFLOWS.md) §11 — none of them fail a gate, so they
are removed opportunistically rather than in a sweep.

## Proposed improvements live next door

Forward-looking items — the ones that came out of the 2026-08-24 workflow
traceability pass, ranked by accuracy and privacy impact — are in
[FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md). This file stays the record of
what is *owed* and what is deliberately deferred. Two entries below have a
concrete proposal there: the `ViewerSession` extras bag (FI-1) and the consent
copy (FI-8).

## 2026-08-31 capture-branch review (PR #101)

Three parallel agents (reuse, quality, efficiency) reviewed
`origin/main...HEAD` (~136 files). Findings below were recorded **before** the
Phase 4 fix pass; rows marked **Fixed 2026-08-31** moved there after tests.

### Fixed on capture branch (shipped before this pass)

| Item | Evidence |
|------|----------|
| Auth fail-closed adoption + profile `create()` race | `firestore_repo.py`, `test_firestore_repo.py` |
| Auth deep-link host check | `AuthActivity.kt` |
| ROI mask / RAW copy off main thread | `StaticAnalysisActivity.kt` |
| Error-code contract pin (FI-9 partial) | `test_error_codes.py`, `ApiErrors.kt` |
| Capture JVM test suite | `app/src/test/.../capture/*`, `NoiseFloor*`, `AnalysisCsv*` |
| Docs: FAQ, lighting report, workflow index | `docs/app/FAQ.md`, `NOISE_FLOOR_STRAIN_ACCURACY.md`, `docs/WORKFLOWS.md` |
| Native engine pin | tag **v0.2.1** (`074602a`) |

### Fixed in maintenance pass (2026-08-31)

| ID | Item |
|----|------|
| TD-1 | FI-11: `UploadWorkOutcomes.isQuotaExhausted` checks `SESSION_QUOTA_EXCEEDED` in body |
| TD-2 | FI-11: `IndicApi.me()` branches on `DEVICE_IN_USE` / `DEVICE_CONFLICT`; added `DeviceInUseException` |
| TD-8 | Budget-fail dialog extracted to `CaptureBudgetUi` |
| TD-9 | Denoise threshold shared via `CaptureNoiseFloor.denoisedByCorrelation` |
| TD-10 | Millistrain conversion centralized in `AnalysisCsvWriter` |
| TD-11 | `captureFloor` cleared when reference swapped via import |
| TD-12 | `LockedCameraSession.close()` clears pending still/luma claims |
| TD-13 | `applySupportedResolution` no longer overwrites intent `cameraId` |
| TD-14 | JPEG bounds decode consolidated in `BitmapDecode.storedBounds` |

### Open register (prioritized)

Priority = (Impact + Risk) × (6 − Effort).

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-3 | Architecture | `ViewerSession` extras bag (FI-1) | 4 | 4 | 5 | **8** | Deferred |
| TD-4 | Code | Capture orchestrators ~1k lines | 3 | 2 | 4 | **10** | Deferred |
| TD-5 | Test | No capture instrumented/E2E | 3 | 3 | 4 | **12** | Deferred |
| TD-6 | Code | FI-11 `ApiException.detail` rename | 2 | 2 | 3 | **8** | Deferred |
| TD-7 | Test | Kover floor raise | 2 | 2 | 3 | **8** | Deferred |
| TD-15 | Quality | `CaptureSessionActivity` process death omits noise-floor / ready state | 3 | 3 | 4 | **9** | Deferred |
| TD-16 | Efficiency | Test-shot path re-reads/re-decodes same JPEG | 3 | 2 | 3 | **12** | Deferred |
| TD-17 | Efficiency | `ShareCenter` reference decoded 5× per field in ZIP export | 3 | 2 | 4 | **9** | Deferred |
| TD-18 | Efficiency | `GrayPngEncoder` full-buffer + `toByteArray()` on hot path | 3 | 2 | 4 | **9** | Deferred |
| TD-19 | Efficiency | Redundant `runOnUiThread` in `StillSequenceRunner` progress | 2 | 1 | 1 | **10** | Deferred |
| TD-20 | Reuse | `LockedCameraSession` duplicate `captureStill` / `captureLuma` bodies | 2 | 2 | 4 | **8** | Deferred |
| TD-21 | Efficiency | ImageReader listener re-registered every capture | 2 | 2 | 3 | **8** | Deferred |
| TD-22 | Reuse | `NoiseFloorGateUi` manual `CaptureNoiseFloor` field mapping | 2 | 1 | 3 | **6** | Deferred |

### Review summary (2026-08-31)

- **Reuse agent:** 6 actionable findings (2 fixed, 4 deferred).
- **Quality agent:** 7 actionable findings (4 fixed, 3 deferred).
- **Efficiency agent:** 8 actionable findings (2 fixed, 6 deferred).
- **False positives skipped:** splitting orchestrators without a concrete bug;
  `@file:Suppress` on capture activities without a proposed extract; streaming
  PNG encode (larger refactor).

## External / deferred (not blocked on code alone)

| Item | Why deferred |
|------|----------------|
| Auth-gated UI E2E | Needs Firebase secrets / fixtures in CI |
| Kover `minBound` raise | Floor is 15; measure stable % on CI first (local AGP 9 often reports no coverage) |
| `ViewerSession` extras bag | `DicKeys` packed in two places (`SessionOpenHelper.intentFor`, `AnalysisNavHelper.openResults`); grill before deepening |
| firebase-admin / hashed lock | Lock is regenerated from txt on each bump (`pip-compile --generate-hashes` on Python 3.12). Direct-dep versions in the lock must match `requirements.txt`. |

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
FastAPI train, Analysis helpers, Kover floor 15, UseKtx/Plurals/Overdraw, etc.)
is in git history — do not re-open closed items without new evidence.
