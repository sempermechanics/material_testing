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
| VSG | Strain window: least-squares plane fit over the points within (window − 1) / 2 steps. The window is entered in data points (odd, 3–31); VSG = `(window − 1) × step + 1` px is what the engine and sessions get (`VsgStudy.vsgFor`) |
| `.dat` | Binary field: 8 floats/point (`x y u v exx eyy exy znssd`), 32 bytes |
| session | One saved analysis on disk (and optionally in the cloud) |

Engine pipeline (detail in `native/docs/ARCHITECTURE.md`): AKAZE seeds → Delaunay
mesh → RGDIC flood-fill → ICGN → VSG strain → `.dat`.

## Layout

```
app/          Android UI (Kotlin). Gradle builds ../native/CMakeLists.txt;
              app/src/main/cpp/ holds only a redirect CMakeLists.txt
native/       Pinned submodule: sempermechanics/semper-dic-engine (solver, tests,
              docs, and the JNI adapter in native/adapters/android/)
backend/      FastAPI on Cloud Run — routers in backend/app/routers/, Firestore
              access in backend/app/repo/ behind the firestore_repo facade
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

Package map: [ARCHITECTURE.md](docs/app/ARCHITECTURE.md). Backend: `backend/app/main.py`
(app, middleware, lifespan), `routers/` (`/v1/*` by prefix), `session_provision.py`
(`provision_session` / `purge_session`).

Kotlin helpers are plain `object` / small classes; no Hilt/Dagger. Keep `lifecycleScope`
and Activity Result launchers on the Activity. Cloud logic under test takes a defaulted
`api: CloudApi` / `tokens: TokenSource`; tests pass `FakeCloudApi` ([ADR-002](docs/adr/ADR-002-cloudapi-seam.md)).

The wizard (`StaticAnalysisActivity`, ViewStub steps) declares full `configChanges`,
so rotation does not recreate it; the state-loss risk is process death
([ADR-005](docs/adr/ADR-005-wizard-process-death.md)). The batch is
`DicBatchRunner.kt`, an extension (`AnalysisViewModel.runBatchAnalysisBody`), not a type.

Home **+** opens `MediaPickerSheet` (shared with the wizard dropzones). Uploads,
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

Empty `app/lint-baseline.xml` / `app/detekt-baseline.xml`: extract or `@file:Suppress`,
never stuff a baseline. `OldTargetApi` stays disabled until a deliberate `targetSdk`
36→37 bump, and no `warningsAsErrors`. Settings / wizard XML stay under
`TooManyViews` via `SettingsScrollContentView` / `WizardStepSettingsContentView`.

Kover `minBound` 37, enforced by `:app:koverVerify` in tier 1 and `ciReleaseGate`
(39.1 % on 2026-09-24). Macrobenchmark CI is emulator smoke, no thresholds
([TESTING.md](docs/app/TESTING.md)). The engine floor (≥ 4557 solves/s,
[PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)) is a manual
reference, not CI. Keep `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-09-24)

- **Deployed.** Production is Cloud Run `semper-api` (`semper-api-35992296245-1`,
  from `4d5a0ab`) behind API Gateway `semper-gw` (config `v202609241122-44`,
  now deployed by CI, ADR-006); staging `semper-api-staging`;
  project IDs keep `indic-*` ([ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md)). Licensing
  is live, consoles on `app.sempermechanics.com` ([§20](docs/backend/CLOUD_ARCHITECTURE_GCP.md)).
  Latest: the #155–#168 burn-down, #173/#179 backend dependency and base-image
  bumps, #183 and the first CI gateway apply ([CHANGELOG.md](docs/ops/CHANGELOG.md)).
- **App release `v1.2-beta.2`** (beta, private GitHub Release, from `fab33cb`):
  `v1.2-beta.1` (the burn-down's app half, #180's strain window in data points,
  engine `v0.2.2`) plus #182, the upload CSV's stats rows above the point
  section (TD-66) ([CHANGELOG.md](docs/ops/CHANGELOG.md)).
- **Ported from material_testing, awaiting release (#189).** Four general-purpose
  fixes from material_testing `3a1a941` / `c15efd3`: the speckle reading stays
  inside the ROI and counts only textured patches (`SubsetRecommender`), the
  `VsgPlotView` y gutter fits its widest tick, `TouchImageView` keeps a zoom
  across a resize, and `AviReader.frameIndexAt` takes half a µs of slack.
- **Request volume, in progress.** One app open sent 19 backend requests
  (10 config, 8 session listings). Pass 1 lets only one `CloudSync.reconcile`
  run at a time, so the calls Home starts together list the cloud once
  ([perf/request-volume.md](docs/perf/request-volume.md)). Device check owed.
- **material_testing shares this history.** It merged this repo's `main` at
  `643462c` (sempermechanics/material_testing#22), so the next sync either way
  is a plain `git merge`. Its lab features (test type, loads, reports) stay
  there; only general-purpose fixes come here.
- **Owed.** A device smoke of `v1.2-beta.2` and its public distribution
  (website / Play). Video/AVI import has run only on emulators
  ([WORKFLOWS.md](docs/app/WORKFLOWS.md) §5.1a). Unchecked "Licensing rollout"
  rows in [PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md).
- **Look it up; this list rots.** `gh pr list --state open`; history in
  [CHANGELOG.md](docs/ops/CHANGELOG.md); proposals in
  [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

## Traps

- An undeclared route 404s in production with nothing in the logs: ESPv2 is an allowlist. `test_gateway_parity.py` checks the spec — [§20.9](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `MAX_SESSIONS_PER_USER` is deleted; a deployment still setting it silently gets `DEMO_MAX_ANALYSES` (25) — [§7](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- List env vars (`CONSOLE_ORIGINS`, `ADMIN_EMAILS`) are space-separated; the deploy action splits on commas — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Restore on a new device has an order: sign in, let one authed request bind the lock, then restore — [§20.10](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Licence terms are mirrored onto users, so editing a licence reaches nobody without `update_license`'s fan-out — [§20.6](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Demo uploads silently; only retrieval is gated. Gating `POST /v1/sessions` would loop old builds on 403 — [§20.3](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Console CSP is `script-src 'self'` with no `'unsafe-inline'`: inline scripts never run — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `check_console.py` requires `__API_BASE_URL__` / `__API_ORIGIN__` to stay placeholders; deploy through `scripts/deploy-console.sh` — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `SCHEMA_VERSION` is 2 and every `campus` / `plan` skew fallback is temporary; retire in the stated order — [§20.5](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Backup stamps PENDING before `CloudSync.enqueueUpload`; reversing it lets a late PENDING overwrite SYNCED — [§8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A 429 that consumes the nonce makes the app's retry a 401 replay: keep a signed route's bucket in `dependencies=[deps.rate_limited(...)]`, never in the handler; unsigned routes call `rate_limit.enforce` — `test_rate_limit_before_nonce.py`.
- Activities are `@MainThread` at class level, so a private helper that runs on `Dispatchers.IO` needs `@WorkerThread` (or `@AnyThread`) or lint fails — TD-24 in [TECH_DEBT.md](docs/ops/TECH_DEBT.md).
- `SessionStore`'s parser uses `ignoreUnknownKeys` so old `index.json` fields load; keep it — [SessionStoreLegacyFloorTest](app/src/test/java/com/indicvision/semper/data/SessionStoreLegacyFloorTest.kt).
- `SubsetRecommender` runs on the paper's `NOISE_VARIANCE`; no import supplies a measured floor — [SubsetRecommender.kt](app/src/main/java/com/indicvision/semper/ui/analysis/SubsetRecommender.kt).
- Viewer screens read `ViewerArgs.from(intent, …)`, never `intent.get…Extra(DicKeys…)`; a new viewer field goes in `ViewerArgs`, its default and its `SessionRecord` mapping — [ADR-003](docs/adr/ADR-003-viewerargs-read-side.md).
- A new wizard input must survive a kill: scalars go in `WizardState`'s Bundle, bytes and lists in `WizardDraft`; and `cacheDir/temp_deformed` is only safe from the janitor while the draft is live — [ADR-005](docs/adr/ADR-005-wizard-process-death.md).
- After Compute, read the run's `RunSpec` / `RunResult` (`spec`, `settings`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Two runs of one build on the emulator do not give bit-identical `.dat` (TD-65), so a hash match cannot prove "engine unchanged"; digest the JNI inputs instead — [ADR-004 As built](docs/adr/ADR-004-runspec.md#as-built-2026-09-23).
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/indicvision/semper/ui/analysis/ConvergenceGate.kt).

**PRs target `main`.** Do not force-push `main`.
