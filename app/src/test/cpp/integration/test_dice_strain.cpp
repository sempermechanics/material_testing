// =====================================================================
// SUITE: DiceStrain — strain recovery on REAL speckle, known truth
//
// Extends the real-image cross-validation beyond rigid translation to STRAIN,
// the way the DIC Challenge's synthetic samples do: take real speckle (DICe's
// ref.tif) and apply a KNOWN homogeneous strain by resampling
// (fixtures/dice/def_exx.tif = ref warped by exx = 0.01, i.e. 1% uniaxial in
// X, centered so displacements stay small). Truth is analytic — in reference
// coordinates u(x) = 0.01*(x - 256), so du/dx = ux = 0.01 exactly — while the
// texture is real. Note: DICe's own dic_challenge_12 (oht_cfrp) is a real
// experiment with NO analytic truth, validated only against DICe's gold; a
// prescribed strain on real speckle is the rigorous alternative.
//
// Each subset is seeded with its expected translation (u = 0.01*(x-256)) — as
// RGDIC propagation would supply in a real run — but the STRAIN gradient is
// recovered from zero, so this genuinely tests strain measurement, not the seed.
//
// We validate the FIELD, not each subset. A single 27 px subset sees only
// ~0.27 px of displacement variation across a 1% strain, so its raw ICGN
// gradient is inherently noisy (individual ux scatter ~0.006..0.016 on real
// speckle) — which is precisely why production DIC derives strain from a
// virtual-strain-gauge window over many subsets, not from one. For a
// homogeneous field the MEAN gradient is the unbiased, low-variance estimator
// (std ~ per-subset/sqrt(N)); we assert on it, plus a scatter bound to catch
// gross breakage. (Per-subset VSG accuracy is covered analytically by the
// Strain suite.)
// =====================================================================
#include "framework/test_framework.h"
#include "framework/image_io.h"
#include "core/OptimizationEngine.h"
#include "preprocessing/ImageProcessor.h"
#include "preprocessing/SubsetPrecomputer.h"

#include <cmath>
#include <cstdio>
#include <string>

#if defined(DIC_HAVE_OPENCV)

using IndicVision::Image;
using IndicVision::SubsetData;
using IndicVision::SubsetPrecomputer;
using IndicVision::OptimizationEngine;
using IndicVision::AnalysisResult;
using IndicVision::INIT_NO_SIMPLEX;
using dictest::GrayImage;
using dictest::load_gray;

namespace {
    constexpr int SUBSET_SIZE = 27;
    constexpr float EXX_TRUE = 0.01f;   // applied 1% uniaxial strain (du/dx in ref coords)
    constexpr float CX = 256.0f;        // strain center (image center)
    constexpr int GRID_LO = 80, GRID_HI = 432, GRID_STEP = 40; // 9x9 grid

    // Field-level tolerances. The mean of N independent per-subset gradients has
    // std ~ sigma/sqrt(N) (~0.005/9 here), so 1.5e-3 on the mean is a genuinely
    // tight check on a 0.01 strain — while RMS_MAX only bounds the per-subset
    // scatter that DIC physics makes unavoidable at this subset size.
    constexpr float MEAN_TOL = 1.5e-3f;  // px/px, on the field-mean gradients
    constexpr double RMS_MAX = 6e-3;     // px/px, per-subset scatter about the truth
    constexpr float V_TOL = 0.15f;       // px, transverse displacement (well conditioned)
    constexpr int MIN_SUBSETS = 60;      // of 81 grid points
} // namespace

TEST_CASE(DiceStrain, RealSpeckle_UniaxialStrain_1pct) {
    GrayImage r, d;
    REQUIRE(load_gray(std::string(DICE_FIXTURES_DIR) + "/ref.tif", r));
    REQUIRE(load_gray(std::string(DICE_FIXTURES_DIR) + "/def_exx.tif", d));
    Image ref(r.w, r.h, r.px.data());
    ref.prepare_data(false);
    Image def(d.w, d.h, d.px.data());
    def.prepare_data(false);

    int solved = 0;
    double ux_sum = 0.0, uy_sum = 0.0, vx_sum = 0.0, vy_sum = 0.0, ux_sq_dev = 0.0;
    for (int y = GRID_LO; y <= GRID_HI; y += GRID_STEP) {
        for (int x = GRID_LO; x <= GRID_HI; x += GRID_STEP) {
            SubsetData subset;
            SubsetPrecomputer::precompute_subset(subset, ref, x, y, SUBSET_SIZE);
            if (!subset.is_initialized) continue;

            // Seed the translation (RGDIC would); the strain gradient is NOT
            // seeded — the engine must recover ux from zero.
            const float guess_u = EXX_TRUE * (static_cast<float>(x) - CX);
            OptimizationEngine engine;
            AnalysisResult res = engine.calculate_deformation(
                subset, def, guess_u, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
            if (res.status != 0) continue;

            std::printf("  DiceStrain (%3d,%3d): ux=%.5f uy=%.5f vx=%.5f vy=%.5f  v=%.4f\n",
                        x, y, (double) res.ux, (double) res.uy,
                        (double) res.vx, (double) res.vy, (double) res.v);

            // The transverse displacement is well conditioned even per-subset.
            CHECK_NEAR(res.v, 0.0f, V_TOL);

            ux_sum += res.ux;
            uy_sum += res.uy;
            vx_sum += res.vx;
            vy_sum += res.vy;
            const double dev = static_cast<double>(res.ux) - EXX_TRUE;
            ux_sq_dev += dev * dev;
            ++solved;
        }
    }

    REQUIRE(solved >= MIN_SUBSETS);
    const double mean_ux = ux_sum / solved;
    const double mean_uy = uy_sum / solved;
    const double mean_vx = vx_sum / solved;
    const double mean_vy = vy_sum / solved;
    const double rms_ux = std::sqrt(ux_sq_dev / solved);
    std::printf("  DiceStrain: N=%d  mean ux=%.5f (truth %.5f)  rms dev=%.5f\n"
                "              mean uy=%.5f  vx=%.5f  vy=%.5f\n",
                solved, mean_ux, (double) EXX_TRUE, rms_ux,
                mean_uy, mean_vx, mean_vy);

    // Primary result: the field-mean gradient equals the applied strain.
    CHECK_NEAR(static_cast<float>(mean_ux), EXX_TRUE, MEAN_TOL);
    // No spurious shear or transverse strain in the field.
    CHECK_NEAR(static_cast<float>(mean_uy), 0.0f, MEAN_TOL);
    CHECK_NEAR(static_cast<float>(mean_vx), 0.0f, MEAN_TOL);
    CHECK_NEAR(static_cast<float>(mean_vy), 0.0f, MEAN_TOL);
    // Per-subset scatter is expected DIC noise, but it must stay bounded —
    // a blow-up here means the solve degraded, not just that strain is noisy.
    CHECK(rms_ux < RMS_MAX);
}

#endif // DIC_HAVE_OPENCV
