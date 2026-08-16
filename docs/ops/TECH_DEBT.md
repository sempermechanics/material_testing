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
fixed (#59 / #60).

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

## Architecture extracts that missed `main`

GitHub marked #65 (wizard slot chrome / coach) and #67 (`DicBatchRunner` /
`DicFieldIo`) MERGED, but they targeted already-merged stack branches, so those
files were not on `origin/main`. Cherry-picks targeting `main` are #69 and #70;
compile/quality leftovers from the lint split are #68.

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
