# Noise floor, lighting, and strain accuracy

**Date:** 2026-08-28  
**Phones:** Pixel 6, Samsung (SM-G996U1)  
**Protocol:** Static speckle on a tripod; **only lighting varied** (bright → medium → dim)

True strain was **zero**. Every Exx value in this study is measurement error — not
material stretch. That is what makes lighting and rig setup visible.

In-app **Why?** / **ⓘ** copy for the measurement-floor dialog lives in
[FAQ.md — lighting-and-accuracy](FAQ.md#lighting-and-accuracy). This page is the
lab write-up: protocol, numbers, and a setup checklist.

---

## Main finding

**More light → better accuracy** when subset size is held fixed. Dim light is
always worst; a larger subset **partially** compensates but does not beat bright
at the same patch size.

Device-to-device scatter differences were **secondary** to lighting and burst
stability. Even, diffuse light and a rigid, repeatable setup matter as much as
which phone you use.

---

## Why this test exists

On a loaded specimen you cannot tell real strain from camera noise. On an
**unloaded** specimen every displacement is error. The study therefore:

1. Fixed the specimen and tripod.
2. Locked camera settings (same path as in-app Record).
3. Changed **only** illumination between sessions.
4. Analysed the **same physical patch** (intersection of per-session contrast
   ROIs; scaled when capture resolution differed).

---

## Fixed subset — lighting alone

Robust Exx scatter (median of per-frame MAD, mε). Lower is better. Pixel,
step = 5, common ROI:

| Subset | Bright | Medium | Dim |
|--------|--------|--------|-----|
| **15 px** | 0.55 | 0.90 | 1.62 |
| **21 px** | 0.33 | 0.54 | 1.04 |
| **39 px** | 0.14 | 0.24 | 0.46 |
| **65 px** | **0.07** | **0.13** | **0.23** |

At **65 px**, dim is about **3×** bright. At **15 px**, dim is about **3×**
bright as well (1.62 vs 0.55 mε). Samsung followed the same ranking: bright ≤
medium < dim at every fixed subset.

---

## The app-default trap

In a normal Record run the app **raises subset size in dim light** (e.g. 65 px
vs 15–21 px in bright). Larger patches average noise down, so dim + large subset
can look “best” on the heatmap even though dim lighting is worse.

| Light | Phone | Stamped floor | App subset | Robust Exx σ |
|-------|-------|---------------|------------|--------------|
| Bright | Pixel | 0.40 mε | 21 px | 0.29 mε |
| Medium | Pixel | 0.56 mε | 15 px | **0.90 mε** (above floor) |
| Dim | Pixel | 1.00 mε | 65 px | 0.23 mε |
| Bright | Samsung | 1.11 mε | 15 px | 0.88 mε |
| Medium | Samsung | 1.12 mε | 15 px | 0.79 mε |
| Dim | Samsung | 1.81 mε | 39 px | 0.67 mε |

**Rule:** compare lighting only at a **fixed subset**. App-default floors and
subsets are useful for that phone and that run; they are not a lighting
comparison by themselves.

---

## Measurement floor

Before recording, the app takes a short **static burst** (2–5 stills) of the
unloaded scene and converts displacement scatter to strain at a **15 px** gauge:

```
σ_ε (mε) ≈ √2 · σ_u (px) / 15
```

- **Gate:** about **1 mε**. Above it, the dialog warns strongly but still allows
  **Record anyway**. The floor is stamped on the PDF, CSV, and session.
- The floor is **not** the heatmap max. It is one number for the whole setup.
- The burst frames are deleted; exported PNGs come from the later recording pass.
  The stamped CSV/PDF value is authoritative for what the run was captured at.

---

## How to read field stats

On a static specimen, mean and median stay near zero even when the run is noisy.
Min/max are single outlier points (±100–800 mε in this study). **Robust σ**
(MAD-based) is the best single accuracy read.

| Stat | On a static test |
|------|------------------|
| Mean / median | Near 0 — looks fine even when noisy |
| Min / max | Outliers, drift, or bad subsets — misleading alone |
| Robust σ | Typical point-to-point error — tracks lighting and subset |
| Measurement floor | Smallest strain the **setup** can trust |

**Setup can dominate lighting.** A few tenths of a pixel of tripod walk
(Pixel medium ~0.6 px vertical over the session) spiked heatmap max to hundreds
of mε while mean/median stayed near zero.

---

## Setup checklist

1. **Prefer bright or medium, even light** on the speckle — avoid glare and deep
   shadows in the ROI. Dim costs accuracy even when the floor dialog passes.
2. **Hold the rig steady** — lock the phone and specimen; wait for settling
   before the noise-floor burst.
3. **Draw a repeatable contrast ROI** on the busiest part of the pattern (same
   rectangle when comparing sessions).
4. **Read the measurement-floor dialog** — treat values above ~1 mε as a warning
   that fine strain may be noise.
5. **Do not trust heatmap max alone** — compare robust scatter (or session
   field stats) to the stamped floor.
6. When comparing lighting or devices in the lab, **hold subset fixed**; do not
   judge light from app-default subset alone.

For general DIC practice (speckle paint, camera choice, calibration), also see
*A Good Practices Guide for Digital Image Correlation* (iDICs). For operating
the app once you have images, see [OPERATING_MANUAL.md](../OPERATING_MANUAL.md).
