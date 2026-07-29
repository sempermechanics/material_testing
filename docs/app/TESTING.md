# App test suite — workflow chunks

Tests are organized into chunks that mirror the user journey through the app.
Each chunk owns one layer; no duplicate assertions across chunks.

## Chunk map

| Chunk | User journey | JVM tests (`app/src/test`) | Instrumented (`androidTest`) |
|-------|--------------|---------------------------|------------------------------|
| **auth** | Splash → Auth / Pending / Home, re-auth | `auth/AccessRouterTest`, `auth/ReauthFlowTest` | `auth/FirebaseAuthIntegrationTest` |
| **session** | Home list, open session, disk layout | `session/SessionPathsTest` | — |
| **analysis** | Import → ROI → batch/sweep | `analysis/VsgStudyTest`, `SubsetRecommenderTest`, `BitmapDecodeTest` | — |
| **results** | `.dat` decode, CSV, heatmap, PDF, GIF | `results/DicResultCsvTest`, `DicResultDecodeTest`, `VisualizationEngineTest`, `ReportBuilderTest`, `GifEncoderTest`, `SummaryAnimationTest` | — |
| **viewer** | Result viewer controls | `viewer/FrameNumberEntryTest` | — |
| **cloud** | Upload, API, restore, account deletion | `cloud/ApiDtosContractTest`, `UploadResumableTest`, `SessionUploadBundlerTest`, `CloudRestoreMappingTest`, `AccountDeletionTest` | — |
| **settings** | Settings sections, contacting support, account deletion | `settings/AnalysisEntriesTest`, `HelpSupportSectionTest`, `DeleteAccountReauthTest` | — |
| **e2e** | Full UI flows | — | `e2e/AppFlowEspressoTest` |
| **pipeline** | JNI + native runtime | — | `pipeline/EnginePipelineSmokeTest` |

## Overlap rules

- **Host C++ tests** own algorithmic displacement accuracy.
- **Android JNI smoke** (`pipeline/`) owns runtime/bridge correctness —
  `System.loadLibrary`, OpenMP threading, JNI marshalling.
- **JVM tests** own Kotlin orchestration and data contracts. Do not add JVM
  tests that re-assert displacement accuracy.

## Running by chunk

```bash
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.auth.*"
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.session.*"
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.analysis.*"
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.results.*"
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.cloud.*"
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.settings.*"
./gradlew :app:testDebugUnitTest --tests "com.rafad.indicvisiondic.viewer.*"
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
- Real Firebase Auth → `auth/FirebaseAuthIntegrationTest` (protected branches only)
