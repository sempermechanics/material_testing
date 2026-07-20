// =====================================================================
// SUITE: DiceGoldField — our field vs DICe's OWN solved field
//
// The standard here is DICe's data, not ours. DICe ships the solved
// displacement field for its `dic_challenge_12` regression case (an open-hole
// tension test on CFRP) as DICe_solution_01.txt; we vendor that file and the
// two images it references, run OUR engine at DICe's own subset coordinates,
// and diff the fields.
//
// Why this supersedes a self-generated baseline: DICe's gold is FIXED and
// EXTERNAL, so diffing against it both anchors us to the reference
// implementation AND detects any drift in our engine — a baseline made from
// our own output could only ever do the latter, and would enshrine our own
// error if we had one.
//
// Honest limits of this comparison:
//  * oht_cfrp is a REAL experiment — there is no analytic truth. DICe's field
//    is a reference *result*, not ground truth. So agreement is the metric.
//  * Independent DIC codes legitimately differ (interpolant, shape function,
//    convergence criteria); quantifying that spread is the DIC Challenge's
//    whole purpose. The tolerance below is therefore an INTER-CODE agreement
//    bound, set from the measured spread — not a correctness tolerance.
//
// We match DICe's setup where we can (subset 27, ZNSSD, Keys-fourth
// interpolation, its exact subset coordinates). Remaining differences: DICe
// used translation + normal-strain shape functions and neighbour-seeded
// guesses; we solve full 6-DOF affine from a zero guess (displacements here
// are sub-pixel, so it converges).
//
// Config, verbatim from the gold header:
//   Subset size: 27 | Step: 35 | ZNSSD | Interpolation: KEYS_FOURTH
//   Coordinates: (0,0) upper-left, x right, y down
// =====================================================================
#include "framework/test_framework.h"
#include "framework/image_io.h"
#include "core/OptimizationEngine.h"
#include "preprocessing/ImageProcessor.h"
#include "preprocessing/SubsetPrecomputer.h"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

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

    constexpr int SUBSET_SIZE = 27;      // DICe gold header: "Subset size: 27"

    // Inter-code agreement bounds (px), set from the MEASURED spread rather than
    // guessed. Our engine reproduces DICe's field on this pair to
    // rms 0.0006 px / max 0.0033 px, converging at 230/230 of its points — so
    // these bounds keep ~8x headroom (and far exceed the <=1e-4 px cross-build
    // fast-math drift documented in docs/engine/TESTING.md) while still being
    // tight enough to actually catch a regression.
    constexpr double RMS_TOL = 0.005;
    constexpr double MAX_TOL = 0.02;
    constexpr double MIN_COMPARED_FRACTION = 0.95;

    struct GoldPt {
        int x, y;
        double u, v;
    };

    // Parse DICe's solution file: '***' header block, then a column-name line,
    // then CSV rows of COORDINATE_X,COORDINATE_Y,DISPLACEMENT_X,DISPLACEMENT_Y,...
    bool load_dice_gold(const std::string &path, std::vector<GoldPt> &out) {
        std::ifstream f(path);
        if (!f) return false;
        std::string line;
        while (std::getline(f, line)) {
            if (line.empty() || line.rfind("***", 0) == 0) continue;
            if (line.rfind("COORDINATE_X", 0) == 0) continue; // column names
            std::stringstream ss(line);
            std::string cell;
            double col[4];
            int n = 0;
            while (n < 4 && std::getline(ss, cell, ',')) {
                col[n++] = std::atof(cell.c_str());
            }
            if (n < 4) continue;
            out.push_back({(int) std::lround(col[0]), (int) std::lround(col[1]), col[2], col[3]});
        }
        return !out.empty();
    }

} // namespace

TEST_CASE(DiceGoldField, OhtCfrp_AgreesWithDiceSolution) {
    GrayImage r, d;
    REQUIRE(load_gray(std::string(DICE_FIXTURES_DIR) + "/oht_cfrp_00.tiff", r));
    REQUIRE(load_gray(std::string(DICE_FIXTURES_DIR) + "/oht_cfrp_01.tiff", d));
    Image ref(r.w, r.h, r.px.data());
    ref.prepare_data(false);
    Image def(d.w, d.h, d.px.data());
    def.prepare_data(false);

    std::vector<GoldPt> gold;
    REQUIRE(load_dice_gold(std::string(DICE_FIXTURES_DIR) + "/DICe_solution_01.txt", gold));
    std::printf("  DiceGoldField: %zu DICe reference points\n", gold.size());

    int compared = 0;
    double su = 0, sv = 0, su2 = 0, sv2 = 0, maxdu = 0, maxdv = 0;
    for (const auto &g : gold) {
        SubsetData subset;
        SubsetPrecomputer::precompute_subset(subset, ref, g.x, g.y, SUBSET_SIZE);
        if (!subset.is_initialized) continue;

        OptimizationEngine engine;
        engine.use_6x6_interpolator = true; // DICe used KEYS_FOURTH
        AnalysisResult res = engine.calculate_deformation(
            subset, def, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
        if (res.status != 0) continue;

        const double du = static_cast<double>(res.u) - g.u;
        const double dv = static_cast<double>(res.v) - g.v;
        su += du; sv += dv;
        su2 += du * du; sv2 += dv * dv;
        if (std::fabs(du) > std::fabs(maxdu)) maxdu = du;
        if (std::fabs(dv) > std::fabs(maxdv)) maxdv = dv;
        ++compared;
    }

    REQUIRE(compared > 0);
    const double frac = static_cast<double>(compared) / static_cast<double>(gold.size());
    const double rms_u = std::sqrt(su2 / compared), rms_v = std::sqrt(sv2 / compared);
    std::printf("  DiceGoldField: compared %d/%zu (%.0f%%)\n"
                "     du: mean=%+.4f rms=%.4f max=%+.4f\n"
                "     dv: mean=%+.4f rms=%.4f max=%+.4f\n",
                compared, gold.size(), frac * 100.0,
                su / compared, rms_u, maxdu,
                sv / compared, rms_v, maxdv);

    CHECK(frac >= MIN_COMPARED_FRACTION);
    CHECK(rms_u < RMS_TOL);
    CHECK(rms_v < RMS_TOL);
    CHECK(std::fabs(maxdu) < MAX_TOL);
    CHECK(std::fabs(maxdv) < MAX_TOL);
}

#endif // DIC_HAVE_OPENCV
