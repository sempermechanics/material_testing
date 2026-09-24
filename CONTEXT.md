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

**Who this repo is for: a first-semester engineering undergraduate** running the
two standard lab experiments with a phone camera in place of the contact
instrument, then handing in the journal write-up. **Tensile** (UTM, round bar):
stress–strain curve and Young's modulus E. **Bending** (simply supported beam,
central hanger load): bending stress σb = M·y/I and E from the deflection,
E = WL³/(48δI). The app's outputs follow the student's handwritten reports
section by section; spec, formulas and worked numbers in
[docs/app/STUDENT_LAB_WORKFLOW.md](docs/app/STUDENT_LAB_WORKFLOW.md). Write copy
for that reader: plain words, formulas spelled out, results the write-up asks
for.

## Domain terms

Use these words. Do not invent synonyms.

| Term | Meaning |
|------|---------|
| subset | Odd-width pixel window tracked around its center (default ~41 px) |
| step | Grid spacing between tracked points, px |
| ZNSSD | Match score; 0 = perfect, ≤ 0.15 accepted, < 0 failed-point sentinel |
| ICGN | Iterative Gauss-Newton sub-pixel solver |
| VSG | Strain window: least-squares plane fit over a circle whose diameter is the window, in px (odd). VSG = window, not `(window − 1) × step + 1` (`VsgStudy.vsgFor`) |
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
Home → TestTypeSheet → StaticAnalysisActivity (wizard) → ResultViewerActivity
     → open session → ResultViewerActivity | VsgLatticeActivity
```

The test type (`data/TestType`: tensile / bending) is chosen on Home and rides `DicKeys.TEST_TYPE` into the wizard; the run commits
it, the specimen dimensions and the per-frame machine loads through
`MechanicalTestInputs` → `SessionRecord` → `metadata.json` schema `/5`.
Results are pure objects in `report/`: `StressStrain` (the curve), `ElasticModulus`
(tensile E, via `LinearFit`) and `LabReport` (the student write-up), drawn by the
viewer ⓘ, `AnalysisCsvWriter` and `LabReportPdf` / `PdfReportGenerator`.

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

## Current state (2026-09-24)

**Student lab outputs, part 1: tensile E and the lab report
(PR #11, `feat/tensile-modulus`, merged).** The repo's audience is now a first-semester
student (Product, above). `report/ElasticModulus` fits Young's modulus to the
longest leading run of frames before the peak whose line keeps R² ≥ 0.995,
free intercept, reference left out (a preloaded gauge otherwise reads 218 GPa
for 194). **Results** — the curve with its fitted line, E with the frames it
used, and peak stress — replaces the heatmap loop in the viewer's summary
slot (the page it opens on) for any session with loads, and closes the ⓘ
sheet; both surfaces share one curve build (`ViewerStressStrainHelper.fill`).
PDF plots render with the day palette (`printContext`) so dark mode does not
print pale axes. **Share → Lab report
(PDF)** (`LabReport` → `LabReportPdf`, offered for tensile sessions with
loads) lays the session out as the handwritten Experiment 2 write-up: same
sections and order, row 1 worked through, Elastic / Plastic / Break point
margin brackets, blank lines for what the app cannot know (diameter, gauge
length, final dimensions) and for the student's conclusion. Extension is in
pixels along the strain axis, from a virtual extensometer over the ends of the
analysed region (`report/Extensometer`, gauge fixed on the first frame).
Its fixed wording is `LabReportText`, English like the rest of the PDF. The
CSV ends with a `# mechanical_results` trailer (still version 2). Loads stay
CSV-only; typing a load per photo and the photo↔load sync are open
decisions. Next: bending deflection — a thickness tap on the reference photo
sets mm/px and marks the load point, δ comes from V there, and E =
WL³/(48δI) per step, averaged and from the load–deflection slope, with a
bending lab report (done in PR #12, below).

**Student lab outputs, part 2: bending deflection and real-data validation
(PR #12, `feat/bending-deflection`, merged).** Bending gets a thickness tap on
the reference photo (`BeamEdgeTapActivity`, stored as
`SpecimenGeometry.loadPoint`, metadata schema `/6`) that sets mm/px and marks
the load point; `report/BeamDeflection` reads δ there and gives σb and
E = WL³/(48δI) per step, averaged and from the load–deflection slope, in the
viewer, the CSV trailer and a bending lab report (`LabReportBending`). A
published steel tensile test (Zenodo 18311953) run through the app
([docs/app/REAL_WORLD_VALIDATION.md](docs/app/REAL_WORLD_VALIDATION.md)) first
read "E not found": `ElasticModulus` stopped at the first run failing R², and
the first three photos, at 6–17 MPa, are noise. It now keeps the longest
leading run that passes (149.6 GPa against the dataset's own 157.5; pinned in
`RealSteelModulusTest`). Bending was run end to end on a synthetic video
built from the Experiment 5 table (`scripts/synthetic_beam_video.py`); the app
matches the report (E from the graph 141.5 vs 142.0 GPa, average 173.1 vs
173.6). That run fixed five things: the tap editor lost its zoom after the
first tap (`TouchImageView` now keeps zoom through a resize); unloaded frames
bent the slope (left out now); a video gave one table row per frame (frames at
one load are now one load step); the speckle check sampled outside the ROI
(clamped); and a load log started after the recording could not be aligned
(new *Log started after the first frame* offset, `LoadSyncRow`). The
real-image check came later (case 2, below).

**Student lab outputs, part 3: the elastic region in the viewer
(PR #14, `feat/viewer-elastic-plot`, merged).**
On the real steel run the Results curve reaches 336 mε. E comes from frames
1–26, all below 2 mε, so those frames and the fit line were a vertical stroke
at x≈0. The Results title on the summary page and in the ⓘ sheet now has a
**Whole test / Elastic region** toggle. It shows only when there is a tensile
fit. `report/ElasticRegion` zooms to the fitted frames plus half their strain
span, starting from the origin. It drops frames after the peak and draws the
fit line across the whole window, so the student sees the curve leave it. On
the steel run that is frames 1–28 up to 1.94 mε, pinned in
`RealSteelModulusTest`. The choice lives in `ResultViewerViewModel`, so both
surfaces keep it. It is the viewer's counterpart to the lab report's
`GRAPH_ELASTIC`, which is unchanged. WORKFLOWS §8.4.14d–f and §8.2a.10a.

**Tap editor crosshairs (PR #17, `feat/tap-crosshair`, merged).** In `BeamEdgeTapActivity`
each mark now draws crosshair lines across the whole photo, so the horizontal
line can be laid along the beam's edge. The bottom mark is held to the top
mark's x (`BeamTapPlacement`): the second tap only sets its height, so the
thickness is measured straight down however the finger slips sideways. Moving
the top moves the bottom's line with it. Taps saved before this with
different x's load as they were and line up on the next edit. WORKFLOWS
§6a.3–6a.4.

**Bending on real images (`feat/bending-real-validation`).** A published PMMA
3-point bend (Zenodo 1172068) was run through the app and compared point by
point with the authors' own DIC
([REAL_WORLD_VALIDATION.md](docs/app/REAL_WORLD_VALIDATION.md), case 2;
`scripts/real_data_pmma_bending.py`). Results:
- Deflection at the load point: 0.0074 mm RMS over 33 frames (0.5% of the
  peak).
- E from the graph 2.000 GPa against the authors' 1.982; average 2.104
  against 2.084. Pinned in `RealPmmaBendingTest`.
- Displacement: 0.013 px RMS.
- Strain: about 1,000 µε RMS at a 15 px strain window, about 390 µε at 45 px.

The run changed three things:
- **The tap editor's photo no longer moves between taps.** #17's longer
  wording broke the fixed three-line instruction, so the photo re-fitted after
  the first tap and a bottom tap aimed at the old position landed 49 px short
  (E 9% low). The instruction now sits over invisible copies of all three steps
  (`BeamTapStepText`), so its box is as tall as the longest at any width or
  font size.
- **Bending's strain window starts at 45 px** (`TestType.defaultStrainWindow`;
  tensile stays 15), on first open and on Reset. δ and E use displacement
  only. The cost is a band of about 20 px at the ROI edges with no strain,
  which also lowers the headline "converged" figure (97.6% → 92.8% here).
- **Found, not fixed:** 0.02% of points are wrong matches with good ZNSSD,
  under the nose and at the bottom fibre late in the test. The engine's VSG
  takes them in, and local strain spikes to about 2,400,000 µε in 6 of 33
  frames. That is a `native/` change.

**VSG = strain window in the docs (PR #19, `docs/vsg-is-strain-window`, merged).** The
engine reads `strain_window` as a circle's diameter in px, so the gauge is the
window itself (`VsgStudy.vsgFor`, two device runs in its KDoc). The operating
manual, WORKFLOWS §8.4.3, `docs/images/vsg.svg`, the glossaries and the
strain-window ⓘ still said `(window − 1) × step + 1`, or counted points. They
now match the code. The engine-vsg FAQ no longer says to coarsen the step: a
window under twice the step leaves fewer than 3 points in the circle. Docs
and one string only; no behaviour change.

**Docs refresh with new screenshots (PR #12).** Every app screenshot in
`docs/images/` was recaptured on 2026-09-23 in light theme, from the tensile
and bending validation runs. The bending run on the new build reads 142.0 GPa
from the graph and 173.7 GPa average. The README and the operating manual are
now image-first:
- the manual gains **§2 Lab tests**, and its user sections are about half as long;
- `STUDENT_LAB_WORKFLOW.md` is tightened;
- lab-report page strips were added;
- four unreferenced images were removed ([docs/images/CAPTURE_CHECKLIST.md](docs/images/CAPTURE_CHECKLIST.md)).

**Key frames, speckle and plot fixes (PR #12).**
- **Key frames:** the video sheet (`VideoSamplingSheet`) now offers
  **Frame rate / Key frames**. Key frames come from `VideoKeyframes` (AVI
  `idx1` flags, else `MediaExtractor` sync samples) and are thinned evenly to
  *Max frames* by `VideoSampling.keyframeTimesMs`. `VideoFrameExtractor` takes
  a list of sample times, so both plans share every rung. Times round to the
  nearest µs, and `AviReader.frameIndexAt` allows half a µs, so a key frame is
  never decoded as the frame before it. The synthetic beam clip gives 29 key
  frames, all distinct.
- **Speckle before an ROI:** the whole-frame reading was ~100 px on the
  steel set, because the median counted patches straddling the bar's edge.
  Only patches with at least 2/3 of the strongest patch's gradient energy
  count now (`SubsetRecommender.texturedSpeckleMedian`); it reads 4.8 px.
- **Plot titles:** `VsgPlotView` sizes its left gutter from the widest tick
  label, so the rotated y title no longer runs over the tick numbers in the
  lab-report graphs.
- `step3-sweep.png` and `result-lattice.png` were recaptured, and so were the
  lab-report strips.

**Loads match by time only, within 100 ms (PR #12; owner decision, 2026-09-24).** A
timed log with video frames is always matched by time, even when its row count
equals the frame count. A frame takes the nearest row only when it is within
`MachineLoadMapper.MATCH_TOLERANCE_MS` (100 ms); otherwise its load is NaN.
NaN means no load: the frame is left off the curve and out of the CSV load
column, it is `null` in `index.json` and it has no `loadN` in `metadata.json`.
A restore keeps it in place. If no frame matches, the gate blocks Next. Photos
have no times, so they are still paired in order or resampled.

**Terms: back to 18+ (owner decision, 2026-09-24).** The 16+ change (#12,
deployed from `semperdic-app` #169 as version `2026-09-24`) is reverted: §1.3
again requires users to be 18 or over (or the local age of majority), and the
Terms, Privacy Policy, hosted pages, `backend/app/legal.py` and `LegalTerms.kt`
are back to version `2026-09-15`, byte for byte. Users who accepted
`2026-09-15` stay accepted; only those who accepted `2026-09-24` while it was
live re-accept. The backend and hosted pages deploy from `semperdic-app`, so the
revert lands there too; keep both repos' `TERMS_VERSION` equal. Still open for
the owner: §1.2 (professional use only) and §1.3 (18+) sit badly with a
first-semester student audience.

**MP4 frames decode forward (`fix/mp4-decode-forward`, on top of
`fix/video-estimate-snackbar`).** MP4 extraction asked the retriever for the
*sync* frame nearest each sample, so every sample within a GOP came back as
the same I-frame; a phone clip gave 26 identical frames and zero displacement.
Ported the parent repo's `HardwareVideoDecoder` (from its 571a82c, 40bab6a and
151a8ba): `MediaExtractor` + `MediaCodec`, lossless Y plane, decoding forward
to the requested frame, then the retriever as a last resort, now asking for the
exact frame first. The parent's **Keyframes (DIC)** sampling mode was left
out of that fix and has since been ported (see *Key frames, speckle and plot fixes* below). On the emulator the
real clip now yields frames that match the source, and
`VideoFrameExtractionDeviceTest` passes 6/6. Not yet tried on a physical
device's vendor decoder (§5.1a.12).

**Video sampling and long snackbars (`fix/video-estimate-snackbar`).** The
sampling sheet promised one frame more than extraction delivered whenever the
segment reached the clip's end, because it sampled at the end itself, where
no frame starts. `ui/analysis/VideoSampling` now holds the sample instants
for the estimate and both extraction paths, and stops at the last frame's
start. `FaqRedirect.snackbar` shows up to five lines and stays up as long as
its message takes to read, capped at 10 s.

**Video import reads AVI (PR #8, `feat/avi-video-import`).** Ported from the
parent repo's #139, so the two apps read the same files. `AviReader` demuxes
RIFF, `AviLuma` reads the uncompressed layouts losslessly, `MjpegHuffman`
repairs tableless motion-JPEG frames and `AviCodecDecoder` hands Xvid/H.264
samples to the platform codecs — no new dependency, no APK growth. A codec the
device cannot decode is named in the error instead of failing blank. Untried on
a real AVI on a device: [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md) §5.1a
rows 7-9.

**This is `material_testing`, pushed from `semperdic-app` `main` at `bfe00e5` on
2026-09-21.** Same `applicationId`, Firebase app and backend as the parent; only
`app_name` ("Material Testing"), `rootProject.name` and the README changed. The
parent repo owns backend deploys. Scope of this repo: a test-type chooser
(Tensile / Bending) ahead of the wizard, machine-load CSV import with the
specimen dimensions each test's stress needs, and stress–strain
outputs in the viewer ⓘ sheet, CSV and PDF. Everything below this paragraph was
written in the parent repo and still applies. PRs #1, #3–#12 are
merged, and the repo is public with a `main` ruleset requiring `CI OK` and a
pull request.

**Test type (PR #5, `feat/test-type`; first opened as #2).** `TestTypeSheet`
sits between the Home **+** and the media picker. The choice is stored inline
on `SessionRecord` (`testType`, `crossSectionMm2`, `loadAxisX`, `loadsN`,
`loadSource`, `loadMapping` — all defaulted, so pre-fork indexes load
unchanged) and shipped in `metadata.json` as schema `/4` (`test` object +
`frames[i].loadN`), which `CloudRestore` reads back. Backend and DTOs are
untouched. This PR recorded the type only; the inputs each test needs came
with PRs #3 / #4 (tensile) and `feat/bending-torsion-inputs`.

**Machine load import (PR #3, `feat/load-csv`).** Step 1 gains a load card:
`AnalysisLoadCard` (ViewStub `stubLoadCard`) with
an `OpenDocument` launcher on the Activity, 8 MB cap, UTF-8 / Latin-1.
`data/MachineLoadCsv` infers delimiter, decimal comma, header, units row, and
the load / time columns; `data/MachineLoadMapper` matches rows to frames
(one-to-one, drop-first, nearest-time for video via the new
`AnalysisViewModel.defFrameTimesMs`, else linear resample) and keeps the sign.
Loads + dimensions are **mandatory** (`AnalysisReadyGate`). The parsed log is
cached on the ViewModel and re-matched from `checkReady()` whenever the frames
change.

**Stress–strain outputs (PR #4, `feat/stress-strain`).** `report/StressStrain`
is the one model (stress in MPa, strain = mean strain over accepted points in
mε, signed as logged). Viewer ⓘ: `ViewerStressStrainHelper` adds the
dimension / load / stress rows and the curve, built on first sheet
open and cached in `ResultViewerViewModel` (since the tensile PR the summary
slot's Results builds it on viewer open instead). CSV is
`semper_csv_version,2`: `load_N,stress_MPa` trail every point row (empty
without a log), typed sessions add `# test_type` / `# cross_section_mm2` /
`# load_axis` / `# load_unit`. PDF: `ReportData.mechanical` draws a
**Mechanical Test** cover block; `generateBatch(stressStrain = …)` closes the
all-frames report with the curve (rendered off screen by `ShareCenter`) and a
per-frame table. `.dat` is untouched.

**Bending inputs (PR #7, `feat/bending-torsion-inputs`).** Both tests import a load
log; what differs is how the load becomes a stress, which is a
`StressStrain.Model`: `Axial` (σ = P/A, strain along the load axis) for tensile
and `Flexural` (three-point, σ = 3PL/2bh² from span / width / thickness) for
bending. Bending's dimensions live in `data/SpecimenGeometry` — one defaulted
field per dimension, carried inline on `SessionRecord`, as a `FloatArray` extra
(`DicKeys.SPECIMEN_GEOMETRY`) and as `test.geometry` in `metadata.json` schema
`/5` (entered dimensions only, so a tensile backup is byte-identical to `/4`).
The wizard reuses the load card: `ui/analysis/SpecimenGeometryFields` shows the
rows the chosen model needs (`row_specimen_dimension` includes), the ready gate
turns on `Model.isComplete`, and the ⓘ dialog gives that test's formula. The
model also names its own rows, axes and CSV keys, so the viewer sheet, the CSV
preamble (`# stress_model` plus that model's dimensions) and the PDF cover /
curve page read "Flexural stress" without a second code path.

**Tensile and bending only.** The chooser offered four tests while the branch
was being built; the app ships **Tensile** and **Bending**. Compression and
torsion, and the torsion stress model, dimensions and strings, are gone from
`TestType`, `SpecimenGeometry` and `StressStrain` — recoverable at `45d2310`
if they are wanted back. The two wire names stay unclaimed, so a session an
earlier build stored as `compression` or `torsion` reads as **no type** and
falls back to the axial model, which is what every reader already shows for an
untyped session. No released build wrote either, and the `/5` geometry is
unmerged, so nothing on a phone or in a backup carries a moment arm or a
diameter.

Not yet done: a Home row badge for the test type; virtual-extensometer strain
(mean-over-ROI understates strain after necking); a manual load / time column
override on the card.


Open debt and improvements: [docs/ops/TECH_DEBT.md](docs/ops/TECH_DEBT.md),
[docs/ops/FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

**Terms clickwrap (merged to `main` in #109, and into `feat/license-demo-pro`
on 2026-09-18).** The Terms of
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

**Licensing ([#100](https://github.com/sempermechanics/semperdic-app/pull/100),
merged 2026-09-19; go-live follow-up
[#110](https://github.com/sempermechanics/semperdic-app/pull/110) and the
link fix #111 merged the same day).** `main` is deployable; nothing has been
deployed yet — production Cloud Run is still `bcc467a` and the gateway config
predates licensing. What is left is ops, at the end of this
section and, checkbox by checkbox, in the "Licensing rollout" section of
[docs/ops/PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md). It extends flat demo/licensed into two licensed
shapes and one roster mechanism. Organised below by subject, not by the order
the branch built it in. Full model:
[docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md)
§20. What follows is the map of decisions that are expensive to rediscover and
easy to undo by accident.

*Shape.* `kind` is `individual` or `institution`, `duration` is `perpetual` or
`timed`, `seating` is `assigned` or `floating`. The three are orthogonal, and
contradictory combinations are refused at mint. Institution seats live at
`licenses/{id}/seats/{uid}`. A floating licence's `maxSeats` caps *concurrent*
leases while the roster stays uncapped — fifty people sharing ten slots — and a
member between leases is demo, the ordinary state rather than a failure. The
lease lives on the seat document, not a `leases` collection, because
`check_device_lock` already reads that document on every institution request;
its expiry is mirrored onto the user so `effective_mode` needs no Firestore
read. `assigned` is the default and means what every pre-existing licence
already meant, so neither seating nor duration needed a migration.

*Terms are mirrored, so editing a licence reaches nobody on its own.*
`resolve_user_config` is deliberately free of Firestore reads, which means
expiry and grace are copied onto each user at activation, and
`PATCH /v1/admin/licenses/{id}` has to fan renewed terms out to the individual
redeemer or every non-revoked seat. A timed licence keeps **full** entitlements
for `graceDays` past `expiresAt` — grace is inside the licensed branch, not a
reduced tier — and a licence stored with no `graceDays` reads as ZERO rather
than the fleet default, or deploying it would have reinstated everyone who had
expired inside the window. Activating past grace is refused (`license_expired`,
403) rather than landing the user silently on demo. The app shows a Home notice
inside 14 days of expiry or during grace, suppressed when its cached config is
over a week old: advisory only, `mode` is still the only gate.

*Revoke and downgrade.* Whole-key revoke drops every seat to demo and frees all
slots; single-seat revoke frees only that slot; disable drops to demo but keeps
the slot held. Downgrade never deletes data — it only blocks new analyses past
the cap, pinned by a test that seeds 30 sessions, downgrades, reactivates, and
loses nothing.

*Nothing is ever typed.* Both shapes are minted against an email address, which
writes `licenseInvites/{sha256(email)}`; that person's next sign-in redeems it
through `claim_pending_invite`, which grants either the licence itself or a
seat. Top-level and hashed: one document read on a hot path rather than a
collection-group query, and a plaintext address would be both an illegal
document id and enumerable. Redemption requires a **verified** email, since the
address is the whole claim to the licence. `ensure_entitlement` tries the
invite *before* `ensure_demo_license`, which stamps a `licenseId` that every
later call short-circuits on. `POST /v1/licenses/activate` and
`IndicApi.activateLicense()` survive as support recovery, with no caller in
`app/src/`.

*The device lock binds on first use.* `_device_lock_state` answers **unbound**
for a licence or seat that has not met a device, and `revalidate_device_lock` —
already on every authed request carrying `X-Device-Id` — binds it
transactionally, first writer wins. `ensure_demo_license` is a compare-and-set
for the same reason: it used to write blind, so of several requests racing at
launch the loser could stamp a demo key over the licence a sibling had just
granted. A claim also deletes the auto-minted demo key it supersedes
(`_drop_superseded_demo`, after the commit rather than inside it — reading the
account in the claim's own transaction locks it, and six concurrent sign-ins
then starve each other out), discriminating on `mode: demo` **and**
`createdByUid: "system"` on the licence, never the holder's mode mirror, which
revocation deliberately leaves demoted-in-place. A claim that merely lost the
race answers with a private `_CONTENDED` logged at `info` and mapped back by
`_public_claim_error`, so contention stops reading in the logs like a licence
with no room left; and a failed claim re-reads the account instead of returning
the caller's pre-race copy, which is what the consoles need, since a browser
sends no `X-Device-Id`. Revoking withdraws the licence's outstanding invites,
and `_write_invite` overwrites one whose licence is revoked or gone, so
mint → revoke → re-mint to the same address delivers. Three emulator tests
cover the three races (one redeemer, one invite, one device); seat
claim/revoke/checkout/release run under real transactions that fail closed,
after a read-then-`WriteBatch` was found able to push a pool past `maxSeats`.

*Moving to another device is a clear, not a revoke.* One primitive,
`clear_device_lock(license_id, uid, actor=…)`, behind four routes: the IT seat
patch, a staff seat clear (`PATCH /v1/admin/licenses/{id}/seats/{uid}/device`),
`clearDeviceLock` on the staff licence patch, and `POST /v1/licenses/unbind`
for the holder. Emptying the lock is the whole change, since the next device to
sign in takes it. `_restore_holder_mode` re-stamps the mode as part of the
clear: a device change is normally preceded by the holder *trying* the new
phone, which demotes the account in place, and `revalidate_device_lock` returns
early for a demo account — without it the clear would leave them on demo
holding a live licence. It is guarded (not a revoked licence, not a revoked or
disabled seat, not an account that has moved on) and runs outside the
transaction. The holder's own path alone waits on
`SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (30) against a `deviceChangedAt` only it
writes, because a second factor proves who is asking and not how often; staff
and IT never read or write it, so support always works. Both halves of a change
are audited, and operators can read the history per licence. **Restore has an
order**: sign in, let one authed request bind the lock, then restore — file
content is device-attested, so restoring first fails as unlicensed on a phone
the user has legitimately just moved to.

*A revoke reaches three places on three clocks, and IT sees only one.*
`seatsUsed` moves inside the revoke transaction; the holder's user document
just after it (`_drop_user_to_demo_if_licensed`, outside the transaction and
unretried); the holder's *device* only at its next `/v1/config` fetch. So the
institution console can report intent and nothing more.
`GET /v1/admin/licenses/{id}/reconcile` is the second number — a plain `ADMIN`
read, one user lookup per seat, sorting each into `active` (with
`never_claimed` for a roster place nobody took up), `revokedConfirmed` or
`revokedStillRunning`, each with a reason. Confirmation takes **two**
conditions, because the demotion already runs at revoke time and a bucket keyed
on stored mode alone would read clean almost always: the record has caught up
**and** `lastSeenAt > revokedAt`. `still_licensed` is a fault, repaired by
revoking again (idempotent); `no_checkin_since_revoke` is waited out. It reads
**stored** mode, never `effective_mode`, under which a floating member between
leases — the ordinary state for most of a roster — would report a healthy pool
as mass revoke failure. Both revoke paths stamp `revokedAt` apart from
`updatedAt`, which a later staff device clear would otherwise reset.
`lastSeenAt` is throttled to an hour, which would have made the read
over-report unlanded revokes for that long; the revoke stamps
`seenCheckpointAt` on the holder in the write the demotion already makes, and
a `lastSeenAt` older than that checkpoint is stale whatever its age — so the
holder's next request confirms the revoke, at the cost of one un-throttled
write per revoked account.

*Three authz tiers, deliberately distinct.* Institution IT authenticates on
`current_user` + APPROVED + verified email in that licence's `adminEmails` — no
device attestation and not Semper `role=admin`, so IT self-service never needs
a phone. `ADMIN_STEPUP` accepts either a device attestation or an admin ID
token carrying a completed second factor from a sign-in newer than
`ADMIN_WEB_REAUTH_SECONDS` (15 min; whole-licence revoke uses the tighter
`ADMIN_WEB_REVOKE_REAUTH_SECONDS`). It is weaker than device binding — a
phished live MFA session inside the window can mint a licence — which is why it
is its own tier rather than folded into `DEVICE_ADMIN`, and why
`ADMIN_WEB_MFA_ENABLED=0` withdraws it. `USER_STEPUP` is the same shape with
`current_user` beneath it (one shared `_attested_or_mfa`), carrying
`/v1/licenses/unbind` and `GET /v1/sessions/{sid}/bundle`. Every route has a row
in `tests/test_route_authz_matrix.py` and a declaration in
`gateway/openapi.yaml`; `tests/test_gateway_parity.py` fails the build on a
missing one, because ESPv2 is an allowlist and an undeclared route is
unreachable in production with nothing in the logs — a trap that has bitten
twice.

*Four web pages, one front door.* `/login` reads `GET /v1/me` and
`GET /v1/institutions/licenses` and forwards: staff to `/console/operator`, an
address named on a live licence's `adminEmails` to `/console/institution`
(deep-linked when there is exactly one), everyone else to `/account`. That
listing is a route rather than a field on `/v1/me`, which promises no extra
Firestore read and is called on every app launch; it is one `array_contains` on
`adminEmails` with kind and status filtered in Python, so no composite index.
`/login` and `/account` are Hosting rewrites into `/console/`, which is why the
relaxed console CSP is restated for them — a header matches the request path,
not the rewrite target — and why every page carries a `<base href>`. Each page
loads one ES module from a file beside it: **inline scripts do not run**, since
the console CSP is `script-src 'self'` with no `'unsafe-inline'`.
`scripts/check_console.py` (CI job `console-pages`, unfiltered) is the consoles'
only gate — they have no compiler — and it is what found all four pages inlined.
TOTP enrolment shows the secret for manual entry, since posting it to a QR
service would hand away the factor protecting licence issuance; SMS is never
enabled, so `auth.js` carries no phone branch. An analysis leaves through
`GET /v1/sessions/{sid}/bundle`: one `ZIP_STORED` archive over every completed
artifact — not a proxy of the stored `Session.zip`, which would drop the
`extras` zip modern sessions also carry — streamed into an unseekable sink so
600 files cost flat memory, with every refusal resolved before the first byte,
since a started body has no status code left. `list_session_artifacts` is kept
apart from the projection feeding `/v1/me/export`, so a field added here can
never widen the GDPR export.

*On the phone.* `seatRequiredToStart` is a **parallel** predicate to the quota
gate — an institution member is licensed, so `isSessionLimitReached` is false
for them by definition and they would sail past every existing check. It gates
the Home **+** before the source menu opens, and both compute paths, guarded by
`wouldCreateNewSession()` so a run in flight never aborts; `SeatRequiredActivity`
is one button that asks again, because seats free themselves. Floating seats
renew in-process on `seatHeartbeatMinutes` and release on sign-out, and a
four-hour `LicenseConfigWorker` refreshes `/v1/config` so an idle phone learns a
remote revoke (FI-16: four hours is the worst case, and shortening the interval
is the wrong lever — it costs every device every day to reach one). Demo
accounts upload silently (see *Demo records* below) but never open the share
sheet; restore maps
`license_device_mismatch` to a bind-first message. Settings → Account shows the
licence **prefix**, which is what support asks for; the key itself never
reaches the device.

*Traps.* `SCHEMA_VERSION` is 2
(`002_rename_campus_to_institution.py`, the first migration to include
`licenses`). The wire said `campus` and `plan: demo|professional`; it now says
`institution` and `mode: demo|licensed`, and every skew fallback is temporary —
the `plan` mirror on `/v1/config`, the hidden `/v1/campus/*` aliases (declared
in the gateway, or ESPv2 rejects them), the old pref key on upgrade,
`kind="campus"` still accepted at mint. Retirement order:
CLOUD_ARCHITECTURE_GCP §20.5. Licences are still keyed by the sha256 of their
key; opaque ids wait for the change that needs them, a licence with no key at
all. `MAX_SESSIONS_PER_USER` is **deleted**, not merely unread: `mode` selects
between `DEMO_MAX_ANALYSES` (25) and `LICENSED_MAX_SESSIONS_PER_USER`, so **a
deployment still setting the old variable silently gets 25 instead of 4**.

*Demo records; only retrieval is licensed (2026-09-19 decision).* A demo
account's analyses are uploaded and stored — images and results — but demo has
no backup/restore *feature*. On the backend that means `POST /v1/sessions` and
the upload broker are open to every approved account and the
`cloudBackupEnabled` gate sits only on `GET /v1/files/{id}/content` and the
session bundle. The reason is the installed fleet: every pre-licensing account
resolves to demo on its first request after the deploy, and the old
`DicUploadWorker` retries a 403 from session creation forever, whereas the old
restore worker gives up on 403 once. So the old app keeps backing up and shows
a single "rejected" on restore. In the new app demo sees **nothing** of cloud:
`CloudSync.uploadsEnabled` ignores the Save-to-cloud toggle for demo, Home
paints no sync badge or row progress, Settings has no Cloud / Analyses-data
section and no Free-up or auto-free control, and `StorageBudget` refuses to
evict on demo because the copy could not come back. Minting an individual
licence for an address that already has an approved, verified account attaches
it at mint time (`_attach_to_existing_holder`) instead of leaving an invite
that `claim_pending_invite` would skip over the stamped demo key.
`deploy-backend.yml` now pins `DEMO_MAX_ANALYSES`,
`LICENSED_MAX_SESSIONS_PER_USER`, `ADMIN_WEB_MFA_ENABLED`, `APP_CHECK_MODE` and
`SELF_DEVICE_CHANGE_COOLDOWN_DAYS` with safe expression defaults and warns when
the retired `MAX_SESSIONS_PER_USER` is still on the service. Still open: one
sentence in the Privacy Policy / Terms saying demo analyses are uploaded, and
the Play Data-safety form — both listed in the readiness gate.

*Dashboards on `app.sempermechanics.com`; CORS (`feat/console-domain-cors`).*
The consoles are hard-bound to Firebase Hosting (`auth.js` imports the SDK and
`firebaseConfig` from the reserved `/__/firebase/` namespace; `/login` and
`/account` are Hosting rewrites), and `sempermechanics.com` is the Netlify
marketing site, so the dashboards get a **custom domain** on the auth Hosting
site rather than a copy: `app.sempermechanics.com`. The marketing site links
"Sign in" there and redirects `/login`, `/account` and `/terms/` — the last
fixes a live 404 that `backend/app/legal.py` and the Terms clickwrap were
already pointing at. The same host carries the app's auth continue links under
`/auth/` (`AUTH_HOST`), with the `firebaseapp.com` host kept as
`LEGACY_AUTH_HOST` for every installed build and for the password-reset action
URL (TD-29). Found on the way: the API had **no CORS at all** — no middleware,
no `allowCors` on the gateway — so a console `fetch` carrying `Authorization`
was preflighted and refused at the edge from any origin. `CONSOLE_ORIGINS`
(Cloud Run, pinned by the workflow) plus `x-google-endpoints … allowCors` with
the `__MANAGED_SERVICE__` placeholder in `gateway/openapi.yaml` (the API's
managed service name — not the gateway hostname, which ESPv2 ignores) close that;
`test_security_controls.py` pins the preflight. The gateway must be redeployed
from the new spec before the consoles are usable.

*Go-live is ops, not code.* Identity Platform has to be enabled with TOTP on and
SMS off, or the consoles sign in and every write fails `mfa_required`; the API
Gateway has to be redeployed, since the backend workflow does not touch it; then
[`scripts/deploy-console.sh`](scripts/deploy-console.sh), whose trap restores
the `__API_BASE_URL__` / `__API_ORIGIN__` / `__AUTH_DOMAIN__` placeholders that
`check_console.py` insists stay placeholders. Steps and failure symptoms are in
[firebase-hosting/public/console/README.md](firebase-hosting/public/console/README.md).

Docs: [docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md)
§20 (§20.12 for the two seat counts), [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md) §9,
[docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md),
[docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md) §3.1,
[docs/OPERATING_MANUAL.md](docs/OPERATING_MANUAL.md) Appendix D, and
[firebase-hosting/public/console/README.md](firebase-hosting/public/console/README.md).

**An architecture pass on the same branch closed four things and deferred
three.** It read the app against the standard boundaries — hoisted UI state,
main-safe I/O, injected dependencies, attested network calls — and the findings
worth acting on were the ones that cost correctness rather than shape.

*App Check answers a question an ID token cannot.* `verify_id_token` attests the
account and `deps.verified_device` attests the device, but neither says which
*binary* is calling, and the Firebase Web API key that mints an ID token ships
inside the APK as an identifier rather than a secret — so a script holding a
valid sign-in could drive `POST /v1/licenses/checkout` and hoard a floating
pool. The phone now attaches `X-Firebase-AppCheck` from a host-scoped OkHttp
interceptor, and `current_user` demands one **only from a caller sending
`X-Device-Id`**: the app always sends it and a browser never does, so the four
consoles are exempt by construction rather than by a route list somebody has to
keep in step. The check runs *before* `get_or_create_user`, so a caller on its
way to being refused neither creates an account row nor moves a device lock, and
it answers `app_check_required`, not `not_approved` — the account may be
perfectly entitled. `APP_CHECK_MODE` is `off` (default) / `monitor` / `enforce`,
and a fourth value fails startup rather than quietly disabling the gate. Play
Integrity is the only provider installed: the debug provider needs a per-install
secret registered by hand, and the interceptor fails open, so a developer build
simply sends no header against `off` or `monitor`.

*429 and 503 are not the same answer, and `RetryOnTransient` does not treat them
alike.* 429 is retried unconditionally — the per-instance token bucket and the
gateway quota both reject *before* the handler runs, so nothing happened and a
repeat is not a second write. 503 carries no such promise, since ESPv2 emits it
on both sides of handing the request on, so it is retried for GET and for the
two POSTs that are idempotent by contract (`/v1/licenses/checkout`, whose repeat
*is* the heartbeat, and `/v1/licenses/release`). Session create and the upload
broker are deliberately absent: a duplicate there costs a Drive object. Three
attempts, `Retry-After` preferred over the backoff and clamped; the long game
stays WorkManager's, and this layer exists for the interactive calls that have
no second chance and used to surface a one-second throttle as a flat failure.

*Four session-index reads still ran from a tap*, in Home and Settings backup,
the session-limit recheck and the viewer's share path. Both backup sites keep
their write order rather than turning optimistic: the PENDING stamp lands before
`CloudSync.enqueueUpload`, or an upload that finishes first has its SYNCED stamp
overwritten by a late PENDING. The viewer warms its `sessionRecord` lazy in
`onCreate` instead of restructuring `ShareCenter` — `by lazy` is synchronized,
so a tap arriving mid-read waits on the read it would have done itself and never
on a second one. `SessionStore`'s blocking accessors carry `@WorkerThread` as
documentation only; see TD-24 for why that is half a pair. And the parameter
sweep moved from the Activity's `lifecycleScope` onto `viewModelScope`, emitting
progress and outcome as `SharedFlow`, so a rotation mid-sweep no longer throws
the run away.

*The three deferrals each name their blocker.* TD-24: `@MainThread` on ~27
Activities is the other half of the thread contract, and needs a lint run to
land against an empty baseline. TD-25: `AuthRepository` has no fake backend to
test against, because `IndicApi` is final with a private constructor — a seam
there admits only the real client, so it wants the interface extraction that
CONTRIBUTING defers to a DI PR of its own. TD-26: wizard and viewer UI state
still lives on the Activity rather than hoisted as `StateFlow`, on a 1.8k-line
native-solve screen with bit-exact `.dat` oracles; the part that was losing work
was the run, and the run is now on `viewModelScope`.

Docs: [docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md) §3.2,
[docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md),
[docs/backend/BACKEND_SETUP_GCP.md](docs/backend/BACKEND_SETUP_GCP.md).


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

**Next architecture (grill before coding):** the `DicKeys` extras bag is now
packed in one place — `ViewerArgs` in `ui/viewer/`, which both
`SessionOpenHelper.intentFor` and `AnalysisNavHelper.openResults` construct,
with `ViewerArgsTest` asserting their key sets against each other so they
cannot drift apart again. The unpack half is still four readers parsing the
bundle themselves, and is the larger move: an Intent already in the back stack
has to keep working across an update. Session **commit** still sits on
`AnalysisViewModel`'s public field bag after the JNI loop leaves.

**PRs target `main`.** Do not force-push `main`.
