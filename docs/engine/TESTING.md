# IndicVision DIC — Test Suite Reference

Complete catalog of the automated tests: what each one proves, why it exists,
and how to run everything. Companion doc: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Running the tests

### Native engine tests (host build — no device needed)

Built out of the source tree (from the repo root) so `app/src/test/cpp/`
stays pure sources — the build dir is regenerated on demand and gitignored:

```bash
cmake -S app/src/test/cpp -B build/native-tests -DCMAKE_BUILD_TYPE=Release  # any C++17 compiler
cmake --build build/native-tests
./build/native-tests/dic_tests                 # all suites
./build/native-tests/dic_tests Engine          # one suite
./build/native-tests/dic_tests Engine.PureTranslation_Subpixel   # one test
```

Dependencies come from the git submodules (`git submodule update --init`):
Eigen (`third_party/eigen`) and OpenCV's universal-intrinsics headers
(`third_party/opencv/modules/core/include`). OpenCV's generated
`opencv2/opencv_modules.hpp` + `cvconfig.h` are committed under
`app/src/test/cpp/shim/opencv2/` so the host build needs no OpenCV configure,
and `<android/log.h>` is replaced by `shim/android/log.h`.

One-liner alternative (any host g++/clang++):

```bash
g++ -std=c++17 -O2 -ffast-math \
  -Iapp/src/test/cpp -Iapp/src/test/cpp/shim -Iapp/src/main/cpp \
  -Iapp/src/main/cpp/third_party/eigen \
  -Iapp/src/main/cpp/third_party/opencv/modules/core/include \
  app/src/test/cpp/test_main.cpp \
  app/src/test/cpp/unit/*.cpp app/src/test/cpp/integration/*.cpp \n  app/src/test/cpp/dice/test_parity.cpp app/src/test/cpp/perf/*.cpp \
  app/src/main/cpp/preprocessing/*.cpp app/src/main/cpp/core/OptimizationEngine.cpp \
  app/src/main/cpp/postprocessing/StrainCalculator.cpp -o dic_tests
```

> Tests compile with `-ffast-math` deliberately — the same floating-point
> model as the production `.so`, so numerical regressions surface here first.

### Kotlin unit tests (JVM)

```bash
./gradlew :app:testDebugUnitTest
```

---

## The synthetic-deformation methodology

The engine suite's ground truth is **exact by construction**, not rendered:

1. A continuous speckle function `g(x,y)` (seeded sum of ~400 Gaussian blobs,
   `framework/synthetic.h`) is sampled to create the reference image.
2. The deformed image is created by sampling `g` at the **analytic inverse**
   of the affine warp: `def(q) = g(c + A⁻¹(q − c − t))`.

No image resampling is involved, so the true 6-DOF parameters are known to
machine precision, and every error the engine reports is the engine's own.
The warp convention matches the engine's shape function exactly
(see ARCHITECTURE.md → "Warp convention").

**Tolerances** (literature-standard for ICGN + bicubic on smooth speckle):
translation ≤ 0.02 px, displacement gradients ≤ 2×10⁻³.

---

## Numerical reproducibility contract

What "the same result" means across builds, and when a difference is a bug.

**Same APK, same device, same inputs → bit-identical results.**
Guarded by `Engine.RepeatSolve_BitIdentical`. Any run-to-run variation on
identical inputs is a defect (threading race, uninitialized memory).

**Different builds / ABIs / dependency versions → small drift is expected.**
The engine compiles with `-ffast-math` and uses FMA-based SIMD reductions, so
any change to the compiler, NDK, OpenCV build (e.g. Carotene on ARM vs the
generic path), Eigen version, or kernel summation order legally perturbs
floating-point rounding. Because ICGN is iterative, last-bit differences per
iteration shift the convergence path. Empirically (verified across the
prebuilt-SDK → from-source OpenCV migration, ARM NEON → portable SIMD):

| Quantity | Expected cross-build agreement |
|---|---|
| Displacements (U, V) | ≤ ~1×10⁻⁴ px (typically identical to 5 decimals) |
| Strains (Exx, Eyy, Exy) | ≤ ~1 µε (0.001 mε) |
| Solver stats (solved/dead counts, convergence %) | identical |
| Report max/min **locations** | may hop between near-tied grid points |

Anything beyond this — values off in the first or second significant digit,
extrema in unrelated regions, changed dead-point counts on the same input —
is a real regression: bisect with the `Engine` suite per-ABI.

Trade-off note: `-ffast-math` makes results build-specific by design. If
bit-reproducibility across builds ever becomes a requirement, compile `core/`
and `preprocessing/` with `-fno-fast-math` and re-benchmark; the explicit SIMD
kernels already do the heavy lifting, so the expected cost is small.

---

## Suite: `Engine` — `integration/test_optimization_engine.cpp`

The synthetic deformation regression suite. Covers `OptimizationEngine`
end-to-end (ICGN, Simplex, auto-search, guards).

| Test | Proves | Failure would mean |
|---|---|---|
| `ZeroDeformation_RecoversZero` | Identity warp → (0,0), ZNSSD ≈ 0 | Broken normalization or warp math — nothing else can be trusted |
| `PureTranslation_IntegerPixel` | Integer shift recovered from an exact guess | Interpolation at integer coords broken |
| `PureTranslation_Subpixel` | **The canonical DIC benchmark.** (2.25, −1.50) px recovered from integer guess; gradients ≈ 0 | Sub-pixel interpolation, gradient, or Hessian regression |
| `PureTranslation_SubpixelPhaseSweep` | 6 fractional phases (0.1…0.9) all recovered | Phase-dependent interpolation bias (a single phase can hide it) |
| `UniaxialStrain_Recovered` | 1% exx strain recovered, other DOFs ≈ 0 | Gradient-DOF columns of steepest-descent images broken |
| `SimpleShear_Recovered` | uy shear recovered without cross-talk | uy/vx column swap or sign error |
| `General6DOF_Recovered` | Translation + all four gradients simultaneously | Cross-DOF coupling errors invisible in single-DOF tests |
| `MultiSubsetGrid_ConsistentRigidTranslation` | DICe-style field consistency: a 3×3 grid of POIs all recover the same rigid translation (0.03 px grid tolerance — see the in-test note on per-POI speckle variance) | Position-dependent defect: coordinate-origin mistake, asymmetric boundary handling, or a row-stride bug invisible to the center-only tests |
| `AutoSearch_FindsLargeTranslation` | (6.4, −8.3) px found with NO initial guess | Coarse SSD search / Simplex / hand-off between stages broken |
| `ZnssdInvariantToBrightnessAndContrast` | Solution unchanged under `I' = 0.7·I + 30` | ZNSSD normalization broken — real-world lighting robustness lost |
| `SubsetOffImage_ReportsFailureStatus` | Impossible warp → `status != 0` | Engine fabricates answers instead of failing (silent corruption) |
| `BothInterpolatorsConverge` | Bicubic AND Keys 6×6 paths both solve | One interpolator selector path regressed |
| `LmDampingPreservesWellPosedSolution` | LM damping (α=1e-3) doesn't shift a good solution | LM applied to wrong DOFs or damping leaking into the answer |
| `RepeatSolve_BitIdentical` | The same solve twice is bit-identical | Threading race, uninitialized buffer, or run-to-run nondeterminism |
| `SuccessfulSolve_CorrelationNonNegative` | Successful solves report ZNSSD ≥ 0 | Sentinel contract broken — the JNI layer marks failed/skipped points with `CORR_INVALID = -1`, so a real score must never be negative |

## Suite: `DiceParity` — `dice/test_parity.cpp`

Cross-validation against **DICe** (Digital Image Correlation Engine,
[github.com/dicengine/dice](https://github.com/dicengine/dice), BSD 3-Clause),
an established reference implementation. Reproduces the **input→output
contract** of DICe's `tests/examples/custom_app` — a rigid 0.4 px
X-translation, subset size 27, four subsets, each recovered within DICe's
`errorTol = 0.1 px` — on our engine and our **analytic synthetic** images. No
DICe code is used; only the published scenario and tolerance. It shows our ICGN
solver matches a reference DIC engine's accuracy on the canonical translation
case, in milliseconds on a laptop (no Trilinos/MPI).

| Test | Proves | Failure would mean |
|---|---|---|
| `PureTranslation_0p4px_FourSubsets` | All 4 subsets recover the 0.4 px shift within DICe's 0.1 px tolerance (we actually land ≤ 0.02 px — see `Engine.PureTranslation_Subpixel`) | Our engine no longer agrees with a reference DIC implementation on rigid translation — a correlation/interpolation regression, or accuracy fallen below the field's accepted bar |

## Suite: `DiceRealImage` — `dice/test_real_image.cpp`

The real-image companion to `DiceParity`: same 0.4 px / 0.1 px contract, but on
DICe's **actual** 512×512 speckle images (`fixtures/dice/ref.tif`, `def.tif` —
their `custom_app` `ref.tif`/`def.tif`, BSD-3, see
[`fixtures/dice/LICENSE.DICe`](../../app/src/test/cpp/fixtures/dice/LICENSE.DICe)).
Where `DiceParity` proves accuracy on math-perfect synthetic texture, this adds
**real-speckle robustness** and **independence** — an image we did not generate,
a target we did not compute. The fixtures path is injected by CMake as
`DICE_FIXTURES_DIR`; a minimal P5 reader loads the PGMs (the host build has no
image codec).

| Test | Proves | Failure would mean |
|---|---|---|
| `CustomApp_0p4px_RealSpeckle` | All 4 subsets recover the 0.4 px X-shift on DICe's real images, within DICe's 0.1 px tolerance | Our engine disagrees with DICe on their own experimental data — a real-texture/robustness gap the synthetic tests don't expose |

## Suite: `SimdKernels` — `unit/test_simd_kernels.cpp`

Every kernel is checked against a **double-precision scalar oracle**. On an
SSE/NEON host this validates the vectorized path; on other compilers, the
scalar fallback.

| Test | Proves |
|---|---|
| `SumSqDiff_AllSizes` | Exact for n = 1…1000, covering full vector blocks, ragged tails, and n < lane-width |
| `SumSqDiff_ZeroWhenAllEqualMean` | No catastrophic cancellation on the degenerate case |
| `Znssd_AllSizes` | ZNSSD reduction matches oracle across sizes |
| `Znssd_PerfectMatchIsZero` | Perfectly correlated signals score ≈ 0 (the convergence anchor) |
| `ErrorAndGradient_MatchesScalarOracle` | Fused kernel: error AND all 6 SoA gradient projections match |
| `FusedErrorEqualsStandaloneZnssd` | Contract: fused error term ≡ standalone `znssd_sum` (callers assume it) |

## Suite: `Image` — `unit/test_image.cpp`

Interpolation ladder + gradients + blur, validated via mathematical identities
(no golden files).

| Test | Property exploited |
|---|---|
| `InterpolatorsReproducePixelValuesAtIntegerCoords` | All three kernels are *interpolating*: weight 1 at s=0, 0 at other integers |
| `InterpolatorsExactOnLinearRamp` | Catmull-Rom & Keys reproduce degree-1 polynomials exactly at any sub-pixel position |
| `GradientOfLinearRampIsExactSlope` | 5-point central difference is exact for degree ≤ 4 |
| `BlurPreservesConstantImage` | Normalized kernel ⇒ constant in = constant out |
| `BilinearOutOfBoundsReturnsZero` | The `0.0f` dead-pixel sentinel contract that ICGN's `val > 0` guard relies on |
| `BoundaryDemotionLadderIsContinuousInRange` | 6×6→bilinear and 4×4→bilinear demotion never extrapolates outside [0,255] |

## Suite: `SubsetPrecomputer` — `unit/test_subset_precomputer.cpp`

| Test | Proves |
|---|---|
| `MeanAndStdMatchManualComputation` | Stats vs a double-precision manual computation; normalized intensities are zero-mean/unit-RMS |
| `SdiPlanesMirrorSteepestDescentImages` | **The SIMD-refactor regression guard**: SoA `sdi_planes` ≡ AoS `steepest_descent_images` element-wise. The SIMD fast path reads planes, the masked path reads vectors — drift silently corrupts solutions |
| `SteepestDescentImagesFollowDefinition` | `sd = [gx, gy, gx·x, gx·y, gy·x, gy·y]/σ` at spot-checked pixels |
| `HessianIsSymmetricAndInverseIsValid` | `H = Hᵀ` and `H·H⁻¹ ≈ I` |
| `RejectsSubsetOffImageBoundary` | Boundary subsets → `is_initialized == false` (never solved) |
| `FastPathMatchesFullPrecompute` | `compute_hessian_only` + `precompute_subset_fast` ≡ full `precompute_subset` — the batch pipeline treats them as interchangeable |

## Suite: `Strain` — `unit/test_strain_calculator.cpp`

Linear displacement fields have closed-form Green-Lagrange strain, and VSG's
linear least-squares fits them **exactly** — so interior tolerances are float
precision, not "close enough".

| Test | Proves |
|---|---|
| `VsgRecoversUniaxialStrainExactly` | 1% exx from u = 0.01·X, exact at grid center |
| `VsgRecoversGeneralLinearField` | All three strain components for a general 4-coefficient field, at multiple interior points |
| `VsgRigidBodyTranslationGivesZeroStrain` | Constant displacement → zero strain (the classic false-strain bug) |
| `VsgLeavesSentinelWhereWindowUnsupported` | Corner points (<90% window fill) and invalidated points keep the `−1000` sentinel |
| `NlvcRecoversLinearFieldInInterior` | NLVC integral reproduces the same closed-form strain (quadrature tolerance 2e-3) |
| `NlvcBoundaryStaysZeroWhenIntegralUnbalanced` | Edge points where the antisymmetric kernel can't balance stay 0 instead of exploding |

## Suite: Kotlin JVM — `app/src/test/java` (`./gradlew :app:testDebugUnitTest`)

### `ApiDtosContractTest`

The property names on the DTOs in `data/net/ApiDtos.kt` ARE the JSON contract
with the GCP backend (`backend/app/models.py`) — no schema sits between them.
This suite pins the exact field names in both directions with the same `Json`
configuration `IndicApi` uses: decodes realistic backend responses (sessions,
upload targets, restore manifests, the snake_case `access_status`), asserts
encoded requests contain the exact backend keys, and proves unknown backend
fields are tolerated. A DTO rename that compiles cleanly fails here instead of
in production.

### `UploadResumableTest` (Robolectric + OkHttp MockWebServer)

The Drive resumable-upload state machine in `IndicApi.uploadResumable`,
against a fake Drive. Each case encodes a bug that shipped once:

| Test | Proves | The incident it encodes |
|---|---|---|
| `resumes from the offset Drive reports` | Continuation PUT starts at the probe's Range offset with the right `Content-Range` and body size | The off-by-one size-mismatch 400s |
| `already-complete upload returns from the probe alone` | A 200 probe short-circuits; zero bytes re-sent | "upload finished without a final Drive response" |
| `fresh upload chunks correctly with clamped chunk size` | No-Range probe starts at 0; a below-minimum server chunk size is clamped to 256 KiB | Blind trust in the server-supplied chunk size |

Enabled by one seam: `IndicApi.device` is `lazy`, because `DeviceKeyManager`
touches the AndroidKeyStore in its constructor and only signed calls need it.

---

## Test layout

Native host tests live under `app/src/test/cpp/`, grouped by scope:

```
app/src/test/cpp/
  test_main.cpp           micro-framework runner entry point
  framework/              the test harness — synthetic.h, test_framework.h
  shim/                   host stand-ins for <android/log.h> and OpenCV configs
  unit/                   one component vs. an oracle / mathematical identity
                            test_simd_kernels, test_image,
                            test_subset_precomputer, test_strain_calculator
  integration/            the assembled engine end-to-end + robustness
                            test_optimization_engine, test_robustness
```

CMakeLists.txt lists sources under `DIC_UNIT_TESTS` / `DIC_INTEGRATION_TESTS`.
JVM tests live in `app/src/test/java/…` (package-mirrored, run by
`testDebugUnitTest`); instrumented tests in `app/src/androidTest/java/…`.

## Adding a new test

1. Pick the suite file, or create `unit/test_<module>.cpp` (component) or
   `integration/test_<module>.cpp` (end-to-end) and add it to the matching
   `DIC_UNIT_TESTS` / `DIC_INTEGRATION_TESTS` list in `CMakeLists.txt`.
2. `TEST_CASE(Suite, Name) { ... }` — use `CHECK`, `REQUIRE`, `CHECK_NEAR`,
   `CHECK_REL` (see `framework/test_framework.h`).
3. For engine tests, build ground truth with `SpeckleField` +
   `AffineDeformation` — never by resampling images, and never with warps that
   don't match the engine's shape-function convention.
4. Document the test's *reason to exist* in this file. A test whose failure
   nobody can interpret is a liability.

## Known limitations / future work

- **JNI bridge** (`IndicVisionJNI.cpp`) is not covered by host tests — it needs
  a JVM + Android runtime. Recommended next step: a small instrumented test
  that round-trips `getImageDimensions`/`initializeReference` on-device.
- **Per-ABI numerical drift**: the host suite runs on x86 SSE. To compare ABIs,
  build the same suite with the NDK toolchain per-ABI and run on devices —
  tolerances are already set to absorb fast-math reassociation differences.
- Kotlin auth *flows* (AuthRepository / IndicApi network paths) are untested —
  they'd need a mock backend (e.g. OkHttp MockWebServer). No serialization-layer
  contract test currently exists either (see the DTO suite note above).
