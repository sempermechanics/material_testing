# App test suite — workflow chunks

Tests are organized into chunks that mirror the user journey through the app.
Each chunk owns one layer; no duplicate assertions across chunks. Each workflow
in [../WORKFLOWS.md](../WORKFLOWS.md) names the tests that pin it, so the two
files answer opposite questions: "what covers this flow?" there, "what does this
chunk own?" here.

## Chunk map

| Chunk | User journey | JVM tests (`app/src/test`) | Instrumented (`androidTest`) |
|-------|--------------|---------------------------|------------------------------|
| **auth** | Splash → Auth / Pending / Home, re-auth, password rules | `auth/AccessRouterTest`, `ReauthFlowTest`, `PasswordPolicyTest` | `auth/FirebaseAuthIntegrationTest` |
| **analysis** | Import → ROI → batch / parameter sweep; speckle and noise-floor suitability; the wizard across a process death | `analysis/AnalysisViewModelTest`, `WizardStateTest`, `VsgStudyTest`, `SubsetRecommenderTest`, `ConvergenceGateTest`, `DicGoodPracticeTest`, `SpeckleScaleTest`, `NoiseFloorProbeTest`, `NoiseFloorStatsTest`, `NoiseCorrelationTest`, `FrameOrderHelperTest`, `BitmapDecodeTest`, `ExifOrientedSizeTest`, `LossyFormatCheckTest`, `RawRgbaTest`, `SweepSetupHelperTest`, `DicBatchRunnerLimitTest`, `ui/common/MediaPickerSheetTest`, `ui/analysis/StudioOverlayViewTest`, `RoiDrawActivityTest`, `VsgLatticeViewTest`, `VsgPlotViewTest` | `ui/analysis/WizardDraftRestoreTest` (incl. a bending wizard's load log, taps and frame times) |
| **session** | Session store durability, disk footprint, failure provenance; the Home list and its multi-select | `session/SessionStoreAtomicTest`, `LocalStorageFootprintTest`, `FailureProvenanceTest`, `ui/home/SessionListAdapterTest`, `SessionSelectionControllerTest` | — |
| **results** | `.dat` decode, CSV, heatmap, PDF, GIF | `results/DicResultCsvTest`, `AnalysisCsvSectionsTest`, `DicResultDecodeTest`, `VisualizationEngineTest`, `ReportBuilderTest`, `ReportBuilderMeanStdParityTest`, `GifEncoderTest`, `SummaryAnimationTest`, `PdfReportGeneratorTest` | `report/PdfReportDeviceTest` |
| **viewer** | Result viewer controls, frame cache bounds, the Intent contract both entry points write and all four readers parse | `viewer/FrameNumberEntryTest`, `ScrubFrameCacheTest`, `ViewerFieldPillsTest`, `ShareCenterTest`, `ui/viewer/ViewerArgsTest`, `TouchImageViewTest`, `ViewerSettingsSheetTest`, `analysis/RunSpecTest`, `viewer/TouchImageViewScrubTest` | `ui/viewer/ViewerEntryParityDeviceTest` |
| **cloud** | Upload, API, restore, quota, account deletion | `cloud/ApiDtosContractTest`, `ApiErrorMappingTest`, `UploadResumableTest`, `DicUploadWorkerOutcomesTest`, `RestoreAndImportSafetyTest`, `QuotaGateTest`, `AccountDeletionTest`, `SessionEverythingExporterTest`, `data/SessionUploadBundlerTest` | `data/SessionUploadBundlerDeviceTest` |
| **settings** | Settings sections, contacting support, account deletion | `settings/AnalysisEntriesTest`, `HelpSupportSectionTest`, `DeleteAccountReauthTest`, `DicSettingsMigrateTest` | — |
| **analytics** | Consent-gated Firebase Analytics events | `analytics/SemperAnalyticsTest` | — |
| **upgrade** | Prefs / session index forward compatibility | (covered in settings + session) | `upgrade/PrefsUpgradeSmokeTest` |
| **e2e** | Wizard chrome smoke (Next + toolbar; Back / Compute / instruction GONE on step 1) | — | `AnalysisWizardSmokeTest` |
| **lab** | Tensile and bending sessions: E from the phone's own `.dat` files, the viewer opening on Results, the Elastic region toggle, the lab-report PDF; viewer gestures (flick and slow swipe scrub one frame, pinch, pan, tap to probe); the beam-edge tap editor under real touches (bottom mark held to the top's x, zoom kept between taps) | `report/*` (`ElasticModulusTest`, `BeamDeflectionTest`, `LabReportTest`, …) | `e2e/LabWorkflowDeviceTest`, `e2e/BeamTapEditorGestureTest` |
| **pipeline** | JNI + native runtime | — | `pipeline/EnginePipelineSmokeTest` |
| **benchmark** | Startup / screen / viewer-scrub / lab Results Macrobenchmarks; hot-path microbenchmarks | — | `:app` androidTest `benchmark/HotPathMicroBenchmark`, `:benchmark` module (both in CI's `tier-benchmark`: label `benchmark` / workflow_dispatch) |

## Overlap rules

- **Host C++ tests** own algorithmic displacement accuracy.
- **Android JNI smoke** (`pipeline/`) owns runtime/bridge correctness —
  `System.loadLibrary`, OpenMP threading, JNI marshalling.
- **JVM tests** own Kotlin orchestration and data contracts. Do not add JVM
  tests that re-assert displacement accuracy.

## Running by chunk

```bash
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.auth.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.session.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.analysis.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.results.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.cloud.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.settings.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.viewer.*"
```

`settings/HelpSupportSectionTest` drives the real `SettingsActivity` under
Robolectric — it is the first UI-level test of that screen, and the pattern to
copy for the other sections. `viewer/FrameNumberEntryTest` does the same for
`ResultViewerActivity`, writing synthetic `.dat` frames to a temp folder and
handing their path in on the intent.

`results/GifEncoderTest` reads its own output back with `javax.imageio` rather
than a decoder of ours: the encoder is written against the GIF89a spec by hand,
so the only claim worth making is that a third-party decoder agrees.

`analysis/WizardStateTest` covers the wizard's process-death restore on the
JVM, `ui/analysis/WizardDraftRestoreTest` covers it through a real Parcel on a
device, and neither can kill the process. The kill is a scripted pass: take
the wizard to step 2, press Home, run `adb shell am kill com.indicvision.semper`
(if `pidof` still shows the process, `adb shell run-as com.indicvision.semper
kill -9 <pid>`), then reopen from Recents. Step, sliders, ROI and both slots
must come back. Run it once more with `run-as … rm -rf cache/temp_deformed`
before reopening: expect an empty step 1 and the "cleared while Semper was in
the background" snackbar ([ADR-005](../adr/ADR-005-wizard-process-death.md)).

`session/LocalStorageFootprintTest` pins the rule that only cloud-backed
analyses may have their local frames freed — it is the guard against a storage
optimisation quietly deleting the one copy of someone's data.
`cloud/RestoreAndImportSafetyTest` and `DicUploadWorkerOutcomesTest` cover the
other half: a cancelled import and a terminally failed upload must both leave
the session store in a state you can come back to.

## Performance benchmarks

Two suites, answering different questions. Neither runs in the normal gate — both
need a device, and they are gated in CI behind the `benchmark` label.

**Micro (`:app` androidTest) — "did this operation get cheaper?"**
Measures median `timeNs` **and `allocationCount`** for the hot paths (`valueRanges`,
`buildReport`, `generateHeatmap`, GIF encode, `computeFieldExtrema`, `decodeDatFile`,
the spatial index). `allocationCount` is the honest memory signal: the workload is
fixed, so a change in allocations is caused by the code and nothing else.

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.indicvision.semper.benchmark.HotPathMicroBenchmark \
  -e androidx.benchmark.suppressErrors EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED \
  com.indicvision.semper.test/androidx.test.runner.AndroidJUnitRunner
```

Emulators on API 34 and 37 both raise `ACTIVITY-MISSING` and `NOT-AOT-COMPILED`
besides `DEBUGGABLE`; leave one out and every case fails at once. Pass the list with
`am instrument`, not `connectedDebugAndroidTest -P …suppressErrors=…`: through Gradle
it arrives cut at its first comma, so only `EMULATOR` is suppressed (seen on Linux CI,
TD-86). CI runs this command.

Results land in logcat (`adb logcat -d -s Benchmark:I`).

**Macro (`:benchmark`) — "what does the user feel?"**
`ViewerScrubBenchmark` seeds a synthetic session via the benchmark-variant-only
`BenchmarkSeedActivity` and scrubs frames, reporting frame timing, max heap and the
`Semper.viewer.decodeDat` trace section. `LabResultsBenchmark` has the same seeder
fabricate a tensile session with a load per frame (`--ez results true`, 30 and 150
frames), opens it on Results and toggles Whole test / Elastic region, reporting frame
timing and the `Semper.viewer.stressStrain` trace section (the curve build over every
frame's `.dat`).

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED
```

CI's `tier-benchmark` job passes the same `suppressErrors` (plus
`enabledRules=Macrobenchmark`) on an **API 34** emulator;
`benchmark/build.gradle.kts` sets the same suppress list so a local emulator run
matches CI. On the same emulator the job then runs the micro suite with the
`am instrument` command above. Numbers are smoke, not
a regression gate; both suites' `*-benchmarkData.json` are uploaded, as
`macrobenchmark-results` and `microbenchmark-results`.

Four things that will otherwise cost you an afternoon:

- **The shell cannot start a non-exported Activity** (API 34+). Macrobenchmark launches
  through the shell, so anything it drives must be exported — `app/src/benchmark/AndroidManifest.xml`
  exports the needed screens for the `benchmark` variant only, never for a shipped build.
- **`startActivityAndWait` may not work on an API 37 emulator.** It confirms a launch by
  parsing `dumpsys gfxinfo <pkg> framestats`, which came back empty for *every* activity
  on an earlier API 37 image, so `StartupBenchmark`/`ScreenBenchmark` failed with "Unable
  to confirm activity launch completion []". The Pixel_10_2 API 37 image runs them
  (2026-09-25), and so does a Pixel 6 on Android 17; if yours does not, use a physical
  device or an older image.
  `ViewerScrubBenchmark` deliberately avoids that API.
- **A seeded session needs its `field_ranges.bin`.** The viewer fixes the summary colour
  scale on open; without the sidecar that pass decodes every frame, and at 150 frames its
  garbage (~155 MB) is what `scrub150Frames` reported as max heap (TD-87).
  `BenchmarkSeedActivity` writes the sidecar, and reseeds a cached session that lacks one.

- **The connected task installs over whatever is on the phone, then uninstalls it.**
  `:benchmark:connectedBenchmarkAndroidTest` installs the `benchmark` build over an
  existing `com.indicvision.semper` (same debug key), keeping its data, and uninstalls
  the app when it finishes, taking that data with it. Back up anything you need first.
  A signed-in session left over from a debug install also changes the launch route
  (Splash → Home rather than sign-in); before TD-90 that crashed both `StartupBenchmark`
  cases on a build with no `INDIC_API_BASE_URL`.

Results land as `*-benchmarkData.json` under the module's
`build/outputs/connected_android_test_additional_output/`. A worked before/after
comparison is in [../perf/round2-main-vs-branch.md](../perf/round2-main-vs-branch.md).

### Pixel 6 against the CI emulator (2026-09-25)

Pixel 6 (`oriole`), Android 17 (API 37, `CP2A.260705.006`), battery 100 %, over
wireless adb, at `main` @ `05aac72` plus the `resolveStatus` half of the TD-90
fix (#38). Reference: CI run 36109382066, API 34 x86_64 emulator. Macro 12/12 passed (the three
`StartupHeadroomBenchmark` cases skip unless `-e startupHeadroom true`, as in CI);
micro `OK (10 tests)`. Medians unless marked; heap is `memoryHeapSizeMaxKb` / 1000.

| Benchmark | Emulator (CI) | Pixel 6 |
|---|---|---|
| Cold start, time to initial display | 966 ms | 378 ms |
| Warm start, time to initial display | 81 ms | 81 ms |
| Settings cold start | 650 ms | 334 ms |
| Wizard cold start | 691 ms | 369 ms |
| `settingsScroll` frame CPU P50 / P90 | 5.0 / 5.9 ms | 6.6 / 8.5 ms |
| `scrub150Frames` max heap | 21.2 MB | 22.3 MB |
| `scrub150Frames` frame CPU P50 / P90 | 6.0 / 7.7 ms | 5.4 / 8.4 ms |
| `scrub10Frames` max heap | 17.8 MB | 18.8 MB |
| LabResults curve build, 30 frames | 123 ms | 40 ms |
| LabResults curve build, 150 frames | 238 ms | 132 ms |
| LabResults frame CPU P90 (30 / 150 frames) | 602 / 596 ms | 9.1 / 7.3 ms |
| micro `valueRanges_150frames` | 786 ms | 1453 ms |
| micro `decodeDatFile_oneFrame` | 0.43 ms | 0.49 ms |

What it says:

- **Startup and the Results curve are 1.8–3× faster on the phone**; warm start is the
  same. The emulator's LabResults frame P90 of ~600 ms is its software renderer, not
  the app: the phone draws the same screens at 7–9 ms.
- **Scrub heap agrees within 1 MB**, so the TD-87 sidecar fix holds on real hardware
  (164 MB before). Both runs show one ~71 MB iteration out of five (phone 70.6,
  CI 71.8): the first one, in which the seeder fabricates the session in the app
  process. The median is the number to compare.
- **Micro times are 1.1–4.4× slower on the phone** (debuggable and not
  AOT-compiled on both, against a server x86 core). They are relative numbers only,
  as the CI job says. Allocation counts are the signal and match to ±0.1 in 7 of 10
  cases; `generateHeatmap_oneFrame` (22.4 vs 17.2), `buildReport_oneFrame` (1714 vs
  1599) and `valueRanges_150frames` (4625 vs 4509) differ by a fixed amount on
  identical code, which points at the platform (API 37 arm64 against API 34
  x86_64), not a regression. Compare allocations like for like: phone to phone,
  emulator image to the same image.

### Known coverage gaps

Worth knowing before you assume something is protected:

- Robolectric's `PdfDocument` has no native document: `startPage` throws
  "document is closed". Anything that draws a PDF page (`PdfReportGenerator`,
  the bundler's reports, the viewer's PDF and ZIP exports) is checked only by
  the device tests above. JVM tests stop at the progress and error contract.

- The strain plot's own gestures — scrub, pinch, pan, double-tap, and the
  fraction it reports to the scrub slider (NaN once the scrub clears) — are
  covered by `ui/analysis/VsgPlotViewTest`. The lattice screen around it — the
  slider itself, double-tap-to-copy and the composed **Save graph** PNG — has
  **no automated coverage**; it is exercised only by the manual pass in
  [WORKFLOWS.md](WORKFLOWS.md) §7.
- The Storage section and the diagnostics consent toggle have no UI test;
  `settings/HelpSupportSectionTest` is the Robolectric pattern to copy if you add
  one.

Emulator (all instrumented):
```bash
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64
```

Macrobenchmark / Baseline Profile (`:benchmark` module — not part of default CI):
```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED
```
CI runs this only with the `benchmark` PR label or workflow_dispatch
`run_benchmark`. `app/src/main/baseline-prof.txt` holds only comments, and
`profileinstaller` ships the AndroidX libraries' own profile rules. A profile of
the app's own startup path has nothing to gain: on a Pixel 6, `Full` compilation
starts no faster than `None` ([perf/startup.md](../perf/startup.md), which has the
`StartupHeadroomBenchmark` steps). Measure that again before generating one.

## Real-data validation

The unit tests type lab tables in by hand. The whole chain — images, solve,
load CSV, curve, E — is checked against a published test with its own strain
measurement in [REAL_WORLD_VALIDATION.md](REAL_WORLD_VALIDATION.md). It is a
manual emulator run plus `scripts/real_data_steel_tensile.py`, not part of CI;
the app's resulting curve is pinned in `report/RealSteelModulusTest`. Re-run it
after a change to the solve, the strain window or `ElasticModulus`.

## What not to test here

- Algorithm accuracy → the engine's own suite, which lives in the `native/`
  submodule and runs in the engine repo's CI, not here
  ([docs/engine/TESTING.md](../engine/TESTING.md))
- Backend API → backend pytest (`backend/tests/`)
- Real Firebase Auth → `auth/FirebaseAuthIntegrationTest`. These self-skip
  (JUnit `assumeTrue`) unless `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD`
  are passed as instrumentation args. CI does not supply them, so they are
  skipped there today — to run them, provide the args locally or wire the
  secrets into the emulator job.
