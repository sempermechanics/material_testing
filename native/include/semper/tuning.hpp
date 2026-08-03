#ifndef SEMPER_TUNING_HPP
#define SEMPER_TUNING_HPP

// Named constants for the DIC pipeline / strain paths.
// Values match the pre-centralization literals exactly (rename-only). Do not
// change a number here without re-baselining throughput and field quality
// against docs/engine/PERF_BASELINE_bd44af0.md.

namespace Semper {
namespace tuning {

/** ZNSSD acceptance gate used by run_full_field Path A/B/prewarm. */
inline constexpr float kCorrAccept = 0.15f;

/** Correlation score above which calculate_deformation may try simplex. */
inline constexpr float kCorrSimplexTrigger = 0.4f;

/** Simplex residual above which the point is marked failed. */
inline constexpr float kSimplexFailScore = 0.1f;

/** Levenberg–Marquardt diagonal damping applied in the full-field paths. */
inline constexpr float kLmAlpha = 0.05f;

/** ICGN iteration budget (also the Path A/B "timeout" classify threshold). */
inline constexpr int kIcgnMaxIter = 50;

/** Reject a subset if more than this fraction of ref pixels hit the ghost wall. */
inline constexpr float kGhostRejectFraction = 0.05f;

/** AKAZE inlier convex-hull coverage for SPARSE vs clustered routing. */
inline constexpr float kAkazeSparseCoverage = 0.05f;

/** Packed-field sentinel for "no valid correlation at this grid point". */
inline constexpr float kCorrInvalid = -1.0f;

/** Strain calculator write sentinel (VSG). Reader tests <= kStrainFailSentinel. */
inline constexpr float kStrainUninitSentinel = -1000.0f;

/** Strain drop-filter threshold in run_full_field (accepts the write sentinel). */
inline constexpr float kStrainFailSentinel = -999.0f;

/** Path B queue wait cancel-poll interval (ms). */
inline constexpr int kCancelPollMs = 20;

/** Floating-point radius fudge used in VSG window membership. */
inline constexpr double kVsgRadiusTiny = 1.0e-5;

} // namespace tuning
} // namespace Semper

#endif // SEMPER_TUNING_HPP
