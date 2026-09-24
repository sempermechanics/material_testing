#!/usr/bin/env python3
"""Real-data check of bending: a published PMMA 3-point bend run through the app.

The dataset is Zenodo record 1172068 (Delorme, Tabiai et al., CC BY 4.0): a
speckled PMMA beam, span 75 mm, depth 31 mm, thickness 12 mm, loaded at
mid-span to 9.34 kN, stereo images plus the authors' VIC-3D full field for
every image. Two steps sit around one manual app run
(docs/app/REAL_WORLD_VALIDATION.md, case 2):

  prep     crop camera 0's images (32 as the reference, 33..97 every second
           one) and write the app's load CSV (N, one row per deformed frame)
  compare  read the app's .dat files and compare them, point by point, with
           the authors' field for the same images: deflection at the tapped
           load point, displacement, and strain

The authors' field is referenced to image 0 and the app's to image 32, so
both are compared as increments from image 32, at the authors' points carried
into image 32. Their x / y / u / v are camera-0 pixels, the frame the app
measures in. Their exy is engineering shear with y up; the app's is tensor
shear with y down, so theirs is compared as -exy / 2.

Needs numpy; `prep` also needs Pillow. Run by hand, never in CI.

Usage:
  python scripts/real_data_pmma_bending.py prep --data DIR --out DIR
  python scripts/real_data_pmma_bending.py compare --data DIR --dat DIR [--taps 1170,39,611] [--windows 15,45,95]
"""
from __future__ import annotations

import argparse
import io
import zipfile
from pathlib import Path

import numpy as np

ARCHIVE = "3pointPMMA.zip"
REF_IMAGE = 32
IMAGES = list(range(33, 98, 2))  # 97 is the peak; the beam breaks at 98
# Camera 0 frames are 2448 x 2048; this crop is the beam across the width,
# the top half of the frame, as a student would film it.
CROP = (40, 820, 2420, 2048)
SPAN_MM, WIDTH_MM, THICKNESS_MM = 75.0, 12.0, 31.0
# The beam's edges under the nose in the cropped reference, found from the
# intensity profile at x 1120..1220: 572 px, 0.0542 mm/px.
TAPS = (1170.0, 39.0, 611.0)
# Mirrors DicResult: 8 float32 per point (x y u v exx eyy exy corr),
# accepted when 0 <= ZNSSD <= 0.15.
STRIDE, MAX_ZNSSD = 8, 0.15
# Mirrors BeamDeflection.
MIN_DEFLECTION_PX, UNLOADED_FRACTION, MIN_PROBE_RADIUS_PX = 1.0, 0.01, 8.0
# The authors' per-image CSV columns used here.
COL_V_MM, COL_EXX, COL_EYY, COL_EXY, COL_SIGMA, COL_X, COL_Y, COL_U, COL_V = 4, 6, 7, 8, 12, 13, 14, 15, 16
# A displacement this far from the authors' is a wrong match, not noise.
BAD_POINT_PX = 0.5


def log_rows(z: zipfile.ZipFile) -> dict[int, tuple[float, float]]:
    """Image -> (time s, force N) from the machine log."""
    out = {}
    for line in z.read("3pointPMMA/PMMA-3point.csv").decode("latin-1").splitlines()[1:]:
        cells = line.split(";")
        out[int(cells[0])] = (float(cells[-3]), float(cells[-1]))
    return out


def prep(data: Path, out: Path) -> None:
    from PIL import Image

    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(data / ARCHIVE) as z:
        log = log_rows(z)
        rows = ["file,original_image,time_s,force_N"]
        for k, n in enumerate([REF_IMAGE] + IMAGES):
            name = f"3pointPMMA002-{n:04d}_0.tif"
            Image.open(z.open(f"3pointPMMA/Images/{name}")).convert("L").crop(CROP).save(out / f"pmma_{k:02d}.png")
            rows.append(f"pmma_{k:02d}.png,{name},{log[n][0]},{log[n][1]}")
    loads = ["Step,Load (N)"] + [f"{k},{log[n][1]:.1f}" for k, n in enumerate(IMAGES, 1)]
    (out / "pmma_loads.csv").write_text("\n".join(loads) + "\n", newline="")
    (out / "frame_index.csv").write_text("\n".join(rows) + "\n", newline="")
    print(f"{len(IMAGES) + 1} frames, pmma_loads.csv and frame_index.csv in {out}")


def authors_field(z: zipfile.ZipFile, n: int) -> np.ndarray:
    text = z.read(f"3pointPMMA/CSV/3pointPMMA002-{n:04d}_0.csv").decode("latin-1")
    return np.genfromtxt(io.StringIO(text), delimiter=",", skip_header=1)


def truth(field: np.ndarray, ref: np.ndarray) -> dict[str, np.ndarray]:
    """The authors' increments from the reference, at their points in the cropped reference."""
    ok = (field[:, COL_SIGMA] >= 0) & (ref[:, COL_SIGMA] >= 0)

    def inc(c: int) -> np.ndarray:
        return field[ok, c] - ref[ok, c]

    return {
        "px": field[ok, COL_X] + ref[ok, COL_U] - CROP[0],
        "py": field[ok, COL_Y] + ref[ok, COL_V] - CROP[1],
        "u": inc(COL_U), "v": inc(COL_V), "exx": inc(COL_EXX), "eyy": inc(COL_EYY),
        "exy": -0.5 * inc(COL_EXY), "down_mm": -inc(COL_V_MM),  # world V is up
    }


class Grid:
    """One app frame on its regular grid, NaN where a point was rejected."""

    def __init__(self, d: np.ndarray):
        ok = (d[:, 7] >= 0) & (d[:, 7] <= MAX_ZNSSD) & np.isfinite(d[:, 2])
        self.points = d[ok]
        self.xs, self.ys = np.unique(d[:, 0]), np.unique(d[:, 1])
        self.step = float(self.xs[1] - self.xs[0])
        ix, iy = np.searchsorted(self.xs, d[ok, 0]), np.searchsorted(self.ys, d[ok, 1])
        self.fields = {}
        for name, col in (("u", 2), ("v", 3), ("exx", 4), ("eyy", 5), ("exy", 6)):
            g = np.full((len(self.ys), len(self.xs)), np.nan)
            g[iy, ix] = d[ok, col]
            self.fields[name] = g

    def at(self, g: np.ndarray, px: np.ndarray, py: np.ndarray) -> np.ndarray:
        """Bilinear, NaN when any corner is missing or the point is off the grid."""
        fx = (px - self.xs[0]) / self.step
        fy = (py - self.ys[0]) / self.step
        i0, j0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
        inside = (i0 >= 0) & (j0 >= 0) & (i0 + 1 < g.shape[1]) & (j0 + 1 < g.shape[0])
        i0, j0 = np.where(inside, i0, 0), np.where(inside, j0, 0)
        tx, ty = fx - i0, fy - j0
        v = (g[j0, i0] * (1 - tx) * (1 - ty) + g[j0, i0 + 1] * tx * (1 - ty)
             + g[j0 + 1, i0] * (1 - tx) * ty + g[j0 + 1, i0 + 1] * tx * ty)
        return np.where(inside, v, np.nan)

    def plane_strain(self, window: int) -> dict[str, np.ndarray]:
        """Strain from the app's own displacements by a plane fit over a circle
        of `window` px diameter (a VSG), the engine's footprint (VsgStudy.vsgFor);
        at least 90% of the circle's points must be present."""
        h = int(window / 2 // self.step)
        k = np.arange(-h, h + 1) * self.step
        kx, ky = np.meshgrid(k, k)
        inside = kx**2 + ky**2 <= (window / 2) ** 2
        need = 0.9 * inside.sum()
        grads = {}
        for name in ("u", "v"):
            f = np.pad(self.fields[name], h, constant_values=np.nan)
            w = np.lib.stride_tricks.sliding_window_view(f, (2 * h + 1, 2 * h + 1))
            m = np.isfinite(w) & inside
            cnt = m.sum((-1, -2))
            wz = np.where(m, w, 0.0)
            mx, my, mf = ((m * kx).sum((-1, -2)) / cnt, (m * ky).sum((-1, -2)) / cnt, wz.sum((-1, -2)) / cnt)
            dx = np.where(m, kx - mx[..., None, None], 0.0)
            dy = np.where(m, ky - my[..., None, None], 0.0)
            df = np.where(m, wz - mf[..., None, None], 0.0)
            sxx, syy, sxy = (dx * dx).sum((-1, -2)), (dy * dy).sum((-1, -2)), (dx * dy).sum((-1, -2))
            sxf, syf = (dx * df).sum((-1, -2)), (dy * df).sum((-1, -2))
            det = sxx * syy - sxy**2
            with np.errstate(invalid="ignore", divide="ignore"):
                gx, gy = (syy * sxf - sxy * syf) / det, (sxx * syf - sxy * sxf) / det
            gx[cnt < need] = np.nan
            gy[cnt < need] = np.nan
            grads[name] = (gx, gy)
        return {"exx": grads["u"][0], "eyy": grads["v"][1], "exy": 0.5 * (grads["u"][1] + grads["v"][0])}


def rms(e: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(e)))) if e.size else float("nan")


def modulus(loads: np.ndarray, delta_mm: np.ndarray, mm_per_px: float) -> tuple[float, float]:
    """Mean E of the loaded steps and E from the load-deflection slope, GPa (BeamDeflection's rules)."""
    inertia = WIDTH_MM * THICKNESS_MM**3 / 12
    loaded = loads > UNLOADED_FRACTION * loads.max()  # every load here is its own step
    has_e = loaded & (np.abs(delta_mm) >= MIN_DEFLECTION_PX * mm_per_px)
    per_step = loads[has_e] * SPAN_MM**3 / (48 * delta_mm[has_e] * inertia) / 1000
    slope = np.polyfit(delta_mm[loaded], loads[loaded], 1)[0]
    return float(per_step.mean()), float(slope * SPAN_MM**3 / (48 * inertia) / 1000)


def compare(data: Path, dat: Path, taps: tuple[float, float, float], windows: list[int]) -> None:
    x0, top, bottom = taps
    thickness_px = bottom - top
    mm_per_px = THICKNESS_MM / thickness_px
    cx, cy, radius = x0, (top + bottom) / 2, max(thickness_px / 2, MIN_PROBE_RADIUS_PX)
    print(f"taps x {x0:g}, y {top:g} / {bottom:g}: {thickness_px:g} px, {mm_per_px:.5f} mm/px")

    comps = ("u", "v", "exx", "eyy", "exy")
    errs = {c: [] for c in comps}
    win_errs = {w: {c: [] for c in ("exx", "eyy", "exy")} for w in windows}
    rows, bad_frames = [], []
    total = bad_total = 0
    with zipfile.ZipFile(data / ARCHIVE) as z:
        log = log_rows(z)
        ref = authors_field(z, REF_IMAGE)
        for k, n in enumerate(IMAGES):
            grid = Grid(np.fromfile(dat / f"frame_{k:04d}.dat", "<f4").reshape(-1, STRIDE))
            t = truth(authors_field(z, n), ref)
            app = {c: grid.at(grid.fields[c], t["px"], t["py"]) for c in comps}
            valid = np.all([np.isfinite(app[c]) for c in comps], axis=0)
            bad = valid & (np.hypot(app["u"] - t["u"], app["v"] - t["v"]) > BAD_POINT_PX)
            total += int(valid.sum())
            bad_total += int(bad.sum())
            if bad.any():
                bad_frames.append((k + 1, int(bad.sum())))
            for c in comps:
                errs[c].append(app[c][valid & ~bad] - t[c][valid & ~bad])
            for w in windows:
                strain = grid.plane_strain(w)
                for c in win_errs[w]:
                    f = grid.at(strain[c], t["px"], t["py"])
                    m = np.isfinite(f) & ~bad
                    win_errs[w][c].append(f[m] - t[c][m])
            p = grid.points
            near_app = (p[:, 0] - cx) ** 2 + (p[:, 1] - cy) ** 2 <= radius**2
            near_truth = (t["px"] - cx) ** 2 + (t["py"] - cy) ** 2 <= radius**2
            # Taps are straight down (BeamTapPlacement), so "down" is image v.
            rows.append((k + 1, n, log[n][1], p[near_app, 3].mean() * mm_per_px, t["down_mm"][near_truth].mean()))

    print("\nframe image  load N   app d mm  authors d mm   exx RMSE ue")
    for (fr, n, w, d_app, d_true), e in zip(rows, errs["exx"]):
        print(f"{fr:5d} {n:5d} {w:7.0f} {d_app:10.4f} {d_true:13.4f} {rms(e) * 1e6:13.0f}")
    loads = np.array([r[2] for r in rows])
    d_app, d_true = np.array([r[3] for r in rows]), np.array([r[4] for r in rows])
    e = d_app - d_true
    print(f"\nDeflection at the load point, {len(rows)} frames: RMSE {rms(e):.4f} mm "
          f"({rms(e) / d_true.max() * 100:.1f}% of the {d_true.max():.3f} mm peak), bias {e.mean():+.4f} mm, "
          f"max |error| {np.abs(e).max():.4f} mm")
    for name, d in (("app", d_app), ("authors", d_true)):
        mean_e, slope_e = modulus(loads, d, mm_per_px)
        print(f"  {name:8s} E from the graph {slope_e:.3f} GPa "
              f"(slope {slope_e * 48 * WIDTH_MM * THICKNESS_MM**3 / 12 / SPAN_MM**3 * 1000:.1f} N/mm), "
              f"average E {mean_e:.3f} GPa")

    print(f"\nWrong displacements (> {BAD_POINT_PX} px off the authors'): {bad_total} of {total} points; "
          f"(frame, count): {bad_frames}")
    per_frame = {c: np.array([rms(x) for x in errs[c]]) for c in comps}
    spikes = per_frame["eyy"] > 3 * np.median(per_frame["eyy"])
    print(f"Frames with strain spikes (eyy RMSE over 3x the median): {list(np.flatnonzero(spikes) + 1)}")
    print("\nOther points, app against the authors:")
    for c in comps:
        scale, unit = (1.0, "px") if c in ("u", "v") else (1e6, "ue")
        clean = np.concatenate([x for x, s in zip(errs[c], spikes) if not s])
        print(f"  {c:3s}: RMSE {rms(clean) * scale:8.3f} {unit} over the frames without spikes; "
              f"frame 1 {per_frame[c][0] * scale:.3f}, last {per_frame[c][-1] * scale:.3f}, "
              f"worst {per_frame[c].max() * scale:.3f}")
    for w in windows:
        print(f"  strain recomputed from the app's displacements, {w} px window: " + ", ".join(
            f"{c} {rms(np.concatenate([x for x, s in zip(win_errs[w][c], spikes) if not s])) * 1e6:.0f} ue"
            for c in ("exx", "eyy", "exy")))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prep")
    p.add_argument("--data", type=Path, required=True, help=f"folder with the Zenodo {ARCHIVE}")
    p.add_argument("--out", type=Path, required=True)
    c = sub.add_parser("compare")
    c.add_argument("--data", type=Path, required=True, help=f"folder with the Zenodo {ARCHIVE}")
    c.add_argument("--dat", type=Path, required=True, help="the app session's frame_NNNN.dat files")
    c.add_argument("--taps", default=",".join(f"{v:g}" for v in TAPS),
                   help="load point x, top y, bottom y in reference pixels (the session's loadPoint)")
    c.add_argument("--windows", default="", help="strain windows to recompute strain at, e.g. 15,45,95")
    args = parser.parse_args()
    if args.cmd == "prep":
        prep(args.data, args.out)
    else:
        x0, top, bottom = (float(v) for v in args.taps.split(","))
        windows = [int(v) for v in args.windows.split(",") if v]
        compare(args.data, args.dat, (x0, top, bottom), windows)


if __name__ == "__main__":
    main()
