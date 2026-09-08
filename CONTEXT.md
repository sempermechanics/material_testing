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
| `ui/capture/` | Home Record: setup, test shot, contrast ROI, AF lock, timed capture |
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

New analysis starts from the Home FAB menu: **Import** opens `MediaPickerSheet`
(shared with wizard dropzones); **Record** runs the capture flow in `ui/capture/`.
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
`targetSdk` 36→37 bump. Capture keeps portrait via `tools:ignore` on
`CaptureSessionActivity`. Do not re-enable those or turn on `warningsAsErrors`
in a drive-by. Settings / wizard settings XML stay under `TooManyViews` by
inflating through `SettingsScrollContentView` /
`WizardStepSettingsContentView`.

Kover `minBound` floor is 27. Macrobenchmark CI is emulator **smoke**
(`suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED`), API 34, no numeric thresholds.

Engine perf floor: [docs/engine/PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)
(≥ 4557 solves/s host). Preserve `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-09-01)

Open debt and improvements: [docs/ops/TECH_DEBT.md](docs/ops/TECH_DEBT.md),
[docs/ops/FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

Lint and detekt burn-down on this branch: empty baselines still; `:app:detekt`
and `:app:lintDebug` report zero findings. Settings / wizard settings content
inflates through `SettingsScrollContentView` /
`WizardStepSettingsContentView`. `OldTargetApi` stays disabled until a
deliberate targetSdk PR.

Home **+** expands to **Import** (existing `MediaPickerSheet`) or **Record**
(`ui/capture/`: setup → Camera-app test shot → contrast ROI → SSSIG gate →
hardware AF lock → user-confirmed focus → noise-floor burst → timed stills →
wizard via `PICKED_REF_URI` + `PICKED_DEF_URIS`). Stills only — the video path
is gone. Frames are lossless grayscale PNG written straight from the locked
session's `YUV_420_888` luma plane (`GrayPngEncoder`), never JPEG. The reference
is taken through the locked session too (`captureLockedReference`), at the run's
own resolution under the same frozen focus and exposure; the vendor Camera app's
test shot is only the fallback when that capture failed, and
`CaptureFrameSizeMatcher` is what makes the fallback usable.

The offered frame rates are a short list, not a free slider: `CaptureFrameCost`
takes the larger of the Camera2 sensor read-out floor
(`CameraCapabilities.sensorFloorMs`) and a real on-device PNG-encode timing
(`CaptureCalibration`, re-measured once after the test shot), and
`CapturePlanOptions` builds the chips from it with `ASSURANCE_MARGIN` on top so
every rate shown is one the run will actually deliver. `StillSequenceRunner`
schedules each frame from t0 rather than from its predecessor, so a slow frame
cannot walk the run off the end of the test window, and `CaptureWorkspace`
clears the previous run's frames so a shorter run cannot inherit the tail of a
longer one.

**Precision work on this branch.** A phone shooting a *static* specimen on a
tripod used to report Exx spanning 48 me — the vendor camera app's own
processing, not the engine, which is bit-exact against its oracles. Two device
runs (Samsung SM-G996U1, Pixel 6) now measure 8.7 me and 3.5 me on the same
kind of scene, with convergence up from 72.7% to 77.3% / 94.8%. What changed:

- `CaptureIspLock` freezes every ISP key the device *lists* — OIS, EIS, noise
  reduction, edge, tonemap, shading, aberration, scene/effect, zoom and AWB —
  and nothing it does not, so a LEGACY HAL gets the subset it honours rather
  than a rejected request that takes the session down. Every key is read back
  out of the `TotalCaptureResult`; what the HAL ignored is named once, effect
  first, with a FAQ link.
- `ExposurePlan` rounds the converged exposure **up** to a whole mains
  half-period (10 ms at 50 Hz, 8.333 ms at 60 Hz, 50 ms when the device will
  not say which — 50 ms is a whole number of both) and scales ISO down to
  hold brightness. Flicker stops moving the tone between frames.
- `NoiseFloorGate` takes up to 6 stills at the end of the test shot, on the
  run's own settings, with the specimen mounted and nothing loaded yet. Six
  is derived, not round: *n* frames give *n − 1* pairwise estimates, and a
  monotone run of *k* exchangeable estimates has probability `2/k!`, so the
  drift test is a coin flip at 3 estimates and usable at 5 — which is 6 frames.
  The verdict **warns and never blocks** — `Record anyway` is the primary
  action and the floor is stamped on the session and the PDF cover, because an
  override that leaves no trace is how a bad number becomes a published number.
  The share CSV opens with `#` metadata and per-frame field stats, then point
  rows with an optional recorded-session suffix (floor in mε plus scene-motion
  columns); imports omit those trailing columns. A clean pass is also a dialog:
  large **measurement floor** value + body, **Continue**, **ⓘ** → FAQ
  `#noise-floor` (does not dismiss) — not **Why?**, because the number is a fact.
  Fail / drift / unsettled use **Why?** the same way.
- **`strain_window` is a diameter in pixels**, not a multiple of the step
  (`VsgStudy.vsgFor`). The gate quoted floors 3-5x better than the settings
  could deliver until the device data caught it. `MAX_STRAIN_WINDOW` (101 px)
  is now the binding limit on how small a floor can honestly be reported.
- The subset recommendation solves against `D(eta)` measured on *this* phone
  under *this* light instead of the 2008 paper's lab camera — but only for a
  run this app captured, and `SubsetRecommender.thresholdFor` clamps so a
  measurement can only ever **raise** the threshold. The Pixel reported
  `D = 0.35` against the Samsung's 34 because a vendor denoiser was running
  underneath the frozen pipeline; believing it would have recommended a subset
  smaller than the paper's own default. `NoiseFloorPixels.noiseCorrelationOf`
  catches that case directly — sensor noise is white between neighbours, a
  denoiser is not — and warns.
- `RigidBodyFit` reports how much of each frame's displacement was the whole
  scene moving (both phones walked 1-2 px over 60 s, monotone, thermal). It is
  **reported and never subtracted**: uniform translation already cancels in
  strain, and a fit taken over the whole ROI would remove real deformation
  along with it.
- `CaptureGallerySave` puts a second copy of the as-captured frames in
  `Pictures/semper/<date>-<time>` so the raw measurement is reachable without this
  app. It never blocks the run and skips with one line when there is no room.

Shipped on this branch from that precision work: k-averaging on the reference
only ([AveragingPlan]; no precision-mode toggle), a catalogue that can offer
frames above 2048 px when RAM allows ([sustainableCeiling]), and picking the
longest rear lens by physical camera id ([pickBackCameraId]).

**Focus is confirmed by the user before anything is measured.** The lock now
stops at the live preview: a ring marks the focus point, a magnified unfiltered
crop of it sits beside a sharpness reading, a tap anywhere re-locks there, and
the burst does not run until the user accepts. Autofocus is weakest on fine
repeating texture and a speckle pattern is nothing else, so the point the
speckle check picked was a guess — and a soft reference sets a floor nothing
downstream recovers, since defocus blurs the very gradients the correlation is
built on. The step sits **after** the lock, not before the test shot as first
sketched: the test shot is a vendor-camera-app intent that runs its own AF, so
there is no lock to carry into it, and only the locked preview shows the run's
own frame. It moves earlier when the test shot moves onto the locked session.
Supporting pieces: `PreviewMap` owns the buffer / view / upright-fraction
geometry as one invertible map (a tap and the ring it draws must be exact
inverses); `FocusSharpness` is mean squared gradient over variance, so the
reading tracks SSSIG and a dim patch does not read as a soft one; and
`FramingWatch` holds the gravity direction from **Start recording** onward and
withdraws Start on a sustained re-aim, because the frozen focus and the measured
floor both describe the framing they were taken in and nothing re-checks either.

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

Licensing lives on `feat/license-demo-pro`
([#100](https://github.com/sempermechanics/semperdic-app/pull/100)), summarised
below. It is a long branch: the sections are in the order they were built, so
later ones correct earlier ones where they disagree.

**Licensing ([#100](https://github.com/sempermechanics/semperdic-app/pull/100), `feat/license-demo-pro`):** extends flat
demo/licensed to two licensed shapes — individual (unchanged) and
institution (seat-count license gated by verified-email domain,
self-service managed by institution IT via `backend/app/routers/institutions.py`
only, no dashboard UI). `AdminLicenseCreate.kind` discriminates
individual/institution; institution seats live at `licenses/{id}/seats/{uid}`.
`POST /v1/licenses/activate` branches on kind and re-validates the device
lock on every authed call, not just at activation. Revoke semantics:
whole-key revoke drops every seat to Demo and frees all slots; single-seat
revoke frees only that slot; disable drops to Demo but keeps the slot held.
Downgrade never deletes data — it only blocks new analysis creation past the
cap, verified by a test that seeds 30 sessions, downgrades, and reactivates
with zero data loss. Institution IT routes authenticate on
`current_user` + APPROVED + verified email in that license's `adminEmails`
— deliberately no device attestation and not Semper `role=admin`; Semper
staff mint/revoke keeps the existing device-attested admin path. Backend:
324 tests passed, 82.72% coverage. Android: only data-layer plumbing shipped
this round (`ApiDtos`/`AppRemoteConfig`/`LicenseEntitlements.licenseKind`,
`IndicApi.activateLicense()`, plus tests) — the UI-layer gating
(`LicenseGate.kt`, `SettingsLicenseSection.kt`, Settings/Home/ShareCenter/
CloudSync wiring) is **not** implemented yet and is open follow-up work.

**Vocabulary rename (same branch):** the wire said `campus` and
`plan: demo|professional`; it now says `institution` and
`mode: demo|licensed`. `SCHEMA_VERSION` is 2, migrated by
`backend/scripts/migrations/002_rename_campus_to_institution.py`, which also
brings `licenses` into the migration chain for the first time (001's
collection list omits it). Every skew direction has a fallback and all of them
are temporary — `/v1/config` carries a `plan` mirror alongside `mode`,
`/v1/campus/*` stays routed as a hidden alias (declared in
`backend/gateway/openapi.yaml` too, or ESPv2 would reject it), the app reads
the old pref key on upgrade, and `AdminLicenseCreate` still accepts
`kind="campus"`. Retirement order and rationale: CLOUD_ARCHITECTURE_GCP §20.5.
Licenses are **still keyed by the sha256 of their key**; moving to opaque ids
is deferred to the change that needs it (a license with no key at all).

**Duration & grace (same branch):** a license is explicitly `perpetual` or
`timed`, validated at mint so neither shape happens by accident. A timed one
keeps **full** entitlements for `graceDays` past `expiresAt` — grace is inside
the licensed branch, not a reduced tier — so a renewal in flight does not
interrupt work. A license already stored with no `graceDays` reads as ZERO, not
the fleet default, or deploying this would have reinstated everyone who expired
inside the window. `duration` absent is inferred from `expiresAt`, so no
migration was needed and `SCHEMA_VERSION` stays 2.
`PATCH /v1/admin/licenses/{id}` renews in place and **fans the new terms out**
to the individual redeemer or every non-revoked seat — the terms are mirrored
onto each user at activation to keep `resolve_user_config` free of Firestore
reads, so editing the license alone reaches nobody. Activating a key past grace
is now refused (`license_expired`, 403) instead of silently landing the user on
Demo. `/v1/me` carries a license summary; the app shows a Home notice inside 14
days of expiry or during grace, suppressed when its cached config is over a
week old — advisory only, `mode` is still the only gate.

**Floating seats (same branch, backend only):** `seating: assigned | floating`
splits the roster from the count. A floating license's `maxSeats` caps
*concurrent* leases while the roster stays uncapped — fifty people sharing ten
slots — and a member between leases is demo, the ordinary state rather than a
failure. `assigned` is the default and is what every existing license already
means, so again **no migration**. The lease lives on the seat document, not a
`leases` collection: `check_device_lock` already reads that document every
institution request, and its expiry is mirrored onto the user so
`effective_mode` stays free of Firestore. Joining changed shape — IT adds
members by email via `POST /v1/institutions/licenses/{id}/seats`, no key
typing; the person just has to have signed in once for demo.
Re-checkout is the heartbeat (8h lease, 30min renew) and is deliberately
unaudited. **This also fixed a pre-existing race**: seat claim was a
read-then-`WriteBatch`, so two concurrent activations could push a pool past
`maxSeats`; claim/revoke/checkout/release now run under real transactions that
fail closed. Android demo gating is the next PR.

**Android seat gate + web consoles (same branch):** `seatRequiredToStart` is a
**parallel** predicate to the quota gate — an institution member is licensed,
so `isSessionLimitReached` is false for them by definition and they would sail
past every existing check. It gates the Home FAB *before* the Import/Record
menu opens, so both capture and import are covered, plus both compute paths —
which is what catches a re-capture started from inside an analysis;
`wouldCreateNewSession()` guards it, so a run in flight never aborts.
`SeatRequiredActivity` is one button that asks again, not an email-support
screen: seats free themselves.
Two static consoles under `firebase-hosting/public/console/` (no build step).
`/console/institution` is fully functional because `institution_admin_context`
is token-only by design. `/console/operator` is **read-only** — every mutating
`/v1/admin/*` route needs `verified_device`, which a browser cannot produce,
and that is the control working rather than a gap. CSP is widened for
`/console/**` alone, `connect-src` only.

Docs: [docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md)
§20, [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md) §9,
[docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md),
[docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md) §3.1, and
[docs/OPERATING_MANUAL.md](docs/OPERATING_MANUAL.md) Appendix D.

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
multi-select stays immediate. Wizard warnings (JPEG, low speckle, frame-size
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
when faded. A short tap anywhere on the figure probes. Settings' Analyses rows
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
