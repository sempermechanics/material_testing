# Real 3-point bend: PMMA beam (published data)

**In Semper Classroom:** + → *Try an example* (or Settings → Help → Example data) → this set. **Try it** opens a new analysis with the photos, the load log and the dimensions already in (you still tap the beam's edges); **Save to phone** copies this folder to `Download/Semper examples/` so you can run it like your own photos, with the steps below.

Credit and licence: [CREDIT.txt](CREDIT.txt). Ground truth: `truth_deflection.csv`, the authors' deflection at the load point for every deformed frame (their 3D DIC, averaged over the circle the app probes).

Real photos of a real test. A speckled PMMA beam on two support pins is
loaded at mid-span by a nose, pushed at 2 mm/min until it breaks. The camera
faces the beam's side. The frames are cropped as you would film it: the beam
spans the full width and fills the top half, with the bottom half left clear
for it to bend into.

> R. Delorme, I. Tabiai, P. Diehl, L. Laberge Lebel, M. Lévesque, *PMMA 3 point
> bending test until failure loaded in displacement*, Zenodo, 2018.
> doi:10.5281/zenodo.1172068. Licence CC BY 4.0.

**What was changed from the original:**
- Camera 0 of the stereo pair only, since the app uses one camera.
- Cropped to `(40, 820)–(2420, 2048)`, giving 2380 × 1228 px, saved as PNG.
- `pmma_00` is image 32, the last one before the nose touches (the
  reference).
- `pmma_01` to `pmma_33` are every second image from 33 to 97. Image 97 is
  the peak, 9.34 kN; the beam breaks at image 98.
- `pmma_loads.csv` holds the machine's load-cell force at each photo, in N.
  The force was recorded at the moment each photo was taken.
- `frame_index.csv` maps every file back to its original image, time and
  force.

## Specimen (from the authors' PDF)

| Span L | Length | Depth in view (t) | Thickness through the view (b) |
|---:|---:|---:|---:|
| 75 mm | 127 mm | 31 mm | 12 mm |

## Steps

1. New analysis → Bending.
2. Reference: `pmma_00.png`. Deformed frames: `pmma_01` to `pmma_33`.
3. Load log: `pmma_loads.csv`, units N. It pairs in order: 33 rows, 33 frames.
4. L **75 mm**, b **12 mm**, t **31 mm**.
5. **Mark load point:** under the nose, at x ≈ 1170 px. Tap the top edge
   (y ≈ 39 px) and the bottom edge (y ≈ 611 px). The readout should say about
   572 px, 0.054 mm/px. Zoom in for the taps: every 6 px of error in the
   thickness moves E by about 1%.
6. ROI: the speckled beam only. Keep it off the pins, the ruler and the
   nose. Accept the suggested subset. Compute.
7. ⓘ → Results; Share → Lab report (PDF).

## Expected

These numbers come from the authors' own DIC (3D, full-field): their vertical
displacement in mm, averaged over the same circle the app probes (the load
point, mid-depth, radius t/2).

| | Expected |
|---|---:|
| Deflection at 9.34 kN (last frame) | ≈ 1.39 mm |
| Load–deflection slope | ≈ 6.72 kN/mm |
| **E from the graph** | **≈ 1.98 GPa** (2.06 GPa using loads below 4.7 kN only) |
| Average E over the steps | ≈ 2.09 GPa |

Run through Try it on engine 0.6.1 (subset 27, step 5, strain window 9
points, taps at 39 and 611 px), the app gave E from the graph 2.00 GPa and an
average of 2.10 GPa (2026-10-27). An earlier run on engine 0.2.x gave δ 1.38 mm
at the peak, within 0.008 mm RMS of the authors' deflection over all 33 frames.

For comparison, from the same data:
- the authors' bottom point P1 gives 2.04 GPa, and their top point P0 (under
  the nose) gives 1.74 GPa;
- the machine's crosshead gives 1.34 GPa, because it also includes contact
  and machine compliance.

The handbook E for PMMA is 2.4–3.3 GPa. This beam is deep: the span is only
2.4 times the depth, so shear adds to the deflection, and WL³/48δI, which
ignores shear, reads E low. Expect the app within a few percent of 2.0 GPa.

Near the peak the curve bends over: PMMA softens before it breaks.
