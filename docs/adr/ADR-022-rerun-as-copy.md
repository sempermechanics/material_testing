# ADR-022: A finished analysis is never overwritten; Re-run copies it into a new one

**Status:** Accepted, built
**Date:** 2026-10-15
**Deciders:** app owner
**Supersedes:** the in-place re-run of [ADR-004](ADR-004-runspec.md) "Later
(2026-10-15)" and [ADR-005](ADR-005-wizard-process-death.md) §"When the draft goes"
(a draft no longer outlives a run's results)

## Context

The wizard kept `AnalysisViewModel.workingLocalId` after a run saved. The next
**Compute** with the same inputs and run kind reused that id, so it replaced
the saved row's `.dat` files, `raw_deformed/` frames, thumbnail and backup in
place; it skipped the seat and quota checks (`wouldCreateNewSession`), and a
re-run whose first frame kept nothing rewrote or dropped the row
(`BatchAnalysis.afterUnsavedRerun`, `UnsavedRerun.kt`). The wizard stayed under
the viewer, so the viewer's Back went back into it. After a run the wizard
pointed at the session's own `raw_deformed/`, so sorting the frames renamed the
saved analysis's images (TD-197).

To start a new analysis from an old one the user had to pick the reference and
every frame again and re-enter the settings: the session keeps only a
display-size (≤ 1080 px) `reference.png`, and the mask was never written.

## Decision

1. **Results end the wizard.** When a run's results open (a full run, a
   partial stop's **View results**, a sweep's lattice) the wizard finishes
   (`BatchRunController.openResults`, since 2026-10-19; `WizardRunOutcomes` before), its draft is discarded, and Back from
   the results lands on Home.
2. **No in-place re-run.** Once a run's row is saved the wizard lets go of the
   session (`RunChannels.applyRunOutcome`, once its outcome says saved); `openRunSession` reuses a working
   id only while no Home row names it (an unsaved or cancelled run's folder).
   Every run is admitted (`admitRun`, `ensureCanStart`), and the session id
   reaches the results through `RunResult.sessionId` (since 2026-10-19 the run's outcome carries its saved row, which opens through `ViewerArgs.of`, the one builder Home uses too). The #439 staging, commit
   and cancel-discard stay.
3. **Re-run from Home.** A saved run also writes `<session>/rerun/`
   (`SessionLayout.rerunDir`, `RerunKeeper`):
   - `inputs.json` (`RerunInputs`, versioned): reference name and size, the
     ROI and whether it was drawn, the frames in run order (relative path,
     picked name, date, size, clip time), clip name, order mode, the
     parameters, and for a sweep its ranges, overlap, line cut and frame;
   - `reference.bin`: the reference bytes the run used (a RAW's RGBA);
   - `mask.bin` when there was one;
   - `frames/`: frames the run did not move into `raw_deformed/` (it stopped
     before them), moved there or copied from another session.

   Home's selection bar offers **Re-run** for exactly one row. It checks
   `RerunInputs.readComplete` (every file present and inside the folder), then
   the FAB's seat and quota gates, and opens the wizard with
   `IntentKeys.RERUN_SESSION_ID`. `RerunImportController` copies the reference,
   mask and frames into the wizard's import cache (`temp_deformed`, through
   `FrameImportHelper.commitStagedBatch`) and applies the settings
   (`AnalysisViewModel.applyRerun`, `WizardParamFields.applyRerun`,
   `SweepSetupController.showRerun`), landing on Parameters, or the sweep page
   for a sweep. Saving is a normal new analysis, with normal unique naming.
4. **TD-197.** `FrameOrderHelper.reprefixTempFiles` renames only frames in the
   import cache, so a sort never renames a session's files.

## Fallback: an analysis without `rerun/` (2026-10-15, owner decision)

A row whose results and every `raw_deformed/` frame are here but whose
`rerun/` is not (`RerunSource.NEEDS_REFERENCE`: saved before this change, or
restored) is re-run by picking its reference again:

- Home (`RerunReferencePicker`, after the seat and quota gates) asks "Pick its
  reference photo", opens the reference picker, and decodes the pick as a
  run does (`decodePickedReference`, shared with the wizard's import). Its
  size must be the record's `imgW` × `imgH`; another size says both and
  offers another pick. The accepted bytes go to
  `cacheDir/rerun_reference.bin` (`CacheJanitor.RERUN_REFERENCE_CACHE`), and
  the wizard gets `RERUN_REFERENCE_PATH` / `RERUN_REFERENCE_NAME` with the id.
- The wizard (`RerunLoader.loadWithReference`, `LegacyRerun.inputs`) builds
  the same copy as for kept inputs: the picked reference, copies of the
  frames in run order (`defNames`) with their clip times, the record's
  parameters, and for a sweep its axes read back from its solved and skipped
  combinations (`LegacyRerun.sweepRanges`), its overlap, line cut and frame.
  The order mode is the import's own, so run order stands.
- No record holds a mask, or whether there was one. A region that is not the
  inset full frame for a subset the run used was drawn, and may have been a
  freehand shape: it comes back as its rectangle, and the wizard says once
  "The mask wasn't saved; the region is a rectangle." A whole-image region
  says nothing.

## Options considered

- **Re-open the saved session's wizard and fork on Compute.** Keeps one flow,
  but the wizard would point at a saved session's files again (TD-197's
  cause) and needs the full-size reference, which was not kept anyway.
- **Rebuild from the session's own files.** No extra storage, but the
  reference is a display copy and the mask is missing, so the re-run would not
  be the same analysis.
- **Keep the inputs beside the session (chosen).**

## Trade-offs

- Storage: each saved run keeps a second copy of its reference at full size
  (RGBA for a RAW), and frames a run stopped before.
- `rerun/` is local only: it is not uploaded (owner decision, 2026-10-15), a
  restore does not bring it back, and **Free up space** drops it
  (`LocalArtifacts`). A restored or pre-2026-10-15 row is re-run through the
  fallback below; a freed-up one (no `raw_deformed/`) cannot be, and Home says so.
- A sweep keeps one solved frame, so its Re-run has that frame only.

## Consequences

- `UnsavedRerun.kt`, `cancel_rerun_body`, `wouldCreateNewSession`,
  `recordKeptRow` and `resolveLocalSessionId` are gone.
- The `.dat` bytes and the batch loop are unchanged: `keepBatchRerun` runs
  after the loop, before the frame paths are repointed.
- Tests: `RerunInputsTest`, `RerunWizardTest`, `SessionSelectionRerunTest`,
  `WizardRunOutcomesTest` (since 2026-10-19 `BatchRunControllerTest`, `RunEndTest`), `CancelDiscardsRunTest`, `RunKindOwnRecordTest`,
  instrumented `EnginePipelineSmokeTest`.

## Action items

- [x] Build, unit tests and docs (2026-10-15).
- [x] Owner (2026-10-15): `rerun/` stays on the phone; a restored row is re-run by picking its reference (fallback above).
- [ ] Pixel / emulator walk-through of [app/WORKFLOWS.md](../app/WORKFLOWS.md) 3.11a–g.
