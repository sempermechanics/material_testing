# App test suite — workflow chunks

Tests are organized into chunks that mirror the user journey through the app.
Each chunk owns one layer; no duplicate assertions across chunks.

## Chunk map

| Chunk | User journey | JVM tests (`app/src/test`) | Instrumented (`androidTest`) |
|-------|--------------|---------------------------|------------------------------|
| **auth** | Splash → Auth / Pending / Home, re-auth, password rules | `auth/AccessRouterTest`, `ReauthFlowTest`, `PasswordPolicyTest` | `auth/FirebaseAuthIntegrationTest` |
| **analysis** | Import → ROI → batch / parameter sweep | `analysis/VsgStudyTest`, `SubsetRecommenderTest`, `ConvergenceGateTest`, `BitmapDecodeTest` | — |
| **session** | Session store durability, disk footprint, failure provenance | `session/SessionStoreAtomicTest`, `LocalStorageFootprintTest`, `FailureProvenanceTest` | — |
| **results** | `.dat` decode, CSV, heatmap, PDF, GIF | `results/DicResultCsvTest`, `DicResultDecodeTest`, `VisualizationEngineTest`, `ReportBuilderTest`, `GifEncoderTest`, `SummaryAnimationTest` | — |
| **viewer** | Result viewer controls | `viewer/FrameNumberEntryTest` | — |
| **cloud** | Upload, API, restore, quota, account deletion | `cloud/ApiDtosContractTest`, `UploadResumableTest`, `DicUploadWorkerOutcomesTest`, `RestoreAndImportSafetyTest`, `QuotaGateTest`, `AccountDeletionTest`, `SessionEverythingExporterTest` | — |
| **settings** | Settings sections, contacting support, account deletion | `settings/AnalysisEntriesTest`, `HelpSupportSectionTest`, `DeleteAccountReauthTest`, `DicSettingsMigrateTest` | — |
| **analytics** | Consent-gated Firebase Analytics events | `analytics/SemperAnalyticsTest` | — |
| **upgrade** | Prefs / session index forward compatibility | (covered in settings + session) | `upgrade/PrefsUpgradeSmokeTest` |
| **e2e** | Wizard chrome smoke (Next + toolbar; Back / Compute / instruction GONE on step 1) | — | `AnalysisWizardSmokeTest` |
| **pipeline** | JNI + native runtime | — | `pipeline/EnginePipelineSmokeTest` |
| **benchmark** | Startup Macrobenchmark / Baseline Profile | — | `:benchmark` module (label `benchmark` / workflow_dispatch) |

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

`session/LocalStorageFootprintTest` pins the rule that only cloud-backed
analyses may have their local frames freed — it is the guard against a storage
optimisation quietly deleting the one copy of someone's data.
`cloud/RestoreAndImportSafetyTest` and `DicUploadWorkerOutcomesTest` cover the
other half: a cancelled import and a terminally failed upload must both leave
the session store in a state you can come back to.

### Known coverage gaps

Worth knowing before you assume something is protected:

- The sweep lattice's newer interactions — pinch-zoom, the scrub slider,
  double-tap-to-copy and the composed **Save graph** PNG — have **no automated
  coverage**. They are exercised only by the manual pass in
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
./gradlew :benchmark:connectedBenchmarkAndroidTest
```
CI runs this only with the `benchmark` PR label or workflow_dispatch
`run_benchmark`. Ship `app/src/main/baseline-prof.txt` + `profileinstaller`;
regenerate the profile from Macrobenchmark output when tightening startup.

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
