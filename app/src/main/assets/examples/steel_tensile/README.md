# Real tensile test: steel 1.4016 (published data)

**In semper Classroom:** + → *Try an example* (or Settings → Help → Example data) → this set. **Try it** opens a new analysis with the photos, the load log and the dimensions already in; **Save to phone** copies this folder to `Download/semper examples/` so you can run it like your own photos, with the steps below.

Credit and licence: [CREDIT.txt](CREDIT.txt). Ground truth: `truth_gauge_strain.csv`, the authors' two gauge points' strain and the force at every deformed frame.

Real photos of a real test, not simulated. This is camera 1 of a published
stereo DIC test on a 12.5 mm × 1 mm stainless-steel strip:

> Sanjeev Koirala, *Tensile test of steel specimen using digital image
> correlation*, Zenodo, 2026. doi:10.5281/zenodo.18311953. Licence CC BY 4.0.

**What was changed from the original:** 41 of the 751 load steps were kept.
`steel_00` is step 0. `steel_01` to `steel_28` are steps 3–30, which cover
the elastic range. `steel_29` to `steel_40` are steps 35–705, from yield to
past the peak. Each frame was cropped to the specimen band (2000 × 435 px) and
saved as PNG. `steel_loads.csv` holds the dataset's measured force at those
steps, in kN, one row per deformed frame.

## Steps

1. New analysis → Tensile.
2. Reference: `steel_00.png`. Deformed frames: `steel_01` to `steel_40`.
3. Load log: `steel_loads.csv`, units kN. It pairs in order: 40 rows, 40
   frames.
4. Area **12.5 mm²**. Load axis **x**.
5. ROI: the whole specimen band. Accept the suggested subset (19 px). Step
   5 px, strain window 5 points (the default).
6. Compute (about 25 s on an emulator). Then open ⓘ → Results and Share → Lab
   report (PDF).

## Expected

| | App (engine 0.6.1) | The dataset's own measurement |
|---|---:|---:|
| E, frames 1–26 (6.5–238.8 MPa) | **148.9 GPa**, R² 0.9952 | 157.5 GPa (two gauge points, 60 mm apart) |
| Peak stress | 435.5 MPa, frame 38 (off the curve: no strain there) | 435.5 MPa, step 509 |
| Frames on the curve | 35 of 40 | |

Both E values sit well below the handbook value for this steel (about
220 GPa), including the authors' own 3D measurement. That is typical of camera
and gauge-point measurements at such small elastic strains (under 1.7 mε).

The app reads about 5% below the gauge points. The likely cause is that
camera 1 of a stereo pair views the strip at an angle, and one camera cannot
tell stretching from the strip moving towards it. A phone facing the specimen
square-on should do better.
