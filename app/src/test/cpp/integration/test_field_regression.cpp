// =====================================================================
// SUITE: FieldRegression — full-field gold/regression (DICe-style)
//
// The piece of DICe's methodology we were missing: freeze the engine's
// WHOLE displacement field on a fixed image pair as a committed gold file,
// then diff every future run against it within tolerance (cf. DICe's
// `DICe_Diff gold results -t ... -f ...`). Where the Engine/DiceParity suites
// check a handful of points against known truth, this pins the entire field —
// so a refactor or dependency bump that silently shifts results anywhere in
// the field fails here.
//
// Input: DICe's real speckle pair (fixtures/dice/ref.pgm, def.pgm). We solve on
// a grid and freeze (x, y, u, v) for every converged subset. Because gold and
// verification run on the SAME CI toolchain (ubuntu x86_64), results are
// deterministic (see Robustness/RepeatSolve_BitIdentical), so the tolerance is
// tight — it catches real drift, not float-formatting noise.
//
// Bootstrapping (author can't run locally — Device Guard): if the gold file is
// absent the test PRINTS the field as `GOLD x y u v` lines and fails, so the
// values can be captured from the CI log and committed once. Paths are injected
// by CMake as DICE_FIXTURES_DIR / GOLD_FIXTURES_DIR.
// =====================================================================
#include "framework/test_framework.h"
#include "framework/pgm.h"
#include "core/OptimizationEngine.h"
#include "preprocessing/ImageProcessor.h"
#include "preprocessing/SubsetPrecomputer.h"

#include <cstdio>
#include <fstream>
#include <map>
#include <string>
#include <utility>
#include <vector>

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
    constexpr int GRID_LO = 80, GRID_HI = 432, GRID_STEP = 40; // 9x9 interior grid
    constexpr float FIELD_TOL = 1e-4f;                          // px; same-toolchain determinism

    struct FieldPt {
        int x, y;
        float u, v;
    };

    // Solve the engine over the grid; return every converged subset's solution.
    std::vector<FieldPt> solve_field(const Image &ref, const Image &def) {
        std::vector<FieldPt> field;
        for (int y = GRID_LO; y <= GRID_HI; y += GRID_STEP) {
            for (int x = GRID_LO; x <= GRID_HI; x += GRID_STEP) {
                SubsetData subset;
                SubsetPrecomputer::precompute_subset(subset, ref, x, y, SUBSET_SIZE);
                if (!subset.is_initialized) continue;
                OptimizationEngine engine;
                AnalysisResult res = engine.calculate_deformation(
                    subset, def, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
                if (res.status == 0) field.push_back({x, y, res.u, res.v});
            }
        }
        return field;
    }
} // namespace

TEST_CASE(FieldRegression, DiceRealField_Gold) {
    Pgm r, d;
    REQUIRE(load_pgm(std::string(DICE_FIXTURES_DIR) + "/ref.pgm", r));
    REQUIRE(load_pgm(std::string(DICE_FIXTURES_DIR) + "/def.pgm", d));
    Image ref(r.w, r.h, r.px.data());
    ref.prepare_data(false);
    Image def(d.w, d.h, d.px.data());
    def.prepare_data(false);

    const std::vector<FieldPt> field = solve_field(ref, def);
    REQUIRE(!field.empty());

    const std::string gold_path = std::string(GOLD_FIXTURES_DIR) + "/dice_realfield.gold";
    std::ifstream gf(gold_path);

    if (!gf) {
        // GENERATE: emit the field so it can be captured from the CI log and
        // committed. The test fails until the gold is in the tree.
        std::printf("=== GOLD MISSING — commit these lines to %s ===\n", gold_path.c_str());
        for (const auto &p : field) {
            std::printf("GOLD %d %d %.6f %.6f\n", p.x, p.y, (double) p.u, (double) p.v);
        }
        std::printf("=== END GOLD (%zu converged points) ===\n", field.size());
        REQUIRE(false); // gold must be committed before this test can pass
        return;
    }

    // VERIFY: load gold, then diff the freshly-computed field against it.
    std::map<std::pair<int, int>, std::pair<float, float>> gold;
    std::string tag;
    int gx = 0, gy = 0;
    float gu = 0, gv = 0;
    while (gf >> tag >> gx >> gy >> gu >> gv) {
        gold[{gx, gy}] = {gu, gv};
    }
    // The set of converged points must be stable — a point that newly fails
    // (or newly converges) is itself a regression.
    CHECK(field.size() == gold.size());
    for (const auto &p : field) {
        auto it = gold.find({p.x, p.y});
        REQUIRE(it != gold.end());
        CHECK_NEAR(p.u, it->second.first, FIELD_TOL);
        CHECK_NEAR(p.v, it->second.second, FIELD_TOL);
    }
}
