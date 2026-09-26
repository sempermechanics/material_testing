# ADR-008: Real-device startup gates check the phone's state, and a trip is settled A/B

**Status:** Accepted, built (references not yet re-taken)
**Date:** 2026-09-26
**Deciders:** app owner

## Context

`benchmark/gates.json` gates a Pixel 6 run at 30 % over single reference
medians, taken on 2026-09-25 in a state nobody recorded. On 2026-09-25/26 the
wizard cold start read +23 % at `1b4253db`, on battery and at full charge. The
investigation (TD-135) found no code cause:

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
5. **References under the protocol.** Re-take them on the charger, at thermal
   status 0, with known app data, and put that state in the device's `label`.
   (Owed.)

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

## Trade-off analysis

State-gating can leave a run with nothing gated: a phone that sits at thermal
status 1 while charging would never be judged. That shows as `GATE not gated`
lines, not as a pass, so the reader sees it. Fifteen cold starts triple the
three cases' run time (about a minute on the Pixel, more on CI's emulator,
which is never gated). The A/B script takes about 15 minutes for two ABBA
blocks and swaps the app on the phone. It keeps the data, since the APK is
signed with the same debug key, and it restores the installed APK at the end.

## Consequences

- A startup gate breach on a Pixel run now means: in the reference state, over
  the limit. Check it with `startup_ab.py` before a code search.
- `gates.json` carries `state` and `abMargin`. Adding a phone means taking its
  references under the same state rules.
- Lowering `COLD_START_ITERATIONS` back to 5 reopens TD-135.

## Action items

- [x] `DeviceStateRule`, the state check in `ci_test_report.py`, `startup_ab.py`,
      15 cold-start iterations.
- [ ] Re-take the Pixel 6 references under the protocol and label their state
      (TD-135 item 4).
