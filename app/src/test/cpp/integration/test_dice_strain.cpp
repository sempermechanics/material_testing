// =====================================================================
// SUITE: DiceStrain — strain recovery on REAL speckle, known truth
//
// Extends the real-image cross-validation beyond rigid translation to STRAIN,
// the way the DIC Challenge's synthetic samples do: take real speckle (DICe's
// ref.pgm) and apply a KNOWN homogeneous strain by resampling
// (fixtures/dice/def_exx.pgm = ref warped by exx = 0.01, i.e. 1% uniaxial in
// X, centered so displacements stay small). Truth is analytic — in reference
// coordinates u(x) = 0.01*(x - 256), so du/dx = ux = 0.01 exactly — while the
// texture is real. Note: DICe's own dic_challenge_12 (oht_cfrp) is a real
// experiment with NO analytic truth, validated only against DICe's gold; a
// prescribed strain on real speckle is the rigorous alternative.
//
// Each subset is seeded with its expected translation (u = 0.01*(x-256)) — as
// RGDIC propagation would supply in a real run — but the STRAIN gradient is
// recovered from zero, so this genuinely tests strain measurement, not the seed.
// =====================================================================
#include "framework/test_framework.h"
#include "framework/pgm.h"
#include "core/OptimizationEngine.h"
#include "preprocessing/ImageProcessor.h"
#include "preprocessing/SubsetPrecomputer.h"

#include <cstdio>
#include <string>

using IndicVision::Image;
using IndicVision::SubsetData;
using IndicVision::SubsetPrecomputer;
using IndicVision::OptimizationEngine;
using IndicVision::AnalysisResult;
using IndicVision::INIT_NO_SIMPLEX;
using dictest::Pgm;
using dictest::load_pgm;

namespace {
    constexpr int SUBSET_SIZE = 27;
    constexpr float EXX_TRUE = 0.01f;   // applied 1% uniaxial strain (du/dx in ref coords)
    constexpr float CX = 256.0f;        // strain center (image center)
    constexpr int GRID_LO = 80, GRID_HI = 432, GRID_STEP = 40;
    constexpr float STRAIN_TOL = 2e-3f; // px/px on ux (real texture + resampling)
    constexpr float OFFAXIS_GRAD_TOL = 3e-3f;
    constexpr float V_TOL = 0.15f;      // px on the transverse displacement
} // namespace

TEST_CASE(DiceStrain, RealSpeckle_UniaxialStrain_1pct) {
    Pgm r, d;
    REQUIRE(load_pgm(std::string(DICE_FIXTURES_DIR) + "/ref.pgm", r));
    REQUIRE(load_pgm(std::string(DICE_FIXTURES_DIR) + "/def_exx.pgm", d));
    Image ref(r.w, r.h, r.px.data());
    ref.prepare_data(false);
    Image def(d.w, d.h, d.px.data());
    def.prepare_data(false);

    int solved = 0;
    double ux_sum = 0.0;
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

            CHECK_NEAR(res.ux, EXX_TRUE, STRAIN_TOL);          // the measurement under test
            CHECK_NEAR(res.uy, 0.0f, OFFAXIS_GRAD_TOL);
            CHECK_NEAR(res.vx, 0.0f, OFFAXIS_GRAD_TOL);
            CHECK_NEAR(res.vy, 0.0f, OFFAXIS_GRAD_TOL);
            CHECK_NEAR(res.v, 0.0f, V_TOL);
            ux_sum += res.ux;
            ++solved;
        }
    }

    REQUIRE(solved > 0);
    std::printf("  DiceStrain: mean ux = %.5f over %d subsets (truth %.5f)\n",
                ux_sum / solved, solved, (double) EXX_TRUE);
    // The field is homogeneous, so the mean averages out per-subset noise and
    // should land very close to the applied strain.
    CHECK_NEAR(static_cast<float>(ux_sum / solved), EXX_TRUE, 1e-3f);
}
