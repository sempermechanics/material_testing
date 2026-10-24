"""`scripts/check_release_ci.py`: the Release workflow signs only a commit whose
full-matrix CI passed (TE6-009).

The check reads the check runs GitHub lists for the commit (`gh api --paginate
--slurp`), so each test hands it a JSON file shaped like that. Stdlib only:
`python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = _ROOT / "scripts" / "check_release_ci.py"
_spec = importlib.util.spec_from_file_location("check_release_ci", _SCRIPT)
ci = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ci)


def _run(name: str, conclusion: str, run_id: int, status: str = "completed") -> dict:
    return {"id": run_id, "name": name, "status": status, "conclusion": conclusion}


def _all_green(start: int = 100) -> list[dict]:
    return [_run(name, "success", start + k) for k, name in enumerate(ci.REQUIRED)]


def _pages(*runs: dict, per_page: int = 4) -> list[dict]:
    """`gh api --paginate --slurp` output: one object per page."""
    runs = list(runs)
    return [{"total_count": len(runs), "check_runs": runs[k:k + per_page]}
            for k in range(0, len(runs), per_page)] or [{"total_count": 0, "check_runs": []}]


class EvaluateTest(unittest.TestCase):
    def test_a_full_matrix_pass_is_accepted(self):
        self.assertEqual(ci.evaluate(_pages(*_all_green())), [])

    def test_a_path_filtered_run_is_not_enough(self):
        """CI OK passes when the emulator tier is skipped; a release needs it run."""
        runs = [r if r["name"] != "Tier 3 — Emulator E2E" else _run(r["name"], "skipped", r["id"])
                for r in _all_green()]
        problems = ci.evaluate(_pages(*runs))
        self.assertEqual(len(problems), 1)
        self.assertIn("Tier 3 — Emulator E2E", problems[0])
        self.assertIn("skipped", problems[0])

    def test_a_missing_or_failed_job_is_named(self):
        runs = [r for r in _all_green() if r["name"] != "Secret scan (full history)"]
        runs = [r if r["name"] != "Firestore rules" else _run(r["name"], "failure", r["id"]) for r in runs]
        problems = "\n".join(ci.evaluate(_pages(*runs)))
        self.assertIn("Secret scan (full history): no check run", problems)
        self.assertIn("Firestore rules: failure", problems)

    def test_the_latest_run_of_a_job_decides(self):
        """A push run skipped Tier 3; the later full_ci dispatch ran it. And a
        rerun that failed after a pass is a failure."""
        runs = _all_green(200) + [_run("Tier 3 — Emulator E2E", "skipped", 10)]
        self.assertEqual(ci.evaluate(_pages(*runs)), [])
        runs = _all_green(200) + [_run("Console pages", "failure", 900)]
        self.assertEqual(ci.evaluate(_pages(*runs)), ["Console pages: failure (check run 900)"])

    def test_an_unfinished_run_is_not_a_pass(self):
        runs = _all_green() + [_run("CI OK", None, 999, status="in_progress")]
        self.assertEqual(ci.evaluate(_pages(*runs)), ["CI OK: in_progress (check run 999)"])

    def test_a_single_page_object_is_read_too(self):
        self.assertEqual(ci.evaluate({"check_runs": _all_green()}), [])


class MainTest(unittest.TestCase):
    def _main(self, payload) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "runs.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            return subprocess.run([sys.executable, str(_SCRIPT), "--runs", str(path), "--sha", "abc1234"],
                                  capture_output=True, text=True, check=False)

    def test_exit_codes(self):
        ok = self._main(_pages(*_all_green()))
        self.assertEqual(ok.returncode, 0, ok.stderr)
        self.assertIn("abc1234", ok.stdout)
        bad = self._main(_pages(*_all_green()[1:]))
        self.assertEqual(bad.returncode, 1)
        self.assertIn("full_ci", bad.stderr)


class WorkflowTest(unittest.TestCase):
    """The names the check requires are CI's job names, and the Release
    workflow runs the check before it signs anything."""

    def test_required_names_are_ci_job_names(self):
        ci_yml = (_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        names = set(re.findall(r"^    name: (.+)$", ci_yml, re.MULTILINE))
        for name in ci.REQUIRED:
            self.assertIn(name, names)

    def test_release_signs_only_after_the_ci_check(self):
        release = (_ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        job = release[release.index("\n  verify-ci:"):]
        job = job[:job.index("\n  build-release:")]
        self.assertIn("checks: read", job)
        self.assertIn("scripts/check_release_ci.py", job)
        self.assertIn("commits/${GITHUB_SHA}/check-runs", job)
        needs = re.search(r"\n  build-release:.*?\n    needs: \[([^\]]*)\]", release, re.DOTALL).group(1)
        self.assertIn("verify-ci", [n.strip() for n in needs.split(",")])


if __name__ == "__main__":
    unittest.main()
