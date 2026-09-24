# Android app architecture

Start with the [documentation index](../README.md) for the DIC primer and
glossary. This page is the map of the **Kotlin / UI layer** — where screens
live, how they hand off, and which helpers to edit.

Nothing here requires the optional cloud backend. Analysis runs entirely
on-device.

## Activity flow

```
SplashActivity
    ├─ (no session / error) → AuthActivity
    ├─ PENDING              → PendingApprovalActivity
    └─ APPROVED / offline   → HomeActivity
                                ├─ + → TestTypeSheet → MediaPickerSheet
                                │        → StaticAnalysisActivity
                                │              └─ ResultViewerActivity
                                └─ open session → ResultViewerActivity
                                                     (or VsgLatticeActivity for sweeps)
```

Access-status routing is centralized in
[`AccessRouter`](../../app/src/main/java/com/indicvision/semper/ui/auth/AccessRouter.kt)
using constants from
[`AccessStatus`](../../app/src/main/java/com/indicvision/semper/data/AccessStatus.kt).
Do not re-encode `"APPROVED"` / `"PENDING"` switches in new screens — call the
router.

Intent extras shared across Activities live in
[`DicKeys`](../../app/src/main/java/com/indicvision/semper/DicKeys.kt).

## Package map

| Package | Role |
|---|---|
| `ui/auth/` | Splash, sign-in, pending approval, Google / AccessRouter helpers |
| `ui/home/` | Session list, selection, open-session intents |
| `ui/analysis/` | Setup wizard (ViewStub steps 2/3; `AnalysisWizardSlots` / `AnalysisWizardCoach`; `goToStep` on the activity; step-settings body via `WizardStepSettingsContentView`), ROI, VSG sweep, `DicBatchRunner` + `DicFieldIo`, import/overlay helpers, ViewModel |
| `ui/viewer/` | Heatmaps, tap-to-probe, report factory, the ⓘ details sheet, `ViewerFieldPills` |
| `ui/settings/` | Settings screen; scroll body inflates via `SettingsScrollContentView`; account/storage/prefs/your-data/help live in `Settings*Section`; restore/download/delete stay on `SettingsActivity` |
| `ui/admin/` | Admin screen — approve/revoke users via `/v1/admin/*` |
| `ui/limit/` | Session-quota screen |
| `ui/common/` | Insets, motion, `TestTypeSheet` (Home **+**), `MediaPickerSheet` (Import / wizard dropzones), `CrispToast`, `TransferBannerController` |
| `data/` | Auth, session store, cloud sync/upload/restore/download, storage budget, param clipboard |
| `data/net/` | Backend HTTP client (`IndicApi`), its two OkHttp interceptors (`RetryOnTransient`, `AppCheckHeader`), token store/provider |
| `report/` | PDF / CSV / visualization |
| `analytics/` | `SemperAnalytics` — consent-gated Firebase Analytics events |
| `imaging/` | `BitmapDecode`, `ImageEncode` — decode/encode away from the UI classes |
| `navigation/` | `AppIntents` — intent factories so `data` / `report` never import a `ui` Activity |
| `util/` | `BrandAssets`, `Digests`, `OverlayFormats` |
| *(root)* | `SemperApp`, `Diagnostics`, `CrashReportingTree`, `DicKeys`, `DicResult`, `FieldHistogram` |

Style for shared UI logic: plain `object` / small classes named `*Helper`,
`*Extractor`, `*Router`, `*Bundler` — same pattern as
`MediaSourceChooser` and `GoogleSignInHelper`. Prefer extracting a helper
over growing an Activity further. Keep `lifecycleScope` and Activity Result
launchers in the Activity.

## Session layout on disk

Each saved analysis lives under the app's session directory (see
[`SessionStore`](../../app/src/main/java/com/indicvision/semper/data/SessionStore.kt)):

```
<sessionId>/
  *.dat                 # per-frame correlation results
  raw_deformed/         # SessionPaths.RAW_DEFORMED_SUBDIR — original frames
  …                     # metadata / previews as written by the ViewModel
```

The constants `SessionPaths.RAW_DEFORMED_SUBDIR`, `FRAME_DAT_FMT`, and
`SessionPaths.frameDat` are shared by the ViewModel / `DicBatchRunner`,
[`DicUploadWorker`](../../app/src/main/java/com/indicvision/semper/data/DicUploadWorker.kt),
and cloud restore so path segments and `frame_0000.dat` names never diverge.

## Sync workers

When cloud is configured (`INDIC_API_BASE_URL`):

| Type | File | Job |
|---|---|---|
| Upload | `DicUploadWorker` | Resume/create remote session, stage artifacts, upload bundles |
| Metadata JSON | `SessionUploadMetadata` | frames / device / engine JSON for the API |
| Bundle build | `SessionUploadBundler` | Render frame bundles + CSV lists offline-testable |
| Restore | `CloudRestore` / `DicRestoreWorker` | Pull remote sessions back into local session dirs |
| Bundle download | `DicBundleDownloadWorker` | Write a session `.zip` into a SAF document the user picked **before** enqueue. Falls back to packing the local session when the cloud copy is unavailable, and deletes the empty destination on failure |
| Backup delete | `BackupDeleteWorker` | Erase a cloud backup once the 5-second undo window closes |

`IndicApi.listSessions` **pages**: it follows `nextPageToken` until the backend
stops returning one, so a deep refresh sees the whole account rather than the
first page. Anything that lists cloud sessions should go through it rather than
issuing a single request.

### The two interceptors on the shared client

Both are application interceptors on `IndicApi`'s companion client, which
`downloadClient` inherits through `newBuilder()`. Retry is added first, so it
wraps the header: a retried attempt reads a fresh App Check token rather than
replaying one that may have expired while it waited.

`RetryOnTransient` retries **429 unconditionally** — the token bucket
(`backend/app/rate_limit.py`) and the gateway quota both reject before the
handler runs, so nothing happened. **503 is not the same promise**: ESPv2 emits
it before *and* after handing a request on, so it is retried only for GET and
for the POSTs whose handlers are idempotent by contract. Session create and the
upload broker are deliberately absent — a duplicate there costs a Drive object.
Three attempts with a ceiling, honouring `Retry-After`; where a call is
worker-mediated, WorkManager's own backoff owns the long game.

`AppCheckHeader` attaches `X-Firebase-AppCheck` to the configured API host
only — Drive shares this client and has no use for it — and **fails open**: a
build that cannot attest sends no header rather than a failed call, because the
decision to refuse belongs to the backend, which is the side that knows whether
it is in `monitor` or `enforce`. See
[AUTH_SETUP.md §3.2](../backend/AUTH_SETUP.md).

## Licensing & entitlements

The app never decides its own plan — `data/LicenseEntitlements.kt` is the one
place that answers "am I demo or licensed," and it reads through
`data/net/AppRemoteConfig.kt`, which caches whatever the backend's
`GET /v1/config` last reported (`plan`, `cloudBackupEnabled`, `shareEnabled`,
`licensePrefix`, `licenseKind`). Fails closed: before the first successful
fetch, and on any ambiguous value, everything reads as Demo.

`IndicApi.activateLicense()` calls `POST /v1/licenses/activate` (bearer +
`X-Device-Id`, not device-signed) to redeem a key — see
[CLOUD_ARCHITECTURE_GCP.md §20](../backend/CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements)
for the backend's individual-vs-institution split. **On the Android side there is
no distinction** between an individual key and a institution seat — both resolve
to `mode=licensed` with identical entitlements; `licenseKind` is carried
through only for display/support (e.g. "activated via university.edu"), not as a
gating input anywhere in `LicenseEntitlements`.

| Concern | File |
|---|---|
| Plan resolution / gating | `data/LicenseEntitlements.kt` |
| Cached config, wire → prefs | `data/net/AppRemoteConfig.kt` (`AppConfigDto` in `ApiDtos.kt`) |
| Redeem a key | `IndicApi.activateLicense()` |
| Expiry notice | `LicenseEntitlements.expiryNoticeDays()` — advisory only; suppressed on a cache older than a week. `mode` stays the only gate. See [WORKFLOWS.md §9.3](WORKFLOWS.md#9-session-limit) |
| Local analysis cap | `LicenseEntitlements.analysisCap()` — demo 25, licensed unlimited; see [WORKFLOWS.md §9](WORKFLOWS.md#9-session-limit) |

## Storage, diagnostics and the parameter clipboard

Three small subsystems added alongside the cloud work. Each is a plain object
with no framework behind it:

| Concern | Files | Notes |
|---|---|---|
| Local disk budget | `data/StorageBudget.kt`, `data/CacheJanitor.kt` | Measures analyses and cache; frees the local frames of **backed-up** analyses only. A user-set GB budget is enforced from `SemperApp.onCreate`, so it runs before any screen |
| Crash reporting | `Diagnostics.kt`, `CrashReportingTree.kt` | Crashlytics collection is **off in the manifest** and enabled only on consent (first-run prompt or the Settings toggle). `CrashReportingTree` is a release-only Timber tree feeding breadcrumbs and non-fatals |
| Product analytics | `analytics/SemperAnalytics.kt` | Same consent flag as Crashlytics (`DicSettings.diagnosticsEnabled`) — events are dropped, not queued, when it is off. Params must stay PII-free: enums, coarse buckets, success/fail. The consent copy names both halves (**Send crash reports and usage data**) — keep it and [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) §2.4 in step with the event set |
| Parameter hand-off | `data/ParamClipboard.kt` | Holds one subset/step/strain-window triple, copied from the sweep lattice's parameter chip and pasted into the analysis wizard's advanced parameters |

An analysis whose local frames were freed becomes a **cloud-only row**: Home
still lists it, badges it, and downloads it on open rather than reporting the
data as gone. The "session data gone" path now means *no* copy exists anywhere.

## Export hand-off

Exports do not go straight to the system chooser. `ui/viewer/ShareCenter.kt`
hands off to `SendToSheet`, a bottom sheet offering **Save to Files** (SAF) or
**Share**. For the single-photo target the artifact is built first and then
offered through the transparent `SaveExportActivity`; for the five slow targets
the sheet comes **first** and the export is written straight into the chosen
document. Settings' two data exports use the same path, so there is one place to
change export UX.

Two exports deliberately bypass it: Settings' **Download** already has its
destination (§4 of [WORKFLOWS.md](WORKFLOWS.md)), and the lattice's **Save graph**
goes to the system chooser directly.

Long exports are not modal. Dismissing the progress dialog parks the job in
`ui/common/TransferBannerController` — a non-modal strip with progress, Cancel
and prev/next paging — hosted by both `ResultViewerActivity` and
`SettingsActivity`, where it also carries restores and bundle downloads.

## Memory & failure invariants

Non-obvious rules the analysis and transfer paths depend on. Breaking one tends to
show up as an OOM, a mid-run crash, or a "nothing happened" report:

- **JNI output buffer is bounded.** `DicBatchRunner` / `VsgStudyRunner` allocate
  one direct `ByteBuffer` via `DicFieldIo` sized to the ROI grid (`(w/step)·(h/step)`
  points). The engine's returned point count is checked against that capacity
  *before* the buffer is read back — a count over capacity is treated as an engine
  failure, not read past the buffer. JNI `computeFullFieldDirect` stays in that
  one batch loop; do not fragment it.
- **Report/upload compositing is capped to `VisualizationEngine.REPORT_MAX_EDGE`
  (1280 px).** The PDF/cloud heatmaps downscale to 600 px anyway; the cap only
  stops intermediate full-res `ARGB_8888` bitmaps from OOMing on large (e.g. 26 MP)
  references. On-screen scrub uses the separate `DISPLAY_MAX_EDGE` (1080).
- **The viewer's frame look-ahead is bounded by bytes, not just by count.**
  `ScrubFrameCache` caps decoded frames on both a frame count and a byte ceiling
  (`maxDataBytes`, heap/8 by default), so a heavy PLC frame simply holds fewer slots
  instead of the window growing with frame size. `ResultViewerActivity.prefetchAround`
  fills that window with **one serialized worker**, cancelled and restarted as the user
  scrubs, admitting a frame only while the cache has room and the heap guard passes.
  It must stay serialized: an earlier version launched a coroutine per neighbour on
  every frame load, so peak memory scaled with *how fast the user scrubbed* rather than
  with any bound.
- **Whole-batch passes are started on demand, never on open.** The summary's colour-scale
  scan (`ViewerSummaryHelper.start`) decodes **every frame in the batch**, so it runs from
  `show()` rather than from viewer startup — opening straight onto a frame must not pay
  for an N-frame decode the user may never look at. The inspect-mode spatial index
  follows the same rule (built lazily on first tap, invalidated on frame load).
- **Batch progress is a buffered `SharedFlow`** (`replay=1`, `extraBufferCapacity`,
  `DROP_OLDEST`), not a `StateFlow` — a conflating flow dropped intra-frame ticks
  when the native solve emitted faster than the UI collected, stalling the bar.
- **Transfer failures are surfaced, not swallowed.** Terminal worker failures carry
  a human reason in their `WorkInfo` output; `HomeActivity` observes **both** the
  `upload` tag (badge dialog + snackbar) and the `restore` tag (snackbar), and
  `SettingsActivity` observes `restore` as well. Quota-full is the one exclusion —
  it routes to its own screen. Progress from the same `WorkInfo` drives the
  per-row badge and progress bar on Home, for downloads as well as uploads.
- **Cancelling a sweep abandons the sweep.** `VsgStudyRunner` checks the cancel
  token *between* combinations as well as inside a solve, so Cancel does not merely
  skip to the next parameter set.
- **A Drive outage must not look like deleted data.** A verifying refresh drops
  local metadata only when the backend confirms a blob is missing, never on an
  indeterminate answer. See [CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md).

## Where to edit

| I want to… | Start here |
|---|---|
| Change sign-in providers / access gate | `data/AuthRepository.kt`, `docs/backend/AUTH_SETUP.md` |
| Change post-auth navigation | `ui/auth/AccessRouter.kt` |
| Change the analysis wizard UI | `StaticAnalysisActivity.goToStep`; slot chrome / coach in `AnalysisWizardSlots` / `AnalysisWizardCoach`; later steps inflate through ViewStubs |
| Change the full-field batch loop | `DicBatchRunner` + `DicFieldIo` (shared with VSG). Do not split `computeFullFieldDirect` out of that loop |
| Change Home list / settings | `ui/home/HomeActivity.kt` + `Session*` / `ui/settings/SettingsActivity` + `Settings*Section` |
| Change import / video extraction | `FrameImportHelper`, `VideoFrameExtractor` tries three rungs in order: `AviVideoDecoder` (the only thing that opens an AVI), `HardwareVideoDecoder` (`MediaExtractor` + `MediaCodec`, lossless Y plane, decodes forward from the previous sync frame so each sample is the frame asked for), then `MediaMetadataRetriever`. All three write through `VideoFrameBatchWriter`, which also records each frame's time for load mapping. Which instants get sampled is `VideoSampling` — the sheet's estimate and every rung share it, so they cannot disagree. `VideoSamplingSheet` offers two plans: evenly at a rate, or the file's own key frames (`VideoKeyframes`: the AVI `idx1` flags, else `MediaExtractor` sync samples). Either way the rungs receive a list of sample times |
| Change AVI support | `imaging/AviReader` (demuxer), `imaging/AviLuma` (uncompressed layouts), `imaging/MjpegHuffman` (table repair), `AviCodecDecoder` (`MediaCodec` for Xvid/H.264) |
| Change parameter-sweep setup UI | `SweepSetupHelper` (run loop stays in the Activity + `VsgStudyRunner`) |
| Change the sweep result lattice | `ui/analysis/VsgLatticeActivity.kt`, `VsgLatticeView`, `VsgPlotView` |
| Change heatmap / probe | `ui/viewer/ResultViewerActivity.kt` + `Viewer*` helpers |
| Change how exports are handed off | `ui/viewer/ShareCenter.kt`, `SendToSheet.kt`, `SaveExportActivity.kt` |
| Change the stress–strain curve or where it appears | `report/StressStrain.kt` (pure: `Model` per test — axial `P/A`, flexural `3PL/2bh²` — strain in mε, signed). Viewer Results: `ui/viewer/ViewerStressStrainHelper.kt` — `fill(Views)` draws into the summary slot (`ViewerSummaryHelper.showsResults`, sessions with loads) and the ⓘ sheet from one build, cached in `ResultViewerViewModel`; `plotSeries` / `resultsText` (in `ViewerStressStrainResults.kt`) are shared with the share path, `printContext` gives PDF plots the day palette. CSV: `AnalysisCsvWriter.mechanicalSuffixColumns`, and the `# mechanical_results` trailer written from `Appender.close`. PDF: `ReportData.mechanical` (cover) and `PdfReportGenerator.generateBatch(stressStrain = …)` (closing page); `ShareCenter.stressStrainPage` renders the plot off screen |
| Change Young's modulus | `report/ElasticModulus.kt` (longest leading run before the peak with R² ≥ 0.995, free intercept; every length is tried) on `report/LinearFit.kt`. MPa ÷ mε = GPa. Pinned on real data by `RealSteelModulusTest` ([REAL_WORLD_VALIDATION.md](REAL_WORLD_VALIDATION.md)) |
| Change bending deflection, scale or E | `report/BeamDeflection.kt` (pure: `Probe` from the taps — mm/px and the top→bottom direction; δ = mean displacement along it inside the probe radius; `summarize` → per-frame `steps`, per-held-load `loadSteps`, mean E and the load–deflection slope over the load steps). Taps: `data/BeamEdgeTaps.kt` in `SpecimenGeometry.loadPoint` (metadata `/6`). Editor: `ui/analysis/BeamEdgeTapActivity.kt` + `BeamEdgeTapOverlay.kt` on `ui/viewer/TouchImageView.kt`, reference decode in `ReferencePreviewLoader.kt`; wizard row `LoadPointRow.kt`. Viewer: `ui/viewer/ViewerBendingResults.kt` |
| Change how load-log rows meet frames | `data/MachineLoadMapper.kt` (a timed log with video frame times is always matched by time, within `MATCH_TOLERANCE_MS` = 100 ms, else NaN = no load; otherwise one-to-one → drop-first → resample; the time match takes `logStartS`). NaN loads are skipped by `StressStrain.build`, read through `loadOfFrame`, stored as `null` in `index.json` and omitted from `metadata.json`. Wizard: `ui/analysis/AnalysisLoadCard.kt`, the log-start offset row `LoadSyncRow.kt` (`row_load_sync.xml`) |
| Change the student lab report | `report/LabReport.kt` (pure: the ordered sections of the handwritten write-up, filled from the curve), `LabReportText.kt` (fixed wording), `LabReportFormat.kt` (numbers, ×10ⁿ strains), `LabReportPdf.kt` (journal-sheet renderer), `LabReportBending.kt` (Experiment 5 layout), `Extensometer.kt` (tensile Extension column: ΔL in px between two end bands of the analysed region, gauge fixed on the first frame by `StressStrain.build` → `Curve.gauge`). `LabReportExporter.offered` decides the share row: tensile with loads, bending once the thickness is tapped. Share row and graph rendering: `ui/viewer/LabReportExporter.kt`, `ShareCenter.labReportPdf` (`KIND_LAB_PDF`). Spec: [STUDENT_LAB_WORKFLOW.md](STUDENT_LAB_WORKFLOW.md) |
| Change transfer progress UI | `ui/common/TransferBannerController.kt` (Settings + viewer), `data/TransferNotifications.kt` (the one channel) |
| Change the test-type chooser or add a test type | `data/TestType.kt` (wire names are on-disk), `ui/common/TestTypeSheet.kt`; per-test inputs go through `data/MechanicalTestInputs.kt` → `SessionRecord` → `SessionUploadMetadata.testJson` / `CloudRestore.recordFrom`, and a new stress model is a `StressStrain.Model` subclass |
| Change the bending dimensions | `data/SpecimenGeometry.kt` (one field per dimension, `toArray` order is the Intent extra), the matching `StressStrain.Model`, and `ui/analysis/SpecimenGeometryFields.kt` + the `row_specimen_dimension` includes in `wizard_load_card.xml` |
| Change how a machine load log is read or matched to frames | `data/MachineLoadCsv.kt` (pure parser: delimiter, decimal mark, header, units, columns) and `data/MachineLoadMapper.kt` (rows → frames; signed, never abs). Card UI: `ui/analysis/AnalysisLoadCard.kt` + `wizard_load_card.xml` (ViewStub `stubLoadCard`), which also hosts the per-test dimension rows; the SAF launcher stays on `StaticAnalysisActivity` |
| Change the new-analysis media sheet | `ui/common/MediaPickerSheet.kt` / `MediaSourceChooser.kt` — shared by the Home **+** and both wizard dropzones |
| Add an analytics event | `analytics/SemperAnalytics.kt` — keep params PII-free and consent-gated |
| Change storage reclaim behaviour | `data/StorageBudget.kt`, `data/CacheJanitor.kt` |
| Change crash-reporting consent | `Diagnostics.kt`, `CrashReportingTree.kt` |
| Change the C++ engine | The engine is a submodule — see [ENGINE_APP_CONTRACT.md](../engine/ENGINE_APP_CONTRACT.md), not this page |

## Related docs

- [Engine ↔ app contract](../engine/ENGINE_APP_CONTRACT.md) (the engine itself
  lives in the `native/` submodule — see [engine/ARCHITECTURE.md](../engine/ARCHITECTURE.md))
- [Auth setup](../backend/AUTH_SETUP.md)
- [Cloud architecture](../backend/CLOUD_ARCHITECTURE_GCP.md)
- [Workflow index](../WORKFLOWS.md) — every flow's entry point, file chain and
  failure surface, plus where backtracking is hard (§E)
- [Contributing](../../CONTRIBUTING.md)
