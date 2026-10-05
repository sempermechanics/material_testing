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
              access in backend/app/repo/ behind the firestore_repo facade
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

Kotlin helpers are plain `object` / small classes; no Hilt/Dagger. Activity Result
launchers are registered before the Activity starts: as a property, or by a part built
there (`WizardMediaPickers`, `RoiStudioLauncher`, `ViewerShareController`). Work that must
outlive the screen follows [ADR-016](docs/adr/ADR-016-work-that-outlives-the-activity.md);
views go through ViewBinding and the `ui/common` kit ([ADR-017](docs/adr/ADR-017-viewbinding-and-ui-kit.md));
failures are typed outcomes ([ADR-018](docs/adr/ADR-018-error-convention.md)). Cloud logic under test takes a defaulted
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
- **Hot loops stay fused.** VisualizationEngine pixel loops, GifEncoder LZW, the
  ReportBuilder fusion pass, `DicResult.decodeDatFile`, `prefetchAround` and
  `PointSpatialIndex.build` each keep their loop body whole in one function. The
  files around them may be split; a split proves itself with the `.dat` / GIF
  oracles and no regression in `HotPathMicroBenchmark` / `ViewerScrubBenchmark`.
- **Upload staging is repeatable.** `DicUploadWorker.doWork` may be broken into
  named steps, but the staged bytes must be identical across attempts: Drive's
  resumable URI and the reconcile check the declared size and sha256.
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
[ADR-008](docs/adr/ADR-008-startup-gates-phone-state.md)) hold this app's own Pixel 6 references, measured
2026-09-25 on its old id (the parent's TD-155 numbers are not copied); the engine floor (≥ 4557 solves/s,
[PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)) is a manual reference.
Keep `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-10-05)

- **Synced with `semperdic-app`** with a plain `git merge` on a `sync/` branch
  ([FORK_SYNC.md](docs/ops/FORK_SYNC.md); `git log --merges --grep=semperdic-app`). Forked
  at `bfe00e5` (2026-09-21). Last: `dfc28e13` and the heads of the parent's #341–#343, on
  `sync/semperdic-dfc28e1` (material_testing#121, open): the quality program (ADR-015–018),
  the app id move (ADR-019), restored analyses dated from their backup (#341) and naming
  section D (#343). The lab code lives in `data/mechanical`, `ui/analysis/load`,
  `ui/viewer/mechanical` and `ui/viewer/share/LabExport`, on the parent's seams:
  `RunSpec.mechanical` (ADR-004), `ViewerArgs` (ADR-003), `WizardState` / `WizardDraft`
  (ADR-005) and `DeformedFrame` per frame. General fixes go back upstream; TD-78 and TD-81
  are lab-only.
- **Shared numbers.** ADR-008, 009, 011, 012, 013 and 020 and TD-133–135, TD-139–144 and
  TD-146–152 are ours; ADR-010, 014–019, TD-145 and TD-153–177 are the parent's. The next
  free row in both registers is TD-178; check both before taking one.
- **App id `com.sempermechanics.materialtesting`**
  ([ADR-020](docs/adr/ADR-020-sempermechanics-materialtesting-id.md)); the backend and
  `assetlinks.json` know it (parent, 2026-10-05). Owed: its Firebase Android app (fingerprints,
  App Check) and client in `app/google-services.json` (a local build needs an uncommitted
  copy until then), a phone check and a release. Released builds, up to `v1.2-beta.3`, are
  `com.indicvision.semper.materialtesting` (ADR-009) and stay installed beside it.
- **Lab outputs (#1–#21).** Tensile: stress–strain under ΔL / L₀ (ADR-012), E, Rp0.2,
  **Adjust curve** (TD-152). Bending: beam-edge taps, δ and E = WL³/(48δI), loads typed in kg,
  a deflection correction (TD-150). Loads from a machine CSV or typed; a lab-report PDF for
  each. Checked against published steel and PMMA
  ([REAL_WORLD_VALIDATION.md](docs/app/REAL_WORLD_VALIDATION.md)); the real-data re-run on a
  Pixel 6 under engine `v0.2.3` is owed (TD-65).
- **Device tests.** `e2e/LabWorkflowDeviceTest`, `BeamTapEditorGestureTest` and
  `RoiEditorGestureTest` run in Tier 3; this sync has not been run on a device yet.
- **Owed.**
  - By hand on a phone ([WORKFLOWS.md](docs/app/WORKFLOWS.md)): ROI zoom and pan feel
    (§6.21–6.25), a phone-recorded MP4 (5.1a.12), a real UTM clip, full-range video frames
    (TD-134). The Galaxy S21+'s demo account is over its cap.
  - Signing in where Semper is signed in too; App Check for this app (production runs it
    `off`). Don't hand out `main`'s CI APK (TD-148); a release over a debug build is a new
    phone to the backend, so reset this app's device first.
  - Owner decision: Terms §1.2 and §1.3 against a first-semester student audience.
- **The parent deploys** backend and Hosting. **Look it up; this list rots:**
  `gh pr list --state open`, [CHANGELOG.md](docs/ops/CHANGELOG.md),
  [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

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
- Activities are `@MainThread` at class level, so a private helper that runs on `Dispatchers.IO` needs `@WorkerThread` (or `@AnyThread`) or lint fails — TD-24 in [TECH_DEBT.md](docs/ops/TECH_DEBT.md).
- `SessionStore`'s parser uses `ignoreUnknownKeys` so old `index.json` fields load; keep it — [SessionStoreLegacyFloorTest](app/src/test/java/com/sempermechanics/semper/data/session/SessionStoreLegacyFloorTest.kt).
- `SubsetRecommender` runs on the paper's `NOISE_VARIANCE`; no import supplies a measured floor — [SubsetRecommender.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/recommend/SubsetRecommender.kt).
- Viewer screens read `ViewerArgs.from(intent, …)`, never `intent.get…Extra(IntentKeys…)`; a new viewer field goes in `ViewerArgs`, its default and its `SessionRecord` mapping — [ADR-003](docs/adr/ADR-003-viewerargs-read-side.md).
- A new wizard input must survive a kill: scalars go in `WizardState`'s Bundle, bytes and lists in `WizardDraft`; and `cacheDir/temp_deformed` is only safe from the janitor while the draft is live — [ADR-005](docs/adr/ADR-005-wizard-process-death.md).
- After Compute, read the run's `RunSpec` / `RunResult` (`spec`, `settings`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Since engine 0.2.3 two runs of one build give bit-identical `.dat` whatever the thread count (TD-65), so a `.dat` hash can prove "engine unchanged" again and any run-to-run difference is a defect — `EnginePipelineSmokeTest.repeatSolveIsBitIdentical`, `engine/tests/integration/test_full_field_determinism.cpp`.
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/run/ConvergenceGate.kt).
- The backend, console and Firestore rules deploy from `semperdic-app` only; the deploy workflows here fail if run. Keep `TERMS_VERSION` equal to the parent's.
- A new lab input goes in `MechanicalTestInputs`, `RunSpec.mechanical`, `ViewerArgs` (both sides) and `WizardState`; missing one gives a viewer or a restored wizard that quietly reads "no test" — `ViewerArgsTest`, `WizardStateTest`.

**PRs target `main`.** Do not force-push `main`.
