# Semper — agent context

Read this before changing code. Commands: [CONTRIBUTING.md](CONTRIBUTING.md); screen maps:
[ARCHITECTURE.md](docs/app/ARCHITECTURE.md); DIC primer: [docs/README.md](docs/README.md).

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
| VSG | Strain window: least-squares plane fit over the points within (window − 1) / 2 steps. The window is entered in data points (odd); VSG = `(window − 1) × step + 1` px (`SweepStudy.vsgFor`) is what the engine takes as its circle's diameter and what sessions store |
| `.dat` | Binary field: 8 floats/point (`x y u v exx eyy exy znssd`), 32 bytes |
| session | One saved analysis on disk (and optionally in the cloud) |

Engine pipeline (`engine/docs/ARCHITECTURE.md`): AKAZE seeds → Delaunay → RGDIC → ICGN → VSG → `.dat`.

## Layout

```
app/          Android UI (Kotlin). Gradle builds ../engine/CMakeLists.txt;
              app/src/main/cpp/ holds only a redirect CMakeLists.txt
engine/       Pinned submodule: sempermechanics/semper-dic-engine (solver, tests,
              docs, and the JNI adapter in engine/adapters/android/)
backend/      FastAPI on Cloud Run — routers in backend/app/routers/, Firestore
              access in the backend/app/repo/ package (ADR-021)
firebase-hosting/  Auth continue URLs, asset links, generated legal pages
```

Bump the engine by changing the `engine` gitlink. Its host / sanitizer / DICe suites run
in the engine repo; this CI only proves the pin **links** (emulator x86_64, release arm64).

## Runtime

```
Splash → Auth / Pending / Home
Home → TestTypeSheet → StaticAnalysisActivity (wizard) → ResultViewerActivity
     → open session → ResultViewerActivity | VsgLatticeActivity
```

The test type (`data/mechanical/TestType`: tensile / bending / 2D DIC) is chosen on Home and rides
`IntentKeys.TEST_TYPE` into the wizard. 2D DIC has no load card and records no
type (`MechanicalTestInputs.NONE`), so its session is a plain, untyped DIC
session. For the lab tests, at Compute the type, the specimen
dimensions and the per-frame machine loads are snapshotted as
`RunSpec.mechanical` (`MechanicalTestInputs`), which the run commits to
`SessionRecord` and `metadata.json` schema `/6` and the viewer reads back
through `ViewerArgs`. Results are pure objects in `report/`: `StressStrain`
(the curve), `ElasticModulus` (tensile E, via `LinearFit`), `BeamDeflection`
(bending δ and E) and `LabReport` / `LabReportBending` (the student
write-ups), drawn by the viewer, `AnalysisCsvWriter` and `LabReportPdf` /
`PdfReportGenerator`.

Access routing is `AccessRouter` + `AccessStatus`. Intent extras are `IntentKeys`.
Session dirs: `SessionStore` + `SessionPaths` (`raw_deformed/`, `frame_%04d.dat`).

Backend: `backend/app/main.py` (app, middleware, lifespan), `routers/` (`/v1/*` by
prefix), `session_provision.py` (`provision_session` / `purge_session`).

Kotlin helpers are plain `object` / small classes; no Hilt/Dagger. Keep `lifecycleScope`
and Activity Result launchers on the Activity. Cloud logic under test takes a defaulted
`api: CloudApi` / `tokens: TokenSource`; tests pass `FakeCloudApi` ([ADR-002](docs/adr/ADR-002-cloudapi-seam.md)).
The wizard (`StaticAnalysisActivity`, ViewStub steps) has full `configChanges`: rotation
does not recreate it, process death does (see Traps). Home **+** opens `MediaPickerSheet` (shared with the wizard dropzones). Uploads,
restores, bundle downloads and backup deletes are WorkManager, shown by the
non-modal `TransferBannerController` strip.

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
- **Analytics and crash reporting share one consent flag** (`AppSettings.diagnosticsEnabled`).
  Events stay PII-free — buckets and enums only, never images, results, session ids
  or specimen names.
- **Release** builds require HTTPS `SEMPER_API_BASE_URL`. Debug emulator boots
  local-only unless `SEMPER_DEV_AUTH_BYPASS=false`.
- **Legal pages** are generated: edit `docs/legal/`, run `scripts/render_legal_pages.py`,
  never hand-edit `firebase-hosting/public/{privacy,terms}/`.

## Quality gates

Baselines, `targetSdk`, Kover and the backend lock: see CLAUDE.md. `OldTargetApi` stays
disabled until the `targetSdk` bump. Settings / wizard XML stay under `TooManyViews` via
`SettingsScrollContentView` / `WizardStepSettingsContentView`. Macrobenchmark CI is smoke,
no thresholds ([TESTING.md](docs/app/TESTING.md)); the phone-run gates (`benchmark/gates.json`,
[ADR-008](docs/adr/ADR-008-startup-gates-phone-state.md)) list no device yet (TD-155); the engine floor (≥ 4557 solves/s,
[PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)) is a manual reference.
Keep `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-10-08)

- **Synced with `semperdic-app`.** Forked at `bfe00e5` (2026-09-21); the parent's
  `main` comes in with a plain `git merge` on a `sync/` branch (`git log --merges
  --grep=semperdic-app`; last merged `51fbfc1`, 2026-10-08 (its #345–#359: the
  restored-backup pairing in Settings, the 2 GB unit-test JVM and closed test beds
  (TD-200), console changes and the live-site check); before that `85650da`, 2026-10-07 (#122: package move ADR-015,
  app id ADR-019, engine at `engine/`); rules in the parent's
  `docs/ops/FORK_SYNC.md`). This app id's Firebase client is in
  `google-services.json`; App Check for it waits for a Play listing. The parent deploys, and its TD rows jump to TD-122. General
  fixes made here go back upstream; TD-78 and TD-81 are lab-only. The lab inputs ride
  upstream's seams: `RunSpec.mechanical` (ADR-004), `ViewerArgs` with a
  `SessionRecord` fallback (ADR-003) and `WizardState` / `WizardDraft` (ADR-005).
  ADR and TD numbers are shared: ADR-008, 009, 011 and 012 and TD-133–135,
  TD-139–144 and TD-146–152 are ours (TD-145 and TD-153–156 are semperdic-app's; its
  TD-139 is ours, ported with ADR-011 in its #302, and ADR-014 is its). The next row
  free in both registers is TD-201 (semperdic-app took TD-200 in its #350); check
  both before taking one.
- **Lab outputs, all merged (#1–#21).** Tensile: stress–strain (strain is the virtual
  extensometer's ΔL / L₀ since ADR-012), E from the
  longest straight leading run, Rp0.2 by the 0.2% offset (#100), the elastic-region
  view, a lab-report PDF. Results' **Adjust curve** takes a strain and stress scale
  and bias to match the machine's own export (`CurveCorrection`, in `Model.Axial`;
  per session; re-sent to a backed-up copy like the deflection correction, TD-152).
  Bending: beam-edge taps, δ and E = WL³/(48δI), a bending lab report. Loads
  from a machine CSV, matched by time within 100 ms for video; bending's are
  typed per photo in kg (`TypedLoadsSheet`, × 9.80665, CSV optional; Pixel 6
  2026-09-28), as totals or as increments per photo (stored as totals), and shown in kg;
  the maths and CSV stay in N. A per-session deflection scale and bias is set from
  Results (`ViewerDeflectionCorrection`, not the wizard), and re-sent to a backed-up
  session's metadata.json (ADR-013, TD-150; route deployed 2026-10-01, the app side ships next release). Strain window in
  points (tensile 5, bending 9). Engine `v0.2.3` (deterministic, TD-65). Checked against published
  steel and PMMA data, on a Pixel 6 too (2026-09-26; steel re-run under ΔL / L₀
  on 2026-09-29, TD-144); a concrete set fails as
  expected ([REAL_WORLD_VALIDATION.md](docs/app/REAL_WORLD_VALIDATION.md)).
  Both cases were re-run on `v0.2.3` on the emulator on 2026-10-01 and match
  `v0.2.2` (steel E 148.853 GPa; PMMA slope 6800.26 N/mm at the same taps);
  `RealPmmaBendingTest` now holds that run's deflections. The repeat solve is
  bit-identical on a Pixel 6 (arm64); the real-data re-run there is still owed
  (TD-65's row).
  Fixed since: TD-91, #51 (TD-92), #55 (TD-93, TD-94), TD-147 (peak stress
  counts frames off the curve).
- **Wizard (#46, #48).** **Which test?** offers 2D DIC: plain DIC, no load card, and
  the session records no test type. The load card's ⓘ shows the CSV header and a
  diagram of each test; bending's row reads **Beam height → Set**. The keyboard makes room
  on every screen with a text field: wizard, ROI editor, viewer frame jump (#71, TD-99).
  The ROI editor zooms (pinch to 10×, double-tap 2× / fit) and pans with two fingers
  (#84); `e2e/RoiEditorGestureTest` passes on the API 37 emulator (2026-09-28); a pan
  now stops where the fingers lift (TD-142), which had kept Tier 3 red on `main` since #86;
  and a pan pushed against an edge stays on it when they lift (TD-146), which had made
  the test's last check flaky on CI.
- **Lab end to end (#24).** `e2e/LabWorkflowDeviceTest` and `e2e/BeamTapEditorGestureTest`
  run in Tier 3 and pass on a Galaxy S21+ and the API 37 emulator (TD-95).
  `WizardDraftRestoreTest`: a load log survives process death.
- **CI and benchmarks.** A docs-only PR skips Tier 1 (#63, TD-96); a merge no longer
  cancels `main`'s running CI (#67, TD-97). `HotPathMicroBenchmark` runs in CI (#31); the
  Pixel 6 medians and the report-only `benchmark/gates.json` are in
  [TESTING.md](docs/app/TESTING.md). The 2026-09-25/26 re-runs pass every gate, but
  the wizard cold start sits at +23 %, 75 % of its headroom: the phone's state, not
  the code (TD-135). Gates now skip throttled or unplugged runs and a trip is settled
  A/B (ADR-008); the Pixel 6 references are owed a re-take.
  TD-86–TD-88 and TD-90 match the parent's numbers; its TD-81 is TD-89 here.
- **Owed.**
  - By hand on a phone ([WORKFLOWS.md](docs/app/WORKFLOWS.md)): how ROI zoom and pan feel
    on a real screen (§6.21–6.25; the gestures themselves pass in e2e on the emulator).
    From §5.1a: a phone-recorded MP4
    (5.1a.12) and a real UTM clip. The rest of §5.1a, including Key frames with loads
    (5.1a.16), passes on a Pixel 6 with synthetic clips (2026-09-26). MP4 and codec AVI
    frames are now stretched to full range (TD-134), not yet re-measured on a phone. The Galaxy S21+'s demo account is over its cap (45 / 25), so a new
    analysis there needs a licensed key or deletes first.
  - Owner decision: Terms §1.2 (professional use only) and §1.3 (18+) sit badly with a
    first-semester student audience ([CHANGELOG](docs/ops/CHANGELOG.md) 2026-09-24).
  - **Own app (ADR-009, TD-133).** `main` installs as `com.sempermechanics.materialtesting`
    since #122 (ADR-019: no in-place upgrade from the released
    `com.indicvision.semper.materialtesting` builds, so testers install it beside them) beside Semper, on Semper's Firebase project and backend; released as `v1.2-beta.1`
    (#82), with Asset Links on Hosting. One phone per app ([ADR-010](docs/adr/ADR-010-device-binding-per-app.md),
    TD-138) is deployed and synced here: `v1.2-beta.2` (2026-09-29, from `fc1aaa4e`)
    sends `X-App-Id` and signed in licensed on a Pixel 6, which now runs `v1.2-beta.3`
    (`528afdfc`, versionCode 4). Don't hand out `main`'s CI APK: its versionCode is
    CI's run number, above every release (TD-148). Signed out (a debug build with no
    API URL), Home's Beta notice is acked once per phone, no longer on every launch
    (#106, TD-149; Pixel 6 2026-09-30); each account still acks once. A release
    installed over a debug build is a new phone to the backend (`ANDROID_ID` follows
    the signing key): reset this app's device first. Owed: signing in where Semper is
    signed in too, and App Check for this app once it is on Google Play (a sideloaded
    APK cannot attest; production runs it `off`). A `sync/` merge keeps this repo's `applicationId` and
    `google-services.json`.
  - The parent owns backend and Hosting deploys; see its CONTEXT.md for production state.
- **Look it up; this list rots.** `gh pr list --state open`; history in
  [CHANGELOG.md](docs/ops/CHANGELOG.md); proposals in [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

## Traps

- An undeclared route 404s in production with nothing in the logs: ESPv2 is an allowlist. `test_gateway_parity.py` checks the spec — [§20.9](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A query that needs a composite index fails `FAILED_PRECONDITION` at runtime, not deploy: run `scripts/deploy-firestore.sh indexes` (Git Bash, Node >= 20) and wait for the build before the backend that queries it (the staff licence list needs three) — [§5](docs/backend/CLOUD_ARCHITECTURE_GCP.md). TTL policies live in that file's `fieldOverrides`; never deploy it with `--force`, which deletes any the file omits.
- `MAX_SESSIONS_PER_USER` is deleted; a deployment still setting it silently gets `DEMO_MAX_ANALYSES` (25) — [§7](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- List env vars (`CONSOLE_ORIGINS`, `ADMIN_EMAILS`) are space-separated; the deploy action splits on commas — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Test phones are shared between sessions: before `adb install -r`, check `ps -A | grep instrument` and the app's `lastUpdateTime` — an install kills a running `am instrument`, and another session's install can replace the build under test. Never the connected task on a phone — [TESTING.md](docs/app/TESTING.md).
- Restore on a new device has an order: sign in, let one authed request bind the lock, then restore — [§20.10](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Licence terms are mirrored onto users, so editing a licence reaches nobody without `update_license`'s fan-out — [§20.6](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Demo uploads silently; only retrieval is gated. Gating `POST /v1/sessions` would loop old builds on 403 — [§20.3](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Console CSP is `script-src 'self'` with no `'unsafe-inline'`: inline scripts never run — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `check_console.py` requires `__API_BASE_URL__` / `__API_ORIGIN__` to stay placeholders; deploy through `scripts/deploy-console.sh` — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `SCHEMA_VERSION` is 2 and every `campus` / `plan` skew fallback is temporary; retire in the stated order — [§20.5](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Backup stamps PENDING before `CloudSync.enqueueUpload`; reversing it lets a late PENDING overwrite SYNCED — [§8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A 429 that consumes the nonce makes the app's retry a 401 replay: keep a signed route's bucket in `dependencies=[deps.rate_limited(...)]`, never in the handler; unsigned routes call `rate_limit.enforce` — `test_rate_limit_before_nonce.py`.
- Activities are `@MainThread` at class level, so a private helper that runs on `Dispatchers.IO` needs `@WorkerThread` (or `@AnyThread`) or lint fails (lint's `WrongThread`; closed as TD-24).
- `SessionStore`'s parser uses `ignoreUnknownKeys` so old `index.json` fields load; keep it — [SessionStoreLegacyFloorTest](app/src/test/java/com/sempermechanics/semper/data/session/SessionStoreLegacyFloorTest.kt).
- `SubsetRecommender` runs on the paper's `NOISE_VARIANCE`; no import supplies a measured floor — [SubsetRecommender.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/recommend/SubsetRecommender.kt).
- Viewer screens read `ViewerArgs.from(intent, …)`, never `intent.get…Extra(IntentKeys…)`; a new viewer field goes in `ViewerArgs`, its default and its `SessionRecord` mapping — [ADR-003](docs/adr/ADR-003-viewerargs-read-side.md).
- A new wizard input must survive a kill: scalars go in `WizardState`'s Bundle, bytes and lists in `WizardDraft`; and `cacheDir/temp_deformed` is only safe from the janitor while the draft is live — [ADR-005](docs/adr/ADR-005-wizard-process-death.md).
- After Compute, read the run's `RunSpec` / `RunResult` (`spec`, `settings`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Since engine 0.2.3 two runs of one build give bit-identical `.dat` whatever the thread count (TD-65), so a `.dat` hash can prove "engine unchanged" again and any run-to-run difference is a defect — `EnginePipelineSmokeTest.repeatSolve_bitIdentical`, `engine/tests/integration/test_full_field_determinism.cpp`.
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/run/ConvergenceGate.kt).
- The backend, console and Firestore rules deploy from `semperdic-app` only; the deploy workflows here fail if run. Keep `TERMS_VERSION` equal to the parent's.
- A new lab input goes in `MechanicalTestInputs`, `RunSpec.mechanical`, `ViewerArgs` (both sides) and `WizardState`; missing one gives a viewer or a restored wizard that quietly reads "no test" — `ViewerArgsTest`, `WizardStateTest`.

**PRs target `main`.** Do not force-push `main`.
