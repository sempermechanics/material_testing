# DICe cross-validation fixtures

Third-party test data from the **DICe** project (Digital Image Correlation
Engine, https://github.com/dicengine/dice), directory
`tests/examples/custom_app`.

- **License:** BSD 3-Clause — see [`LICENSE.DICe`](LICENSE.DICe). Copyright 2015
  National Technology & Engineering Solutions of Sandia, LLC (NTESS). The
  copyright notice, conditions, and disclaimer are retained here per clause 1.
- **Provenance:** `ref.pgm` / `def.pgm` are lossless 8-bit-grayscale PGM (P5)
  conversions of DICe's `ref.tif` / `def.tif` (512×512, 8-bit) via Pillow
  `convert("L")`. Only the container changed (TIFF → PGM); pixel values are
  unchanged. PGM is used because the host test build has no image codec.
- **Ground truth:** DICe's `custom_app` asserts each subset recovers
  **U = 0.4 px in X** within 0.1 px (`errorTol = 0.1`, `subset_size = 27`,
  subsets at (100,100), (200,200), (300,300), (400,400)). It checks the X
  component only. Empirically `def` is a **diagonal ~(0.4, 0.4)** shift — our
  engine recovers V ~ 0.4 as well — but DICe publishes no Y ground truth, so
  only X is cross-validated.

Consumed only by `integration/test_dice_realimage.cpp`, which runs **our**
engine on these images and checks it meets DICe's published contract. No DICe
source code is used.
