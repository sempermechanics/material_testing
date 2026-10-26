# Readiness review — rubric addenda

General rules the Admin states while triaging or accepting findings. Each one is a
criterion for every lane in every later run, cited as `ADDENDUM-n`. Kept across runs;
decisions on single findings stay in [ADMIN_DECISIONS.md](ADMIN_DECISIONS.md).

| Id | Date | Rule (verbatim) | Applies to |
|---|---|---|---|
| ADDENDUM-1 | 2026-10-24 | "Keep fast-math (Recommended)": the engine may build with `-O3 -ffast-math` while the bit-exact `.dat` / GIF oracles and the determinism tests pass; removing it is an `ORACLE-CHANGE`. | TE-2 compiler-flag check (replaces "no `-ffast-math`") |
