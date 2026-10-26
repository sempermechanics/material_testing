"""The `truth` steps of `scripts/real_data_*.py`: the ground truth the bundled
example sets ship with (ADR-031).

The scripts read the authors' published files, which are not in the repo, so
these tests feed the pieces that turn them into rows small hand-made inputs.
The scripts need numpy; without it (CI's stdlib-only step) they skip.
"""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

try:
    import numpy as np
except ImportError:  # CI's source-check step installs nothing
    np = None

_SCRIPTS = Path(__file__).resolve().parents[1]


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, _SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipIf(np is None, "numpy not installed")
class SteelTruthTest(unittest.TestCase):

    def setUp(self):
        self.steel = _load("real_data_steel_tensile")

    def test_one_row_per_deformed_frame_with_stress_from_the_area(self):
        ref = {step: (1000.0 * step, 0.01 * step) for step in self.steel.STEPS}
        rows = self.steel.truth_rows(ref)
        self.assertEqual("Frame,Step,Load (kN),Stress (MPa),Gauge strain (1e-3)", rows[0])
        self.assertEqual(len(self.steel.STEPS) - 1, len(rows) - 1)
        # Frame 1 is step 3: 3 kN on 12.5 mm² is 240 MPa.
        self.assertEqual("1,3,3.0000,240.000,0.03000", rows[1])

    def test_truth_reads_the_gauge_file_and_writes_the_csv(self):
        lines = ["header", "units", "more"]
        for step in range(0, 751):
            cells = ["0"] * 18
            cells[0], cells[1] = str(step), str(step / 100)  # step, force kN
            cells[2], cells[10] = "60", "0"  # the gauge points' x: 60 mm apart
            cells[6], cells[14] = str(step * 0.0006), "0"  # the far point moves
            lines.append(";".join(cells))
        with tempfile.TemporaryDirectory() as tmp:
            data, out = Path(tmp, "data"), Path(tmp, "out")
            data.mkdir()
            (data / self.steel.GAUGE_FILE).write_text("\n".join(lines), encoding="latin-1")
            self.steel.truth(data, out)
            rows = (out / "truth_gauge_strain.csv").read_text().splitlines()
        self.assertEqual(41, len(rows))
        self.assertTrue(rows[-1].startswith("40,705,"))


@unittest.skipIf(np is None, "numpy not installed")
class PmmaTruthTest(unittest.TestCase):

    def setUp(self):
        self.pmma = _load("real_data_pmma_bending")

    def test_the_probe_is_centred_between_the_taps_with_half_the_thickness(self):
        self.assertEqual((1170.0, 325.0, 286.0), self.pmma.probe_circle((1170.0, 39.0, 611.0)))
        # Taps too close: the radius does not go below the floor.
        self.assertEqual(self.pmma.MIN_PROBE_RADIUS_PX, self.pmma.probe_circle((10.0, 50.0, 52.0))[2])

    def test_the_authors_deflection_averages_their_points_inside_the_circle(self):
        t = {
            "px": np.array([100.0, 104.0, 100.0, 300.0]),
            "py": np.array([50.0, 50.0, 57.0, 50.0]),
            "down_mm": np.array([1.0, 2.0, 3.0, 99.0]),
        }
        # Radius 8: the first three points are in, the one at x 300 is not.
        self.assertAlmostEqual(2.0, self.pmma.authors_delta_mm(t, (100.0, 50.0, 8.0)))


if __name__ == "__main__":
    unittest.main()
