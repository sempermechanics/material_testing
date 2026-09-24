# ADR-003: `ViewerArgs.from` read side with a `SessionRecord` fallback

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** app owner

## Context

`ViewerArgs` (`app/.../ui/viewer/ViewerArgs.kt`) is the single writer of the
viewer's ~25 `DicKeys` extras: Home (`SessionOpenHelper.intentFor`) and the
fresh-run path (`AnalysisNavHelper.openResults`) both build it and call
`toIntent`. The read side is still four hand-written parsers —
`ResultViewerActivity`, `VsgLatticeActivity`, `ViewerSettingsSheet`,
`ViewerReportFactory` — and they disagree:

| Field | ResultViewer | ViewerReportFactory | ViewerSettingsSheet (ⓘ) | VsgLattice |
|---|---|---|---|---|
| `SUBSET_SIZE` missing | 41 | 41 | 0 | — |
| `STRAIN_WINDOW` missing | 15 | 15 | 0 | — |
| `STEP` missing | 5 | — | — | 1 |
| `ROI_W/H` missing | image W/H | — | 0 (row hidden) | 0 |
| `IMG_W/H` missing | decoded from the reference | — | 0 | — |

Every one of those defaults is silent. The two writers also disagree on
*values*, which is an accuracy fault (TD-61):

- ROI: a fresh run passes the ROI the user drew (`AnalysisNavHelper.kt:113-116`)
  while the session saves the resolved, inset ROI the engine actually ran on
  (`DicBatchRunner.kt:340-343`). The report, ⓘ sheet, CSV header and lattice
  line cut therefore change when the same session is reopened from Home.
- `SESSION_ID`: `Pending_Cloud_Sync_…` from a fresh run vs the local id from
  Home, so the PDF's "Session ID" line differs.
- Subset / strain window for a sweep: slider values vs the first combination.

The lattice→viewer hop copies the whole bundle (`putExtras(intent.extras)`)
instead of re-packing. Two legacy keys, `SWEEP_SKIP_*`, are read but never
written: they serve Intents already in the back stack from older builds.

Constraint: an Intent sitting in the back stack when the app updates must keep
opening.

## Decision

Add `ViewerArgs.from(intent: Intent, record: SessionRecord?): ViewerArgs` and
make the four readers take the parsed object.

- Parse every key `toIntent` writes, plus the legacy `SWEEP_SKIP_*` (folded into
  `ViewerSweepArgs.skippedJson`) and `START_FRAME`.
- A missing field resolves from the `SessionRecord` found by `SESSION_LOCAL_ID`
  (`SessionStore.get`) — the record carries almost every field.
- Only then fall back to **one** documented default per field, defined once in
  `ViewerArgs`, and log `Timber.w` naming each defaulted key.
- The lattice→viewer hop becomes `args.copy(startFrame = n).toIntent(host)`.
- The debug and benchmark seed activities and the Robolectric tests build
  `ViewerArgs` instead of packing extras by hand.

Writer side (with ADR-004): `AnalysisNavHelper` takes ROI, step, subset and
strain window from the run's `RunSpec`, and `SESSION_ID` from the saved record,
so both entry points produce equal `ViewerArgs` for the same session.

## Options considered

### A: Strict `from(intent)` that fails on a missing extra

**Cons:** crashes an Intent from an older build restored from the back stack.

### B: `from(intent, record)` with record fallback and one default table (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | Four readers, two seed activities, tests |
| Scalability | New fields are added in one type |
| Team familiarity | Same `ViewerArgs` type the writers already use |

**Pros:** old Intents still open; a missing value is repaired from the record
or at least logged; one default per field.

### C: Pass only the session id; load everything from `SessionStore`

**Cons:** a fresh sweep in which every combination failed has no record; it
also changes the back-stack format in one step.

## Trade-off analysis

B keeps compatibility with old Intents (A does not) and handles runs with no
saved record (C does not). The cost is a record lookup on viewer open, which
`ResultViewerActivity` already warms (`sessionRecord` lazy, `:277`), so it adds
no I/O.

## Consequences

- Easier: the ⓘ sheet, report, CSV and lattice agree whatever the entry path.
- Harder: a new viewer field must be added to `ViewerArgs`, its default table
  and `SessionRecord` mapping — which is the point.
- Revisit: once no build older than this can be in a back stack (two releases),
  drop the `SWEEP_SKIP_*` legacy read.

## Action items

1. [ ] `ViewerArgs.from` + default table + `Timber.w` on defaulted keys.
2. [ ] Four readers take `ViewerArgs`; lattice hop re-packs.
3. [ ] Seed activities and Robolectric tests build `ViewerArgs`.
4. [ ] Tests: round trip `from(toIntent(x)) == x`; legacy-extras fixture;
       `ViewerArgsTest` value parity between the two entry points.
5. [ ] Emulator: `ViewerEntryParityDeviceTest`, `LatticeToViewerHopTest`.
6. [ ] Close TD-3 and TD-61.
