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
[`AccessRouter`](../../app/src/main/java/com/rafad/indicvisiondic/ui/auth/AccessRouter.kt)
using constants from
[`AccessStatus`](../../app/src/main/java/com/rafad/indicvisiondic/data/AccessStatus.kt).
Do not re-encode `"APPROVED"` / `"PENDING"` switches in new screens — call the
router.

Intent extras shared across Activities live in
[`DicKeys`](../../app/src/main/java/com/rafad/indicvisiondic/DicKeys.kt).

## Package map

| Package | Role |
|---|---|
| `ui/auth/` | Splash, sign-in, pending approval, Google / AccessRouter helpers |
| `ui/home/` | Session list, settings sheet, selection, open-session intents |
| `ui/analysis/` | Setup wizard, ROI, VSG sweep, import/overlay helpers, ViewModel |
| `ui/viewer/` | Heatmaps, inspect, report factory, settings-used sheet |
| `ui/common/` | Insets, media source chooser, motion |
| `data/` | Auth, session store, cloud sync/upload/restore, API client |
| `report/` | PDF / CSV / visualization |

Style for shared UI logic: plain `object` / small classes named `*Helper`,
`*Extractor`, `*Router`, `*Bundler` — same pattern as
`MediaSourceChooser` and `GoogleSignInHelper`. Prefer extracting a helper
over growing an Activity further. Keep `lifecycleScope` and Activity Result
launchers in the Activity.

## Session layout on disk

Each saved analysis lives under the app's session directory (see
[`SessionStore`](../../app/src/main/java/com/rafad/indicvisiondic/data/SessionStore.kt)):

```
<sessionId>/
  *.dat                 # per-frame correlation results
  raw_deformed/         # SessionPaths.RAW_DEFORMED_SUBDIR — original frames
  …                     # metadata / previews as written by the ViewModel
```

The constant `SessionPaths.RAW_DEFORMED_SUBDIR` is shared by the ViewModel,
[`DicUploadWorker`](../../app/src/main/java/com/rafad/indicvisiondic/data/DicUploadWorker.kt),
and cloud restore so path segments never diverge.

## Sync workers

When cloud is configured (`INDIC_API_BASE_URL`):

| Type | File | Job |
|---|---|---|
| Upload | `DicUploadWorker` | Resume/create remote session, stage artifacts, upload bundles |
| Metadata JSON | `SessionUploadMetadata` | frames / device / engine JSON for the API |
| Bundle build | `SessionUploadBundler` | Render frame bundles + CSV lists offline-testable |
| Restore | `CloudRestore` | Pull remote sessions back into local session dirs |

## Where to edit

| I want to… | Start here |
|---|---|
| Change sign-in providers / access gate | `data/AuthRepository.kt`, `docs/backend/AUTH_SETUP.md` |
| Change post-auth navigation | `ui/auth/AccessRouter.kt` |
| Change the analysis wizard UI | `ui/analysis/StaticAnalysisActivity.kt` + helpers in the same package |
| Change import / video extraction | `FrameImportHelper`, `VideoFrameExtractor` |
| Change sweep planner UI | `SweepSetupHelper` (run loop stays in the Activity + `VsgStudyRunner`) |
| Change heatmap / inspect / share | `ui/viewer/ResultViewerActivity.kt` + `Viewer*` helpers |
| Change Home list / settings | `ui/home/HomeActivity.kt` + `Session*` / `ui/settings/SettingsActivity` |
| Change the C++ engine | `docs/engine/ARCHITECTURE.md` — not this page |

## Related docs

- [Engine architecture](../engine/ARCHITECTURE.md)
- [Auth setup](../backend/AUTH_SETUP.md)
- [Cloud architecture](../backend/CLOUD_ARCHITECTURE_GCP.md)
- [Contributing](../../CONTRIBUTING.md)
