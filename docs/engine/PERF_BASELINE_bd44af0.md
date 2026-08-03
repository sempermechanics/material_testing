# Performance / quality baseline — `bd44af0`

**Commit:** `bd44af027a42c1f03b928f2cafa975e75addc799` (2026-07-31)  
**Purpose:** Non-regression floor for tech-debt work (plan: robustness without regression).

## Speed (host Release, no sanitizers)

Captured via `dic_tests` (`Perf.SubsetSolveThroughput`). Gate for later phases: **≥ 95% of 4797 ⇒ ≥ 4557 solves/s**.

| Metric | Value |
|---|---|
| Subsets precomputed | 196 |
| Subsets solved | 162 |
| Solve rate | **≥ 4797 solves/s** |
| Time per solve | **≤ 0.208 ms** |

## Quality floors (do not loosen)

From `EnginePipelineSmokeTest` (emulator):

- translation / rotation / skew: median |U|,|V| error &lt; **0.25 px**; coverage floors unchanged
- blurred deformed: median error &lt; **0.4 px**; min coverage **0.10**
- DICe (when OpenCV available): `RMS_TOL = 0.005`, `MIN_COMPARED_FRACTION = 0.95`

## Compile flags to preserve on release pipeline

`-O3 -ffast-math` (math + pipeline); `-flto -fopenmp` on pipeline. Do not drop `-ffast-math` without a measured A/B that still meets the 95% speed gate and quality floors.
