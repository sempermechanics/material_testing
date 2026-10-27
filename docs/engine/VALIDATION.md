# Engine validation record

One row per dataset per engine pin: what was compared against a reference, the
bound, the result, and where the evidence is. Every engine bump adds rows here in
the bump PR ([TESTING.md](TESTING.md), Bumping the engine pin). A row says what a
run showed on that date; it does not carry forward to a later pin.

The comparisons themselves are engine tests in the submodule
(`engine/tests/dice/`, `engine/tests/integration/`); the engine repo's CI runs the
DICe ones on every commit. The DIC Challenge sweep and the real-data checks (PMMA,
steel) run only by hand: the sweep needs `SEMPER_RUN_SEEDBENCH=1` and
`SEMPER_DICE_REPO` set to a `dicengine/dice` checkout
(`engine/tests/integration/test_seeding_bench.cpp:120-128`), and the real-data
scripts live in material_testing ([FORK_SYNC.md](../ops/FORK_SYNC.md)).

## v0.6.1 (`f072da0`), pinned by #470

| Date | Dataset | Metric and bound | Result | Evidence |
|---|---|---|---|---|
| 2026-10-22 | DICe `dic_challenge_12` (open-hole tension, CFRP), step 01 | u, v RMS against `DICe_solution_01.txt` below 0.005 px, max below 0.02 px, at least 95 % of points compared (`engine/tests/dice/test_field_agreement.cpp:54-56`) | Pass | Engine CI run 39769086078 on `f072da0` (push to `main`): Host tests, Sanitized (asan-ubsan) and Sanitized (tsan) succeeded |
| 2026-10-22 | DICe `dic_challenge_12`, steps 03, 06, 11 | RMS against `DICe_solution_{03,06,11}.txt` below 0.05 px, at least 90 % compared (`engine/tests/dice/test_field_agreement.cpp:162-163`) | Pass | Same run |
| — | DIC Challenge sample 5 (`test_seeding_bench.cpp`) | as in the test | **Not recorded at this pin** | Owed: someone with `SEMPER_DICE_REPO` runs it at `f072da0` and adds the row |
| — | Real data: PMMA 3-point bend, `steel_24` | as in material_testing's scripts | **Not recorded at this pin** | `engine/CHANGELOG.md` 0.6.1 cites PMMA numbers without a date or run id; owed as above |

The DICe agreement bounds are inter-code agreement, not correctness: the
experiment has no analytic truth (`engine/tests/dice/test_field_agreement.cpp:8-11`).
