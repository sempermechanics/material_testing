# Semper — agent context

Read this before changing code. Commands live in [CONTRIBUTING.md](CONTRIBUTING.md).
Screen maps live in [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md).
DIC primer and glossary: [docs/README.md](docs/README.md).

## Product

Semper is an Android app that measures **how a surface deforms** from photographs.
A specimen is painted with random speckle; one **reference** image and one or more
**deformed** frames go through an on-device C++ engine. Output is a full-field
**displacement** (U, V, ~1/100 px) and **strain** (Exx, Eyy, Exy) as interactive
heatmaps, PDF, CSV, and PNG.

Analysis is **offline**. Cloud (Firebase Auth → Cloud Run → Firestore → Drive) is
optional identity, metadata, and blob sync. Bytes never transit Cloud Run; the
phone PUTs to a Drive resumable URI. No JSON service-account keys.

## Domain terms

Use these words. Do not invent synonyms.

| Term | Meaning |
|------|---------|
| subset | Odd-width pixel window tracked around its center (default ~41 px) |
| step | Grid spacing between tracked points, px |
| ZNSSD | Match score; 0 = perfect, ≤ 0.15 accepted, < 0 failed-point sentinel |
| ICGN | Iterative Gauss-Newton sub-pixel solver |
| VSG | Strain window: least-squares plane fit, odd width |
| `.dat` | Binary field: 8 floats/point (`x y u v exx eyy exy znssd`), 32 bytes |
| session | One saved analysis on disk (and optionally in the cloud) |

Engine pipeline (detail in `native/docs/ARCHITECTURE.md`): AKAZE seeds → Delaunay
mesh → RGDIC flood-fill → ICGN → VSG strain → `.dat`.

## Layout

```
app/          Android UI + JNI adapter (`app/src/main/cpp/`)
native/       Pinned submodule: sempermechanics/semper-dic-engine (solver, tests, docs)
backend/      FastAPI on Cloud Run — routers in backend/app/routers/
firebase-hosting/  Auth continue URLs, asset links, generated legal pages
docs/         Human docs. This file is the agent map.
```

The engine is **not** in this repo. Bump it by changing the `native` gitlink.
Engine host / sanitizer / DICe suites run in the engine repo. This CI only proves
the pin still **links** (emulator x86_64, release arm64).

## Runtime

```
Splash → Auth / Pending / Home
Home → StaticAnalysisActivity (wizard) → ResultViewerActivity
     → open session → ResultViewerActivity | VsgLatticeActivity
```

Access routing is `AccessRouter` + `AccessStatus`. Intent extras are `DicKeys`.
Session dirs: `SessionStore` + `SessionPaths` (`raw_deformed/`, `frame_%04d.dat`).

| Package | Role |
|---------|------|
| `ui/analysis/` | Wizard, ROI, import, VSG sweep, batch run |
| `ui/viewer/` | Heatmaps, probe, `ShareCenter` |
| `ui/settings/` | `SettingsActivity` + `Settings*Section` |
| `ui/home/` | Session list |
| `ui/common/` | Insets, `MediaPickerSheet`, `CrispToast`, `TransferBannerController` |
| `data/` | Auth, session store, upload/restore/download workers, storage budget |
| `analytics/` | `SemperAnalytics` — consent-gated events, same flag as Crashlytics |
| `report/` | PDF / CSV / `VisualizationEngine` |
| `backend/app/main.py` | App, middleware, lifespan |
| `backend/app/routers/` | `/v1/*` by prefix: health, account, devices, sessions, files, provision_tasks, admin |
| `backend/app/session_provision.py` | `provision_session` / `purge_session` |

Kotlin helpers are plain `object` / small classes (`*Helper`, `*Runner`, `*Bundler`).
No Hilt/Dagger. Keep `lifecycleScope` and Activity Result launchers on the Activity.

Wizard later steps inflate through **ViewStubs**. `goToStep` stays on
`StaticAnalysisActivity`. Slot chrome / coach marks: `AnalysisWizardSlots` /
`AnalysisWizardCoach`.

Full-field batch: `DicBatchRunner` + `DicFieldIo` shared with VSG. JNI
`computeFullFieldDirect` stays **inside that one loop**.

New analysis starts in `MediaPickerSheet` — one sheet for the Home FAB and both
wizard dropzones (Images grid in-sheet, Files → SAF). Long transfers show a
non-modal `TransferBannerController` strip in Settings and the viewer; uploads,
restores, `DicBundleDownloadWorker` downloads and backup deletes are WorkManager.

## Invariants

- **Bit-exact fields.** Do not change `.dat` packing, ZNSSD threshold, or DatCodec
  oracles unless the engine contract major-bumps. GIF bytes are pinned 0-delta.
- **JNI buffer is bounded.** Allocate to the ROI grid; a point count over capacity
  is an engine failure, never a read past the buffer.
- **Do not split** VisualizationEngine loops, GifEncoder LZW, ReportBuilder fusion,
  `DicResult.decodeDatFile`, `DicUploadWorker.doWork`, `prefetchAround` /
  `ScrubFrameCache`, `PointSpatialIndex.build`.
- **Scrub cache** is byte-bounded and filled by **one** serialized worker.
- **Whole-batch** summary / spatial index start on demand, never on viewer open.
- **Batch progress** is a buffered `SharedFlow` (`DROP_OLDEST`), not a `StateFlow`.
- **Cancel sweep** abandons the sweep (check between combinations).
- **Drive unknown ≠ deleted.** Drop local metadata only when the backend confirms
  a blob is missing.
- **Storage reclaim** frees local frames of **backed-up** sessions only.
- **Analytics and crash reporting share one consent flag** (`DicSettings.diagnosticsEnabled`).
  Events stay PII-free — buckets and enums only, never images, results, session ids
  or specimen names.
- **Release** builds require HTTPS `INDIC_API_BASE_URL`. Debug emulator boots
  local-only unless `INDIC_DEV_AUTH_BYPASS=false`.
- **Legal pages** are generated: edit `docs/legal/`, run `scripts/render_legal_pages.py`,
  never hand-edit `firebase-hosting/public/{privacy,terms}/`.
- **Image installs `requirements.lock`** with `--require-hashes`. Bump txt and
  regenerate the lock on **Python 3.12**. CI checks versions, not just names.

## Quality gates

Empty `app/lint-baseline.xml` and `app/detekt-baseline.xml`. Prefer a targeted
`@file:Suppress` or an extract over stuffing a baseline.

Open lint warnings (leave them): `OldTargetApi` (`compileSdk` 37 / `targetSdk` 36),
`TooManyViews` on `activity_settings.xml`. Do not bump `targetSdk` or turn on
`warningsAsErrors` in a drive-by.

Kover `minBound` floor is 15. Macrobenchmark CI is emulator **smoke**
(`suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED`), API 34, no numeric thresholds.

Engine perf floor: [docs/engine/PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)
(≥ 4557 solves/s host). Preserve `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-08-20)

`origin/main` includes PRs #85–#96 (FAQ error map). Open follow-ups: wizard
step/overlap + step-2/3 reorder ([#97](https://github.com/sempermechanics/semperdic-app/pull/97));
campus/institution licensing on `feat/license-demo-pro` (branch pushed, PR to
be opened — see below). Refresh with `gh pr list --state open` — anything
named here will rot.

**Licensing (`feat/license-demo-pro`, backend-complete):** extends flat
Demo/Professional to two Professional shapes — individual (unchanged) and
campus/institution (seat-count license gated by verified-email domain,
self-service managed by institution IT via `backend/app/routers/campus.py`
only, no dashboard UI). `AdminLicenseCreate.kind` discriminates
individual/campus; campus seats live at `licenses/{id}/seats/{uid}`.
`POST /v1/licenses/activate` branches on kind and re-validates the device
lock on every authed call, not just at activation. Revoke semantics:
whole-key revoke drops every seat to Demo and frees all slots; single-seat
revoke frees only that slot; disable drops to Demo but keeps the slot held.
Downgrade never deletes data — it only blocks new analysis creation past the
cap, verified by a test that seeds 30 sessions, downgrades, and reactivates
with zero data loss. Campus IT routes authenticate on
`current_user` + APPROVED + verified email in that license's `adminEmails`
— deliberately no device attestation and not Semper `role=admin`; Semper
staff mint/revoke keeps the existing device-attested admin path. Backend:
268 tests passed, 81.99% coverage. Android: only data-layer plumbing shipped
this round (`ApiDtos`/`AppRemoteConfig`/`LicenseEntitlements.licenseKind`,
`IndicApi.activateLicense()`, plus tests) — the UI-layer gating
(`LicenseGate.kt`, `SettingsLicenseSection.kt`, Settings/Home/ShareCenter/
CloudSync wiring) is **not** implemented yet and is open follow-up work.
Docs: [docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md)
§20, [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md) §9,
[docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md), and
[docs/OPERATING_MANUAL.md](docs/OPERATING_MANUAL.md) Appendix D.

**Merged since 2026-08-08:** lint extracts #59–#64 and #66; compile/quality #68;
wizard slots/coach #69; `DicBatchRunner` + `DicFieldIo` #70; hashed lock /
Gradle 9.7 / docs #71; faster Release CI #74; API-34 emulators #75; brand/UX
polish #77; device-bound accounts + share logos #78; viewer chrome + media
picker #79; share caption / PDF thread #80; viewer Tufte restyle #81; FAB Files
→ SAF + splash #82; lattice viewer Tufte #83; workflows/docs recapture #84;
launcher icon contrast #85; viewer field FAB + vertical colour rail #86;
media-picker grid seam #87; wizard Paste params row #88; wizard warning FAQs
#91; engine failure reason #92; viewer probe / rest-fit #94; lattice Y readout /
mismatch names / picker dim #95.

New analysis picks media in-sheet (Images gallery; **Files** dismisses the sheet
and opens SAF). The reference picker opens full height and dims the grid for
1 s behind a large centred hint ("Select the reference image"); deformed
multi-select stays immediate. Wizard warnings (JPEG, low speckle, frame-size
mismatch, ROI too small, empty/too-big sweep plan) and remaining actionable
errors (engine failure dialog **Why?** plus a lasting ⓘ on the status line,
import / video, viewer batch/OOM/scale, lattice hollow nodes) link to Troubleshooting
sections on the public site behind a leave-the-app confirm
([docs/app/FAQ_LINKS.md](docs/app/FAQ_LINKS.md)). Frame-size copy names the
mismatched files. `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` are requested when
the gallery tab needs them. Summary GIFs bake `viewer_canvas` (night `#101518`)
into the cache filename. Launcher adaptive background is day `#F4F9FC` / night
`#101518`; night inverts the S on splash (`windowSplashScreenAnimatedIcon`).

Viewer chrome is inset-aware glass: heatmap rest-fit contains the ROI or
accepted points between the top bar and the scrub bar (colour scale overlays
the right edge), field switcher as a top-left glass pill with the live field
checked in its popup, auto-hiding on a timer, restored by a centre double-tap
when faded. A short tap anywhere on the figure probes. Settings' Analyses rows
carry three actions — **Download** (SAF destination first, then a worker),
**Restore** (only when local frames are gone) and **Delete**. The sweep
lattice's plot toggle is an **All / Node** pill defaulting to **All**; the scrub
readout shows x and y.

Docs for all of the above: [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md).

A single-setting run that solves zero points shows the same VSG-failure
wording as a sweep lattice node, not the generic "No data produced" dialog.

**Next architecture (grill before coding):** `DicKeys` extras bag packed in
`SessionOpenHelper.intentFor` and `AnalysisNavHelper.openResults` (~25 extras).
A deep `ViewerSession` module would be the one pack/unpack. Session **commit**
still sits on `AnalysisViewModel`'s public field bag after the JNI loop leaves.

**PRs target `main`.** Do not force-push `main`.
