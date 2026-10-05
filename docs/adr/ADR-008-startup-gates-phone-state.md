# ADR-008: Real-device startup gates check the phone's state, and a trip is settled A/B

**Status:** Accepted, built (in material_testing 2026-09-26; ported here 2026-10-01).
Semper's Pixel 6 references taken 2026-10-05 for the tests that stayed at status 0; the rest are owed (TD-155).
**Date:** 2026-09-26
**Deciders:** app owner

This decision was made in material_testing (its TD-135), on that app's Pixel 6
gates. The harness it built is shared code, so it is ported here unchanged; the
references are per app and are not.

## Context

material_testing's `benchmark/gates.json` gated a Pixel 6 run at 30 % over
single reference medians, taken on 2026-09-25 in a state nobody recorded. On
2026-09-25/26 the wizard cold start read +23 % at its `1b4253db`, on battery and
at full charge. The investigation (TD-135) found no code cause:

- The reference build (`05aac72`), run interleaved with `1b4253db` on the same
  phone, read 439–623 ms and failed its own gate in 2 of 4 rounds.
- Both builds slowed 30–40 % in six minutes as the phone warmed (unplugged,
  battery 38–39 °C, thermal status 1, 2.5 of 3 GB swap).
- Each median came from 5 cold starts, and one run's starts spread 404–478 ms.

A fixed-millisecond gate on an unrecorded state reads the phone as much as the
app. Left alone it produces false alarms, and people learn to ignore it.

## Decision

1. **Record the state.** `DeviceStateRule` runs in every gated benchmark class.
   It writes each test's thermal status, battery temperature and level, charger,
   free memory and swap, at start and end, to `*-deviceState.json` next to the
   results.
2. **Gate only in the reference state.** `ci_test_report.py --gates` does not
   gate a result whose test ran above `state.maxThermalStatus` (0) or off the
   charger (`state.requirePlugged`). It prints the reason instead. A result
   with no state file is gated as before, with a note.
3. **Settle a trip A/B.** `scripts/startup_ab.py` runs the reference APK and
   the candidate in ABBA order on the same phone, pools each build's runs, and
   fails the candidate only if its median is more than `abMargin` (10 %) over
   the reference's.
4. **More starts per median.** The three cold-start cases run 15 iterations
   (`StartupBenchmark.COLD_START_ITERATIONS`), not 5.
5. **References under the protocol.** Take them on the charger, at thermal
   status 0, with known app data, and put that state in the device's `label`.
6. **One harness, two apps.** The benchmark sources, `ci_test_report.py`,
   `startup_ab.py` and CI's benchmark job name no app id. `:benchmark` reads
   `:app`'s `applicationId` from its build (`benchmark/build.gradle.kts`, into
   `BuildConfig.TARGET_PACKAGE` and the manifest's `<queries>`); the CI job and
   `startup_ab.py` read the same line of `app/build.gradle.kts`. Only
   `gates.json`'s `devices` differ between the repos.

## Options considered

- **Widen the margin** (30 % → 50 %). Hides the drift and real regressions
  alike. Rejected.
- **Lock CPU clocks** (`androidx.benchmark` `cpuLocked`). Needs root; the Pixel
  is a personal phone. Rejected.
- **Gate only A/B, drop the fixed references.** Every check would then need two
  builds on the phone. The fixed gate stays as the cheap first check, and A/B is
  what decides when it trips.
- **Refuse a run in the wrong state** (fail the test). That loses the numbers.
  Recording the state and declining to gate keeps them visible.
- **Target package as an instrumentation argument** (`targetPackage`, with a
  default in the code). The default would still be a literal that differs
  between the repos, and an `am instrument` run without the argument would
  drive the wrong app. Rejected for the build-time value.
- **Copy material_testing's references.** They were measured on its app (its
  own id, its lab features), in the unrecorded state above. Rejected: Semper
  takes its own.

## Trade-off analysis

State-gating can leave a run with nothing gated: a phone that sits at thermal
status 1 while charging would never be judged. That shows as `GATE not gated`
lines, not as a pass, so the reader sees it. Fifteen cold starts triple the
three cases' run time (about a minute on the Pixel, more on CI's emulator,
which is never gated). The A/B script takes about 15 minutes for two ABBA
blocks and swaps the app on the phone. It keeps the data, since the APK is
signed with the same debug key, and it restores the installed APK at the end.
`:benchmark` now evaluates after `:app` (`evaluationDependsOn`), which costs
nothing today but would need another route if the build adopts isolated
projects.

## Consequences

- A startup gate breach on a phone run means: in the reference state, over the
  limit. Check it with `startup_ab.py` before a code search.
- `gates.json` carries `state` and `abMargin`. Adding a phone means taking its
  references under the same state rules.
- A test with no reference in `gates.json` is not gated. On Semper's Pixel 6 that is
  the startup cold and warm start and the wizard cold start (TD-155).
- Lowering `COLD_START_ITERATIONS` back to 5 reopens TD-135.

## Action items

- [x] `DeviceStateRule`, the state check in `ci_test_report.py`, `startup_ab.py`,
      15 cold-start iterations (material_testing, then here).
- [x] The harness reads the app id from `:app`'s build, so both repos carry the
      same files.
- [ ] Take Semper's Pixel 6 references under the protocol and label their state
      (TD-155).
