# Real 3-point bend: reinforced concrete beam (published data)

**In semper Classroom:** + → *Try an example* (or Settings → Help → Example data) → this set. **Try it** opens a new analysis with the photos, the load log and the dimensions already in (you still tap the beam's edges); **Save to phone** copies this folder to `Download/semper examples/` so you can run it like your own photos, with the steps below.

Credit and licence: [CREDIT.txt](CREDIT.txt).

> **Expected to fail.** The app cannot analyse this set. It stops after
> frame 2 ("Stopped early"). The concrete surface has too little
> texture for the app to track it, even at the largest subset the app allows.
> Keep this set as an example of photos that don't work, not as a result to
> compare against. The details are [below](#what-happens-when-you-run-it).

Real photos of a large lab test. A 150 × 150 × 800 mm reinforced concrete beam
is loaded at mid-span by a hydraulic piston (MTS, 250 kN) at 2 mm/min. The
test pauses at 10, 20, …, 60 kN, and a fixed Canon 5D Mark II takes a photo
at each pause.

> A. Sjölander, V. Belloni, V. Peterson, J. Ledin, *Monitoring of structural
> performance of cracked reinforced concrete using DIC and CMfM*, Mendeley
> Data v3, doi:10.17632/z3yc9z84tk.3. Described in Data in Brief 51 (2023)
> 109703. Licence CC BY 4.0.

**What was changed from the original:**
- Beam 5, camera IB (fixed) only.
- Cropped to `(600, 480)–(5350, 3744)` and halved to 2375 × 1632 px, JPEG
  quality 95.
- `concrete_00` is t = 49 s, the start of the test (0.10 kN), used as the
  reference.
- `concrete_01` to `concrete_07` are the pauses at t = 228, 358, 464, 553,
  636, 729 and 840 s.
- `concrete_loads.csv` gives the force at each pause, in kN, from the
  authors' time-matched log (`Beam5Crack.txt`, 25 Hz).
- `frame_index.csv` also lists the piston displacement at each photo.
- The two reference photos with a ruler held over the beam are left out: the
  ruler covers the face, so DIC cannot use them.

**How this set differs from how you will film:**
- **It does not follow your framing.** The camera sees about 300 mm of the
  800 mm beam, next to the piston, not the full span. The beam fills about
  70% of the height, not the top half.
- **The piston is the load point.** It is near the right-hand side of the
  frame, at x ≈ 1800 px.
- **The beam was already cracked** from an earlier test, and it cracks
  further here. That is realistic, but it means the load–deflection curve is
  not linear and **E is not meaningful**.
- **The support span is not published** (the paper gives only the 800 mm
  length). The app needs L to compute E; enter **700 mm** as a placeholder.
  E scales with L³, so the E values are only indicative.

## What happens when you run it

Tried on a Pixel 6 on 2026-09-26 with these steps:

1. New analysis → Bending.
2. Reference: `concrete_00.jpg`. Deformed frames: `concrete_01` to
   `concrete_07`.
3. Load log: `concrete_loads.csv`, units kN. It pairs in order: 7 rows, 7
   frames.
4. L **700 mm** (placeholder), b **150 mm**, t **150 mm**.
5. **Mark load point:** under the piston at x ≈ 1800 px. Top edge y ≈ 40
   px, bottom edge y ≈ 1180 px. The readout said 1141 px, 0.1314 mm/px.
6. ROI (Manual): x 60, y 70, 2250 × 1080, the concrete face only. The
   speckle check suggested subset 109 and warned "Speckle contrast is low".
   Step 5 px, strain window 9 points. Compute.

**Result:** the run stopped after frame 2. Only 41% of the points were tracked
in frame 1, and the app stops when two frames in a row track fewer than
half. That took 102 s. The two frames it kept give a line through two points,
which says nothing.

## Why it fails

The app accepts a point only when the patch around it matches the next photo
closely: a match score (ZNSSD) of 0.15 or better, which is a correlation of
about 0.925. This concrete is mostly smooth grey. Its pores are few and far
apart, so the camera's own noise and the JPEG compression are a large part
of what each patch contains. Even two photos taken 2 minutes apart, with the
same lighting and almost no movement (about 7 px), only match at a
correlation of about 0.88. That is below the bar.

A larger subset averages out the noise. Measured on these photos, the share of
points that pass (without sub-pixel refinement, which adds a little):

| Subset | 109 px | 151 px | 201 px | 251 px |
|---|---:|---:|---:|---:|
| Points that pass | 30% | 37% | 49% | 58% |

The app needs at least half, and it allows subsets up to **121 px**. No
setting in the app gets this set through.

**One more trap:** between the reference photo (0.1 kN) and frame 1 (9.8 kN)
the piston moved **5.9 mm**. That is the pad and the supports settling, not the
beam bending. It is more than the whole rest of the test (4.2 mm from 10 to
60 kN). Even a set that tracked would need its reference taken after this
settling, at frame 1.

## What this set is still good for

- **Seeing what bad texture looks like.** Compare `concrete_00.jpg` with
  the PMMA or steel photos: dense, sharp, high-contrast dots against wide
  smooth patches. If your own specimen looks like this concrete, paint a
  speckle pattern on it before you test.
- **Seeing the stop message.** "Stopped early" is what the app shows when the
  photos can't be tracked, rather than giving a wrong answer. The session list
  then shows "2 of 7 frames" and "Convergence collapsed before this
  combination".

For a bending result to check your own work against, use
`2_bending_pmma_real`.
