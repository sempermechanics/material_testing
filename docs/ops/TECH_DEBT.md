# Tech debt

Program status: **cleared**.

- `app/lint-baseline.xml` and `app/detekt-baseline.xml` are empty.
- `./gradlew :app:lintDebug` reports **no errors or warnings**.
- Inherent size/complexity in a few UI orchestration files uses targeted
  `@file:Suppress` — prefer extracting over widening those lists.
- Catalog version-availability lint IDs are disabled; bump deps in deliberate PRs.

## External / deferred (not blocked on code alone)

| Item | Why deferred |
|------|----------------|
| Auth-gated UI E2E | Needs Firebase secrets / fixtures in CI |
| Kover `minBound` raise | Measure stable % on CI first (local AGP 9 often reports no coverage) |
| firebase-admin 7.x | Held with cryptography **44.0.0**; bump as a pair after verifying train |

## Perf / quality gates (do not loosen)

See [../engine/PERF_BASELINE_bd44af0.md](../engine/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s host; smoke/DICe floors; preserve `-O3 -ffast-math` / OpenMP / LTO
on the release pipeline.

## History

Earlier burn-down (CI path filters, Hilt removal, engine Path A–C split, OkHttp 5,
FastAPI train, Analysis helpers, Kover floor 15, UseKtx/Plurals/Overdraw, etc.)
is in git history — do not re-open closed items without new evidence.
