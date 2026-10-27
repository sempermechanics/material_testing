"""`scripts/check_framework_coverage.py`: a status count written in the gate's
cross-check section must match its table (TE6-012: the narrative said
"PASS 11, ... GAP 10" for days after row 1.3 moved to PASS).

The coverage check's other rules are tested in
backend/tests/test_script_check_framework_coverage.py. Stdlib only:
`python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = _ROOT / "scripts" / "check_framework_coverage.py"
_spec = importlib.util.spec_from_file_location("check_framework_coverage_counts", _SCRIPT)
cov = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = cov
_spec.loader.exec_module(cov)


def _repo(root: Path, prose: str) -> Path:
    """Items 1.1 (PASS) and 1.2 (GAP, owned by TD-61); every Part B-E key PASS."""
    ops = root / "docs" / "ops"
    ops.mkdir(parents=True)
    markers = "\n".join(cov.PART_KEYS.values())
    (ops / "READINESS_FRAMEWORK_2026-10.md").write_text(
        f"# F\n\n| # | Item |\n|---|---|\n| 1.1 | a |\n| 1.2 | b |\n\n{markers}\n", encoding="utf-8")
    rows = ["| 1.1 | r | PASS | e | — |", "| 1.2 | r | GAP | e | TD-61 |"]
    rows += [f"| {k} | r | PASS | e | — |" for k in cov.PART_KEYS]
    (ops / "PRODUCTION_READINESS_GATE.md").write_text(
        "# Gate\n\nPASS 1, PARTIAL 0, GAP 9, EXTERNAL 0, N-A 0 (outside the section)\n\n"
        "## Framework cross-check\n\n" + prose + "\n\n| Item | Req | Status | Evidence | Owner |\n|---|---|---|---|---|\n"
        + "\n".join(rows) + "\n\n## Risk acceptance\n", encoding="utf-8")
    (ops / "TECH_DEBT.md").write_text("| ID | Item |\n|---|---|\n| TD-61 | x |\n", encoding="utf-8")
    (ops / "FUTURE_IMPROVEMENTS.md").write_text("# Future\n", encoding="utf-8")
    return root


class CountTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.passes = 1 + len(cov.PART_KEYS)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_a_matching_count_passes(self):
        line = f"Count: PASS {self.passes}, PARTIAL 0, GAP 1, EXTERNAL 0, N-A 0."
        errors, _ = cov.check(_repo(self.root, line))
        self.assertEqual(errors, [])

    def test_a_stale_count_fails_and_names_the_true_one(self):
        line = f"the count is now PASS {self.passes - 1}, PARTIAL 0, GAP 2, EXTERNAL 0,\nN-A 0, after the move."
        errors, _ = cov.check(_repo(self.root, line))
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("count", errors[0])
        self.assertIn(f"PASS {self.passes - 1}, PARTIAL 0, GAP 2", errors[0])
        self.assertIn(f"table has PASS {self.passes}, PARTIAL 0, GAP 1", errors[0])

    def test_every_count_in_the_section_is_checked(self):
        good = f"PASS {self.passes}, PARTIAL 0, GAP 1, EXTERNAL 0, N-A 0"
        bad = f"PASS {self.passes}, PARTIAL 0, GAP 1, EXTERNAL 1, N-A 0"
        errors, _ = cov.check(_repo(self.root, f"{good}.\n\nLater: {bad}."))
        self.assertEqual(len(errors), 1, errors)


class RealGateTest(unittest.TestCase):
    def test_the_gate_states_its_count_and_it_matches(self):
        gate = (_ROOT / cov.GATE).read_text(encoding="utf-8")
        section = gate[gate.index(cov.SECTION):]
        section = section[:section.index("\n## ", 1)]
        self.assertRegex(section, r"\*\*Count:\*\* PASS \d+, PARTIAL \d+, GAP \d+, EXTERNAL \d+, N-A \d+")
        errors, _ = cov.check(_ROOT)
        self.assertEqual([e for e in errors if "count" in e], [])


if __name__ == "__main__":
    unittest.main()
