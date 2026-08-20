# Semper documentation

Start with the [project README](../README.md) for what Semper is and how to
build it. This page routes you to the rest.

## Find your way by what you're doing

| I want to… | Read |
|---|---|
| Understand DIC itself before touching anything | [DIC in five minutes](#dic-in-five-minutes) below |
| Change the Android UI / understand app flow | [app/ARCHITECTURE.md](app/ARCHITECTURE.md) |
| Walk every user flow, or run a manual test pass | [app/WORKFLOWS.md](app/WORKFLOWS.md) |
| See which errors open the public FAQ | [app/FAQ_LINKS.md](app/FAQ_LINKS.md) |
| Operate the app to analyse a DIC image set | [OPERATING_MANUAL.md](OPERATING_MANUAL.md) |
| Change the correlation engine (C++) | [engine/ARCHITECTURE.md](engine/ARCHITECTURE.md) — the engine is a submodule, so this points into `native/docs/` |
| Know what the app may assume of the engine | [engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md) |
| Check the math, or write it up | [engine/MATHEMATICS.md](engine/MATHEMATICS.md) |
| Add or run app tests | [app/TESTING.md](app/TESTING.md) |
| Benchmark the app, or check a perf claim | [app/TESTING.md — Performance benchmarks](app/TESTING.md#performance-benchmarks), then [perf/](perf/) |
| Add or run engine tests | [engine/TESTING.md](engine/TESTING.md) |
| Build, test or bump the engine pin | [../CONTRIBUTING.md](../CONTRIBUTING.md) |
| Know why CI is red / how tiers work | [ops/CI.md](ops/CI.md) |
| Cut a release | [ops/RELEASING.md](ops/RELEASING.md) |
| Map GitHub Environments / secrets / main hygiene | [ops/ENVIRONMENTS.md](ops/ENVIRONMENTS.md) |
| Production launch checklist | [ops/PRODUCTION_READINESS_GATE.md](ops/PRODUCTION_READINESS_GATE.md) |
| Tech-debt status / deferred gates | [ops/TECH_DEBT.md](ops/TECH_DEBT.md) |
| Work on the cloud backend | [backend/CLOUD_ARCHITECTURE_GCP.md](backend/CLOUD_ARCHITECTURE_GCP.md) |
| Deploy the backend myself | [backend/BACKEND_SETUP_GCP.md](backend/BACKEND_SETUP_GCP.md) (CLI) or [BACKEND_SETUP_CONSOLE.md](backend/BACKEND_SETUP_CONSOLE.md) (browser) |
| Fix sign-in / set up auth | [backend/AUTH_SETUP.md](backend/AUTH_SETUP.md) |

Nothing in `backend/` is needed to build, run, or contribute to the app — the
analysis engine is entirely on-device and offline.

## How the docs are organized

```
docs/
  app/       Android UI layer — Activity flow, packages, session layout
  engine/    the app-facing engine contract, plus stubs into the submodule's own docs
  backend/   the optional GCP cloud side — architecture, setup, sign-in
  legal/     privacy policy and terms — the source the hosted pages are generated from
  ops/       running the project — CI, releases, environments, tech debt, readiness gate
  perf/      measured before/after reports backing performance changes
  design/    UI wireframes kept as reference for a redesign in flight
```

`docs/engine/` is mostly signposts: the engine lives in its own repository, and
its architecture, math and test docs are canonical at `native/docs/`. The one
real document here is
[engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md), which states what
the app is allowed to assume across the JNI boundary.

## DIC in five minutes

Digital Image Correlation measures deformation from photographs:

1. A specimen is painted with a random **speckle pattern**.
2. A **reference** photo is taken, then more photos while it deforms.
3. The image is divided into small windows called **subsets** (41×41 px by
   default). Subset widths are always odd — a subset is indexed around its
   center pixel.
4. For each subset the engine finds where it moved in the deformed image, to
   ~1/100th of a pixel, using **ICGN**: an iterative Gauss-Newton solver that
   minimizes **ZNSSD**, a difference score immune to lighting changes (lower is
   better; ≤ 0.15 is accepted).
5. Displacements (**U** horizontal, **V** vertical, in pixels) are
   differentiated into **strains** (**Exx**, **Eyy**, **Exy**) — how much the
   material stretched and sheared.

That is enough theory for most contributions. The full pipeline — AKAZE feature
seeding → Delaunay mesh → RGDIC propagation → ICGN refinement → VSG strain — is
in `native/docs/ARCHITECTURE.md` inside the engine submodule.

## Terms you'll meet in the code

| Term | Meaning |
|---|---|
| `subset` | The small pixel window being tracked (`SubsetData`). Odd width, always |
| `step` | Grid spacing between tracked points, in px |
| `corr` / ZNSSD | Match quality. 0 = perfect, > 0.15 = rejected, < 0 = failed-point sentinel |
| `ICGN` | The iterative sub-pixel solver (`OptimizationEngine`) |
| `RGDIC` | Reliability-guided propagation — solved points seed their neighbors |
| `VSG` / strain window | Least-squares plane fit over neighboring points that turns displacement into strain. Odd width |
| `.dat` files | Binary results: 8 floats per point (x, y, u, v, exx, eyy, exy, znssd) |

For the directory layout, see the [repository map](../README.md#repository-map)
in the project README — it is maintained in one place so the two cannot drift.

## Conventions

- UI strings belong in `strings.xml`, never hardcoded.
- Shared intent keys live in `DicKeys.kt`; binary-format constants in
  `DicResult.kt`.
- Engine changes happen in the engine repository and must keep `dic_tests`
  green there. Results must stay inside the tolerance contract in
  [engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md); if a change
  legitimately moves results, say so explicitly and update the contract on both
  sides.
- Lint and detekt baselines are empty — new findings fail CI. A few large UI
  files use targeted `@file:Suppress` for inherent size; prefer extracts.
- Run `./gradlew spotlessApply` before pushing.
