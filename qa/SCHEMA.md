# QC data schema (schema = 1)

Every TOML file under `qa/` starts with `schema = 1`. `scripts/qa/qa.py check`
enforces everything on this page; when the two disagree, the code's `FIELDS`
table is what runs, and this page is the bug. How to use the files:
[README.md](README.md).

Types: *string*, *int*, *bool*, *list* (of strings), *table*. "Req." marks a
required field; the rest are optional.

## Extending

- **New field:** prefix it `x-` (`x-owner = "qa"`). `check` accepts any `x-`
  field anywhere and rejects every other unknown one.
- **Adopting it:** when an `x-` field earns its place, drop the prefix, add it
  to `FIELDS` in `qa.py` and to this page, rename it in the data, all in one
  PR. That is not a schema bump: old files still read.

## Changing the schema

Bump `SCHEMA` in `qa.py` and `schema` in every file only when an existing
file would be **read differently**: a field renamed or removed, a meaning
changed, a vocabulary word dropped. In the same PR:

1. migrate every file under `qa/` (and the `runs/` result tables if their
   columns change);
2. add a one-line note to the table below.

| Schema | Since | Change |
|--------|-------|--------|
| 1 | ADR-034 | First version. |

## `environments.toml`

Top level: `schema` (int, req.), `[[device]]`, `[[build]]`, `[[backend]]` (each req.).

### `[[device]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Unique. For an emulator, usually its AVD name. |
| `kind` | string | yes | `emulator` or `real`. |
| `avd` | string | for an emulator | The AVD name `emulator -avd` takes. |
| `adb_device` | string | | For a real phone: the `device:` value `adb devices -l` shows. |
| `model` | string | | Marketing name. |
| `api` | string | yes | Android API level, a string so `"36.1"` fits. |
| `abi` | string | yes | `x86_64`, `arm64-v8a`. |
| `page_size` | string | yes | `4k` or `16k`. |
| `tags` | list | yes | Lower-case words joined by `-` (`api30`, `16k`, `real`). Lanes and scenarios pick devices by `tag:<tag>`. |
| `use` | bool | yes | `false` keeps a phone listed but unusable: no lane may name it. |
| `note` | string | if `use = false` | Why. |

### `[[build]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Unique. |
| `app` | string | yes | `semper` or `semperclassroom`. |
| `variant` | string | yes | `debug` or `release`. |
| `abi` | string | yes | The ABI it is built or shipped for. |
| `source` | string | yes | Where it comes from: this repository, the fork, a released APK. |
| `recipe` | string | yes | What a lane runs to build and install it. |
| `env` | table | | Environment variables set in the lane's shell only (string values). |
| `note` | string | | |

### `[[backend]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Unique; lanes name it. |
| `service`, `gateway`, `console`, `docs`, `note` | string | | Where it is and where to read about it. |

## `accounts.toml`

Top level: `schema` (int, req.), `domain` (string, req.: the QC address
domain, which must be in the backend's `TEST_ACCOUNT_DOMAINS`),
`[[institution]]`, `[[account]]` (req.). No field anywhere may name a
password, secret, seed or key.

### `[[institution]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Unique; accounts name it. |
| `title` | string | yes | |
| `kind` | string | yes | `assigned` or `floating`. |
| `domain_lock` | string | yes | The licence's `domainLock`. |
| `max_seats` | int | yes | |
| `max_devices` | int | | The pool's device count (assigned licences). |
| `apps` | list | yes | `semper`, `semperclassroom`. |
| `admins` | list | yes | Account ids of its IT admins (may be empty). |
| `note` | string | | |

### `[[account]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Unique; scenarios name it (`qc-ind1`). |
| `address` | string | yes | The address pattern. `{lane}` is replaced by the lane id in lower case, so each lane has its own account; `<…>` marks a part a person fills in. Must end `@<domain>` unless `test_account = false`. |
| `role` | string | yes | `holder`, `member`, `it-admin` (free-form, shown in briefs). |
| `apps` | list | yes | Apps it is licensed or used for. |
| `licence` | string | yes | `individual`, `institution`, `floating` or `none` (Demo). |
| `max_devices` | int | | The licence's device count, for an individual licence. |
| `institution` | string | for `institution` / `floating` | An `[[institution]]` id. |
| `second_factor` | string | | `totp` when it enrols one. |
| `test_account` | bool | | Default true. `false` for an address that cannot be an ADR-027 test account; needs a `note`. |
| `created_by` | string | yes | Who creates it (`staff`). |
| `used_for` | string | yes | What scenarios use it for; shown in briefs. |
| `note` | string | | |

## `lanes.toml`

Top level: `schema` (int, req.), `[[lane]]` (req.), `[[gate]]`.

### `[[lane]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Letters, digits, `-`, `_`; names `LANE-<id>.md`. |
| `title` | string | yes | |
| `devices` | list | yes | Device ids or `tag:<tag>`; may be empty. No device may be in two lanes, and none with `use = false`. |
| `builds` | list | yes | Build ids; may be empty. |
| `backend` | string | yes | A backend id. |
| `suites` | list | yes | Suite ids. |
| `groups` | list | | Id groups to take (`L2`); each must be in some suite's `[issued]`. |
| `scenarios` | list | | Extra scenario ids to take, past `groups` and the tag filters. |
| `tags` | list | | Keep a group pick only if it has one of these tags. |
| `exclude_tags` | list | | Drop a group pick with any of these tags. |
| `tools` | list | | Free text: other tools the lane uses (the in-app browser). |
| `note` | string | | |

### `[[gate]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | Scenarios name it in `human_gate`. |
| `who` | string | yes | Who acts. |
| `ask` | string | yes | What the lane asks for, as an instruction. |

## `suites/<name>.toml`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `schema` | int | yes | `1`. |
| `id` | string | yes | Same as the file name without `.toml`. |
| `title` | string | yes | |
| `prefix` | string | yes | Capital letters; starts every id in the suite; unique across suites. |
| `since` | string | yes | The ADR or PR that brought the feature. |
| `issued` | table | yes | Group (`L3`) → the highest number ever issued in it (int ≥ 1). |
| `note` | string | | |
| `[[scenario]]` | tables | yes | Below. |

### `[[scenario]]`

| Field | Type | Req. | Meaning |
|-------|------|------|---------|
| `id` | string | yes | `<prefix><group>.<n>` (`L3.5`). Unique across all suites; never reused or deleted; `n` ≤ `[issued]` for its group. |
| `title` | string | yes | One line, no `\|`. |
| `area` | string | yes | Free-form grouping (`activate`, `console`). |
| `tags` | list | yes | Lower-case words joined by `-`; lanes and `list --tag` filter on them. |
| `accounts` | list | yes | Account ids from `accounts.toml`; may be empty. |
| `needs` | list | yes | One or more of `emulator`, `device`, `web`, `backend`. |
| `human_gate` | list | | Gate ids from `lanes.toml`; the lane stops and asks at each. Default none. |
| `devices` | list | | Device ids or `tag:<tag>` the scenario must run on. |
| `builds` | list | | Build ids it needs (prefilled in the results when the lane has exactly one of them). |
| `steps` | list | yes | At least one step, in order. |
| `expected` | string | yes | What a pass looks like. |
| `automation` | string or list | yes | See below. |
| `status` | string | yes | `active`, `deferred` (cannot run yet) or `retired` (kept for its id). |
| `note` | string | if not `active` | Why it is deferred or retired; anything else worth knowing. |
| `replaced_by` | string | | For a retired scenario: the id that took over. |
| `since` | string | yes | The ADR or PR that added it. |

### Automation

One reference or a list of them:

| Form | Meaning |
|------|---------|
| `manual` | No code covers it (or, in a list, part of it is manual). |
| `androidTest:<Class>[#method]` | `<Class>.kt` must exist under `app/src/androidTest`. |
| `unitTest:<Class>[#method]` | `<Class>.kt` must exist under `app/src/test`. |
| `pytest:<path>[::test]` | The file must exist, from the repository root. |
| `node:<path>` | A `node --test` file, from the repository root. |
| `check:<path>` | A source check script, from the repository root. |
| `planned:<any of the above>` | Not written yet: the form is checked, the file is not. |

## Run folders (`runs/<name>/`)

`plan` writes them; people and agents fill them; `report` reads them.

- `LANE-<id>.md`: free text, then one Markdown table with exactly these
  columns: `id | title | device | build | result | evidence | note`.
  `device` is a device id or `-`. `result` is `pass`, `fail`, `blocked`,
  `skipped` or blank (not run). A `|` inside a cell is written `\|`.
- `BRIEF-<id>.md`: the agent's brief; not read back.
- `evidence/`: screenshots and log excerpts the tables point at. Never a
  password, token or key.
- `REPORT.md`: written by `report`; never edited by hand.

A fail is **explained** when its note contains `TD-<n>`, `FI-<n>` or `#<n>`.
