"""Synthetic bending-test video for checking the app end to end.

Writes a tripod video of a speckled beam's side face at mid-span that sinks by
the Experiment 5 dial readings as the hanger loads go on, plus the machine's
timed load log. The truth is the handwritten report's table, so every number
the app prints can be checked against it (docs/app/REAL_WORLD_VALIDATION.md).

    python scripts/synthetic_beam_video.py --out <folder> [--log-lag 2]

Needs numpy, scipy and imageio-ffmpeg.
"""

import argparse
from pathlib import Path

import imageio_ffmpeg
import numpy as np
from scipy.ndimage import gaussian_filter, map_coordinates

WIDTH, HEIGHT, FPS = 1280, 720, 10
SPAN_MM, THICKNESS_MM = 935.0, 6.38
THICKNESS_PX = 170.0
MM_PER_PX = THICKNESS_MM / THICKNESS_PX
TOP_PX = 250.0  # beam top edge in the reference frame
HANGER_KG = np.array([4.28, 5.24, 6.92, 8.68, 10.06, 11.92])
DIAL_MM = np.array([1.14, 1.46, 2.02, 2.63, 3.25, 3.88])
FIRST_LOAD_S, HOLD_S = 3.25, 4.0
DURATION_S = FIRST_LOAD_S + HOLD_S * len(HANGER_KG) + 1.75
G = 9.81


def state(t):
    """(load N, mid-span deflection mm) at time t of the recording."""
    k = min(int(np.floor((t - FIRST_LOAD_S) / HOLD_S)), len(HANGER_KG) - 1) if t >= FIRST_LOAD_S else -1
    return (0.0, 0.0) if k < 0 else (HANGER_KG[k] * G, DIAL_MM[k])


def speckle(rng, h, w):
    """Dark dots on a light face, about 5 px across."""
    canvas = np.zeros((h, w))
    n = int(h * w / 22)
    canvas[rng.integers(0, h, n), rng.integers(0, w, n)] = 1.0
    dots = gaussian_filter(canvas, 1.3)
    return gaussian_filter(0.92 - np.clip(dots * 3.2, 0, 0.8), 0.6)


class Scene:
    def __init__(self, seed):
        rng = np.random.default_rng(seed)
        self.noise = np.random.default_rng(seed + 1)
        self.beam = speckle(rng, int(THICKNESS_PX) + 40, WIDTH + 40)  # origin at (-20, -20)
        bg = gaussian_filter(rng.normal(0.18, 0.05, (HEIGHT, WIDTH)), 4)
        self.background = bg + np.linspace(0, 0.04, WIDTH)[None, :]
        self.yy, self.xx = np.mgrid[0:HEIGHT, 0:WIDTH].astype(float)
        self.cache = {}

    def frame(self, deflection_mm):
        if deflection_mm not in self.cache:
            self.cache[deflection_mm] = self._render(deflection_mm)
        img = self.cache[deflection_mm] + self.noise.normal(0, 0.006, (HEIGHT, WIDTH))
        return np.clip(img * 255, 0, 255).astype(np.uint8)

    def _render(self, mid_mm):
        # Elastic line of a centrally loaded, simply supported beam, relative to mid-span.
        s = np.abs((self.xx - WIDTH / 2) * MM_PER_PX)
        xp = SPAN_MM / 2 - s
        ratio = xp * (3 * SPAN_MM**2 - 4 * xp**2) / SPAN_MM**3
        slope = (3 * SPAN_MM**2 - 12 * xp**2) / SPAN_MM**3 * np.sign(self.xx - WIDTH / 2)
        y_mat = self.yy - mid_mm * ratio / MM_PER_PX
        x_mat = self.xx + (y_mat - (TOP_PX + THICKNESS_PX / 2)) * mid_mm * slope / SPAN_MM
        alpha = np.clip(np.minimum(y_mat - TOP_PX, TOP_PX + THICKNESS_PX - y_mat) + 0.5, 0, 1)
        tex = map_coordinates(self.beam, [y_mat - TOP_PX + 20, x_mat + 20], order=3, mode="nearest")
        return alpha * tex + (1 - alpha) * self.background


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--log-lag", type=float, default=2.0, help="seconds the load log starts after the video")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    scene = Scene(args.seed)
    # A keyframe every second, as a phone records: extraction seeks cost one GOP.
    writer = imageio_ffmpeg.write_frames(
        str(args.out / "beam_video.mp4"),
        (WIDTH, HEIGHT),
        fps=FPS,
        codec="libx264",
        pix_fmt_in="gray",
        pix_fmt_out="yuv420p",
        output_params=["-crf", "12", "-preset", "slow", "-g", str(FPS)],
    )
    writer.send(None)
    frames = int(DURATION_S * FPS)
    for i in range(frames):
        writer.send(scene.frame(state(i / FPS)[1]).tobytes())
    writer.close()

    rows = ["Time (s),Load (N)"]
    rows += [f"{t:.1f},{state(t + args.log_lag)[0]:.2f}" for t in np.arange(0, DURATION_S - args.log_lag, 0.5)]
    (args.out / "beam_loads.csv").write_text("\n".join(rows) + "\n", encoding="ascii")
    print(f"{frames} frames, {DURATION_S:.2f} s, {MM_PER_PX:.4f} mm/px; log starts {args.log_lag:g} s late")


if __name__ == "__main__":
    main()
