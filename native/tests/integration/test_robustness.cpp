// =====================================================================
// SUITE: Robustness — failure modes, degraded inputs, and concurrency
//
// The golden tests (test_optimization_engine.cpp) prove the engine is
// ACCURATE on clean input. This suite proves it is SAFE on bad input
// and CORRECT under the parallel usage pattern of the production JNI
// bridge (many worker threads solving against one shared, read-only
// reference subset and deformed image).
//
// Run under ThreadSanitizer (-DDIC_SANITIZER=thread) to turn the
// concurrency test into a data-race detector.
// =====================================================================
#include "framework/test_framework.h"
#include "framework/synthetic.h"
#include <semper/solver.hpp>
#include <semper/subset.hpp>

#include <random>
#include <thread>
#include <vector>

using Semper::Image;
using Semper::SubsetData;
using Semper::SubsetPrecomputer;
using Semper::OptimizationEngine;
using Semper::AnalysisResult;
using Semper::INIT_NO_SIMPLEX;

namespace {

    constexpr int W = 160, H = 160;
    constexpr int CX = 80, CY = 80;
    constexpr int DIM = 31;

    dictest::AffineDeformation translation(float u, float v) {
        dictest::AffineDeformation d;
        d.u = u; d.v = v;
        d.cx = (float) CX; d.cy = (float) CY;
        return d;
    }

    Image make_flat_image(float level) {
        std::vector<uint8_t> dummy((size_t) W * H, 0);
        Image img(W, H, dummy.data());
        for (auto &v : img.intensities) v = level;
        img.prepare_data(false);
        return img;
    }

} // namespace

// --- Sensor noise must not break sub-pixel recovery -------------------
// Real cameras add noise the analytic golden tests never see. ±3 grey
// levels of seeded uniform noise on the deformed image only (the
// reference stays clean, as in a real experiment where the reference is
// averaged) must still allow sub-pixel recovery, just with a looser
// tolerance than the clean-image 0.02 px.
TEST_CASE(Robustness, NoisyDeformedImage_TranslationStillRecovered) {
    const float u_true = 2.25f, v_true = -1.5f;
    dictest::SpeckleField field(7777, W, H);
    Image ref = dictest::make_reference_image(field, W, H);
    Image def = dictest::make_deformed_image(field, W, H, translation(u_true, v_true));

    std::mt19937 rng(42);
    std::uniform_real_distribution<float> noise(-3.0f, 3.0f);
    for (auto &v : def.intensities) {
        v += noise(rng);
        if (v < 0.0f) v = 0.0f;
        if (v > 255.0f) v = 255.0f;
    }
    def.prepare_data(false);

    SubsetData subset;
    SubsetPrecomputer::precompute_subset(subset, ref, CX, CY, DIM);
    REQUIRE(subset.is_initialized);

    OptimizationEngine engine;
    auto res = engine.calculate_deformation(subset, def, 2.0f, -2.0f, 0.0f,
                                            0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
    REQUIRE(res.status == 0);
    CHECK_NEAR(res.u, u_true, 0.05f);
    CHECK_NEAR(res.v, v_true, 0.05f);
}

// --- Textureless subset must be rejected at precompute ----------------
// A flat (zero-gradient) subset has a singular Hessian: there is no
// information to track. The precomputer's determinant guard must refuse
// it (is_initialized == false) rather than hand the solver a garbage
// system that could "converge" to a confident nonsense answer.
TEST_CASE(Robustness, TexturelessSubset_RejectedAtPrecompute) {
    Image flat = make_flat_image(128.0f);

    SubsetData subset;
    SubsetPrecomputer::precompute_subset(subset, flat, CX, CY, DIM);
    CHECK(!subset.is_initialized);
}

// --- Low contrast: ZNSSD normalization must carry the load ------------
// Compress the speckle contrast to 6% of nominal (a badly lit specimen).
// The zero-normalized criterion divides by the subset std-dev, so the
// solution should be nearly as good as full contrast.
TEST_CASE(Robustness, LowContrast_TranslationStillRecovered) {
    const float u_true = 1.4f;
    dictest::SpeckleField field(31415, W, H);
    Image ref = dictest::make_reference_image(field, W, H);
    Image def = dictest::make_deformed_image(field, W, H, translation(u_true, 0.0f));

    for (auto &v : ref.intensities) v = 128.0f + (v - 128.0f) * 0.06f;
    for (auto &v : def.intensities) v = 128.0f + (v - 128.0f) * 0.06f;
    ref.prepare_data(false);
    def.prepare_data(false);

    SubsetData subset;
    SubsetPrecomputer::precompute_subset(subset, ref, CX, CY, DIM);
    REQUIRE(subset.is_initialized);

    OptimizationEngine engine;
    auto res = engine.calculate_deformation(subset, def, 1.0f, 0.0f, 0.0f,
                                            0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
    REQUIRE(res.status == 0);
    CHECK_NEAR(res.u, u_true, 0.03f);
    CHECK_NEAR(res.v, 0.0f, 0.03f);
}

// --- Concurrency: shared read-only data, per-thread engines -----------
// This mirrors the production JNI bridge exactly: N worker threads, one
// OptimizationEngine each, all solving against the SAME SubsetData and
// deformed Image. Every thread must produce a result bit-identical to
// the single-threaded answer. Any cross-thread interference (hidden
// mutable state in Image/SubsetData, lazy caches, races) shows up as a
// mismatch — and as a report under -fsanitize=thread.
TEST_CASE(Robustness, ConcurrentSolves_BitIdenticalToSingleThread) {
    const float u_true = 2.25f, v_true = -1.5f;
    dictest::SpeckleField field(2025, W, H);
    Image ref = dictest::make_reference_image(field, W, H);
    Image def = dictest::make_deformed_image(field, W, H, translation(u_true, v_true));

    SubsetData subset;
    SubsetPrecomputer::precompute_subset(subset, ref, CX, CY, DIM);
    REQUIRE(subset.is_initialized);

    // Single-threaded reference answer
    OptimizationEngine ref_engine;
    auto expected = ref_engine.calculate_deformation(
            subset, def, 2.0f, -2.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_NO_SIMPLEX);
    REQUIRE(expected.status == 0);

    constexpr int kThreads = 8;
    constexpr int kSolvesPerThread = 25;
    std::vector<AnalysisResult> results((size_t) kThreads);
    std::vector<std::thread> workers;
    workers.reserve(kThreads);

    for (int t = 0; t < kThreads; ++t) {
        workers.emplace_back([&, t]() {
            OptimizationEngine engine; // per-thread, like the JNI worker pool
            AnalysisResult last{};
            for (int i = 0; i < kSolvesPerThread; ++i) {
                last = engine.calculate_deformation(
                        subset, def, 2.0f, -2.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                        INIT_NO_SIMPLEX);
            }
            results[(size_t) t] = last;
        });
    }
    for (auto &w : workers) w.join();

    for (int t = 0; t < kThreads; ++t) {
        const auto &r = results[(size_t) t];
        REQUIRE(r.status == 0);
        CHECK(r.u == expected.u);
        CHECK(r.v == expected.v);
        CHECK(r.ux == expected.ux);
        CHECK(r.uy == expected.uy);
        CHECK(r.vx == expected.vx);
        CHECK(r.vy == expected.vy);
        CHECK(r.correlation_score == expected.correlation_score);
    }
}
