# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Semper is a single Android app (`:app`) that performs 2D Digital Image Correlation
(DIC) on-device: it measures full-field displacement and strain from photographs of
a speckled specimen. The correlation math is native C++; the UI and orchestration are
Kotlin. An optional FastAPI backend (`backend/`) provides authenticated cloud sync.

`CONTRIBUTING.md` is the source of truth for build/test/gate commands, and `docs/`
holds the deep documentation. Prefer reading those over re-deriving from code.

## The C++ engine is not in this repo

The correlation engine lives in a **separate repository**
(`semperdic/semper-dic-engine`) and is pinned here as a git submodule at `native/`,
which itself has Eigen/OpenCV submodules. Consequences:

- Clone with `git submodule update --init --recursive`, or `native/` is empty and the
  first CMake build fails confusingly.
- `app/src/main/cpp/` is only the app's side of the JNI boundary. The solver, strain,
  seeding, and engine tests are in the submodule — do not look for them here.
- Bumping the engine = a normal PR changing the `native` gitlink. Engine host tests,
  DICe-comparison, and sanitizer suites run in the engine repo, **not here**; this
  repo's CI only proves the pinned engine still links (arm64 release + x86_64 emulator).
- The app-facing contract (what the engine guarantees, numeric-result tiers) is
  `docs/engine/ENGINE_APP_CONTRACT.md`. Never edit the engine from this repo's tree.

## Common commands

Use `./gradlew` (macOS/Linux/Git Bash) or `gradlew.bat` (Windows PowerShell).

```bash
# Full local push gate — mirrors CI tiers 1 + 5 (native/backend run separately)
./gradlew ciReleaseGate

# The app gate on its own (no native/release build)
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug

# Run one test chunk (chunks map to workflow areas — see docs/app/TESTING.md)
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.results.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.viewer.*"

# Format (Kotlin only; C++ under native/ is excluded from Spotless)
./gradlew spotlessApply        # spotlessCheck to verify

# Install on an emulator (skips sign-in, builds only the emulator ABI)
./gradlew :app:installDebug -PabiFilters=x86_64

# Emulator smoke test (needs a running x86_64 AVD)
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64

# Backend suite
cd backend && pip install -r requirements-test.txt && pytest tests/ -v
```

Two non-path-filtered gates also run on **every** PR (docs-only included):

```bash
python scripts/render_legal_pages.py --check   # legal pages match docs/legal/
gitleaks detect --config .gitleaks.toml         # secrets scan
```

Native OpenCV build cache lives under `app/.cxx/` (~GB); safe to delete to reclaim space.

## Architecture (Kotlin layer)

Full map: `docs/app/ARCHITECTURE.md`. Package root is `com.indicvision.semper`.

Activity flow: `SplashActivity` → (`AuthActivity` | `PendingApprovalActivity` |
`HomeActivity`) → `StaticAnalysisActivity` → `ResultViewerActivity`
(or `VsgLatticeActivity` for parameter sweeps).

- `ui/analysis/` — setup wizard, ROI drawing, VSG parameter sweep, `AnalysisViewModel`
- `ui/viewer/` — heatmaps, point inspector, export hand-off
- `ui/auth/` — sign-in, access gating; **route via `ui/auth/AccessRouter.kt`**, do not
  re-encode `"APPROVED"`/`"PENDING"` string switches in new screens
- `data/` — auth, `SessionStore`, cloud sync/upload/restore workers, storage budget
- `report/` — PDF/CSV/visualization export
- Intent extras are centralized in `DicKeys.kt`.

Convention: prefer extracting a small `object`/`*Helper`/`*Runner`/`*Router` next to
existing ones over growing a god Activity. **There is no DI framework** (no Hilt/Dagger,
zero `@Inject` sites) — don't add one for small helpers; propose it in its own PR if a
screen grows real injectable dependencies. `findViewById` is used throughout;
ViewBinding is deliberately off.

## Non-obvious invariants

Breaking these tends to surface as OOM, a mid-run crash, or a silent "nothing happened"
(full list in `docs/app/ARCHITECTURE.md` → Memory & failure invariants):

- The JNI output `ByteBuffer` is pre-sized to the ROI grid; an engine point count over
  capacity is treated as failure, never read past the buffer.
- Report/upload compositing is capped to `VisualizationEngine.REPORT_MAX_EDGE` (1280 px);
  on-screen scrub uses `DISPLAY_MAX_EDGE` (1080). These caps stop full-res bitmaps OOMing.
- Batch progress is a buffered `SharedFlow` (`DROP_OLDEST`), not a `StateFlow` — a
  conflating flow dropped intra-frame ticks and stalled the progress bar.
- Transfer failures are surfaced with a human reason via `WorkInfo` output, not swallowed.
- A Drive outage must never look like deleted data: local metadata is dropped only when
  the backend **confirms** a blob is missing, never on an indeterminate answer.

## Build/config gotchas

- **Release builds require `INDIC_API_BASE_URL` (HTTPS)** and fail without it, so cloud
  sync cannot ship silently disabled. Debug/local builds without it just run offline.
- `DEV_AUTH_BYPASS` is debug-only and hardcoded `false` in release. On an emulator a
  debug build boots straight to Home as a local-only account with cloud off. Set
  `INDIC_DEV_AUTH_BYPASS=false` in `local.properties` to exercise real sign-in.
- Ships **arm64-v8a only**; debug/benchmark variants add x86_64 for emulators. Override
  with `-PabiFilters=...`.
- **Never hand-edit `firebase-hosting/public/privacy/` or `.../terms/`** — they are
  generated from `docs/legal/*.md` by `scripts/render_legal_pages.py`. Edit the markdown
  and re-run the script (without `--check`), committing both.
- Spotless (ktlint 1.5.0) and detekt (MaxLineLength 120) must not fight over the same
  lines — several function-signature/expression-body ktlint rules are disabled in the
  root `build.gradle.kts` for exactly this reason. Coverage (Kover) excludes `ui/`.

## Where to change what / more docs

- `docs/README.md` — DIC primer, glossary, documentation index
- `docs/app/WORKFLOWS.md` — every user-facing screen and flow
- `docs/app/TESTING.md` — what each test chunk owns
- `docs/backend/` — auth setup, GCP cloud architecture, Firestore runbooks
- `docs/ops/CI.md` — CI tier map and required checks (the one required status is `CI OK`)
- `docs/ops/RELEASING.md` — release process (every handed-out build bumps `versionCode`)

Target PRs at **`main`**, keep them to one concern, and if you change auth/quotas/deploy
env vars/CI modes, update the matching `docs/` page in the same PR.
