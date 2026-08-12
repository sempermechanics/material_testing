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
                                ├─ new analysis → StaticAnalysisActivity
                                │                    └─ ResultViewerActivity
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
| `ui/analysis/` | Setup wizard, ROI, VSG sweep, import/overlay helpers, ViewModel |
| `ui/viewer/` | Heatmaps, inspect, report factory, settings-used sheet |
| `ui/settings/` | Settings screen, cloud/account controls, analysis-data listing |
| `ui/admin/` | Admin screen — approve/revoke users via `/v1/admin/*` |
| `ui/limit/` | Session-quota screen |
| `ui/common/` | Insets, media source chooser, motion |
| `data/` | Auth, session store, cloud sync/upload/restore, storage budget, param clipboard |
| `data/net/` | Backend HTTP client (`IndicApi`), token store/provider |
| `report/` | PDF / CSV / visualization |
| *(root)* | `SemperApp`, `Diagnostics`, `CrashReportingTree`, `DicKeys`, `DicResult` |

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

The constant `SessionPaths.RAW_DEFORMED_SUBDIR` is shared by the ViewModel,
[`DicUploadWorker`](../../app/src/main/java/com/indicvision/semper/data/DicUploadWorker.kt),
and cloud restore so path segments never diverge.

## Sync workers

When cloud is configured (`INDIC_API_BASE_URL`):

| Type | File | Job |
|---|---|---|
| Upload | `DicUploadWorker` | Resume/create remote session, stage artifacts, upload bundles |
| Metadata JSON | `SessionUploadMetadata` | frames / device / engine JSON for the API |
| Bundle build | `SessionUploadBundler` | Render frame bundles + CSV lists offline-testable |
| Restore | `CloudRestore` | Pull remote sessions back into local session dirs |

`IndicApi.listSessions` **pages**: it follows `nextPageToken` until the backend
stops returning one, so a deep refresh sees the whole account rather than the
first page. Anything that lists cloud sessions should go through it rather than
issuing a single request.

## Storage, diagnostics and the parameter clipboard

Three small subsystems added alongside the cloud work. Each is a plain object
with no framework behind it:

| Concern | Files | Notes |
|---|---|---|
| Local disk budget | `data/StorageBudget.kt`, `data/CacheJanitor.kt` | Measures analyses and cache; frees the local frames of **backed-up** analyses only. A user-set GB budget is enforced from `SemperApp.onCreate`, so it runs before any screen |
| Crash reporting | `Diagnostics.kt`, `CrashReportingTree.kt` | Crashlytics collection is **off in the manifest** and enabled only on consent (first-run prompt or the Settings toggle). `CrashReportingTree` is a release-only Timber tree feeding breadcrumbs and non-fatals |
| Parameter hand-off | `data/ParamClipboard.kt` | Holds one subset/step/strain-window triple, copied from the sweep lattice readout and pasted into the analysis wizard's advanced parameters |

An analysis whose local frames were freed becomes a **cloud-only row**: Home
still lists it, badges it, and downloads it on open rather than reporting the
data as gone. The "session data gone" path now means *no* copy exists anywhere.

## Export hand-off

Exports do not go straight to the system chooser. `ui/viewer/ShareCenter.kt`
builds the artifact with determinate progress, then hands it to
`SendToSheet`, a bottom sheet offering **Save to Files** (which routes through
the transparent `SaveExportActivity` to open SAF) or **Share**. Settings' two
data exports use the same path, so there is one place to change export UX.

## Memory & failure invariants

Non-obvious rules the analysis and transfer paths depend on. Breaking one tends to
show up as an OOM, a mid-run crash, or a "nothing happened" report:

- **JNI output buffer is bounded.** `AnalysisViewModel` / `VsgStudyRunner` allocate
  one direct `ByteBuffer` sized to the ROI grid (`(w/step)·(h/step)` points). The
  engine's returned point count is checked against that capacity *before* the
  buffer is read back — a count over capacity is treated as an engine failure, not
  read past the buffer.
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
| Change the analysis wizard UI | `ui/analysis/StaticAnalysisActivity.kt` + helpers in the same package |
| Change import / video extraction | `FrameImportHelper`, `VideoFrameExtractor` |
| Change parameter-sweep setup UI | `SweepSetupHelper` (run loop stays in the Activity + `VsgStudyRunner`) |
| Change the sweep result lattice | `ui/analysis/VsgLatticeActivity.kt`, `VsgLatticeView`, `VsgPlotView` |
| Change heatmap / inspect | `ui/viewer/ResultViewerActivity.kt` + `Viewer*` helpers |
| Change how exports are handed off | `ui/viewer/ShareCenter.kt`, `SendToSheet.kt`, `SaveExportActivity.kt` |
| Change Home list / settings | `ui/home/HomeActivity.kt` + `Session*` / `ui/settings/SettingsActivity` |
| Change storage reclaim behaviour | `data/StorageBudget.kt`, `data/CacheJanitor.kt` |
| Change crash-reporting consent | `Diagnostics.kt`, `CrashReportingTree.kt` |
| Change the C++ engine | The engine is a submodule — see [ENGINE_APP_CONTRACT.md](../engine/ENGINE_APP_CONTRACT.md), not this page |

## Related docs

- [Engine ↔ app contract](../engine/ENGINE_APP_CONTRACT.md) (the engine itself
  lives in the `native/` submodule — see [engine/ARCHITECTURE.md](../engine/ARCHITECTURE.md))
- [Auth setup](../backend/AUTH_SETUP.md)
- [Cloud architecture](../backend/CLOUD_ARCHITECTURE_GCP.md)
- [Contributing](../../CONTRIBUTING.md)
