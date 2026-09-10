# Semper FAQ

Canonical text for the public manual at
`https://semperdic.github.io/website/manual/faq/`. Each **Why?** or **ⓘ** in the
app opens one of these sections (after a leave-app confirm).

**Publish:** copy or render this file into [semperdic/website](https://github.com/semperdic/website)
on release. Anchor IDs must match `url_faq_*` in `app/src/main/res/values/strings.xml`.
App ↔ FAQ map: [FAQ_LINKS.md](FAQ_LINKS.md).

**Last updated:** 2026-08-28 (measurement-floor dialog, lighting study, strain stats).

---

## Table of contents

| Section | Anchor | Opened from |
|---------|--------|-------------|
| Lossy formats | [jpeg-warning](#jpeg-warning) | Wizard step 1 chip |
| Speckle contrast & size | [speckle-contrast](#speckle-contrast) | Wizard chips; speckle readout |
| Lighting & accuracy | [lighting-and-accuracy](#lighting-and-accuracy) | Reports |
| Strain field stats | [strain-field-stats](#strain-field-stats) | Result viewer; reports |
| Frame size mismatch | [frame-size-mismatch](#frame-size-mismatch) | Wizard step 2 chip |
| ROI too small | [roi-too-small](#roi-too-small) | Wizard ROI snackbar **Why?** |
| Sweep subset range | [sweep-subset-range](#sweep-subset-range) | Sweep plan chip |
| Sweep empty plan | [sweep-empty-plan](#sweep-empty-plan) | Sweep plan chip |
| Engine: decorrelation | [engine-features](#engine-features) | Engine failure **Why?**; lattice node |
| Engine: ROI | [engine-roi](#engine-roi) | Engine failure **Why?** |
| Engine: decode / init | [engine-init](#engine-init) | Engine failure **Why?** |
| Engine: low convergence | [engine-convergence](#engine-convergence) | Engine failure **Why?** |
| Engine: strain window | [engine-vsg](#engine-vsg) | Engine failure **Why?**; lattice all-failed |
| Import reference | [import-reference](#import-reference) | Wizard import snackbar **Why?** |
| Import deformed batch | [import-deformed](#import-deformed) | Wizard import snackbar **Why?** |
| Video read | [video-read](#video-read) | Wizard video snackbar **Why?** |
| Video extract | [video-extract](#video-extract) | Wizard video snackbar **Why?** |
| No batch data | [no-batch-data](#no-batch-data) | Result viewer snackbar **Why?** |
| Viewer out of memory | [viewer-oom](#viewer-oom) | Result viewer snackbar **Why?** |
| Custom colour scale | [custom-scale](#custom-scale) | Result viewer snackbar **Why?** |

---

## jpeg-warning {#jpeg-warning}

**When you see it:** Wizard step 1 — a chip warns that a frame or reference is not
lossless (JPEG, HEIC, etc.).

**Why it matters:** Lossy compression adds blocking artefacts and softens speckle
edges. Displacement can still run, but strain noise and failed subsets rise.

**What to do:** Re-export or re-shoot as **PNG** or **TIFF** when accuracy matters.
On a phone, shoot in a camera mode that writes lossless stills rather than pulling
JPEGs out of the gallery.

---

## speckle-contrast {#speckle-contrast}

**When you see it:** Either of the two speckle chips in the wizard's first step
(**Why?** opens this section). They report different faults and are worth telling
apart.

### Low speckle contrast

**Why it matters:** DIC tracks small windows of random pattern. If gradients inside
a subset are weak, correlation fails or wanders.

**What to do:**

- Paint a finer, high-contrast speckle (black on white or white on black).
- Improve **lighting** — even, diffuse light; avoid glare and deep shadows in the ROI.
- Focus sharply on the speckled surface.
- Draw the ROI over the busiest part of the pattern.

### Speckle size

Under the subset slider the wizard reports how large your speckles actually
measure, in pixels of the frame you imported. Anything wrong with the *size*
of the pattern is flagged on step 1, beside the images — the only fix is a
different photograph. Anything wrong with the *subset* is flagged on step 2,
beside the slider that fixes it. The iDICs *Good Practices Guide for
Digital Image Correlation* asks for dots spanning **3 to 9 px**, and the app
measures yours from the reference frame's own autocorrelation.

| What it says | What it means | What to do |
|---|---|---|
| Below 3 px | The pattern is too fine for this frame to resolve. It aliases, and points can fail to correlate at all. | Shoot closer, or spray a coarser pattern. |
| 3–9 px | Nothing to change. | — |
| Above 9 px | It will correlate, but the extra pixels buy no extra accuracy, and a subset large enough to span the dots leaves you fewer measurement points across the ROI. | A finer pattern, or shoot from further back. |
| Subset spans too few speckles | A subset should cover about three dots. Fewer than that and it looks much like its neighbours, so it can correlate confidently against the wrong place. | Raise the subset size to the value the chip names — the chip sits under the slider on step 2, so it clears as you move it. |

Contrast and size are independent: a pattern can be crisp and black-on-white and
still be far too fine, and the contrast chip will say nothing about it. That is why
both are reported.

See [lighting-and-accuracy](#lighting-and-accuracy) for how much light changes
measured strain noise.

---

## lighting-and-accuracy {#lighting-and-accuracy}

**Context:** Internal noisetest (2026-08-28) — static speckle, fixed specimen and
tripod, **only lighting varied** (bright / medium / dim). True strain = **0**; all
reported Exx values are measurement error.

### Main finding

**More light → better accuracy** when subset size is held fixed. Dim light is
always worst; a larger subset **partially** compensates but does not beat bright
at the same patch size.

Example — robust strain scatter (typical error, mε) at **65 px subset**, same ROI:

| Lighting | Typical σ |
|----------|-----------|
| Bright | **0.07–0.12** |
| Medium | **0.10–0.13** |
| Dim | **0.23–0.37** |

At **15 px subset**, dim scatter can be **2–3×** bright (e.g. 1.6 vs 0.5 mε).

### Why dim sometimes looked “best” in the app

The app **raises subset size in dim light** (e.g. 65 px vs 15–21 px in bright).
Larger patches average noise down. Comparing app-default runs **confuses lighting
with subset**. Always compare at the **same subset** to isolate lighting.

### Image quality vs strain

| Lighting | Effect on images | Effect on strain |
|----------|------------------|------------------|
| **Dim** | Dark ROI, low sharpness, weak SSSIG | Highest scatter; needs large subset |
| **Medium** | Good luma and SSSIG | Middle — but **tripod drift** in some runs dominated outliers |
| **Bright** | Strong ROI luma, good contrast | Lowest scatter at fixed subset |

### Practical rules

1. **Prefer bright or medium light** on the speckle — dim costs accuracy even when
   the floor dialog passes.
2. **Hold the rig steady** — session drift (e.g. 0.6 px vertical over ten frames)
   can spike max strain to hundreds of mε while mean/median stay near zero.
3. **Draw the same contrast ROI** when comparing lighting sessions (lab scripts use
   the intersection of per-session ROIs).
4. **Do not trust heatmap max alone** — see [strain-field-stats](#strain-field-stats).

Device-to-device differences were **secondary** to lighting and burst stability in
this study.

Full protocol, fixed-subset tables, and setup checklist:
[NOISE_FLOOR_STRAIN_ACCURACY.md](NOISE_FLOOR_STRAIN_ACCURACY.md).

---

## strain-field-stats {#strain-field-stats}

**When you see it:** Result viewer heatmap, CSV `# field_stats`, PDF field summary.

On a **static** specimen (zero true strain), the app still reports mean, median,
min, max, and scatter. They answer different questions.

| Stat | What it is | How to read it on a static test |
|------|------------|-----------------------------------|
| **Mean / median** | Average / middle Exx in the field | Stay **near 0 mε** — looks fine even when the run is noisy |
| **Min / max** | Single worst points in the field | **Spike** (±100–800 mε) from outliers, drift, or bad subsets — **misleading alone** |
| **Robust σ (MAD)** | Typical point-to-point scatter | **Best single accuracy read** — tracks lighting and subset |

### Patterns from the lighting study

- **Mean and median** barely moved across bright / medium / dim — do not use them
  alone to judge quality.
- **Max** swung with drift and small subsets; **min/max tightened** with larger
  subset and stable burst.
- **Robust σ** ranked lighting cleanly: bright < medium < dim at fixed subset.

### What to trust

1. Compare **robust σ to the measurement floor** on static or near-static checks.
2. Treat **max** as “worst pixel this frame,” not “accuracy.”
3. If mean ≈ 0 but max is huge, look for **tripod drift** or **too small a subset**
   before blaming the specimen.

CSV exports max/min/mean per frame; median on points is computed in reports. The
heatmap colour scale uses percentiles (p02–p98); CSV extrema are raw.

---

## frame-size-mismatch {#frame-size-mismatch}

**When you see it:** Wizard step 2 — deformed frames differ in pixel size from the
reference.

**Why it matters:** Subset positions are in reference pixels; a size change breaks
the grid unless frames are rescaled (not automatic).

**What to do:** Re-export all frames at the same resolution, or re-shoot with a
fixed resolution setting.

---

## roi-too-small {#roi-too-small}

**When you see it:** ROI width or height is smaller than the subset diameter.

**What to do:** Enlarge the ROI on the speckle, or reduce subset size in step 3.

---

## sweep-subset-range {#sweep-subset-range}

**When you see it:** Sweep plan chip — subset range extends above what the ROI can
fit.

**What to do:** Widen the ROI, lower the maximum subset in the sweep, or reduce step
so fewer grid points are required.

---

## sweep-empty-plan {#sweep-empty-plan}

**When you see it:** No valid subset × strain-window combinations for this ROI and
ranges.

**What to do:** Enlarge the ROI or narrow subset / VSG ranges until at least one
combination fits.

---

## engine-features {#engine-features}

**When you see it:** Engine failure — decorrelation / AKAZE could not match the pair.

**What to do:** Check focus and speckle; ensure reference and deformed frames are
the same scene; improve [speckle contrast](#speckle-contrast).

---

## engine-roi {#engine-roi}

**When you see it:** Engine failure — ROI held no valid points.

**What to do:** Enlarge ROI; confirm it lies on speckle; check subset fits inside ROI
([roi-too-small](#roi-too-small)).

---

## engine-init {#engine-init}

**When you see it:** Engine failure — decode or engine start failed.

**What to do:** Re-import images; confirm files are readable PNG/TIFF; free memory on
low-RAM devices.

---

## engine-convergence {#engine-convergence}

**When you see it:** Engine failure — too few subsets converged.

**What to do:** Improve speckle and lighting; try a larger subset or smaller step;
check for motion blur between frames.

---

## engine-vsg {#engine-vsg}

**When you see it:** Engine failure or hollow lattice nodes — strain window too large
for ROI/step, or no points survived VSG filtering.

**What to do:** Reduce strain window (VSG), enlarge ROI, or coarsen step. In sweeps,
tap a hollow node for its one-line reason; **Why?** opens this section.

---

## import-reference {#import-reference}

**When you see it:** Reference image failed to load or decode.

**What to do:** Try PNG/TIFF; avoid corrupted or unsupported RAW without conversion;
check storage permission.

---

## import-deformed {#import-deformed}

**When you see it:** One or more deformed frames failed to load.

**What to do:** Same as reference; ensure batch paths are stable and formats match.

---

## video-read {#video-read}

**When you see it:** Video metadata could not be read.

**What to do:** Re-copy the file; try a shorter clip; confirm the container is
supported on this device.

---

## video-extract {#video-extract}

**When you see it:** Too few frames extracted from the selected segment.

**What to do:** Widen the segment, lower sampling interval, or raise max frames in
Settings.

---

## no-batch-data {#no-batch-data}

**When you see it:** Result viewer opened without a `.dat` batch for this session.

**What to do:** Re-run analysis from the wizard, or open a session that completed
successfully.

---

## viewer-oom {#viewer-oom}

**When you see it:** Not enough memory to decode a full-field frame.

**What to do:** Close other apps; open a smaller analysis; reduce the ROI or shoot
at a lower resolution.

---

## custom-scale {#custom-scale}

**When you see it:** Custom colour-scale min ≥ max.

**What to do:** Set min below max, or reset to auto scale.
