#!/usr/bin/env python3
"""Real-data check of tensile E: a published steel test run through the app.

The dataset is Zenodo record 18311953 (Koirala, CC BY 4.0): 1.4016 steel
sheet, 12.5 mm² section, stereo DIC images plus two gauge points 60 mm apart.
Two steps sit around one manual app run (docs/app/REAL_WORLD_VALIDATION.md):

  prep     crop camera 1's frames for the steps in STEPS and write the
           app's load CSV (kN, one row per deformed frame)
  compare  read the app's .dat files and print its strain against the
           gauge points, and E by the app's rule next to fixed-window fits.
           The app's strain is the virtual extensometer's ΔL / L0 (ADR-012);
           the region-mean Exx it plotted before is printed beside it.

Needs numpy; `prep` also needs Pillow. Run by hand, never in CI.

Usage:
  python scripts/real_data_steel_tensile.py prep --data DIR --out DIR
  python scripts/real_data_steel_tensile.py compare --data DIR --dat DIR
"""
from __future__ import annotations

import argparse
import zipfile
from pathlib import Path

import numpy as np

GAUGE_FILE = "X,Y coordinates and displacements.txt"
IMAGE_ZIP = "Images_series.zip"
AREA_MM2 = 12.5
# Step 0 is the reference; dense through the elastic range, sparse after.
STEPS = [0] + list(range(3, 31)) + [35, 40, 45, 55, 80, 130, 205, 305, 405, 509, 605, 705]
# Camera 1 frames are 2000 px wide; this band holds the specimen's gauge part.
CROP = (0, 780, 2000, 1215)
# Mirrors DicResult: 8 float32 per point, accepted when 0 <= ZNSSD <= 0.15.
STRIDE, IDX_X, IDX_U, IDX_EXX, IDX_ZNSSD, MAX_ZNSSD = 8, 0, 2, 4, 7, 0.15
# Mirrors Extensometer: each end band is this share of the accepted length along x.
BAND_FRACTION = 0.1
# Mirrors ElasticModulus.
MIN_R2, MIN_POINTS = 0.995, 3


def gauge_points(data: Path) -> dict[int, tuple[float, float]]:
    """Step -> (force N, strain mε) from the dataset's two gauge points."""
    rows = []
    for line in (data / GAUGE_FILE).read_text(encoding="latin-1").splitlines():
        cells = [c.strip() for c in line.split(";")]
        try:
            rows.append([float(c) if c else float("nan") for c in cells[:18]])
        except ValueError:
            continue  # the three header lines
    a = np.array(rows)
    gauge_mm = a[0, 2] - a[0, 10]
    strain = (a[:, 6] - a[:, 14]) / gauge_mm * 1e3
    return {int(s): (f * 1000, e) for s, f, e in zip(a[:, 0], a[:, 1], strain)}


def prep(data: Path, out: Path) -> None:
    from PIL import Image

    ref = gauge_points(data)
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(data / IMAGE_ZIP) as z:
        for k, step in enumerate(STEPS):
            name = f"Images_series/series_step_{step}_camera_pos_1.tiff"
            Image.open(z.open(name)).convert("L").crop(CROP).save(out / f"steel_{k:02d}.png")
    loads = ["Step,Load (kN)"] + [f"{k},{ref[s][0] / 1000:.4f}" for k, s in enumerate(STEPS) if k]
    (out / "steel_loads.csv").write_text("\n".join(loads) + "\n", newline="")
    print(f"{len(STEPS)} frames and steel_loads.csv in {out}")


def fit(x: np.ndarray, y: np.ndarray) -> tuple[float, float]:
    slope, intercept = np.polyfit(x, y, 1)
    r2 = 1 - ((y - (slope * x + intercept)) ** 2).sum() / ((y - y.mean()) ** 2).sum()
    return slope, r2


def app_rule(strain: np.ndarray, stress: np.ndarray) -> tuple[int, float, float] | None:
    """The longest leading run up to the peak with R² >= MIN_R2."""
    peak = int(np.argmax(stress))
    for n in range(peak + 1, MIN_POINTS - 1, -1):
        slope, r2 = fit(strain[:n], stress[:n])
        if r2 >= MIN_R2:
            return n, slope, r2
    return None


def extensometer(frames: list[np.ndarray]) -> np.ndarray:
    """ΔL / L0 in mε per frame, as Extensometer and StressStrain.Model.Axial read it.

    The gauge is fixed on the first frame with accepted points: a band at each
    end of their x extent, in reference positions. ΔL is the far band's mean u
    minus the near band's; a frame where a band has no accepted point is NaN.
    """
    gauge = None
    out = []
    for d in frames:
        x, u = d[:, IDX_X], d[:, IDX_U]
        z = d[:, IDX_ZNSSD]
        ok = (z >= 0) & (z <= MAX_ZNSSD)
        if gauge is None and ok.any():
            lo, hi = x[ok].min(), x[ok].max()
            band = (hi - lo) * BAND_FRACTION
            near, far = (lo, lo + band), (hi - band, hi)
            at = [x[ok & (x >= a) & (x <= b)].mean() for a, b in (near, far)]
            if at[1] > at[0]:
                gauge = (near, far, at[1] - at[0])
        if gauge is None:
            out.append(np.nan)
            continue
        (n0, n1), (f0, f1), length = gauge
        in_near, in_far = ok & (x >= n0) & (x <= n1), ok & (x >= f0) & (x <= f1)
        if not in_near.any() or not in_far.any():
            out.append(np.nan)
            continue
        out.append((u[in_far].mean() - u[in_near].mean()) / length * 1e3)
    if gauge is not None:
        print(f"DIC gauge L0 = {gauge[2]:.0f} px, bands x {gauge[0][0]:.0f}-{gauge[0][1]:.0f} "
              f"and {gauge[1][0]:.0f}-{gauge[1][1]:.0f} px")
    return np.array(out)


def compare(data: Path, dat: Path) -> None:
    ref = gauge_points(data)
    steps = STEPS[1:]
    stress = np.array([ref[s][0] / AREA_MM2 for s in steps])
    gauge = np.array([ref[s][1] for s in steps])
    frames, mean = [], []
    for k in range(len(steps)):
        d = np.fromfile(dat / f"frame_{k:04d}.dat", "<f4").reshape(-1, STRIDE)
        z = d[:, IDX_ZNSSD]
        frames.append(d)
        mean.append(d[(z >= 0) & (z <= MAX_ZNSSD), IDX_EXX].mean() * 1e3)
    mean = np.array(mean)
    ext = extensometer(frames)

    print("frame  step  stress MPa  app dL/L0 1e-3  region mean 1e-3  gauge 1e-3")
    for k, s in enumerate(steps):
        print(f"{k + 1:5d} {s:5d} {stress[k]:11.1f} {ext[k]:15.4f} {mean[k]:17.4f} {gauge[k]:11.4f}")

    # The curve leaves out frames with no strain; so does every fit below.
    on = ~np.isnan(ext)
    elastic = on & (np.arange(len(steps)) < 28)
    mid = on & (np.arange(len(steps)) >= 12) & (np.arange(len(steps)) < 28)
    diff = (ext - gauge)[elastic] * 1e3
    print(f"frames 13-28: app / gauge = {ext[mid].sum() / gauge[mid].sum():.4f}; "
          f"frames 1-28: RMSE {np.sqrt((diff ** 2).mean()):.0f} µε, bias {diff.mean():+.0f} µε; "
          f"frames off the curve: {[k + 1 for k in np.flatnonzero(~on)]}")

    run = app_rule(ext[on], stress[on])
    if run is None:
        print("app rule: no run")
    else:
        n, slope, r2 = run
        g_slope, g_r2 = fit(gauge[on][:n], stress[on][:n])
        print(f"app rule: frames 1-{n}, E {slope:.1f} GPa, R2 {r2:.4f}; "
              f"gauge points, same frames: E {g_slope:.1f} GPa, R2 {g_r2:.4f}")
    for lo, hi in ((20, 100), (30, 150), (50, 200)):
        s = on & (stress >= lo) & (stress <= hi)
        a_e, a_r2 = fit(ext[s], stress[s])
        g_e, g_r2 = fit(gauge[s], stress[s])
        print(f"{lo}-{hi} MPa (n={s.sum()}): app E {a_e:.1f} (R2 {a_r2:.4f}), "
              f"gauge E {g_e:.1f} (R2 {g_r2:.4f})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prep")
    p.add_argument("--data", type=Path, required=True, help="folder with the Zenodo files")
    p.add_argument("--out", type=Path, required=True)
    c = sub.add_parser("compare")
    c.add_argument("--data", type=Path, required=True, help="folder with the Zenodo files")
    c.add_argument("--dat", type=Path, required=True, help="the app session's frame_NNNN.dat files")
    args = parser.parse_args()
    if args.cmd == "prep":
        prep(args.data, args.out)
    else:
        compare(args.data, args.dat)


if __name__ == "__main__":
    main()
