# Engine architecture

API-style reference for the native Digital Image Correlation (DIC) engine and
its Android integration — module by module, with the invariants each one
depends on. Written for developers modifying, extending, or debugging the
pipeline.

New to DIC? Read the [five-minute primer](../README.md#dic-in-five-minutes)
first. For the derivations behind the code see
[MATHEMATICS.md](MATHEMATICS.md); for how it's verified,
[TESTING.md](TESTING.md).

```
┌─────────────────────────  Kotlin / Android  ─────────────────────────┐
│ StaticAnalysisActivity ──▶ IndicVisionNativeLib (JNI surface)        │
│        │                          │                                  │
│  AuthActivity/Splash        DicUploadWorker ──▶ Cloud Run ─▶ Drive   │
└───────────────────────────────────┼──────────────────────────────────┘
                                    ▼  JNI (bridge/IndicVisionJNI.cpp)
┌─────────────────────────── C++ Engine ───────────────────────────────┐
│                                                                       │
│  preprocessing/          core/                    postprocessing/     │
│  ┌────────────────┐      ┌──────────────────┐     ┌────────────────┐  │
│  │ ImageProcessor │──▶──│ SubsetPrecomputer │──▶──│                │  │
│  │  (Image, interp│      │  (SubsetData)    │     │ StrainCalculator│ │
│  │   gradients)   │      └──────────────────┘     │  (VSG / NLVC)  │  │
│  └────────────────┘      ┌──────────────────┐     └────────────────┘  │
│                          │ OptimizationEngine│                        │
│                          │  (ICGN + Simplex) │                        │
│                          └──────────────────┘                        │
│                    core/SimdKernels.h (portable SIMD, all ABIs)       │
└───────────────────────────────────────────────────────────────────────┘
```

**Data flow for one analysis:** decode images → `Image` (float intensities +
gradients) → for each grid point: `SubsetPrecomputer` builds a `SubsetData` →
`OptimizationEngine.calculate_deformation` solves the 6-DOF warp →
displacement field → `StrainCalculator` → results buffer back through JNI.

---

## Module: `core/Types.h`

Shared value types. All math is 32-bit `float` (`scalar_t`).

### `struct AnalysisResult`
The engine's answer for one subset.

| Field | Type | Meaning |
|---|---|---|
| `u, v` | float | Displacement of the subset center (px) |
| `ux, uy, vx, vy` | float | Displacement gradients (∂u/∂x, ∂u/∂y, ∂v/∂x, ∂v/∂y) |
| `status` | int | `0` = converged, non-zero = failed/aborted |
| `correlation_score` | float | Final ZNSSD / valid_pixels. **Lower is better**; `2.0` = worst |
| `iters` | int | ICGN iterations consumed |
| `invalid_ref_pixels` | int | Reference pixels masked by the ROI ("Ghost Wall") |

### `struct SubsetData`
Everything precomputed about one reference subset. Invariants:

- `x_offsets/y_offsets` (and `_f` float mirrors): relative coords in `[-dim/2, +dim/2]`, row-major, length `n = dim²`.
  **`dim` must be odd** — the offsets are built symmetrically around the center
  pixel, so an even `dim` yields an off-center subset. The UI enforces this
  (see [Parameters from the app](#parameters-from-the-app)); direct callers must.
- `norm_ref_intensities[i] = (ref_intensities[i] − mean_intensity) / std_dev`.
- `H` = Σ sd·sdᵀ (6×6 Gauss-Newton Hessian), `H_inv` = its inverse.
- `steepest_descent_images[i]` = `[gx, gy, gx·x, gx·y, gy·x, gy·y] / std_dev` (Eigen 6×1, AoS).
- **`sdi_planes`** = SoA mirror of the above: plane *k* occupies `[k·n, (k+1)·n)`.
  **Must stay in sync with `steepest_descent_images`** — the SIMD fast path reads
  the planes, the masked slow path reads the Eigen vectors.
  (Guarded by `SubsetPrecomputer.SdiPlanesMirrorSteepestDescentImages` test.)
- `is_initialized == false` ⇒ subset touched the image boundary or lacked texture; do not solve it.

---

## Module: `preprocessing/ImageProcessor` — class `Image`

Owns intensities + precomputed gradients for one image.

```cpp
Image(int_t w, int_t h, const uint8_t* raw_pixels);   // grayscale, row-major
void prepare_data(bool apply_dice_blur);              // gradients (+ optional 7-tap blur)
```

**Must call `prepare_data` before any gradient/interpolation consumer.**
Gradients are 4th-order central differences (exact for polynomials ≤ deg 4),
valid in the interior `[2, dim−3]`; the border ring is zero.

### Interpolation ladder

| Method | Kernel | Valid region | Out-of-range behavior |
|---|---|---|---|
| `interpolate_keys_fourth(x,y)` | 6×6 Keys 4th-order | `(2.5, w−3.5)` | demotes → bicubic boundary rule |
| `interpolate_bicubic(x,y)` | 4×4 Catmull-Rom | `[1, w−2)` | demotes → bilinear |
| `interpolate_bilinear(x,y)` | 2×2 | `[0, w−1.5)` | returns `0.0f` (kills pixel) |

All three are *interpolating* kernels: they reproduce pixel values exactly at
integer coordinates and reproduce linear ramps exactly at sub-pixel positions.
The engine treats intensity `< 0` as "dead pixel" — hence bilinear's `0.0f`
return is a sentinel further filtered by callers (`val > 0` checks).

---

## Module: `preprocessing/SubsetPrecomputer`

Static factory for `SubsetData`. Three entry points with one contract:
**all paths must produce interchangeable state** (guarded by
`FastPathMatchesFullPrecompute`).

```cpp
// Full precompute: stats + gradients + SDI + Hessian + inverse.
static void precompute_subset(SubsetData&, const Image& ref, int cx, int cy, int dim);

// Stats + Hessian only — for the batch pipeline's Hessian pool.
static CachedHessianData compute_hessian_only(const Image& ref, int cx, int cy, int dim);

// Rebuilds per-pixel data but reuses pooled Hessian/stats (~65% cheaper).
static void precompute_subset_fast(SubsetData&, const Image& ref,
                                   int cx, int cy, int dim,
                                   const CachedHessianData& cached);
```

Failure modes (→ `is_initialized = false` / `cached.valid = false`):
subset crosses the image boundary; > 50% masked pixels; singular or
ill-conditioned Hessian (`cond(H₂ₓ₂) > 1e12`).

---

## Module: `core/OptimizationEngine`

Per-thread solver object (owns scratch buffers — **do not share across OMP threads**).

```cpp
AnalysisResult calculate_deformation(const SubsetData& subset, const Image& def_img,
                                     float guess_u, float guess_v,
                                     float guess_ux, float guess_uy,
                                     float guess_vx, float guess_vy,
                                     InitializationMode init_mode);
```

### Initialization modes

| Mode | Pipeline |
|---|---|
| `INIT_AUTO_SEARCH` | coarse ±15 px SSD grid search → translation-only Simplex → full ICGN |
| `INIT_NO_SEARCH` | ICGN from the given 6-DOF guess; Simplex rescue if ICGN fails or score > 0.4 |
| `INIT_NO_SIMPLEX` | pure ICGN from the given guess — no rescue (fastest, needs good seeds) |

### Warp convention (the sign contract everything depends on)

The shape function maps reference offsets `(x, y)` from subset center `c` to
deformed-image coordinates:

```
X_def = cx + (1+ux)·x + uy·y + u
Y_def = cy + vx·x + (1+vy)·y + v
```

i.e. a material point at `c + p` displaces by `t + [[ux,uy],[vx,vy]]·p`.
Synthetic test data must be generated with this exact convention
(see `app/src/test/cpp/framework/synthetic.h`).

### Solver internals

- **ICGN** (inverse-compositional Gauss-Newton): the Hessian is constant per
  subset (precomputed); each iteration interpolates the deformed subset,
  normalizes (ZNSSD → invariant to affine intensity changes `a·I + b`),
  accumulates the gradient via `simd::znssd_error_and_gradient`, then updates
  `W ← W · ΔW⁻¹`. Converges when `‖Δp‖ < 0.001`, max 50 iterations.
- **Guards:** ≥ 90% of warped pixels must land inside the image; post-convergence
  texture check (sum of squared gradients of surviving pixels) rejects
  texture-less matches.
- **Levenberg-Marquardt option** (`lm_enabled`, `lm_alpha`): adds `α` to
  `H(0,0)`/`H(1,1)` only (translation DOFs), DICe-compatible. Off by default.
- **Simplex** (Nelder-Mead on ZNSSD): derivative-free rescue for bad seeds;
  2-DOF or 6-DOF.

---

## Module: `core/SimdKernels.h`

Portable SIMD hot loops via **OpenCV universal intrinsics** — one codepath
compiled to NEON on ARM and SSE on x86, scalar fallback elsewhere. Header-only;
no OpenCV linkage required.

```cpp
namespace IndicVision::simd {
  // Σ (vals[i] − mean)²
  float sum_sq_diff(const float* vals, size_t n, float mean);

  // Σ (ref[i] − (vals[i] − mean)·inv_std)²
  float znssd_sum(const float* vals, const float* ref, size_t n,
                  float mean, float inv_std);

  // Fused: returns the ZNSSD error AND accumulates the 6 gradient
  // projections dp[k] = Σ sdi_plane_k[i]·diff over SoA planes.
  float znssd_error_and_gradient(const float* vals, const float* ref,
                                 const float* sdi_planes, size_t n,
                                 float mean, float inv_std, float dp_out[6]);
}
```

Contracts (all test-guarded):
- Exact for any `n` (full vector blocks + scalar tail).
- `znssd_error_and_gradient`'s error term ≡ `znssd_sum` for identical input.
- `sdi_planes` layout: plane `k` at `[k·n, (k+1)·n)` — produced by `SubsetPrecomputer`.

---

## Module: `postprocessing/StrainCalculator`

```cpp
struct DisplacementField { int width, height, step;       // grid dims + px spacing
                           std::vector<float> u, v;        // px displacements
                           std::vector<bool> valid; };

static StrainField compute_vsg_strain (const DisplacementField&, int window_pixels);
static StrainField compute_nlvc_strain(const DisplacementField&, int horizon_pixels);
```

Both output **Green-Lagrange strain**:
`exx = ∂u/∂x + ½((∂u/∂x)² + (∂v/∂x)²)`, etc.

| | VSG (default) | NLVC |
|---|---|---|
| Method | least-squares plane fit over a circular window | nonlocal Gaussian-derivative integral |
| Exact for | any linear displacement field | linear fields (up to quadrature error) |
| Rejection | < 90% window fill or `rcond < 1e-12` → sentinel `−1000.0f` | unbalanced kernel integral → stays `0.0f` |

**Consumer warning:** VSG failures are marked with `−1000.0f`, NLVC failures
with `0.0f`. Check before rendering/statistics.

---

## Module: `bridge/IndicVisionJNI.cpp`

JNI surface (see `IndicVisionNativeLib.kt` for the Kotlin declarations).
Key behaviors:

- `initializeReference(...)` caches the decoded reference image in
  `g_refImg` (guarded by `jni_engine_mutex`) so batch runs decode once.
- `computeFullFieldDirect(...)` runs the full pipeline with OpenMP
  (`schedule(dynamic, 32)`), reliability-guided seed propagation, and
  progress callbacks marshalled through a `NewGlobalRef` + `AttachCurrentThread`.
- Results are written into a direct `ByteBuffer` (no copy back through JNI).

Threading contract: **one `OptimizationEngine` + one `SubsetData` per OMP
thread**; `SubsetData` buffers are reused across grid points via the
`precompute_subset_fast` pool.

### Parameters from the app

`StaticAnalysisActivity` ("Advanced parameters" in the setup wizard) is the
only producer of the three solve parameters. Each has a slider plus an
editable numeric field; both write the same slider value, which is what
`currentSubsetSize()` / `currentStepSize()` / `currentStrainWindow()` read.

| Parameter | JNI argument | Default | Range | Step |
|---|---|---|---|---|
| Subset size | `subsetSize` → `dim` | 41 | 15–101 | 2 (**odd only**) |
| Step size | `stepSize` → grid spacing | 5 | 1–30 | 1 |
| Strain window | `strainWindow` → `window_pixels` | 15 | 5–51 | 2 (**odd only**) |

Typed input is clamped to the range and snapped onto the slider's step grid
by `snapToSlider`, so the odd-`dim` invariant above holds for hand-entered
values too. Fields commit on IME "Done", on focus loss, and before the solve
starts — a pending edit can never reach the engine uncommitted.

---

## Native dependencies (git submodules, built from source)

Both native libraries are **git submodules pinned to release tags**, not vendored
binaries. Run `git submodule update --init --recursive` after cloning.

| Dependency | Path | Version | How it's used |
|---|---|---|---|
| **Eigen** | `third_party/eigen` | 3.4.0 | header-only; added via `include_directories` |
| **OpenCV** | `third_party/opencv` | 4.12.0 | **compiled from source** in the native build |

**Sparse OpenCV checkout:** the full OpenCV repo includes `doc/`, `samples/`,
`data/`, and `apps/` that this project never builds. After submodule init, run
`scripts/sparse-opencv.sh` (or `scripts/sparse-opencv.ps1` on Windows) to drop
those trees from the worktree (~100+ MB). CMake only needs `modules/`,
`include/`, `3rdparty/`, `cmake/`, and the top-level `CMakeLists.txt`.

**OpenCV from-source integration** (`app/src/main/cpp/CMakeLists.txt`):

- `add_subdirectory(third_party/opencv …)` builds OpenCV as part of the app's
  CMake project. A curated `BUILD_LIST` compiles only the modules the engine
  uses — `core, imgproc, imgcodecs, features2d, calib3d, flann` — which keeps the
  build to a few minutes per ABI instead of tens.
- `BUILD_SHARED_LIBS OFF` → OpenCV is **statically linked** into
  `libindicvision_core.so` (which is why it is ~10–22 MB per ABI). We link the
  in-tree targets directly: `opencv_core opencv_imgproc opencv_imgcodecs
  opencv_features2d opencv_calib3d opencv_flann`.
- OpenCV's bundled 3rd-party image codecs (`zlib/png/jpeg/tiff/webp`) are built
  in-tree, so `cv::imdecode` / `cv::imwrite` work with **no system libraries**.
- OpenCV's in-tree targets don't export their header dirs, so the module
  `include/` paths and the generated-header dir (`opencv_modules.hpp`,
  `cvconfig.h` in `CMAKE_BINARY_DIR`) are added explicitly.
- `OPENCV_PYTHON_SKIP_DETECTION ON` disables OpenCV's host-Python detection —
  we build no bindings, so **no Python is required** (and it avoids broken
  Windows `python3` shims aborting configure).
- OpenCV compiles with its **own** default flags: `add_subdirectory` runs before
  the engine's aggressive `-O3 -flto -ffast-math` flags are set, so those apply
  to the DIC code only, not to OpenCV.

The host test build ([TESTING.md](TESTING.md)) consumes OpenCV's universal-intrinsics
header from the submodule source; the two generated headers it needs are
committed under `app/src/test/cpp/shim/opencv2/` so host builds need no OpenCV
configure.

## Build & ABI strategy

- `app/build.gradle.kts`: no `abiFilters` — all four ABIs
  (arm64-v8a, armeabi-v7a, x86, x86_64) build; ABI `splits` produce
  per-architecture APKs + a universal APK. Devices auto-select.
- SIMD portability comes from `SimdKernels.h`; there is **no**
  architecture-conditional code left in the engine (`#if __aarch64__` was
  removed — do not reintroduce it; extend the kernels instead).
- Production flags: `-O3 -flto -ffast-math -fopenmp` — note `-ffast-math`
  disables NaN semantics; the code uses explicit sentinels (`-1.0f`, `-1000.0f`)
  instead of `std::isnan`. Preserve this convention.
- 16 KB page alignment: `-Wl,-z,max-page-size=16384` on the shared lib for
  Android 15+ devices.
