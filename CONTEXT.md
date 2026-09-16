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

New analysis starts from the Home **+**, which opens `MediaPickerSheet`
(shared with the wizard dropzones).
Long transfers show a
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

`OldTargetApi` is disabled in `app/build.gradle.kts` until a deliberate
`targetSdk` 36→37 bump. Do not re-enable that or turn on `warningsAsErrors`
in a drive-by. Settings / wizard settings XML stay under `TooManyViews` by
inflating through `SettingsScrollContentView` /
`WizardStepSettingsContentView`.

Kover `minBound` floor is 27. Macrobenchmark CI is emulator **smoke**
(`suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED`), API 34, no numeric thresholds.

Engine perf floor: [docs/engine/PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)
(≥ 4557 solves/s host). Preserve `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-09-15)

Open debt and improvements: [docs/ops/TECH_DEBT.md](docs/ops/TECH_DEBT.md),
[docs/ops/FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

**Branch `feat/legal-terms-clickwrap`** (off `main` 1aeea8a): the Terms of
Service were rewritten from the owner's side (IP and anti-reverse-engineering,
acceptable use, AS-IS warranty disclaimer, capped liability, indemnity, sole
discretion termination, Indian law with Chennai arbitration and a class waiver,
professional use only, not offered in the EU/UK) and the Privacy Policy brought
to a GDPR-grade standard without EU/UK-specific commitments (grievance officer,
consent-based product improvement, transfers, breach handling, full rights
list). Hosted pages regenerated. Acceptance is now a **clickwrap**:
`AccessRouter.intentFor` sends every sign-in through `TermsActivity` until the
device has accepted the version in force; the backend records it
(`POST /v1/me/terms`, 409 on a stale app) and a separate, pre-ticked-but-declinable, withdrawable
improvement consent (`PUT /v1/me/consents`, Settings → Your data). Details in
[docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md) §3a. Bracketed operator
fields in both documents still have to be filled before public distribution.

Lint and detekt burn-down on this branch: empty baselines still; `:app:detekt`
and `:app:lintDebug` report zero findings. Settings / wizard settings content
inflates through `SettingsScrollContentView` /
`WizardStepSettingsContentView`. `OldTargetApi` stays disabled until a
deliberate targetSdk PR.

**The in-app camera recording feature has been removed.** Home **+** now opens
the import source chooser directly; there is one acquisition path. Deleted with
it: `ui/capture/` in full, the `CAMERA` permission and camera `<queries>`, the
two capture activities, and the measured **capture noise floor** end to end —
the stored `SessionRecord.captureFloor`, its viewer caption, its CSV preamble
line and its PDF cover section.

Two consequences worth knowing:

- **The CSV's rigid-body motion columns survived and became unconditional.**
  `shift_u_px, shift_v_px, shift_rot_deg` used to be gated on a session having a
  measured floor, which tied an export column to the camera by accident. The fit
  is read off the solved field itself (`RigidBodyFit`), so every session now
  carries the columns; a frame that admits no fit writes three empty ones.
- **`SubsetRecommender` is back on the paper's constant.** The measured
  `D(eta)` came from the capture burst and no import can supply it. Since
  `thresholdFor` clamps to `max(measured, NOISE_VARIANCE)`, recommendations can
  only loosen slightly, and only relative to runs this app captured itself.

Upgrade safety is pinned by `SessionStoreLegacyFloorTest`: `SessionStore`'s
parser is built with `ignoreUnknownKeys`, so an `index.json` written before the
removal — still carrying its `captureFloor` object — loads unchanged. No
migration.

`NoiseFloorPixels` / `NoiseFloorProbe` / `NoiseFloorStats` stay in
`ui/analysis/`: they take plain arrays or raw image bytes, so they judge an
imported frame as readily as a captured one.

`SpeckleScale` and `DicGoodPractice` came the same way and now have a caller.
`SubsetRecommender.recommend` measures the speckle diameter by autocorrelation on
the very patches it already reads for SSSIG — no extra decode — and returns the
median as `Result.speckleDiameterPx`. The wizard's first step reports it against
the iDICs 3-9 px band: a muted readout under the subset slider whenever it is
measurable, and a warning chip when the pattern is under- or over-resolved, or
when the recommended subset would not span three dots.

The two speckle chips answer different questions and neither substitutes for the
other. SSSIG is a *sum* of gradients over a subset, so it clears its threshold on
a pattern far too fine to resolve simply by growing the subset; speckle size is
what the guidance is actually written against, and what the user can fix at the
bench. The size measurement only reports — it does not steer the recommended
subset, which stays the SSSIG answer.


`RawRgba` closed the DNG-in-`RoiDrawActivity` gap:
one shared helper detects a `w*h*4` blob and samples straight into a
preview-sized bitmap, so the full-resolution allocation never happens.

Import measures a frame the way the engine will see it: `ExifOrientedSize`
applies the EXIF orientation tag to `BitmapFactory`'s bounds, because OpenCV's
`imdecode` rotates and `BitmapFactory` does not. Without it one portrait photo
picked as both reference and deformed frame reported a size mismatch against
itself (4080×3072 vs 3072×4080).

A sweep now runs its whole plan. `ConvergenceGate` is the batch path only:
a batch's consecutive solves are successive frames, so decorrelation means
every later frame is worse, but a sweep's are parameter combinations on one
frame pair, ordered smallest subset first — exactly the ones most likely to
under-converge. The gate was killing sweeps in their opening combinations.

The wizard's inline format warning names whichever formats in the set are not
lossless (`LossyFormatCheck`), reference included, instead of only saying
"JPEG".

**Security pass on this branch.** `_emails_conflict` fails closed, so a token
with no email cannot adopt a device-bound account, and adoption additionally
requires `email_verified`. First-sign-in profile creation uses `create()`
rather than `set()`, so a launch race cannot reset an approved profile to
PENDING. The device signature covers the query string when a request has one.
`AuthActivity` checks arriving auth links against `AUTH_HOST` before handing an
`oobCode` to Firebase — the activity is exported, so an explicit intent
bypasses the App Link filter. The ROI mask read and the RAW reference copy in
`StaticAnalysisActivity` moved off the main thread.

The Demo/Professional plan and license-key work that was mixed into this
working tree belongs to **`feat/license-demo-pro`** and was moved there: no
`plan`, entitlement flags, or license fields are part of the user account
definition on this branch.

`origin/main` includes PRs #85–#96 (FAQ error map), the Dependabot batch
#102–#104, and #93 (Tier 3 microbenchmark skip + hashed lock). Open follow-up:
wizard step/overlap + step-2/3 reorder
([#97](https://github.com/sempermechanics/semperdic-app/pull/97)).
Refresh with `gh pr list --state open` — anything named here will rot.

**Backend lock regeneration is a CI workflow now, not a local chore**
([#105](https://github.com/sempermechanics/semperdic-app/pull/105)). Dependabot
bumps `backend/requirements.txt` and cannot produce the hashed
`requirements.lock`, so every backend bump used to land Tier 4 red — #103 left
`main` red for exactly that reason. `.github/workflows/backend-lock.yml`
compiles on Linux / Python 3.12, verifies on any PR touching either file
(attaching the regenerated lock as an artifact), and pushes on
`workflow_dispatch` against a chosen branch. See
[docs/ops/CI.md](docs/ops/CI.md) § Dependabot cheap path.

**Merged since 2026-08-08:** lint extracts #59–#64 and #66; compile/quality #68;
wizard slots/coach #69; `DicBatchRunner` + `DicFieldIo` #70; hashed lock /
Gradle 9.7 / docs #71; faster Release CI #74; API-34 emulators #75; brand/UX
polish #77; device-bound accounts + share logos #78; viewer chrome + media
picker #79; share caption / PDF thread #80; viewer Tufte restyle #81; FAB Files
→ SAF + splash #82; lattice viewer Tufte #83; workflows/docs recapture #84;
launcher icon contrast #85; viewer field FAB + vertical colour rail #86;
media-picker grid seam #87; wizard Paste params row #88; wizard warning FAQs
#91; engine failure reason #92; viewer probe / rest-fit #94; lattice Y readout /
mismatch names / picker dim #95; Tier 3 microbenchmark skip #93; Dependabot
actions / backend / gradle #102–#104.

New analysis picks media in-sheet (Images gallery; **Files** dismisses the sheet
and opens SAF). The reference picker opens full height and dims the grid for
1 s behind a large centred hint ("Select the reference image"); deformed
multi-select stays immediate. Wizard warnings (JPEG, low speckle, speckle size,
frame-size
mismatch, ROI too small, empty/too-big sweep plan) and remaining actionable
errors (engine failure dialog **Why?** plus a lasting ⓘ on the status line,
import / video, viewer batch/OOM/scale, lattice hollow nodes) link to Troubleshooting
sections on the public site behind a leave-the-app confirm; canonical copy in
[docs/app/FAQ.md](docs/app/FAQ.md) with map in
[docs/app/FAQ_LINKS.md](docs/app/FAQ_LINKS.md). Frame-size copy names the
mismatched files. `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` are requested when
the gallery tab needs them. Summary GIFs bake `viewer_canvas` (night `#101518`)
into the cache filename. Summary overview and the Animations share target are
single-setting only; a parameter sweep has neither. Launcher adaptive background is day `#F4F9FC` / night
`#101518`; night inverts the S on splash (`windowSplashScreenAnimatedIcon`).

Viewer chrome is inset-aware glass: heatmap rest-fit contains the ROI or
accepted points between the top bar and the scrub bar (colour scale overlays
the right edge), field switcher as a top-left glass pill with the live field
checked in its popup, auto-hiding on a timer, restored by a centre double-tap
when faded. A short tap anywhere on the figure probes. On a still frame the
ⓘ sheet quotes true min/max/mean and a histogram of accepted values. The
looping summary GIF uses one colour scale from every frame's trimmed ends
(lowest min, highest max, possibly from different frames); its ⓘ sheet
quotes those same ends without mean or histogram. Settings' Analyses rows
carry three actions — **Download** (SAF destination first, then a worker),
**Restore** (only when local frames are gone) and **Delete**. The sweep
lattice's plot toggle is an **All / Node** pill defaulting to **All**; the scrub
readout shows x and y.

Docs for all of the above: [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md) (the
manual test pass). Every workflow — app, background and backend — mapped to its
files, failure surfaces and tests: [docs/WORKFLOWS.md](docs/WORKFLOWS.md);
proposals coming out of that map: [docs/ops/FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

A single-setting run that solves zero points shows the same VSG-failure
wording as a sweep lattice node, not the generic "No data produced" dialog.

**Next architecture (grill before coding):** `DicKeys` extras bag packed in
`SessionOpenHelper.intentFor` and `AnalysisNavHelper.openResults` (~25 extras).
A deep `ViewerSession` module would be the one pack/unpack. Session **commit**
still sits on `AnalysisViewModel`'s public field bag after the JNI loop leaves.

**PRs target `main`.** Do not force-push `main`.
