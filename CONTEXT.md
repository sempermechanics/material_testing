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
A class with cloud decisions to test takes `api: CloudApi = IndicApi.get(context)` and
`tokens: TokenSource = TokenProvider` as defaulted parameters; tests pass `FakeCloudApi`
([ADR-002](docs/adr/ADR-002-cloudapi-seam.md)).

Wizard later steps inflate through **ViewStubs**. `goToStep` stays on
`StaticAnalysisActivity`. Slot chrome / coach marks: `AnalysisWizardSlots` /
`AnalysisWizardCoach`. The wizard declares full `configChanges` in the manifest,
so rotation does not recreate it; the state-loss risk is process death
([ADR-005](docs/adr/ADR-005-wizard-process-death.md)).

Full-field batch: `DicBatchRunner.kt` (`AnalysisViewModel.runBatchAnalysisBody`,
an extension, not a type) + `DicFieldIo` shared with VSG. JNI
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

Kover `minBound` is 37 (`app/build.gradle.kts`), enforced by `:app:koverVerify`
in CI tier 1 and `ciReleaseGate` (measured 39.1 % on 2026-09-24). Macrobenchmark CI is
emulator **smoke** (`suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED`), API 34, no
numeric thresholds.

Engine perf floor: [docs/engine/PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)
(≥ 4557 solves/s host) is a manual engine-repo reference; no CI job enforces
it. Preserve `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-09-24)

- **Deployed.** Production is Cloud Run `semper-api` behind API Gateway
  `semper-gw` (staging `semper-api-staging`); #146 renamed services and queues,
  project IDs keep `indic-*` ([ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md)).
  Licensing is live, consoles on `app.sempermechanics.com` ([§20](docs/backend/CLOUD_ARCHITECTURE_GCP.md)).
- **Live 2026-09-24.** #154: signed routes take their rate-limit bucket as
  `dependencies=[deps.rate_limited(...)]`, resolved before `verified_device`,
  so a 429 no longer spends the nonce; 429s send `Retry-After`. Production
  `semper-api-35957034833-1`; proven from a Pixel 6 ([CHANGELOG.md](docs/ops/CHANGELOG.md)).
- **Merged, deploy pending (#169).** Terms §1.3 admits users from 16, with a
  parent or guardian agreeing under 18; Privacy §8 matches. `TERMS_VERSION`
  `2026-09-24` in `backend/app/legal.py` and `LegalTerms.kt`, so every user
  re-accepts once after the backend deploys. Open for owner and counsel: §1.2
  (professional use only) and the DPDP Act's verifiable parental consent,
  which the clickwrap does not collect.
- **In flight.** Tech-debt burn-down, one PR per step (#155–#168 open,
  each stacked on the one before): [TECH_DEBT.md](docs/ops/TECH_DEBT.md) register,
  [docs/adr/](docs/adr/README.md) ADR-001..006 all built. ADR-006's gateway
  job has never run: it waits on the owner's IAM grant and a `dry-run` dispatch. Video/AVI import
  (#136–#139) has run only on emulators ([WORKFLOWS.md](docs/app/WORKFLOWS.md)
  §5.1a). Unchecked "Licensing rollout" rows in
  [PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md) are owed.
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
