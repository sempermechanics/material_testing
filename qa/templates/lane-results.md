# Lane $lane_id results: $lane_title

Run `$run`, written by `python scripts/qa/qa.py plan` on $generated ($count rows).
Suites: $suites. Backend: $backend.

## Devices

$devices

## Builds

$builds

## How to fill this in

- One row per scenario and device. Fill `result` with one of: `pass`, `fail`,
  `blocked`, `skipped`. Leave it blank if the row was not run.
- `build`: the build id you installed (from `qa/environments.toml`).
- `evidence`: a file under `evidence/` (a screenshot, a log excerpt) or a
  log line. A `pass` with no evidence is accepted; a `fail` never is.
- `note`: why it failed, was blocked or was skipped. A `fail` names where it
  is tracked: a TECH_DEBT row (`TD-123`), a future improvement (`FI-12`) or
  the PR that fixes it (`#456`). `qa.py report` exits non-zero on a fail
  whose note names none.
- Do not change the columns or the ids. Write a `|` inside a cell as `\|`.
- Expected behaviour and steps: `python scripts/qa/qa.py list --lane $lane_id`,
  or the scenario's table in `qa/suites/`.

## Results

$table
