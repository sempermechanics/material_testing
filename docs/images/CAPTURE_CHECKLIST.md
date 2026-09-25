# Screenshot capture checklist

**2026-09-23 pass.** Setup:
- a debug build on a Pixel 10 emulator;
- **light theme**;
- `INDIC_DEV_AUTH_BYPASS` on (local-only sessions);
- SystemUI demo mode (09:30, full battery, no notifications).

Screens are cropped to **460 px wide, ≤ 1022 tall**, and shown at `width="260"`. The inputs are the ones the validation uses:
- **tensile:** the 40-frame steel set, 12.5 mm², ROI 1960 × 297 at (20, 69);
- **bending:** the synthetic beam video at 1 fps, log offset 2 s, 935 / 150 / 6.38 mm, taps 170 px, ROI 1240 × 157 at (20, 255).

The results match [app/REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md):
- tensile E ≈ 149.6 GPa;
- bending E from the graph ≈ 142.0 GPa, average 173.7 GPa.

## Current

| File | Shows | Used in |
|---|---|---|
| `home.png` | Four analyses (tensile and bending) with **upload pending** badges | Manual §3, README |
| `test-type.png` | **Which test?** with Tensile, Bending and 2D DIC (retaken 2026-09-25, API 36 emulator, same setup) | Manual §2, README |
| `new-analysis-source.png` | Images tab: the gallery with a badged video | Manual §5 |
| `media-picker-files-saf.png` | Files tab: the system browser in Downloads | Manual §5 |
| `video-extract.png` | Video sampling in **Key frames** mode: 29 key frames | Manual §4 |
| `step1-tensile.png` | Reference, 40 frames, `steel_loads.csv` (kN), 12.5 mm², axis X, speckle chip | Manual §2, lab workflow, README |
| `step1-bending.png` | Timed log, time-match chip, **Log started after the first frame** = 2, L / b / t, load point 170 px | Manual §2, lab workflow, README |
| `beam-taps.png` | The tap editor zoomed, both marks and the probe circle, 0.0375 mm/px | Manual §2, lab workflow, README |
| `frame-order-menu.png` | The sort menu over the 40-frame strip | Manual §5 |
| `roi-editor.png` | Manual entry, ROI 1960 × 298 at (20, 70) | Manual §7, README |
| `step2-parameters.png` | Single mode, ROI set, subset 19, speckle readout 4.8 px | Manual §5 |
| `step3-sweep.png` | Sweep step 3: the planned 3 × 3 lattice and the line cut, steel set | Manual §8 |
| `result-lattice.png` | The sweep result: 6 of 9 solved, the Exx line cut for each node | Manual §8 |
| `running.png` | Frame 10 of 40, with the converged and convergence tiles | Manual §5 |
| `results-tensile.png` | Results: stress–strain curve, E ≈ 149.6 GPa (frames 1–26), peak 435.50 MPa | Manual §2, lab workflow, README |
| `results-bending.png` | Results: load–deflection graph, E 142.0 GPa (slope), 173.7 GPa (average), 0.0375 mm/px | Manual §2, lab workflow, README |
| `result-viewer.png` | Exx, frame 29 / 40, a probe at the centre | Manual §9, README |
| `settings-used.png` | The ⓘ sheet: stats, histogram, settings with test type, area, load, stress | Manual §9 |
| `share-sheet.png` | Share, with **Lab report (PDF)** first | Manual §10, README |
| `lab-report-tensile.png` | All four PDF pages, side by side | Manual §2, lab workflow, README |
| `lab-report-bending.png` | All three PDF pages, side by side | Manual §2, lab workflow, README |
| `home-selection.png` | One row selected: rename and delete | Manual §11 |
| `delete-dialog.png` | The local-only delete confirmation | Manual §11 |
| `settings.png` | The Settings sections, collapsed | Manual §11 |

The report strips are rendered from the exported PDFs (PyMuPDF, 110 dpi, 900 px tall, 16 px apart).

**Removed as unreferenced:**
- `step1-frames.png` (replaced by `step1-tensile.png`);
- `speckle-warning.png` and `speckle-span-warning.png` (the chip shows in `step1-tensile.png`, and the manual describes the span chip in words);
- `media-picker-permission-empty.png`.

## Still needs a real backend and account

These need `INDIC_DEV_AUTH_BYPASS=false`, a live `INDIC_API_BASE_URL`, and an approved account with a cloud-backed analysis. Don't fake them.

- **Home:** mixed **Synced** / **Pending** / **Only in cloud** badges.
- **Delete dialog:** **Delete device** / **Delete cloud**, on a row with both copies.
- **Settings:** **Analyses data management** with Download / Restore / Delete, and the transfer banner mid-transfer.
- **The Settings Download flow** (the SAF picker, a row mid-download).
