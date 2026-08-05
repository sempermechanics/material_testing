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

## 2026-08-05 transparency / robustness audit

A whole-app + backend pass for silent failures, crashes, security and tech debt.
The branch was already strong; findings and fixes were small:

- **Crash guards (fixed).** The JNI full-field solve reads its direct output buffer
  back by the engine's returned point count. Added a bound: a count exceeding the
  ROI-grid buffer capacity is now treated as an engine failure instead of reading
  past the buffer (`AnalysisViewModel`, `VsgStudyRunner`). Closes the residual
  "JNI buffer" concern from the earlier Tier-0 list.
- **Fragile null-asserts (fixed).** `ShareCenter` replaced nine `snap!!` sites with
  a checked `requireSnapshot()` that fails the share job cleanly (snackbar) rather
  than NPE-crashing.
- **Silent restore failure (fixed).** A background restore that failed terminally
  was never surfaced; `SettingsActivity` now observes the `restore` work tag and
  shows the reason, mirroring the existing upload-failure surfacing on Home.
- **Verified clean (no change):** cleartext disabled, no local token storage, no
  hardcoded secrets, release signing wired to an env keystore, backend Drive query
  escaping + `parents` confused-deputy guard, and no PII in Crashlytics WARN/ERROR
  breadcrumbs (`CrashReportingTree`).

## History

Earlier burn-down (CI path filters, Hilt removal, engine Path A–C split, OkHttp 5,
FastAPI train, Analysis helpers, Kover floor 15, UseKtx/Plurals/Overdraw, etc.)
is in git history — do not re-open closed items without new evidence.
