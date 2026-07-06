# IndicVision DIC — Test Suite Reference

Complete catalog of the automated tests: what each one proves, why it exists,
and how to run everything. Companion doc: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Running the tests

### Native engine tests (host build — no device needed)

```bash
cd app/src/test/cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release     # any C++17 compiler
cmake --build build
./build/dic_tests                  # all suites
./build/dic_tests Engine           # one suite
./build/dic_tests Engine.PureTranslation_Subpixel   # one test
```

No dependencies beyond a compiler: the framework is a self-contained header
(`framework/test_framework.h`), Eigen and the OpenCV intrinsics headers ship in
the repo, and `<android/log.h>` is replaced by `shim/android/log.h`.

One-liner alternative (any host g++/clang++):

```bash
g++ -std=c++17 -O2 -ffast-math \
  -Iapp/src/test/cpp -Iapp/src/test/cpp/shim -Iapp/src/main/cpp \
  -Iapp/src/main/cpp/include -Iapp/src/main/cpp/opencv/sdk/native/jni/include \
  app/src/test/cpp/*.cpp \
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

## Suite: `Engine` — `test_optimization_engine.cpp`

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
| `AutoSearch_FindsLargeTranslation` | (6.4, −8.3) px found with NO initial guess | Coarse SSD search / Simplex / hand-off between stages broken |
| `ZnssdInvariantToBrightnessAndContrast` | Solution unchanged under `I' = 0.7·I + 30` | ZNSSD normalization broken — real-world lighting robustness lost |
| `SubsetOffImage_ReportsFailureStatus` | Impossible warp → `status != 0` | Engine fabricates answers instead of failing (silent corruption) |
| `BothInterpolatorsConverge` | Bicubic AND Keys 6×6 paths both solve | One interpolator selector path regressed |
| `LmDampingPreservesWellPosedSolution` | LM damping (α=1e-3) doesn't shift a good solution | LM applied to wrong DOFs or damping leaking into the answer |

## Suite: `SimdKernels` — `test_simd_kernels.cpp`

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

## Suite: `Image` — `test_image.cpp`

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

## Suite: `SubsetPrecomputer` — `test_subset_precomputer.cpp`

| Test | Proves |
|---|---|
| `MeanAndStdMatchManualComputation` | Stats vs a double-precision manual computation; normalized intensities are zero-mean/unit-RMS |
| `SdiPlanesMirrorSteepestDescentImages` | **The SIMD-refactor regression guard**: SoA `sdi_planes` ≡ AoS `steepest_descent_images` element-wise. The SIMD fast path reads planes, the masked path reads vectors — drift silently corrupts solutions |
| `SteepestDescentImagesFollowDefinition` | `sd = [gx, gy, gx·x, gx·y, gy·x, gy·y]/σ` at spot-checked pixels |
| `HessianIsSymmetricAndInverseIsValid` | `H = Hᵀ` and `H·H⁻¹ ≈ I` |
| `RejectsSubsetOffImageBoundary` | Boundary subsets → `is_initialized == false` (never solved) |
| `FastPathMatchesFullPrecompute` | `compute_hessian_only` + `precompute_subset_fast` ≡ full `precompute_subset` — the batch pipeline treats them as interchangeable |

## Suite: `Strain` — `test_strain_calculator.cpp`

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

## Suite: Kotlin — `SupabaseModelContractTest.kt`

JVM-only; pins the JSON wire format between the app and Supabase tables.
The `@SerialName` annotations ARE the schema contract — these tests fail at
build time instead of login failing at runtime.

| Test | Proves |
|---|---|
| `AuthProfile decodes full row…` | Column names `access_status` / `device_fingerprint` / `hardware_public_key` map correctly |
| `AuthProfile tolerates missing optional device columns` | Fresh registrations (NULL device columns) don't crash the decoder |
| `AuthProfile ignores unknown columns…` | Server-side schema additions never break old clients |
| `UserProfileInsert serializes with exact database column names` | Outbound payload matches `auth_profiles` columns |
| `UserProfileInsert defaults access_status to PENDING` | Security invariant: client-side default can never be APPROVED |
| `UserProfileInsert omits defaulted access_status with standard Json` | **Pinned subtlety** (found by this suite): kotlinx.serialization omits defaulted fields, so the DB column default decides initial status — documented so nobody "fixes" it blindly |
| `AnalysisSessionInsert/Response…` | Upload worker payload and `session_id` response contract |

---

## Adding a new test

1. Pick the suite file (or create `test_<module>.cpp` and add it to
   `CMakeLists.txt`).
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
- Kotlin auth *flows* (AuthRepository network paths) are untested — they'd
  need a Supabase mock; the current tests cover only the serialization layer.
