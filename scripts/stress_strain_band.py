#!/usr/bin/env python3
"""Prototype: a tensile stress-strain curve with a strain-variance band.

The app's tensile curve plots stress (load / area) against the mean axial
strain of each frame's accepted points (StressStrain.axisStrainMilli). This
script draws the same curve from a session's files and shades, around it,
mean +/- K standard deviations of that axial strain over the same points
(K = 3 by default). The band runs left-right, along the strain axis, at each
frame's stress.

The SD is the spread of strain across the specimen, not the uncertainty of
the mean (that is SD / sqrt(n), with thousands of points, far narrower). It
holds measurement noise and any real non-uniformity, such as a neck forming,
so a band that widens late in the test is expected.

Inputs are the ones the app writes and reads:
  --dat    the session's frame_NNNN.dat files (8 float32 per point:
           x y u v exx eyy exy znssd; accepted when 0 <= ZNSSD <= 0.15,
           as DicResult.isAcceptedPoint)
  --loads  the load CSV given to the app: a header, then one row per
           deformed frame, the load in the last column; kN when the header
           says (kN), N otherwise

Needs numpy and matplotlib. Run by hand, never in CI.

Usage:
  python scripts/stress_strain_band.py --dat DIR --loads CSV --area MM2
      [--axis x|y] [--k 3] [--zoom MILLI] [--out band.png]
      [--table band.csv]
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# Mirrors DicResult: 8 float32 per point; strain columns and acceptance.
STRIDE, IDX_EXX, IDX_EYY, IDX_ZNSSD = 8, 4, 5, 7
MAX_ZNSSD = 0.15
STRAIN_TO_MILLI = 1e3
BAND, WIDTH = "#0288d1", "#d64545"


@dataclass
class FrameStrain:
    frame: int
    load_n: float
    stress_mpa: float
    mean_milli: float
    sd_milli: float
    points: int


def read_loads(path: Path) -> list[float]:
    """Loads in N, one per deformed frame, from the app's load CSV."""
    with path.open(newline="", encoding="utf-8-sig") as f:
        rows = [r for r in csv.reader(f) if r and any(c.strip() for c in r)]
    header, body = rows[0], rows[1:]
    scale = 1000.0 if "kn" in header[-1].lower() else 1.0
    return [float(r[-1]) * scale for r in body]


def axial_strain(dat: Path, axis: str) -> np.ndarray:
    """Axial strain of the accepted points of one frame, in millistrain."""
    d = np.fromfile(dat, "<f4").reshape(-1, STRIDE)
    z = d[:, IDX_ZNSSD]
    col = IDX_EXX if axis == "x" else IDX_EYY
    e = d[(z >= 0) & (z <= MAX_ZNSSD), col].astype(np.float64)
    return e[np.isfinite(e)] * STRAIN_TO_MILLI


def frame_strains(
    dat_dir: Path, loads_n: list[float], area_mm2: float, axis: str
) -> list[FrameStrain]:
    rows = []
    for k, load in enumerate(loads_n):
        path = dat_dir / f"frame_{k:04d}.dat"
        if not path.exists() or not np.isfinite(load):
            continue
        e = axial_strain(path, axis)
        if e.size < 2:
            continue
        rows.append(
            FrameStrain(
                k,
                load,
                load / area_mm2,
                float(e.mean()),
                float(e.std(ddof=1)),
                int(e.size),
            )
        )
    return rows


def draw_band(ax, rows: list[FrameStrain], k: float, legend: bool) -> None:
    """The curve, and the band as a strip joined frame to frame.

    Drawn as one polygon in frame order, not by stress, so it follows the
    curve past the peak, where stress falls. Each frame's own range is an
    error bar, which stays readable where the curve runs flat.
    """
    stress = np.array([r.stress_mpa for r in rows])
    mean = np.array([r.mean_milli for r in rows])
    half = k * np.array([r.sd_milli for r in rows])
    ax.fill(
        np.r_[mean - half, (mean + half)[::-1]],
        np.r_[stress, stress[::-1]],
        color=BAND,
        alpha=0.2,
        lw=0,
        label=f"mean ± {k:g} SD of axial strain",
    )
    ax.errorbar(
        mean, stress, xerr=half, fmt="none", ecolor=BAND, elinewidth=0.8, alpha=0.7
    )
    # Led by the unloaded reference at the origin, as the app draws it.
    ax.plot(
        np.r_[0.0, mean],
        np.r_[0.0, stress],
        "-o",
        color=BAND,
        ms=3,
        lw=1.4,
        label="mean strain (app curve)",
    )
    ax.set_xlabel("Strain (mε)")
    ax.set_ylabel("Stress (MPa)")
    ax.grid(alpha=0.3)
    if legend:
        ax.legend(loc="lower right")


def zoom_rows(rows: list[FrameStrain], zoom_milli: float | None) -> list[FrameStrain]:
    """The early frames, up to zoom_milli of mean strain (default 1% of the largest)."""
    limit = (
        zoom_milli
        if zoom_milli is not None
        else 0.01 * max(abs(r.mean_milli) for r in rows)
    )
    return [r for r in rows if abs(r.mean_milli) <= limit]


def plot(
    rows: list[FrameStrain], k: float, title: str, zoom_milli: float | None, out: Path
) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    early = zoom_rows(rows, zoom_milli)
    fig, (ax, ax_zoom, ax_sd) = plt.subplots(
        1,
        3,
        figsize=(16, 5),
        gridspec_kw={"width_ratios": [5, 4, 3]},
        constrained_layout=True,
    )

    draw_band(ax, rows, k, legend=True)
    ax.set_title(title)

    if len(early) >= 2:
        draw_band(ax_zoom, early, k, legend=False)
        ax_zoom.set_title(
            f"First {len(early)} frames (mean ≤ {max(abs(r.mean_milli) for r in early):.2f} mε)"
        )
    else:
        ax_zoom.set_axis_off()

    frames = [r.frame + 1 for r in rows]
    ax_sd.plot(frames, [k * r.sd_milli for r in rows], "-o", color=WIDTH, ms=3, lw=1.2)
    ax_sd.set_yscale("log")
    ax_sd.set_xlabel("Frame")
    ax_sd.set_ylabel(f"Band half-width, {k:g} SD (mε)")
    ax_sd.set_title("Band width by frame")
    ax_sd.grid(alpha=0.3, which="both")

    fig.savefig(out, dpi=150)
    plt.close(fig)


def write_table(rows: list[FrameStrain], k: float, out: Path) -> None:
    with out.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(
            [
                "frame",
                "load_N",
                "stress_MPa",
                "mean_strain_milli",
                "sd_strain_milli",
                f"band_low_milli_{k:g}sd",
                f"band_high_milli_{k:g}sd",
                "points",
            ]
        )
        for r in rows:
            w.writerow(
                [
                    r.frame + 1,
                    f"{r.load_n:.2f}",
                    f"{r.stress_mpa:.3f}",
                    f"{r.mean_milli:.5f}",
                    f"{r.sd_milli:.5f}",
                    f"{r.mean_milli - k * r.sd_milli:.5f}",
                    f"{r.mean_milli + k * r.sd_milli:.5f}",
                    r.points,
                ]
            )


def main() -> None:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument(
        "--dat",
        type=Path,
        required=True,
        help="folder with the session's frame_NNNN.dat files",
    )
    p.add_argument(
        "--loads", type=Path, required=True, help="the load CSV given to the app"
    )
    p.add_argument("--area", type=float, required=True, help="cross-section area, mm²")
    p.add_argument(
        "--axis",
        choices=("x", "y"),
        default="x",
        help="load axis: x reads Exx, y reads Eyy",
    )
    p.add_argument(
        "--k",
        type=float,
        default=3.0,
        help="band half-width in standard deviations (default 3)",
    )
    p.add_argument(
        "--zoom",
        type=float,
        help="middle panel: frames up to this mean strain, mε (default 1%% of the largest)",
    )
    p.add_argument("--title", default="Tensile stress–strain with strain spread")
    p.add_argument("--out", type=Path, default=Path("stress_strain_band.png"))
    p.add_argument(
        "--table", type=Path, help="also write the per-frame numbers to this CSV"
    )
    a = p.parse_args()

    rows = frame_strains(a.dat, read_loads(a.loads), a.area, a.axis)
    if not rows:
        raise SystemExit("no frame had a load and accepted points")
    plot(rows, a.k, a.title, a.zoom, a.out)
    if a.table:
        write_table(rows, a.k, a.table)

    print(
        f"{len(rows)} frames; band = mean +/- {a.k:g} SD of E{a.axis}{a.axis} over accepted points"
    )
    print("frame  stress MPa  mean mstr   SD mstr   points")
    for r in rows:
        print(
            f"{r.frame + 1:5d} {r.stress_mpa:11.1f} {r.mean_milli:9.4f} {r.sd_milli:9.4f} {r.points:8d}"
        )
    print(f"wrote {a.out}" + (f" and {a.table}" if a.table else ""))


if __name__ == "__main__":
    main()
