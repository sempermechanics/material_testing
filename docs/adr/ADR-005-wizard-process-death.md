# ADR-005: The wizard survives process death through a draft

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** app owner

## Context

TD-26 said the wizard loses state on rotation. It does not:
`StaticAnalysisActivity` declares the full `configChanges` set
(`AndroidManifest.xml:147-149`), so rotation, dark mode and font scale never
recreate it. **Process death** does, and nothing survives it:

- `AnalysisViewModel` has no `SavedStateHandle` (none exists anywhere in
  `app/src/main`), and the Activity has no `onSaveInstanceState`.
- Lost: reference bytes and name, deformed-frame lists, ROI and mask, sweep
  configuration, the current step (it resets to 1), and `workingLocalId` — so a
  re-run after a kill creates a **second** Home row and counts twice against
  the quota.
- `CacheJanitor.sweepOnStartup` deletes `cacheDir/temp_deformed` on every
  process start (`CacheJanitor.kt:132`), on purpose, because the ViewModel did
  not survive — so saving paths alone would restore nothing.
- If the process dies while `RoiDrawActivity` is open, its result lands on a
  fresh ViewModel with `realRefWidth == 0`; `applyRoiResult` computes
  `hasCustomRoi = true` and applies a ROI with no reference loaded.

Android kills background processes routinely on low-RAM phones; a user who
switches to the gallery or a file manager mid-import is exactly the case.

## Decision

A **wizard draft**:

- `AnalysisViewModel` takes a `SavedStateHandle` (created with
  `SavedStateViewModelFactory` via `by viewModels`). It holds the small state:
  current step, ROI + `hasCustomRoi`, single-run settings, sweep configuration,
  `workingLocalId`, subset-recommendation key.
- The heavy inputs — reference bytes, mask, staged deformed frames — are staged
  under `filesDir/wizard_draft/` instead of `cacheDir/temp_deformed`, with a
  marker file carrying the draft's creation time.
- `CacheJanitor` leaves a live draft alone. It reclaims one older than 24 h, and
  the run's commit removes it.
- On recreate, the ViewModel restores from the handle and the draft. If any
  staged file is missing, the wizard resets to step 1 with a snackbar that says
  the draft was lost.
- `applyRoiResult` ignores a result that arrives with no reference loaded.

## Options considered

### A: `SavedStateHandle` for scalar state only

**Cons:** step, settings and ROI come back, but the frames do not
(`CacheJanitor` deletes them), leaving a half-restored wizard.

### B: Draft = `SavedStateHandle` + files in `filesDir` (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | ViewModel, staging helpers, `CacheJanitor`, restore path |
| Scalability | New editing state is one handle key |
| Team familiarity | Standard Jetpack API |

**Pros:** a kill is invisible to the user; no second Home row.
**Cons:** staged frames now live in `filesDir` until the run commits or 24 h
pass, so they count toward storage.

### C: Accept the loss; only guard the unsafe paths

**Cons:** the user still redoes the import; only the crash-like edge cases go
away.

## Trade-off analysis

B costs disk space for at most one draft for at most 24 hours; the draft is
already on disk today (in cache). A and C trade that space for a user redoing
the import, which on a 150-frame batch is minutes.

## Consequences

- Easier: long imports survive app switches; quota is not double-counted.
- Harder: `StorageBudget` must count the draft; the draft directory is one more
  path for `SessionPaths`/`CacheJanitor` to own.
- Revisit: combined with ADR-004's `RunSpec`, a full `StateFlow` UI state is a
  small step if the wizard grows.

## Action items

1. [ ] `SavedStateHandle` on `AnalysisViewModel`; keys for the small state.
2. [ ] Stage heavy inputs under `filesDir/wizard_draft/` + marker; commit
       deletes it.
3. [ ] `CacheJanitor`: keep a live draft; reclaim after 24 h.
4. [ ] Restore path + "draft lost" snackbar; guard `applyRoiResult`.
5. [ ] Tests: JVM restore from a `SavedStateHandle`; emulator
       `WizardDraftRestoreTest`; scripted `adb shell am kill` pass.
6. [ ] Close TD-26.
