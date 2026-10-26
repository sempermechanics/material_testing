# Sub-pixel shift (DICe test images)

A speckle image and a copy of it shifted by 0.4 px, from the test data of the
Digital Image Correlation Engine (DICe), Sandia National Laboratories. The
images are 512 × 512 px, 8-bit. `def.tif` holds a second page; the app reads
the first, which is the shifted one.

**In Semper Classroom:** + → *Try an example* (or Settings → Help → Example data) → this set. **Try it** opens a new 2D DIC analysis with both images in; **Save to phone** copies this folder to `Download/Semper examples/`.

Credit: [CREDIT.txt](CREDIT.txt). Licence: BSD-3-Clause, full text in [LICENSE.DICe](LICENSE.DICe), which has to travel with these files.

## Steps

1. New analysis → 2D DIC.
2. Reference: `ref.tif`. Deformed frame: `def.tif`.
3. ROI: x 64–448, y 64–448 px. Subset 27 px.
4. Compute, then look at U and V.

## Expected

Every point moved by the same amount: **u = v = 0.4 px**. DICe's own test
accepts |u − 0.4| ≤ 0.1 px. The strain should be zero everywhere, so whatever
strain the app shows here is its noise on a near-perfect image.
