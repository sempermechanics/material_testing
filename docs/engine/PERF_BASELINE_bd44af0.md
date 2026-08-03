# Performance / quality baseline — `bd44af0`

**Commit:** `bd44af027a42c1f03b928f2cafa975e75addc799` (2026-07-31)  
**Purpose:** Non-regression floor for tech-debt work (plan: robustness without regression).

## Speed (host Release, no sanitizers)

Captured on this machine via `build/native-tests/dic_tests` (`Perf.SubsetSolveThroughput`).

ICGN math at HEAD differs from `bd44af0` only by `float x[N] = {0.0f}` → `= {}` in `optimization_engine.cpp` (identical codegen intent), so the Release host binary is a valid speed proxy for this baseline.

| Metric | Value |
|---|---|
| Subsets precomputed | 196 |
| Subsets solved | 162 |
| Solve rate | **≥ 4797 solves/s** (repeat run ~5019) |
| Time per solve | **≤ 0.208 ms** (repeat ~0.199 ms) |
| Gate for later phases | **≥ 95% of 4797 ⇒ ≥ 4557 solves/s** |

Raw log: [`bd44af0-throughput-capture.txt`](bd44af0-throughput-capture.txt).

## Quality floors (do not loosen)

From `EnginePipelineSmokeTest` (emulator) at the same product line:

- translation / rotation / skew: median |U|,|V| error &lt; **0.25 px**; coverage floors unchanged
- blurred deformed: median error &lt; **0.4 px**; min coverage **0.10**
- DICe (when OpenCV available): `RMS_TOL = 0.005`, `MIN_COMPARED_FRACTION = 0.95`

## Compile flags to preserve on release pipeline

`-O3 -ffast-math` (math + pipeline); `-flto -fopenmp` on pipeline. Do not drop `-ffast-math` without a measured A/B that still meets the 95% speed gate and quality floors.
