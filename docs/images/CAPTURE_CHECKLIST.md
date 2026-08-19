# Screenshot recapture checklist

Every PNG in this folder is a real device capture (460×1022) from **2026-08-07**
— before the media-picker, viewer, lattice and Settings redesign that landed
2026-08-08 → 08-18 (PRs #40–#83, audited in
[docs/app/WORKFLOWS.md](../app/WORKFLOWS.md)). They now show controls that no
longer exist. This file is the retake list: exact screen, exact state, so the
next pass is mechanical. Device: any phone-class emulator or device at the
project's debug build; light theme unless noted. Keep the 460×1022 crop so the
manual's `width="300"` doesn't distort.

| File | Used in §. | Current screen to capture | State to be in |
|---|---|---|---|
| `home.png` | §1 | Home | A few sessions with mixed sync badges (Synced / Pending / Only in cloud) so the badge language is visible in one shot |
| `new-analysis-source.png` | §4 | The **New analysis** sheet (`MediaPickerSheet`) | Images tab open, gallery grid populated, at least one video tile showing its badge |
| `step1-frames.png` | §4 | Wizard step 1 | Reference + 3 deformed frames loaded, order badges visible |
| `frame-order-menu.png` | §4 | Wizard step 1 | The sort menu open over the loaded strip |
| `step2-parameters.png` | §4 | Wizard step 2 | Advanced parameters expanded, **Compute** button visible (not "Run analysis") |
| `running.png` | §4 | Progress overlay | Mid-solve, so **# converged** / **convergence** tiles are showing (not old tile labels) |
| `roi-editor.png` | §6 | ROI editor | Draw + Crop mode, one rectangle drawn, HUD showing `W × H at (x, y)` |
| `step3-sweep.png` | §7 | Wizard step 3 | Ranges populated, planned lattice visible, **Compute** button |
| `result-lattice.png` | §7 | `VsgLatticeActivity` | Summary line above the lattice, some hollow (skipped) nodes, plot open with the **All / Node** pill visible (not "Isolate/Highlight") |
| `result-viewer.png` | §8 | `ResultViewerActivity` | A strain field (Exx) on screen with chrome visible: back · title · ⓘ · Home · share along the top, field pills + scale + scrub along the bottom |
| `settings-used.png` | §8 | The ⓘ details sheet | On a sweep result, so the line-cut section shows; include a run that stopped early if possible, to show the "Stopped early / Frames solved" rows |
| `home-selection.png` | §10 | Home, long-press selection | Two or more rows selected so the bar reads a count |
| `delete-dialog.png` | §10 | The delete choice dialog | On a row with both local and cloud copies, so it reads **Delete device** / **Delete cloud** (not "everywhere" / "on this device only") |
| `settings.png` | §10 | Settings, scrolled to show sections | **Analyses data management** expanded with a row showing all three actions — Download / Restore / Delete — and, if a transfer is running, the top transfer banner (§4.0 of WORKFLOWS.md) |

## Retired — do not recapture

- `viewer-tools.png` — the Inspect / X,Y / Max-Min tool row it showed was
  removed (tap-to-probe replaced it). Not referenced anywhere in
  `OPERATING_MANUAL.md`; safe to delete once someone confirms nothing else
  links to it.

## New screens with no screenshot yet

Not previously documented, so there's no stale asset to replace — net-new if a
capture pass happens:

- The **New analysis** sheet's Files tab and its permission empty state.
- The Settings **Download** flow: the SAF save-location picker, and a row mid-download.
- The non-modal **transfer banner** itself (Settings and the viewer), ideally
  mid-transfer with the ‹ › paging visible on two concurrent jobs.
- The viewer's **Send to** sheet for a slow export (opens before generation —
  distinct from the single-photo path, which still builds first).
