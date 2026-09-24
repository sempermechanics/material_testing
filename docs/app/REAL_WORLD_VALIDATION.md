# Real-world validation: published test data run through the app

**Date:** 2026-09-23  
**App:** `feat/bending-deflection`, debug build on the emulator (x86_64)  
**Scope:** tensile Young's modulus E and peak stress on published data
(case 1); bending deflection, E, displacement and strain against a published
3-point bend's own DIC (case 2, 2026-09-24); bending end to end on a synthetic
video ([below](#bending-end-to-end-on-a-synthetic-video)).

Unit tests prove the math on numbers typed in by hand. This page runs the whole
app — images in, the DIC solve, the load CSV, the stress–strain curve and E out —
on a test someone else ran and published, and compares the result with that
dataset's own measurement. Each case records the data, the exact app settings,
the numbers and what they showed, so the check can be run again after any change
to the solve, the strain or the E fit.

For how the student uses these outputs, see
[STUDENT_LAB_WORKFLOW.md](STUDENT_LAB_WORKFLOW.md). For what a phone on a tripod
measures when nothing moves, see
[NOISE_FLOOR_STRAIN_ACCURACY.md](NOISE_FLOOR_STRAIN_ACCURACY.md).

---

## Case 1 — steel sheet in tension (Zenodo 18311953)

### The data

Sanjeev Koirala, *Tensile test of steel specimen using digital image
correlation*, Zenodo, 2026, [doi:10.5281/zenodo.18311953](https://doi.org/10.5281/zenodo.18311953).
Licence CC BY 4.0. Nothing from the dataset is committed here; the test fixture
in `RealSteelModulusTest` holds the app's own strain and stress per frame, not
the dataset's files.

| | |
|---|---|
| Material | 1.4016 (X6Cr17), ferritic stainless steel sheet |
| Specimen | 12.5 mm × 1 mm, so A = 12.5 mm² |
| Rig | Stereo pair (two cameras), 751 load steps, force per step |
| Reference strain | Two gauge points 60 mm apart, tracked in 3D by the authors' software |
| Peak | Step 509, 5443.8 N = 435.5 MPa |

The dataset is a stereo (3D) measurement. The app does 2D DIC from one camera,
so **only camera 1** was used, as a student's single phone would be.

### What was run

- **Frames:** step 0 as the reference, then steps 3–30 (every step, 6.5–263 MPa,
  the elastic range) and 35, 40, 45, 55, 80, 130, 205, 305, 405, 509, 605, 705
  (yield to past the peak): 40 deformed frames. Each frame was cropped to the
  specimen band, `(0, 780, 2000, 1215)`, and saved as PNG.
- **Loads:** the dataset's force at those steps, as a kN load CSV with one row
  per deformed frame.
- **App settings:** test type Tensile; area 12.5 mm²; load axis x; ROI
  1960 × 298 px at (20, 69); subset 19 px (the speckle check's suggestion);
  step 5 px; strain window 15 px (a VSG; the window is now entered in points,
  and 15 px at step 5 is none of them).
- **Result of the solve:** 40 of 40 frames on the curve, 96.1% of points
  converged, about 25 s on the emulator.

### Strain: the app against the dataset's gauge points

App strain is the mean Exx over the analysed region (what the curve plots).
Dataset strain is the change in distance between the two gauge points over
their starting 60 mm.

| Frame | Step | Stress (MPa) | App strain (mε) | Gauge points (mε) |
|---:|---:|---:|---:|---:|
| 1 | 3 | 6.5 | 0.062 | 0.064 |
| 13 | 15 | 80.0 | 0.549 | 0.532 |
| 20 | 22 | 160.4 | 1.006 | 0.935 |
| 26 | 28 | 238.8 | 1.661 | 1.575 |
| 33 | 80 | 345.7 | 21.07 | 20.98 |
| 38 | 509 | 435.5 (peak) | 213.9 | 200.5 |
| 40 | 705 | 422.1 | 336.1 | 301.7 |

Over frames 13–28 the app reads **7.8% higher** than the gauge points. Over
the elastic frames 1–28 (up to 1.8 mε) the strain RMSE is **67 µε**, bias
+50 µε. Removing the constant 6.8% scale leaves 30 µε. Over frames 29–40
(3–302 mε) it is 12,400 µε, about 4% of the range. The two
agree to within the scatter of the gauge points themselves at the smallest loads
(the gauge-point fits below have R² as low as 0.94).

A likely cause is the camera, not the solve: camera 1 of a stereo pair looks at
the specimen at an angle, and one camera cannot tell stretching from the
specimen moving towards it. A phone facing the specimen square-on should do
better. This has not been checked here.

After necking (frames 36–40) the app reads 4–11% higher. The mean over
the analysed region includes the neck, while the gauge points span 60 mm.

### Young's modulus E

| Source | Frames or window | E (GPa) | R² |
|---|---|---:|---:|
| **App** (`ElasticModulus`) | frames 1–26, 6.5–238.8 MPa | **149.6** | 0.9955 |
| Gauge points, same frames | frames 1–26 | 157.5 | 0.9915 |
| Gauge points | 20–100 MPa | 157.7 | 0.939 |
| Gauge points | 30–150 MPa | 165.6 | 0.974 |
| Gauge points | 50–200 MPa | 163.6 | 0.981 |
| App strain, same windows | 20–100 / 30–150 / 50–200 MPa | 147.1 / 156.4 / 155.2 | 0.976 / 0.991 / 0.995 |
| Handbook, ferritic stainless steel | — | about 220 | — |

- The app's E is **5% below** the dataset's own gauge-point E over the same
  frames, which follows from the 7.8% strain difference above.
- Both are far below the handbook value. The dataset's own stereo measurement
  gives about 160 GPa, so this gap is not the phone's doing. Optical E on thin
  sheet is often low; the app quotes no textbook E and labels its own as
  approximate ([STUDENT_LAB_WORKFLOW.md](STUDENT_LAB_WORKFLOW.md)).
- Peak stress reads 435.50 MPa at frame 38, which matches the dataset exactly.
  This only shows that the load CSV is read correctly: the loads came from the
  dataset's own force column. It does not test the camera.

### What this run found and changed

The first run showed **"Young's modulus E: not found"**. `ElasticModulus` used
to grow the run one frame at a time and give up at the first run that failed
R² ≥ 0.995. Frames 1–3 span only 6.5–16.8 MPa, where camera noise is as large as
the signal, so their line has R² 0.913 and the fit stopped there. Longer runs
pass: frames 1–20 reach 0.9956, and frames 1–26 reach 0.9955.

The rule now tries every leading run and keeps the longest one that is
straight enough. On the Experiment 2 lab table it still uses all 12 rows
(194.03 GPa), and it still stops at the knee (`ElasticModulusTest`). This curve
is pinned as a regression test in
`app/src/test/java/com/indicvision/semper/report/RealSteelModulusTest.kt`: E
149.58 GPa over frames 1–26, the first three frames alone at R² 0.913, and
within 10% of the gauge-point E.

The viewer's Results section plots the whole test, out to 336 mε, so the
elastic frames sit in a vertical line at the left edge. The lab-report PDF
draws a separate elastic-region graph. The viewer has an **Elastic region**
toggle beside the Results title (`report/ElasticRegion`). It zooms to the
fitted frames plus half their strain span, which is frames 1–28 up to 1.94 mε
here, with the fit line across it. It is pinned in `RealSteelModulusTest`.

### Reproduce

The image archive is 2.3 GB (`Images_series.zip`). Put it, together with
`X,Y coordinates and displacements.txt`, in one folder, then:

```bash
python scripts/real_data_steel_tensile.py prep --data <zenodo folder> --out <frames folder>
```

This writes `steel_00.png` (the reference) through `steel_40.png`, plus
`steel_loads.csv`. It needs numpy and Pillow.

1. Copy the frames and the CSV to the device (`adb push … /sdcard/Download/`).
2. New analysis → reference `steel_00.png`, deformed frames `steel_01`–`steel_40`
   (the file picker's Select all).
3. Choose Tensile, load `steel_loads.csv` (kN), area 12.5 mm², load axis x.
4. Draw the ROI over the specimen band, apply the suggested subset, run.
5. Pull the session's results:

```bash
adb shell run-as com.indicvision.semper ls files/sessions
```

Copy each `files/sessions/<session>/frame_NNNN.dat` out with
`adb exec-out run-as com.indicvision.semper cat …`, then:

```bash
python scripts/real_data_steel_tensile.py compare --data <zenodo folder> --dat <dat folder>
```

It prints the strain table, the app-rule E with the gauge-point E over the
same frames, and the fixed-window fits above. Expect the values in the tables
above to within a few tenths of a GPa. A change to the solve or the strain
window that moves them further than that deserves a look before it merges.

---

## Case 2 — PMMA beam in 3-point bending (Zenodo 1172068)

### The data

R. Delorme, I. Tabiai, P. Diehl, L. Laberge Lebel, M. Lévesque, *PMMA 3 point
bending test until failure loaded in displacement*, Zenodo, 2018,
[doi:10.5281/zenodo.1172068](https://doi.org/10.5281/zenodo.1172068). Licence
CC BY 4.0. Nothing from the dataset is committed here; `RealPmmaBendingTest`
holds the app's own deflection per frame, not the dataset's files.

| | |
|---|---|
| Specimen | Speckled PMMA beam: span L 75 mm, depth in view t 31 mm, thickness b 12 mm |
| Rig | Loading nose at mid-span, 2 mm/min to failure; stereo pair, one image per second, force per image |
| Reference | The authors' VIC-3D full field for every image: camera-0 pixel positions and displacements, world displacements in mm, and strain |
| Peak | Image 97, 9337 N; the beam breaks at image 98 |

Only **camera 0** was used, as with the steel. Its pixel positions and
displacements are the image plane the app measures in, so displacement is
compared in pixels without any calibration.

### What was run

- **Frames:** image 32 (the last before the nose touches) as the reference,
  then every second image from 33 to 97: 33 deformed frames. Each was cropped
  to `(40, 820, 2420, 2048)`, which puts the beam across the full width in
  the top half of the frame, as a student would film it.
- **Loads:** the dataset's force at those images, in N.
- **App settings:** Bending; L 75, b 12, t 31 mm; thickness taps under the
  nose at x 1170, y 38.8 and 609.6 (570.8 px, 0.0543 mm/px; the edges are at
  39 and 611 by the intensity profile); ROI 2259 × 509 px at (59, 69); subset
  27 px (the speckle check's suggestion); step 5 px; strain window 45 px
  (bending's default then) and, in a second run, 15 px. Both are VSGs: the
  window has since been entered in points, and bending's default is now
  9 points, a 41 px VSG at step 5.
- **Solve:** about 2 min 17 s on the emulator for 33 frames.

The authors' field is referenced to image 0 and the app's to image 32, so
both are compared as increments from image 32, at the authors' 20 px grid
points carried into image 32 (about 2,800 per frame).

### Deflection at the load point and E

The probe is what the app reads δ from: the mean displacement of accepted
points within t/2 of the taps' midpoint, along the top-to-bottom direction.
The authors' δ is their world vertical displacement in mm, averaged over the
same circle.

| Frame | Image | Load (N) | App δ (mm) | Authors' δ (mm) |
|---:|---:|---:|---:|---:|
| 2 | 35 | 239 | 0.0240 | 0.0259 |
| 5 | 41 | 1004 | 0.1335 | 0.1345 |
| 10 | 51 | 2455 | 0.3337 | 0.3367 |
| 17 | 65 | 4657 | 0.6573 | 0.6611 |
| 25 | 81 | 7200 | 1.0232 | 1.0333 |
| 33 | 97 | 9337 (peak) | 1.3842 | 1.3955 |

Over all 33 frames: **RMSE 0.0074 mm**, 0.5% of the 1.396 mm peak; bias
−0.006 mm; largest error 0.019 mm.

| | App | Authors' DIC, same probe |
|---|---:|---:|
| Load–deflection slope | 6777.7 N/mm, R² 0.9993 | 6716.5 N/mm |
| **E from the graph** | **2.000 GPa** | 1.982 GPa |
| Average E (31 load steps) | 2.104 GPa | 2.084 GPa |

Both E values are within 1%. The app's Results showed "E from the graph ≈
2.0 GPa (slope 6777.73 N/mm, R² 0.9993)" and "Average E ≈ 2.1 GPa (31 load
steps)". The slope is pinned in `RealPmmaBendingTest`.

The handbook E of PMMA is 2.4–3.3 GPa. This beam is deep (span only 2.4
times the depth), so shear adds to the deflection and W L³ / (48 δ I), which
leaves shear out, reads low. That is the formula's limit, not the camera's:
the authors' own deflection gives the same low value.

**The taps matter most.** In a first attempt the bottom tap landed 49 px
short (523 px instead of 572; see below). That alone put δ 10% high: RMSE
0.076 mm and E from the graph 1.81 GPa. A pixel of tap error changes E by
about 100 / N %, where N is the thickness in pixels.

### Displacement and strain, point by point

Displacement agrees to **0.013 px RMS** in both u and v, about 0.7 µm here,
and to no worse than 0.015 px on any frame up to the peak.

**20 of 91,179 points** (0.02%) are wrong matches, more than 0.5 px off the
authors', in frames 25 and 27–29. The 45 px run has 12 of 84,249, because it
trims the ROI's edges. They sit right under the nose and on the
bottom fibre below it, where the nose indents the surface and the beam later
breaks. Their correlation is good (ZNSSD 0.007–0.1), so they pass the
acceptance test, and the strain window takes them in. Around them strain
reads up to about 2,400,000 µε, in frames 24, 25, 27–29 and 32. The engine's
strain fit does not reject an outlying displacement; that is a `native/`
change and is not made here. The RMSEs below leave those six frames out.

| Strain window | exx RMSE | eyy RMSE | exy RMSE | Points with a strain value |
|---:|---:|---:|---:|---:|
| 15 px | 1004 µε | 960 µε | 814 µε | 97.6% |
| **45 px** (bending's default) | **398 µε** | **374 µε** | **385 µε** | 92.8% |

For scale, the authors' exx at the peak is about 5,700 µε RMS over the beam.
Most of the error at 15 px is noise: at 35 N, where there is almost no strain,
it is already 810 µε. The authors' own strain has about 90 µε of noise at the
same load, so theirs is the smoother reference.

The script also recomputes strain from the app's own displacements, by a
plane fit over the same circular footprint as the engine. At 15 and 45 px
it lands within 35 µε of the engine's own strain, and it gives the trend
beyond:

| Window | 15 | 25 | 45 | 65 | 95 px |
|---|---:|---:|---:|---:|---:|
| exx RMSE (µε) | 1022 | 662 | 406 | 289 | 197 |

A wider window costs two things:

- **The edges.** A point keeps its strain only if at least 90% of its circle
  holds data. At 45 px that trims a band about 20 px wide along every ROI
  edge. On a bending ROI the top and bottom edges are the outer fibres, where
  strain is largest. The trimmed points also count against the headline, so
  this run shows "92.8% converged", not 97.6%. The deflection is unaffected.
- **Detail.** A 45 px window can't resolve anything smaller, such as the
  contact zone under the nose.

For bending that trade is worth it, because δ and E come from displacement and
strain only draws the maps. That is why bending starts at a wide window, now
9 points, a 41 px VSG at step 5 (`TestType.defaultStrainWindow`). On a thin beam, a 45 px band can be a large
share of the depth: lower the window, or frame closer.

**Shear sign.** The authors' exy is engineering shear with y pointing up; the
app's is tensor shear with y pointing down. Theirs is −2 × the app's, which
their own pixel displacements confirm (slope −0.513). The tables compare
−exy / 2.

### Reproduce

The archive is 749 MB (`3pointPMMA.zip`). Put it in a folder, then:

```bash
python scripts/real_data_pmma_bending.py prep --data <zenodo folder> --out <frames folder>
```

This writes `pmma_00.png` (the reference) through `pmma_33.png`,
`pmma_loads.csv` and `frame_index.csv`. It needs numpy and Pillow.

1. Copy the frames and the CSV to the device.
2. New analysis → Bending → reference `pmma_00.png`, deformed frames
   `pmma_01`–`pmma_33`, load log `pmma_loads.csv` (N).
3. L 75, b 12, t 31 mm. **Mark**: zoom in, tap the top and bottom edges under
   the nose at x ≈ 1170 (y ≈ 39 and 611). The readout should say about 572 px.
4. ROI (Manual) x 60, y 70, 2260 × 510. Accept the suggested subset, and keep
   step 5 and bending's window of 9 points (VSG 41 px). Compute. The tables
   above were made at a 45 px VSG, which points at step 5 can't give, so
   strain reads a little noisier at 41 px; δ and E don't depend on it.
5. Pull `files/sessions/<session>/frame_NNNN.dat` as for case 1. Read the
   taps from the session's `loadPoint` in `files/sessions/index.json`, then:

```bash
python scripts/real_data_pmma_bending.py compare --data <zenodo folder> --dat <dat folder> --taps 1170.17,38.83,609.59 --windows 15,45
```

It prints δ per frame against the authors', the deflection RMSE, both E
values from both sides, the wrong-match count, the spike frames, and the
displacement and strain RMSEs. `--windows` adds the plane-fit recompute.

---

## Lab tables (math only)

The two handwritten lab reports the app's outputs follow are typed-in tables,
not images. They verify the formulas and the report layout, not DIC:

| Report | Test | Checks |
|---|---|---|
| Experiment 2, tensile | `ElasticModulusTest`, `LabReportTest` | E 194.03 GPa over 12 rows, R² 0.99887; row-1 stress 72.06 MPa |
| Experiment 5, bending | `BeamDeflectionTest` | σb₁ 9.645 MPa; E₁ 193.21 GPa; mean E 173.58 GPa; slope 27.064 N/mm, R² 0.99814, E from the graph 141.98 GPa |

## Bending: end to end on a synthetic video

Before case 2, no published bending test had been run through the app, so
the whole bending path was first run on a **synthetic** video whose truth is
the Experiment 5 table. Every number the app prints could then be checked
against the handwritten report.

**The video.** 1280 × 720, 10 fps, 29 s, keyframe every second. A speckled
beam side face 170 px thick (t = 6.38 mm, so 0.0375 mm/px) moves down by the
lab's dial readings (1.14 … 3.88 mm) as the six hanger loads (4.28 …
11.92 kg) go on, one every 4 s from 3.25 s. The load log is a timed CSV
(`time_s, load_N`, 54 rows) that starts **2 s after** the recording, as a
machine started by hand would.

**The run.** Bending → video → 1 fps (28 frames) → load log → *Log started
after the first frame* = 2 → L 935, b 150, t 6.38 → Mark: double-tap to zoom,
tap the top and bottom edges → ROI on the beam (the speckle check then
suggests subset 25) → Compute → Share → Lab report (PDF).

| | App | Report / truth |
|---|---:|---:|
| Thickness taps | 169 px, 0.0376 mm/px | 170 px, 0.0375 |
| δ, step 1 | 1.144 mm | 1.14 mm |
| σb, step 1 | 9.645 MPa | 9.645 MPa |
| E, step 1 | 192.60 GPa | 193.21 GPa |
| Rows in the table | 6 | 6 |
| Average E | 173.06 GPa | 173.58 GPa |
| Slope, R² | 26.98 N/mm, 0.9981 | 27.06 N/mm, 0.9981 |
| E from the graph | 141.5 GPa | 141.98 GPa |

The remaining differences come from the 169-px tap (0.6%).

**What the run found and changed.**

- The tap editor lost its zoom after the first tap: the instruction below the
  photo changed length, the photo resized, and a resize re-fitted it. The
  second tap then landed on the un-zoomed image (223 px instead of 170).
  `TouchImageView` now keeps a zoom through a resize. At rest the photo still
  re-fitted when the instruction changed height, and longer wording later
  broke the fixed three lines (case 2's first attempt). The instruction's box
  is now as tall as its longest step, at any width or font size.
- The slope included the three unloaded frames at (0, 0) and read 151.8 GPa.
  The report's own readings do not pass through the origin (the dial is
  zeroed with the hanger seated), so the slope now uses loaded frames only.
- A video gives four frames per hanger weight, so the table had 28 rows, the
  first three at zero load, and row 1's worked calculation read 0 N. Frames
  held at one load are now one step, averaged; the table, the calculation,
  the average E and the slope use those steps.
- The speckle check measured 70–78 px on the beam, because its sample patches
  could reach outside the ROI into the dark background. They are now kept
  inside the ROI (`SubsetRecommenderRoiTest`), and it reads 6 px.
- The log started 2 s late and nothing in the wizard could say so. The *Log
  started after the first frame* field now shifts the time match.

This proves the plumbing, the formulas and the report against known truth.
It does not show what a camera sees on a real beam: lighting, focus,
out-of-plane motion and support settling are all absent. Case 2 covers the
real images. A future case should still record:

- Thickness taps and the resulting mm/px, against a scale bar in the frame if
  there is one.
- DIC deflection at the load point against the dial gauge at every step.
- Both E values (the per-step mean and the slope of the graph) against the
  dataset's own.

## Adding a case

- Use openly licensed data and cite it. Keep the images out of the repo.
- Write down the frames, the crop, and every wizard setting, so the run can be
  repeated.
- Compare with the dataset's own measurement, not only a handbook value.
- Pin the app's curve in a unit test once the numbers are understood, and link
  the test from here.
