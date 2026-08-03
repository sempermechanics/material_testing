# App test suite — workflow chunks

Tests are organized into chunks that mirror the user journey through the app.
Each chunk owns one layer; no duplicate assertions across chunks.

## Chunk map

| Chunk | User journey | JVM tests (`app/src/test`) | Instrumented (`androidTest`) |
|-------|--------------|---------------------------|------------------------------|
| **auth** | Splash → Auth / Pending / Home, re-auth | `auth/AccessRouterTest`, `auth/ReauthFlowTest` | `auth/FirebaseAuthIntegrationTest` |
| **analysis** | Import → ROI → batch/sweep | `analysis/VsgStudyTest`, `SubsetRecommenderTest`, `ConvergenceGateTest`, `BitmapDecodeTest` | — |
| **results** | `.dat` decode, CSV, heatmap, PDF, GIF | `results/DicResultCsvTest`, `DicResultDecodeTest`, `VisualizationEngineTest`, `ReportBuilderTest`, `GifEncoderTest`, `SummaryAnimationTest` | — |
| **viewer** | Result viewer controls | `viewer/FrameNumberEntryTest` | — |
| **cloud** | Upload, API, restore, account deletion | `cloud/ApiDtosContractTest`, `UploadResumableTest`, `AccountDeletionTest`, `SessionEverythingExporterTest` | — |
| **settings** | Settings sections, contacting support, account deletion | `settings/AnalysisEntriesTest`, `HelpSupportSectionTest`, `DeleteAccountReauthTest` | — |
| **e2e** | Wizard chrome smoke (Next + toolbar; Back / Compute / instruction GONE on step 1) | — | `AnalysisWizardSmokeTest` |
| **pipeline** | JNI + native runtime | — | `pipeline/EnginePipelineSmokeTest` |

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

Emulator (all instrumented):
```bash
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64
```

## What not to test here

- Algorithm accuracy → host C++ suite ([docs/engine/TESTING.md](../engine/TESTING.md))
- Backend API → backend pytest (`backend/tests/`)
- Real Firebase Auth → `auth/FirebaseAuthIntegrationTest`. These self-skip
  (JUnit `assumeTrue`) unless `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD`
  are passed as instrumentation args. CI does not supply them, so they are
  skipped there today — to run them, provide the args locally or wire the
  secrets into the emulator job.
