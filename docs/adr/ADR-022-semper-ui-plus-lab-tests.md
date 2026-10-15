# ADR-022: Material Testing is Semper's UI plus two test choices

**Status:** Accepted, built (2026-10-14, the sync of semperdic-app `8ba7081`)
**Date:** 2026-10-14
**Deciders:** app owner

## Context

material_testing takes semperdic-app's `main` by merge ([FORK_SYNC](../ops/FORK_SYNC.md)).
Over its first weeks it also changed shared screens for everyone, not only for
its lab tests. A plain 2D DIC analysis therefore looked and behaved unlike
Semper's in five places:

1. the video sampling sheet (Frame rate by default, its own key-frame count);
2. every sweep plot's scrub label, "(x, y)" with units;
3. the CSV, version 2 with empty `load_N,stress_MPa` cells on every row;
4. the strain window's ⓘ text, reworded with a bending sentence;
5. "Which test?" listed 2D DIC last, and a wizard opened without a type was
   Tensile.

Each one also had to be merged by hand against the parent's own changes to the
same screen, sync after sync.

## Decision

The app is Semper with two more choices. **+** asks **Which test?**:
**2D DIC** (first) is Semper's analysis, unchanged; **Tensile** and **Bending**
add what they need and nothing else.

1. **Shared screens are the parent's.** A sync takes the parent's version of a
   shared file and puts back only the lab hooks.
2. **A fork change to a shared screen is gated on the test.** It runs only for
   a tensile or bending session: `TestType.hasMachineLoad`, a non-blank
   `testType` (`AnalysisCsvWriter.Metadata.isLabTest`), or a flag only the lab
   code sets (`SweepPlotView.scrubShowsPoint`). Plain 2D DIC, and a session with
   no test type, takes the parent's path.
3. **The one screen the fork adds** in front of Semper's flow is the
   **Which test?** sheet (`TestTypeSheet`), drawn as the parent's sheets are.
4. **What the lab needs from shared code** goes in as the smallest addition the
   parent's code allows: `ReferenceFrame.timeMs` and
   `AnalysisViewModel.refVideoTimeMs` for the load log's frame times,
   `PdfReportGenerator.Stage.STRESS_STRAIN`, the lab-report row in the share
   sheet. A change useful to both apps goes to the parent first.

The five places above were brought back to the parent's behaviour in the
2026-10-14 sync (G1–G5 in its PR).

## Options considered

- **Keep the fork's versions.** Every sync re-merges them against the
  parent's changes to the same screens, and Semper users who move to this app
  meet a different app. Rejected.
- **A build flavour in semperdic-app.** The cleanest end state; still not now
  ([ADR-009](ADR-009-material-testing-app-identity.md)), but this decision keeps
  the difference small enough to move into one later.

## Consequences

- A sync resolves conflicts in shared UI files by taking the parent's side and
  re-applying the lab hook; the hooks are listed in the sync's PR.
- The CSV has two versions: Semper's 1 for a plain session, 2 for a lab test.
- Docs follow: the operating manual is Semper's, with the lab tests in its §12;
  the screenshots are the parent's except the lab ones
  ([CAPTURE_CHECKLIST](../images/CAPTURE_CHECKLIST.md)).

## Action items

- [ ] Retake `test-type.png`, `video-extract.png`, `share-sheet.png` and
      `settings-used.png` on this build.
- [ ] Check 2D DIC side by side with a Semper build on one phone
      ([WORKFLOWS](../app/WORKFLOWS.md) §3b).
