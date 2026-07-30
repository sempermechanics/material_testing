// DICe's published translation contract, on DICe's real speckle.
//
// Source: DICe tests/examples/custom_app — ref.tif/def.tif (512x512), four
// subsets, subset size 27. Its assertion is |U - 0.4| <= 0.1 px.
//
// Only U is asserted, because only U has published truth. `def` is actually a
// diagonal ~(0.4, 0.4) shift and our engine recovers V ~ 0.4 as well (printed
// below), but DICe publishes no Y reference — asserting V would just be
// checking our engine against itself.
#include "framework/test_framework.h"
#include "framework/image_io.h"
#include <semper/solver.hpp>
#include <semper/image.hpp>
#include <semper/subset.hpp>

#include <cstdio>
#include <string>

#if defined(DIC_HAVE_OPENCV)

using Semper::Image;
using Semper::SubsetData;
using Semper::SubsetPrecomputer;
using Semper::OptimizationEngine;
using Semper::AnalysisResult;
using Semper::INIT_NO_SIMPLEX;
using dictest::GrayImage;
using dictest::load_gray;

namespace {

    constexpr int SUBSET_SIZE = 27;   // DICe input.xml: subset_size = 27
    constexpr float U_TRUE = 0.4f;    // DICe: def = ref shifted +0.4 px in X
    constexpr float DICE_TOL = 0.1f;  // DICe errorTol = 0.1 px

} // namespace

// DICe custom_app on the REAL images: recover the 0.4 px X-shift at DICe's four
// subset coordinates, each within DICe's 0.1 px tolerance.
TEST_CASE(DiceTranslationReal, CustomApp_0p4px_RealSpeckle) {
    const std::string dir = DICE_FIXTURES_DIR;

    GrayImage r, d;
    REQUIRE(load_gray(dir + "/ref.tif", r));
    REQUIRE(load_gray(dir + "/def.tif", d));
    REQUIRE(r.w == 512);
    REQUIRE(r.h == 512);
    REQUIRE(d.w == r.w);
    REQUIRE(d.h == r.h);

    // Stack objects — no new/delete. Image copies the pixels into its own
    // vector, so r.px / d.px only need to outlive construction (they do).
    Image ref(r.w, r.h, r.px.data());
    ref.prepare_data(false);
    Image def(d.w, d.h, d.px.data());
    def.prepare_data(false);

    // DICe's subsets.txt coordinates.
    const int points[4][2] = {
        {100, 100}, {200, 200}, {300, 300}, {400, 400},
    };

    int solved = 0;
    for (const auto &p : points) {
        SubsetData subset;
        SubsetPrecomputer::precompute_subset(subset, ref, p[0], p[1], SUBSET_SIZE);
        REQUIRE(subset.is_initialized);

        OptimizationEngine engine;
        AnalysisResult res = engine.calculate_deformation(
            subset, def, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);

        // Echo the recovered solution so the CI log shows the actual numbers.
        // V is printed (def is a diagonal shift, V ~ 0.4) but NOT asserted —
        // DICe publishes no Y ground truth for this pair.
        std::printf("  DiceTranslationReal (%3d,%3d): status=%d  U=%.4f  V=%.4f\n",
                    p[0], p[1], res.status, (double) res.u, (double) res.v);

        CHECK(res.status == 0);
        CHECK_NEAR(res.u, U_TRUE, DICE_TOL);   // DICe's contract: |U - 0.4| <= 0.1
        if (res.status == 0) ++solved;
    }
    CHECK(solved == 4);
}

#endif // DIC_HAVE_OPENCV
