# ADR-034: QC framework: catalogue as data, lanes as config

**Status:** Accepted, built (PR "Q" of the ADR-033 plan); no run yet
**Date:** 2026-10-29
**Deciders:** app owner
**Relates to:** ADR-033 (licence per app, explicit activation; its suite is the
first user), [ADR-027](ADR-027-test-accounts.md) (the QC test accounts),
[TESTING.md](../app/TESTING.md), [WORKFLOWS.md](../app/WORKFLOWS.md)

## Context

Manual and device QC has been prose. A readiness run or a feature check lives
in a Markdown checklist (the readiness reports under `docs/ops/readiness/`,
WORKFLOWS' tables), written for that occasion. The next feature starts a new
checklist, so the effort is not reused:

- nothing checks that an account, device or test a row names exists;
- a row's id is a line number or a heading, so a later run cannot say which
  rows changed;
- splitting the work across agents (the CLAUDE.md "Working" rule: no two
  agents share a device, an emulator or a Gradle build) is redone by hand
  each time, as are the briefs and their rules (`ANDROID_SERIAL`, no
  `gradlew --stop`, never `pm clear` the Pixel 6);
- results are free text, so nobody can count fails, see what ran only on an
  emulator, or diff two runs.

ADR-033's change needs about a hundred checks over five emulators, a real
phone, three consoles and two apps, run by parallel agents with human gates
for every staff or IT step-up. The product owner asked that the QC effort be
easy to read and interpret, and reusable for future testing.

## Decision

1. **The catalogue is data.** Scenarios are TOML tables in
   `qa/suites/<feature>.toml`, one table per check, with fixed fields (steps,
   expected, accounts, needs, human gates, automation, status). Devices,
   builds and backends are `qa/environments.toml`; account roles are
   `qa/accounts.toml`.
2. **Lanes are config.** `qa/lanes.toml` says how a run is split: each lane
   names its devices, builds, backend and the id groups or tags it takes.
   Re-splitting a run is an edit there, not a rewrite of the checklist.
3. **One small tool.** `scripts/qa/qa.py` (stdlib only) checks the data,
   lists scenarios, writes each lane's results table and agent brief from
   `qa/templates/`, and merges filled tables into a report with a scenario ×
   device matrix. `check` runs in CI on every event.
4. **Ids are permanent.** An id is `<prefix><group>.<n>` (`L3.5`). A suite's
   `[issued]` table holds each group's high-water mark; every id up to it must
   exist. A scenario is retired with `status = "retired"` and a note, never
   deleted, and its id is never reused. CI also fails if an id present on the
   base commit is gone.
5. **Extension is by prefix and by version.** An unknown field must start
   `x-` until `qa/SCHEMA.md` adopts it; a change that makes old files read
   differently bumps `schema` with a one-line migration note.
6. **Each lane has its own accounts.** An account address carries `{lane}`,
   so lanes running at once never touch each other's licences.
7. **Results carry their explanation.** A fail passes the report only when
   its note names a TECH_DEBT row, a future improvement or a PR.

## Options

| Option | For | Against |
|--------|-----|---------|
| **TOML + stdlib script (chosen)** | `tomllib` is in Python 3.11+, so CI and every machine read it with nothing to install; comments allowed; tables read like a form | Less expressive than a schema language; validation is hand-written |
| YAML | Familiar; terse lists | Needs PyYAML (a new dependency in CI and the lock); implicit typing (`no`, `1.10`) bites ids and API levels |
| JSON + JSON Schema | Formal schema, editor support | No comments; noisy to hand-edit; a validator is a dependency |
| Markdown checklists (today) | Nothing to learn | No validation, no stable ids, no matrix, no reuse |
| A test-management service | Dashboards, history | Outside the repository and its review; another account and cost; agents cannot read it offline |

## Trade-offs

- The data is more rigid than prose. A check that does not fit the fields
  goes in `steps` / `expected` text or an `x-` field until the schema grows.
- `qa.py` is ours to maintain (about 1,000 lines, and 600 of tests); in return it
  knows this repository's rules (devices that must not be used, the
  `TD-`/`FI-` register, the Kotlin test layout).
- Results tables are Markdown for people and agents to fill, so the report
  parses Markdown. The columns are fixed by the schema to keep that safe.
- Automation references are checked against files, not test names: a renamed
  test method is not caught, a moved or deleted test class is.

## Consequences

- A feature's QC plan lands as a suite file beside its PRs; WORKFLOWS and
  readiness docs point at the suite instead of copying rows.
- `qa/runs/<name>/` keeps every run, so a later run can `report --compare`
  against it and regressions are listed.
- CI's `console-pages` job runs `qa.py check --base HEAD^1` and the tool's
  tests, so broken references and deleted ids fail the PR that causes them.
- The licence-activation suite references tests that ADR-033's PRs will
  write, as `planned:`; `check --verbose` notes when each appears.

## Action items

- [x] `qa/` data, the licence-activation suite (L1–L15), templates, `qa.py`
      and its tests, the CI step (PR "Q").
- [ ] First run, `qa/runs/2026-11-licence-activation`, after ADR-033's PRs
      reach staging.
- [ ] Drop `planned:` from each reference as ADR-033's tests land.
- [ ] Point `docs/app/WORKFLOWS.md` at the suite for the activation rows when
      ADR-033's app PR documents them.
