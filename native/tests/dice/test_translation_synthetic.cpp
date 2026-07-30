// DICe's published translation contract, on our synthetic speckle.
//
// Reproduces the scenario from DICe tests/examples/custom_app — rigid 0.4 px
// X-shift, subset size 27, four subsets, |U - 0.4| <= 0.1 px — but on the
// analytic speckle field (framework/synthetic.h), where ground truth is exact
// by construction. test_translation_real_image.cpp runs the same contract on DICe's actual
// images; this one isolates solver accuracy from image noise.
//
// No DICe code is used, only its published scenario and tolerance.
#include "framework/test_framework.h"
#include "framework/synthetic.h"
#include <semper/solver.hpp>
#include <semper/subset.hpp>

using Semper::Image;
using Semper::SubsetData;
using Semper::SubsetPrecomputer;
using Semper::OptimizationEngine;
using Semper::AnalysisResult;
using Semper::INIT_NO_SIMPLEX;

namespace {
    constexpr int W = 160, H = 160;
    constexpr int SUBSET_SIZE = 27;   // DICe input.xml: subset_size = 27
    constexpr float U_TRUE = 0.4f;    // DICe def.tif = ref shifted +0.4 px in X
    constexpr float DICE_TOL = 0.1f;  // DICe errorTol = 0.1 px
} // namespace

// DICe custom_app parity: a rigid 0.4 px X-translation recovered at four
// independent subset locations, each within DICe's 0.1 px tolerance. Our
// engine typically lands ~5x tighter (see Engine.PureTranslation_Subpixel,
// 0.02 px), but the assertion mirrors DICe's published contract exactly.
TEST_CASE(DiceTranslationSynthetic, PureTranslation_0p4px_FourSubsets) {
    dictest::SpeckleField field(/*seed=*/4242, W, H);

    dictest::AffineDeformation def;
    def.u = U_TRUE;                 // pure translation → uniform shift everywhere,
    def.v = 0.0f;                   // so the deformation center is irrelevant here
    def.cx = W / 2.0f;
    def.cy = H / 2.0f;

    Image ref = dictest::make_reference_image(field, W, H);
    Image deformed = dictest::make_deformed_image(field, W, H, def);

    // Four subset locations (DICe's subsets.txt has 4 points) — a 2x2 grid,
    // each well clear of the image border for the 27 px subset.
    const int points[4][2] = {
        {50, 50}, {110, 50}, {50, 110}, {110, 110},
    };

    int solved = 0;
    for (const auto &p : points) {
        SubsetData subset;
        SubsetPrecomputer::precompute_subset(subset, ref, p[0], p[1], SUBSET_SIZE);
        REQUIRE(subset.is_initialized);

        OptimizationEngine engine;
        // Start from a zero guess so ICGN must pull the full 0.4 px purely from
        // image gradients — the same demand DICe's gradient-based solve meets.
        AnalysisResult res = engine.calculate_deformation(
            subset, deformed, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);

        REQUIRE(res.status == 0);              // converged (DICe: subset is valid)
        CHECK_NEAR(res.u, U_TRUE, DICE_TOL);   // DICe assertion: |U - 0.4| <= 0.1
        CHECK_NEAR(res.v, 0.0f, DICE_TOL);     // V ~ 0 (DICe checks X only; we verify both)
        ++solved;
    }

    // DICe asserts local_num_subsets() == 4; our equivalent is that all four
    // subsets were valid and solved.
    CHECK(solved == 4);
}
