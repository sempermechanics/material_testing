# 4× per-point solve divergence reconciliation

Pinned during tech-debt N3 against baseline `bd44af0` quality/speed charter.
Canonical accept/reject policy lives in [`tuning.hpp`](../../native/include/semper/tuning.hpp).

| Divergence | Chosen behaviour | Rationale |
|---|---|---|
| Corr gate / LM / ghost / timeout classify | `kCorrAccept` / `kLmAlpha` / `kGhostRejectFraction` / `kIcgnMaxIter` on all four copies | Already unified; preserves field quality |
| Path B seed search flag | Honor `ALLOW_SIMPLEX_RESCUE` like Path A / prewarm / neighbor | Toggle must disable simplex everywhere |
| Prewarm stored `icgn_iters` | Use `res.iters` (not engine-counter Δ) | Same metric as timeout classify + debug maps |
| Path B seed iter stats | Always `stats_pathB[tid].icgn_iters += res.iters` | Telemetry must count seed solves |
| Neighbor claim / path-specific guesses | Unchanged | Not accidental paste; flood reclaim + seed models differ |
| Debug export `icgn_iters < 50` | `tuning::kIcgnMaxIter` | Same threshold as classify |

Guess DOFs (mesh 6-DOF vs seed u,v vs kinematic neighbor) intentionally remain path-specific.
