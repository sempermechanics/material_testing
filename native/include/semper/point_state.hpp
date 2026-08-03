#ifndef SEMPER_POINT_STATE_HPP
#define SEMPER_POINT_STATE_HPP

#include <cstdint>

namespace Semper {
namespace pipeline {

/**
 * Mesh / skip / solved semantics for a full-field grid node.
 *
 * The hot `GridPoint` in `run_full_field` still uses `bool solved` (+
 * `mesh_assignment_type` / `inMesh`) so layout and throughput stay at the
 * bd44af0 floor. Use this enum at phase boundaries / debug exports — do not
 * widen the hot GridPoint without a measured A/B.
 */
enum class PointState : std::uint8_t {
    Open = 0,       ///< Eligible for Path A/B
    Skipped = 1,    ///< ROI / boundary reject (never solved)
    InMesh = 2,     ///< Assigned to the AKAZE/Delaunay mesh
    Solved = 3,     ///< Successfully correlated
};

/** Classify worker-facing flags without changing GridPoint layout. */
inline PointState classify_point_state(bool solved, bool in_mesh, float corr) {
    if (solved && corr >= 0.0f) return PointState::Solved;
    if (solved) return PointState::Skipped; // pre-skipped or claimed-failed
    if (in_mesh) return PointState::InMesh;
    return PointState::Open;
}

} // namespace pipeline
} // namespace Semper

#endif // SEMPER_POINT_STATE_HPP
