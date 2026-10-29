# Carbon-fibre plate with a hole (DICe test images)

Two images of an open-hole tension specimen of carbon-fibre composite, from the
test data of the Digital Image Correlation Engine (DICe), Sandia National
Laboratories. `oht_cfrp_ref.tiff` is the first load step and `oht_cfrp_def.tiff` the next one.
The images are 400 × 1040 px, 8-bit.

**In semper Classroom:** + → *Try an example* (or Settings → Help → Example data) → this set. **Try it** opens a new 2D DIC analysis with both images in; **Save to phone** copies this folder to `Download/semper examples/`.

Credit: [CREDIT.txt](CREDIT.txt). Licence: BSD-3-Clause, full text in [LICENSE.DICe](LICENSE.DICe), which has to travel with these files.

## Steps

1. New analysis → 2D DIC.
2. Reference: `oht_cfrp_ref.tiff`. Deformed frame: `oht_cfrp_def.tiff`.
3. ROI: the plate, about x 40–360, y 40–990 px. Subset 27 px, step 35 px
   matches DICe's own run.
4. Compute, then look at V and Eyy.

## Expected

DICe's own solution for this pair is in `DICe_solution_01.txt` (x, y, u, v,
strain, at 230 points on a 35 px grid). Over those points:

| | DICe |
|---|---:|
| Median v | −0.63 px (from −0.92 to −0.39) |
| Median u | −0.10 px |
| Median eyy | +0.48 × 10⁻³ |

The plate is pulled along y, so v changes along the plate and is largest near
the grips; the hole concentrates strain beside it.
