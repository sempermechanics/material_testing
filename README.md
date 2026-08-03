# Semper — Digital Image Correlation on Android

[![CI](https://github.com/shankar-indicvision/IndicVisionDIC/actions/workflows/ci.yml/badge.svg)](https://github.com/shankar-indicvision/IndicVisionDIC/actions/workflows/ci.yml)

Semper measures **how objects deform** — from nothing but photographs.
Paint a random speckle pattern on a specimen, photograph it before and during
loading, and the app computes full-field **displacement** (U, V — to ~1/100th
of a pixel) and **strain** (Exx, Eyy, Exy) everywhere on the surface. Results
render as interactive heatmaps and export as PDF reports, CSVs, and images.

Everything runs **on the phone**: the correlation engine is native C++
(OpenCV + Eigen), works fully offline, and optionally syncs results to the
cloud for authenticated testers.

## Features

- **Full-field 2D DIC** — ICGN sub-pixel solver, AKAZE + Delaunay mesh
  seeding, reliability-guided (RGDIC) propagation, VSG strain
- **Batch analysis** — one reference vs. many deformed frames, or frames
  extracted automatically from a video
- **Region of interest** — rectangles or freehand-painted masks
- **Interactive results** — heatmaps per field, point inspector, min/max
  markers, custom color scales, frame scrubbing
- **Exports** — multi-page PDF metrology report, per-frame and batch CSV,
  annotated PNGs, batch image ZIP
- **Offline-first cloud sync** — analyses queue on-device and upload to
  Google Drive via the GCP backend when a network appears
  ([docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md))
- **Portable SIMD** — one code path compiles to NEON on the shipped arm64-v8a
  build and SSE on x86 emulator builds

## Quick start

```bash
git clone https://github.com/shankar-indicvision/IndicVisionDIC
cd IndicVisionDIC
git submodule update --init      # Eigen + OpenCV sources (large, one-time)
# Optional: drop unused OpenCV doc/samples/data/apps (keeps modules + hal for Carotene)
./scripts/sparse-opencv.sh       # or: .\scripts\sparse-opencv.ps1 on Windows
```

Open the folder in Android Studio and press Run. The first build compiles
OpenCV from source — slow once, then cached. **No API keys or accounts are
required**; without them, cloud sync simply stays off and everything else
works.

Running on an **emulator**? Build for its ABI and sign-in is skipped
automatically:

```bash
./gradlew :app:installDebug -PabiFilters=x86_64
```

A debug build on an emulator boots straight to Home as a local-only dev
account, with cloud calls switched off for the run (so nothing hits the backend
unauthenticated). It cannot happen in a release build or on a physical device.
To exercise the real sign-in flow on an emulator, put
`INDIC_DEV_AUTH_BYPASS=false` in `local.properties`.

## How it works

```
reference + deformed images
        │  decode, grayscale, optional blur          (OpenCV imgcodecs/imgproc)
        ▼
AKAZE feature matching ──▶ global seeds ──▶ Delaunay mesh solve   "Path A"
        │                                        │
        │              unsolved points ──▶ RGDIC flood-fill        "Path B"
        ▼                                        │
   per-point ICGN refinement (C++/SIMD, OpenMP + thread pool)
        ▼
displacement field (U,V) ──▶ VSG least-squares ──▶ strain (Exx,Eyy,Exy)
        ▼
.dat results ──▶ heatmaps / PDF report / CSV ──▶ optional cloud sync
```

Each tracked point is a small **subset** (window) of the reference image; the
**ICGN** solver warps it with 6 degrees of freedom until it matches the
deformed image, scored by **ZNSSD** (0 = perfect, ≤ 0.15 accepted — the score
is immune to lighting changes). Full detail:
[docs/engine/ARCHITECTURE.md](docs/engine/ARCHITECTURE.md).

### Analysis parameters

Three numbers drive the solve. They live under **Advanced parameters** in the
setup wizard and can be set either by dragging the slider or by typing into
the value field next to it — the two stay in sync.

| Parameter | Default | Range | Notes |
|---|---|---|---|
| Subset size | measured (41 px fallback) | 15–101, **odd only** | The tracked window. Bigger = more robust, less spatial detail. The starting value is measured from the reference speckle (SSSIG criterion, below) |
| Step size | 5 px | 1–30 | Grid spacing between tracked points. Smaller = denser field, slower |
| Strain window | 15 px | 5–51, **odd only** | VSG gauge length for the displacement→strain fit |

The two window sizes are odd because the engine indexes a subset as
`[−dim/2, +dim/2]` around its center pixel; an even width would sit
off-center. A typed even value snaps to the nearest odd one, and any value
outside the range is clamped.

**Subset size is suggested, not guessed.** When a reference image is loaded,
the app applies the SSSIG criterion of Pan et al., *Opt. Express* **16**, 7037
(2008): the standard-deviation error of a measured displacement is
`σ = √(D(η) / SSSIG)`, where `D(η)` is the image-noise variance and SSSIG is
the sum of squared intensity gradients over the subset. Inverting it for a
noise variance of 4 gray levels² and a target accuracy of 0.007 px gives the
SSSIG a subset must reach; the app grows the subset by 2 px at a grid of points
across the ROI until both directions clear it, and suggests the median. Weak
speckle therefore asks for a bigger window than strong speckle, automatically.
Change the value and yours is kept — Reset returns to the suggestion.

## Repository map

| Path | What lives there |
|---|---|
| `native/include/semper/` | Public C++ API (types, image, solver, strain, pipeline) |
| `native/src/math/` | ICGN solver + subset/image math + SIMD kernels |
| `native/src/strain/` | VSG strain |
| `native/src/seeding/` | AKAZE + RANSAC seeding |
| `native/src/pipeline/` | Full-field Path A/B/C orchestration |
| `native/adapters/android/` | Thin JNI → `libsemper_core.so` |
| `app/src/main/java/.../ui/analysis/` | Setup wizard, ROI drawing |
| `app/src/main/java/.../ui/viewer/` | Heatmap viewer + exports |
| `app/src/main/java/.../ui/auth/` | Sign-in and access gating |
| `app/src/main/java/.../report/` | PDF report generation |
| `app/src/main/java/.../data/` | Cloud sync client, upload worker |
| `backend/` | GCP backend (FastAPI on Cloud Run) |
| `native/tests/` | Native test suite — runs on your PC, no device |
| `docs/` | **[Documentation index](docs/README.md)** — engine, backend, ops |

## Testing

```bash
# C++ engine tests: synthetic images with exact known deformations
cmake -S native/tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests -j
./build/native-tests/dic_tests

# Kotlin unit tests + quality gates
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug
```

CI runs both on every push, plus AddressSanitizer/UndefinedBehaviorSanitizer
and ThreadSanitizer builds of the engine suite, and an arm64-v8a native build
that proves the shipped `.so` links. Details in [docs/ops/CI.md](docs/ops/CI.md);
the test catalog is in [docs/engine/TESTING.md](docs/engine/TESTING.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Start with the
[documentation index](docs/README.md) for the DIC primer and glossary, and
[docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md) for the Android UI map.
You can contribute without knowing all of it: UI and docs work needs no C++,
and the engine tests run on your PC with no Android at all. Issues tagged
`good first issue` are scoped for newcomers.

## License

See [LICENSE](LICENSE). Terms will be finalized before a public release; until
then, all rights reserved.
