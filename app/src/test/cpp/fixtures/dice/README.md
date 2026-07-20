# DICe fixtures

Third-party test data from **DICe** (Digital Image Correlation Engine,
https://github.com/dicengine/dice), vendored unmodified in its original format.

**License:** BSD 3-Clause — see [`LICENSE.DICe`](LICENSE.DICe). Copyright 2015
National Technology & Engineering Solutions of Sandia, LLC (NTESS). The
copyright notice, conditions, and disclaimer are retained here per clause 1.

## Contents

| File | Source | Used by |
|---|---|---|
| `ref.tif`, `def.tif` | `tests/examples/custom_app` (512×512) | `dice/test_translation_real_image.cpp` |
| `def_exx.tif` | **Derived here** — `ref.tif` resampled by a known 1% uniaxial strain | `dice/test_strain_gradients.cpp`, `dice/test_strain_vsg.cpp` |
| `oht_cfrp_00/01/03/06/11.tiff` | `tests/regression/dic_challenge_12/images` (400×1040) | `dice/test_field_agreement.cpp` |
| `DICe_solution_01/03/06/11.txt` | `tests/regression/dic_challenge_12/gold` | `dice/test_field_agreement.cpp` |

## What counts as truth

- **`custom_app` (ref/def)** — a constructed 0.4 px shift with a published
  assertion (`|U − 0.4| ≤ 0.1`, subset 27, four subsets). Real known answer,
  for X only: the pair is actually a diagonal ~(0.4, 0.4) shift, but DICe
  publishes no Y reference.
- **`def_exx.tif`** — our own prescribed deformation, so truth is analytic:
  `du/dx = 0.01` exactly, on real texture.
- **`oht_cfrp` + `DICe_solution_*`** — an open-hole-tension CFRP *experiment*.
  There is **no analytic truth**; DICe's field is a reference *result*, so the
  test measures inter-code **agreement**, not correctness. The solutions span
  load steps with growing deformation (~0.9, 3.2, 6.6, 12.0 px), which is what
  exercises the coarse-search path.

No DICe source code is used — only its data and published contracts.
