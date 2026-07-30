// Solver throughput — precompute and solve rates, printed for tracking.
//
// NOT a benchmark gate. CI runners are noisy and this same binary is built
// under ASan/UBSan/TSan, where everything is several times slower, so a
// subsets/sec assertion would be flaky and meaningless. The measured rate is
// printed for humans to compare across commits; the only assertion is a
// generous wall-clock ceiling that catches a hang or catastrophic regression.
//
// For real benchmarking, run this locally on a quiet machine.
#include "framework/test_framework.h"
#include "framework/synthetic.h"
#include <semper/solver.hpp>
#include <semper/subset.hpp>

#include <chrono>
#include <cstdio>

using Semper::Image;
using Semper::SubsetData;
using Semper::SubsetPrecomputer;
using Semper::OptimizationEngine;
using Semper::AnalysisResult;
using Semper::INIT_NO_SIMPLEX;

namespace {
    constexpr int W = 512, H = 512;
    constexpr int SUBSET_SIZE = 27;
    constexpr int GRID_LO = 60, GRID_HI = 452, GRID_STEP = 28; // 15x15 = 225 subsets

    // Generous: ~225 subsets should take well under a second natively, and a
    // few seconds even under TSan. 120 s only trips on a hang or a pathological
    // regression.
    constexpr double WALL_CLOCK_CEILING_S = 120.0;
} // namespace

TEST_CASE(Perf, SubsetSolveThroughput) {
    dictest::SpeckleField field(/*seed=*/7, W, H);
    dictest::AffineDeformation def;
    def.u = 0.35f;
    def.v = -0.20f;
    def.cx = W / 2.0f;
    def.cy = H / 2.0f;

    const Image ref = dictest::make_reference_image(field, W, H);
    const Image deformed = dictest::make_deformed_image(field, W, H, def);

    using clock = std::chrono::steady_clock;
    const auto t0 = clock::now();

    int precomputed = 0, solved = 0;
    double precompute_s = 0.0, solve_s = 0.0;
    for (int y = GRID_LO; y <= GRID_HI; y += GRID_STEP) {
        for (int x = GRID_LO; x <= GRID_HI; x += GRID_STEP) {
            const auto tp = clock::now();
            SubsetData subset;
            SubsetPrecomputer::precompute_subset(subset, ref, x, y, SUBSET_SIZE);
            precompute_s += std::chrono::duration<double>(clock::now() - tp).count();
            if (!subset.is_initialized) continue;
            ++precomputed;

            const auto ts = clock::now();
            OptimizationEngine engine;
            AnalysisResult res = engine.calculate_deformation(
                subset, deformed, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
            solve_s += std::chrono::duration<double>(clock::now() - ts).count();
            if (res.status == 0) ++solved;
        }
    }

    const double total_s = std::chrono::duration<double>(clock::now() - t0).count();
    REQUIRE(solved > 0);

    std::printf("  Perf: %d subsets precomputed, %d solved in %.3f s\n"
                "     precompute %.3f s (%.0f subsets/s) | solve %.3f s (%.0f solves/s)\n"
                "     %.3f ms per solve\n",
                precomputed, solved, total_s,
                precompute_s, precomputed / (precompute_s > 0 ? precompute_s : 1.0),
                solve_s, solved / (solve_s > 0 ? solve_s : 1.0),
                1000.0 * solve_s / solved);

    // Smoke-level guard only — see the header comment.
    CHECK(total_s < WALL_CLOCK_CEILING_S);
}
