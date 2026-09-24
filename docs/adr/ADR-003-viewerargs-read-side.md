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

1. [x] `ViewerArgs.from` + one default per field + `Timber.w` naming each
       key filled from the record or defaulted.
2. [x] Four readers take `ViewerArgs`; the lattice hop re-packs
       (`args.copy(startFrame = n).toIntent`).
3. [x] Seed activities and Robolectric tests build `ViewerArgs`
       (`ViewerArgs.ofFrames`).
4. [x] `ViewerArgsTest` (11): single-run and sweep round trips, Home's Intent
       reads back as Home's args, the hop, a legacy `SWEEP_SKIP_*` fixture,
       record fallback, defaults with no record.
5. [x] Emulator: `ViewerEntryParityDeviceTest` (2), which covers the hop too
       instead of a separate `LatticeToViewerHopTest`.
6. [x] Close TD-3 and TD-61.

## As built (2026-09-23)

- **The record is a lambda, read only for a missing key.**
  `from(intent, record: () -> SessionRecord?)`. The Trade-off analysis above
  was wrong that the lookup is free: `ResultViewerActivity` warms
  `sessionRecord` on the IO dispatcher, so a synchronous read in `onCreate`
  would have been a main-thread index read on every open. Today's writers put
  every key, so the lambda never runs for them.
- **A present key wins even when its value is null.** `ENGINE_STATS` and
  `SESSION_ID` can legitimately be null (a seeded viewer, a sweep that solved
  nothing); only an *absent* key falls back.
- **The defaults** are step 5, subset 41, strain window 15 and the whole image
  as ROI. They were step 5 or 1, subset 41 or 0, and ROI image-size or 0,
  depending on the reader.
- **`engineStats` is `List<Float>?`**, so `ViewerArgs` equality means
  something and the round-trip tests compare whole objects.
- **`startFrame` picks the destination.** A sweep opens on its lattice unless
  a node has been picked; the hop is `copy(startFrame = n)`.
- **Sweep keys never fall back to the record**: their absence is what makes an
  Intent a single run. Legacy `SWEEP_SKIP_*` arrays fold into `skippedJson`.
- **What still differs between the entry points is intended**: `defPath` and
  `defFilePaths`, the fresh run's own image paths. The viewer prefers the
  persisted originals either way.

## In material_testing (2026-09-24)

The merge of this ADR into material_testing adds the mechanical test to both
sides: `testType`, `crossSectionMm2`, `loadAxisX`, `loadsN` (a `List<Float>`,
like `engineStats`, so the round trip compares by value) and `geometry`. Each
falls back to its `SessionRecord` field; `loadsN` only when the record's loads
cover every frame (`hasMachineLoads`). `ResultViewerActivity` and
`ViewerSettingsSheet` read them from `args`.
