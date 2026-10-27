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

Engine pipeline (`engine/docs/ARCHITECTURE.md`): anchor-lattice seeds (AKAZE + RANSAC homography + ICGN, engine 0.6.0) → Delaunay → RGDIC → ICGN → VSG → `.dat`.

## Layout

```
app/          Android UI (Kotlin). Gradle builds ../engine/CMakeLists.txt
              (app/build.gradle.kts externalNativeBuild); no app/src/main/cpp/
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
  The golden files that hold both are `app/src/test/resources/oracles/`
  (`results/DatFieldOracleTest`, `results/SummaryGifOracleTest`); regenerating
  one (`-PupdateGoldens`) is a reviewed decision, never a fix for a red build.
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

## Current state (2026-10-26)

- **Semper's UI plus two test choices ([ADR-022](docs/adr/ADR-022-semper-ui-plus-lab-tests.md)).**
  **Which test?** offers 2D DIC (Semper's analysis: no load card, no test type recorded),
  Tensile and Bending; a fork change to a shared screen runs only for a lab test.
  semperdic-app accepted making this a product flavour of its repo on 2026-10-22 ([ADR-025](docs/adr/ADR-025-test-type-extension-points.md), mirrored).
- **Synced with `semperdic-app`** (forked at `bfe00e5`; `git log --merges
  --grep=semperdic-app`; rules in its `docs/ops/FORK_SYNC.md`). Last: `25334af1`, 2026-10-26,
  on `sync/semperdic-25334af`: its #471–#486, among them tamper-evident records (#471,
  [ADR-028](docs/adr/ADR-028-tamper-evident-records.md)), the Data safety fixes (#475), the
  console redesign (#478) and the Semper Classroom rename's backend and consoles (#484,
  [ADR-030](docs/adr/ADR-030-semper-classroom-app-identity.md)). Before: `968637cb`, 2026-10-22,
  on `sync/semperdic-968637c`: its #463–#470, deploy and decision docs, the backend's Firestore
  outage as 503 (TD-250), audit-row expiry (TD-237), a test account's phone released on its
  own (TD-314), a staging round-trip device test (TD-313), ADR-025 phase 2's first part
  (`SessionRecord.extras`, empty here: the lab fields stay top-level until an extension
  moves them) and engine v0.6.1, already here by #143; that merge (#144) set the gitlink to
  0.2.3 (`1284edf`), put back to v0.6.1 by `claude/readiness-20261024-engine-pin` (MT-TE2-001). Before: `9d116f24` (#461, #462: **engine v0.6.0**, AKAZE + RANSAC seeding).
  Before: `14b279d6`, 2026-10-21,
  on `ccr-603fe346-1bw2fa` (merged as #141; not deployed): its #453 (`firebase-bom` 35), #457 (the
  review fixes and tech-debt rounds), #458 (the incremental reference), #459 (unit-test JVM
  diagnostics, TD-312) and #460 (staff test accounts, [ADR-027](docs/adr/ADR-027-test-accounts.md):
  backend and console, deployed from there); it closed TD-305, TD-311 and TD-207. The lab
  wizard state is `WizardInputs.lab`, changed through `edit {}`, and a run's result reads
  the lab test from its saved row (`ViewerArgs.of`). Metadata schema `/7` here (ours past
  `/3` are not the parent's numbers). Lab inputs ride its seams: `RunSpec.mechanical` (ADR-004), `ViewerArgs` (ADR-003),
  `WizardDraft` (ADR-005). ADR and TD numbers are shared; TD-330 is the rename, TD-331–TD-333 the example data (semperdic-app uses the same three numbers for other rows), TD-349 and TD-350 the Play listing, and the rest of TD-318–TD-348 are semperdic-app's.
- **#140 merged 2026-10-20** (`a66606d9`, the 2026-10-19 review; not released): load logs
  in kgf/tf, decimal commas and the delimiter read after a long preamble, logged time units;
  photo times matched within 1 s when any lacks EXIF sub-seconds; E refitted over 10–40 % of
  Rp0.2; bending strain along the beam from the thickness gradient (none without taps); one
  `LabSession` and Results panel (TD-301); beam-edge taps square to a tilted beam (TD-310);
  Home's Re-run copies a tensile or bending test (TD-280). Left: TD-300.
- **Readiness port, merged 2026-10-19, not deployed** (#137, #138: semperdic-app's
  #448/#449; TD-214–TD-279, FI-17–FI-24): **minSdk 30**, the signed offline entitlement
  ([ADR-023](docs/adr/ADR-023-signed-offline-entitlement.md); tokens name `semperclassroom`,
  `materialtesting` before and still read), its own `*_SEMPER_CLASSROOM` version gate
  (`*_MATERIAL_TESTING` still read), kill switches, EMM config, account-anchored
  entitlements ([ADR-024](docs/adr/ADR-024-account-anchored-entitlements.md)). The backend,
  consoles, indexes and dashboards deploy only from semperdic-app; never deploy Hosting from
  here. Owed: TD-214–TD-217, TD-219, TD-226, TD-229–TD-232, TD-277; for a release, this
  repo's `SEMPER_ENTITLEMENT_PUBKEYS` variable and `SEMPER_API_CERT_PINS` secret.
- **Lab outputs.** Tensile: stress–strain (ΔL / L₀, ADR-012), E, Rp0.2, **Adjust curve**,
  a lab report. Bending: beam-edge taps, δ, E = WL³/(48δI), loads in kg, a deflection
  correction (ADR-013), a lab report. Checked against published steel and PMMA data
  ([REAL_WORLD_VALIDATION.md](docs/app/REAL_WORLD_VALIDATION.md)); re-run owed (TD-300).
- **Releases.** Semper Classroom, `com.sempermechanics.semperclassroom` (ADR-009, ADR-030),
  beside Semper, on its backend; last `v1.4-beta.2` as Material Testing
  (`com.sempermechanics.materialtesting`, `d08aae07`, 2026-10-17, versionCode 1006), not yet
  smoked on a phone. Never hand out `main`'s CI APK (TD-148). App Check waits for Play.
- **Owed by hand** ([WORKFLOWS.md](docs/app/WORKFLOWS.md)): after the 2026-10-21 sync,
  `LabWorkflowDeviceTest` and `WizardDraftRestoreTest` on a device, and #458's incremental
  reference on a lab clip; ROI zoom and pan (§6.21–6.25);
  §5.1a with a load log, a phone MP4 and a UTM clip; Semper Classroom beside Semper (§3b),
  ADR-022's screenshots; the synced tip cards, summary pill and Re-run (3.11a–3.11m); Pixel 6
  references (TD-135). Owner decision: Terms §1.2 and §1.3 against a student audience.
- **Example data in the app ([ADR-031](docs/adr/ADR-031-bundled-example-data.md)), merged
  2026-10-26 (#149, `8b11ed8f`), not released:** steel, PMMA, concrete (CC BY 4.0) and two DICe
  pairs (BSD-3) in `app/src/main/assets/examples/` (~80 MB), with ground truth and credits;
  Try it / Save to phone / Credits & licences. Terms §4.6 merged upstream (semperdic-app #487),
  not yet synced here or deployed to Hosting; the website's Examples page merged (semper-website
  #13). Owed: that sync and deploy, the device pass §3c, TD-331–TD-333.
- **Rename to Semper Classroom ([ADR-030](docs/adr/ADR-030-semper-classroom-app-identity.md)):** merged
  2026-10-26 (#147, `f25ebd01`), not released; still owed: the upstream deploy and the owner's Firebase app registration.
- **Play submission ([gate](docs/ops/PRODUCTION_READINESS_GATE.md#google-play-submission-semper-classroom)):**
  branch `claude/play-readiness-checks`. The release check now fails
  a targetSdk below 36 or a native library off 16 KB pages; the rest is the operator's
  (signing key TD-349, reviewer access TD-350, TD-36, TD-229–TD-232).
- **Faster CI ([CI.md](docs/ops/CI.md)):** branch `claude/ci-faster-benchmarks`, stacked on
  the Play branch. The `benchmark` build type is R8-minified and the benchmark job
  cold-starts it first (`scripts/launch_smoke.py`), the only R8 run in CI; Macrobenchmarks
  dry-run on PRs; heavy tiers wait for `tier1-static` only. Two unit-test workers reproduce TD-312, so it stays one.
- **CI fixes after main's failures on 2026-10-26:** merged as #152 (2026-10-27). Submodule
  checkout no longer needs GitLab when Eigen's pin is cached (`checkout-submodules`), and
  `PdfReportGeneratorTest` reads its statuses without a race.
- **Look it up; this list rots.** `gh pr list --state open`; history in
  [CHANGELOG.md](docs/ops/CHANGELOG.md); proposals in [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

## Traps

- An undeclared route 404s in production with nothing in the logs: ESPv2 is an allowlist. `test_gateway_parity.py` checks the spec — [§20.9](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A query that needs a composite index fails `FAILED_PRECONDITION` at runtime, not deploy (`test_firestore_query_indexes.py` catches one missing from the file; declare a new query's shape there): run `scripts/deploy-firestore.sh indexes` (Git Bash, Node >= 20) and wait for the build before the backend that queries it (the staff licence list needs three) — [§5](docs/backend/CLOUD_ARCHITECTURE_GCP.md). TTL policies live in that file's `fieldOverrides`; never deploy it with `--force`, which deletes any the file omits.
- `MAX_SESSIONS_PER_USER` is deleted; a deployment still setting it silently gets `DEMO_MAX_ANALYSES` (25) — [§7](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- List env vars (`CONSOLE_ORIGINS`, `ADMIN_EMAILS`) are space-separated; the deploy action splits on commas — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Test phones are shared between sessions: before `adb install -r`, check `ps -A | grep instrument` and the app's `lastUpdateTime` — an install kills a running `am instrument`, and another session's install can replace the build under test. Never the connected task on a phone — [TESTING.md](docs/app/TESTING.md).
- Restore on a new device has an order: sign in, let one authed request bind the lock, then restore — [§20.10](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Licence terms are mirrored onto users, so editing a licence reaches nobody without `update_license`'s fan-out — [§20.6](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Demo uploads silently; only retrieval is licence-gated. Gating `POST /v1/sessions` on the licence would loop old builds on 403; a kill switch refuses it with 503 `feature_paused`, which every build retries — [§20.3](docs/backend/CLOUD_ARCHITECTURE_GCP.md), [§20.14](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Share has no backend route: the licence check and the signed token are its only gate, so a modified APK can share (accepted, TD-274). Anything that touches the cloud is checked by the server — [ADR-024](docs/adr/ADR-024-account-anchored-entitlements.md).
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
- After Compute, read the run's `RunSpec` (`BatchAnalysisOutcome.spec`) and the saved row its outcome carries (`.record`, opened through `ViewerArgs.of`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Since engine 0.2.3 two runs of one build give bit-identical `.dat` whatever the thread count (TD-65), so a `.dat` hash can prove "engine unchanged" again and any run-to-run difference is a defect — `EnginePipelineSmokeTest.repeatSolveIsBitIdentical`, `engine/tests/integration/test_full_field_determinism.cpp`.
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/run/ConvergenceGate.kt).
- The backend, console and Firestore rules deploy from `semperdic-app` only; the deploy workflows here fail if run. Keep `TERMS_VERSION` equal to the parent's.
- A new lab input goes in `MechanicalTestInputs`, `RunSpec.mechanical`, `ViewerArgs` (both sides, `ViewerArgs.of` included) and `LabWizardState` (`WizardInputs.lab`, with its Bundle key in `putLab` / `restoreLab`); missing one gives a viewer or a restored wizard that quietly reads "no test" — `ViewerArgsTest`, `RunSpecTest`, `WizardStateLabTest`.

**PRs target `main`.** Do not force-push `main`.
