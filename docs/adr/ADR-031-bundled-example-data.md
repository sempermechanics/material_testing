# ADR-031: Example data ships in the app, each set under its own licence

**Status:** Accepted, built (not merged or released)
**Date:** 2026-10-26
**Deciders:** app owner
**Relates to:** [ADR-015](ADR-015-package-layout.md) (packages),
[ADR-016](ADR-016-work-that-outlives-the-activity.md) (Save to phone runs on the view model),
[ADR-018](ADR-018-error-convention.md) (its outcome),
[ADR-022](ADR-022-semper-ui-plus-lab-tests.md) (shared screens),
[REAL_WORLD_VALIDATION](../app/REAL_WORLD_VALIDATION.md)

## Context

A student who has not run their own test yet has nothing to try the app on.
We already had five prepared sets with known answers: three published tests
(steel tensile, PMMA bending, a concrete beam that cannot be tracked) and two
pairs of DICe test images. Until now they lived outside the repo, and
`REAL_WORLD_VALIDATION.md` said to keep images out.

The owner asked to ship them with the app, checked against each licence:

| Set | Source | Licence |
|---|---|---|
| `steel_tensile` | Koirala 2026, Zenodo, doi:10.5281/zenodo.18311953 | CC BY 4.0 |
| `pmma_bending` | Delorme, Tabiai, Diehl, Laberge Lebel, Lévesque 2018, Zenodo, doi:10.5281/zenodo.1172068 | CC BY 4.0 |
| `concrete_bending` | Sjölander, Belloni, Peterson, Ledin 2023, Mendeley Data, doi:10.17632/z3yc9z84tk.3 | CC BY 4.0 |
| `dice_oht_cfrp`, `dice_translation` | DICe, github.com/dicengine/dice, © 2015 NTESS | BSD-3-Clause |

The licences were read from the live records on 2026-10-26. Both allow
commercial redistribution, which is what selling the app with them inside
is. CC BY 4.0 asks for:

- credit (creator, title, a link or DOI, the licence and its link);
- a note of what was changed;
- no added terms or technical measures that restrict what the licence
  lets a recipient do with those files (§2(a)(5)(C));
- no suggestion that the authors endorse us.

BSD-3 asks that a binary redistribution reproduce its notice "in the
documentation and/or other materials".

## Decision

1. **The sets are committed into `app/src/main/assets/examples/<id>/`.** That is
   about 80 MB in this public repo, which has no LFS. The owner chose this over
   a separate data repository. Each folder holds:
   - the frames and the load log;
   - `example.json` (schema 1, `data/examples/ExampleManifest`);
   - `README.md`, `CREDIT.txt`;
   - the ground truth (`truth_gauge_strain.csv`, `truth_deflection.csv`,
     `DICe_solution_01.txt`);
   - `LICENSE.DICe` for the BSD sets.

   The DICe files are byte-for-byte copies of `engine/examples/samples`
   (`ExampleCatalogTest`). The steel and PMMA truth comes from the `truth`
   steps of `scripts/real_data_*.py`, run on the authors' own files.
2. **Every set credits itself in three places:**
   - its sheet on the Examples screen;
   - **Credits & licences** (`LicencesActivity`, reached from Settings → Help);
   - the `CREDIT.txt` that travels with every copy Save to phone makes.

   `assets/licences/` holds the DICe BSD-3, OpenCV Apache-2.0 and Eigen
   MPL-2.0 texts, shown in full.
3. **The Terms carve the example files out** (§4.6, written in semperdic-app
   first): they keep their own licences, and nothing in the Terms restricts them.
4. **Two ways in.**
   - **Try it** opens the wizard as if the files had been picked: provider URIs
     (`ExampleAssetProvider`, read only, not exported), plus the test, its
     dimensions (`LabRerun.putOn`), the ROI and the load log. The student still
     taps a beam's edges.
   - **Save to phone** copies the set to `Download/Semper examples/<set>/` through
     MediaStore, with no permission needed (minSdk 30).

   Both pass the same gates as the + button. An example run counts toward the
   demo limit like any other.
5. **Entry points.**
   - The **Which test?** sheet gets a "try an example" row. That screen belongs
     to this fork (ADR-022 rule 3).
   - Settings → Help gets "Example data" and "Credits & licences". Settings is a
     shared screen, so this is a stated **exception to ADR-022's
     upstream-first rule**: the owner chose to build it here and port it to
     semperdic-app with the rest of the feature.
   - Home's empty state already leads to the + sheet, so it gets no button of
     its own.
6. **Assets that are opened as file descriptors stay uncompressed**
   (`androidResources.noCompress` for `tif`, `tiff`, `csv`, `txt`, `md`, `json`).
   PNG and JPEG already are.

## Consequences

- The APK and AAB grow by about 80 MB, for Play and for sideloading alike.
  `scripts/check_release_apk.py` checks that every listed file is in the APK
  and fails above 150 MB.
- Every clone and every CI checkout (`fetch-depth: 0`) carries the images. If
  that becomes a burden, the way out is a data repository fetched at build time
  by hash; the manifests and the code would not change.
- A new set needs `example.json`, `README.md` and `CREDIT.txt`, plus its licence
  file if the licence asks for one. `ExampleCatalogTest` fails without them.
- The app now has a licences screen. The AndroidX, Firebase and OkHttp
  Apache-2.0 notices are still missing from it (TD-57).
- semperdic-app has none of this until it is ported. ADR-025's
  `materialTesting` flavour would own the lab sets; the DICe sets and the
  screens suit both apps.
