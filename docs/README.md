# inDIC documentation

Start with the [project README](../README.md) for what inDIC is and how to
build it. This page routes you to the rest.

## Find your way by what you're doing

| I want to… | Read |
|---|---|
| Understand DIC itself before touching anything | [DIC in five minutes](#dic-in-five-minutes) below |
| Change the Android UI / understand app flow | [app/ARCHITECTURE.md](app/ARCHITECTURE.md) |
| Walk every user flow, or run a manual test pass | [app/WORKFLOWS.md](app/WORKFLOWS.md) |
| Change the correlation engine (C++) | [engine/ARCHITECTURE.md](engine/ARCHITECTURE.md) |
| Check the math, or write it up | [engine/MATHEMATICS.md](engine/MATHEMATICS.md) |
| Add or run app tests | [app/TESTING.md](app/TESTING.md) |
| Add or run engine tests | [engine/TESTING.md](engine/TESTING.md) |
| Know why CI is red | [ops/CI.md](ops/CI.md) |
| Cut a release | [ops/RELEASING.md](ops/RELEASING.md) |
| Work on the cloud backend | [backend/CLOUD_ARCHITECTURE_GCP.md](backend/CLOUD_ARCHITECTURE_GCP.md) |
| Deploy the backend myself | [backend/BACKEND_SETUP_GCP.md](backend/BACKEND_SETUP_GCP.md) (CLI) or [BACKEND_SETUP_CONSOLE.md](backend/BACKEND_SETUP_CONSOLE.md) (browser) |
| Fix sign-in / set up auth | [backend/AUTH_SETUP.md](backend/AUTH_SETUP.md) |

Nothing in `backend/` is needed to build, run, or contribute to the app — the
analysis engine is entirely on-device and offline.

## How the docs are organized

```
docs/
  app/       Android UI layer — Activity flow, packages, session layout
  engine/    the C++ correlation engine — architecture, math, tests
  backend/   the optional GCP cloud side — architecture, setup, sign-in
  ops/       running the project — CI, releases
```

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
in [engine/ARCHITECTURE.md](engine/ARCHITECTURE.md).

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

## Where things live

| Path | What it is |
|---|---|
| `native/src/math/` | ICGN solver + SIMD kernels (the hot loops) |
| `native/src/pipeline/` + `seeding/` | Full-field orchestration + AKAZE |
| `native/adapters/android/` | Thin JNI → `libindicvision_core.so` |
| `app/src/main/java/.../ui/analysis/` | Setup wizard, ROI drawing |
| `app/src/main/java/.../ui/viewer/` | Heatmap viewer, exports, share sheet |
| `app/src/main/java/.../report/` | PDF report generation |
| `app/src/main/java/.../data/` | Cloud sync client, upload/restore workers |
| `native/tests/` | Native test suite — runs on your PC, no device |
| `backend/` | GCP backend (FastAPI on Cloud Run) |

## Conventions

- UI strings belong in `strings.xml`, never hardcoded.
- Shared intent keys live in `DicKeys.kt`; binary-format constants in
  `DicResult.kt`.
- Engine changes must keep `dic_tests` green, and results must stay inside the
  tolerance contract in [engine/TESTING.md](engine/TESTING.md). If a change
  legitimately moves results, say so explicitly and update the contract.
- Only *new* detekt/lint findings fail CI; existing ones are baselined. Run
  `./gradlew spotlessApply` before pushing.
