# Readiness review — rubric addenda

General rules the Admin states while triaging or accepting findings. Each one is a
criterion for every lane in every later run, cited as `ADDENDUM-n`. Kept across runs;
decisions on single findings stay in [ADMIN_DECISIONS.md](ADMIN_DECISIONS.md).

| Id | Date | Rule (verbatim) | Applies to |
|---|---|---|---|
| ADDENDUM-1 | 2026-10-24 | "Keep fast-math (Recommended)": the engine may build with `-O3 -ffast-math` while the bit-exact `.dat` / GIF oracles and the determinism tests pass; removing it is an `ORACLE-CHANGE`. | TE-2 compiler-flag check (replaces "no `-ffast-math`") |
| ADDENDUM-2 | 2026-10-28 | "Enforce domain lock (Recommended)": institution IT may add only addresses on the licence's domain(s), and add / import replies are the same whether or not an address is licensed, invited or known elsewhere. | TE-3 authorization matrix, TE-4, AE-2 |
| ADDENDUM-3 | 2026-10-28 | "Demo / Licensed (Recommended)": the free tier is "Demo" (capital D) and the paid tier "Licensed" in the app, console and backend text. The Terms stay with counsel. | AE-3, AE-1 strings, AE-2 |
| ADDENDUM-4 | 2026-10-28 | "evrything, issuer too. but its "semper" not "Semper"" and "Everywhere user-facing": the product name is lower-case "semper" in every user-facing surface (app name and launcher label, strings, console including the authenticator issuer, emails, PDF, user docs). Legal pages excluded while counsel holds them. | AE-3, AE-1, AE-2 |
