"""`scripts/check_engine_pin.py`: the engine gitlink is a tagged engine release
and never moves backwards (TE2-006; MT-TE2-001 pinned an untagged commit older
than its previous pin).

Each test builds an engine repository (commits and v* tags) and a superproject
whose commits carry an `engine` gitlink, under a temporary directory, and runs
the check with `--engine-git` on that engine. Stdlib only (and git):
`python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import importlib.util
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = _ROOT / "scripts" / "check_engine_pin.py"
_spec = importlib.util.spec_from_file_location("check_engine_pin", _SCRIPT)
pin = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pin)


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


def _init(repo: Path) -> None:
    repo.mkdir(parents=True)
    _git(repo, "init", "-q")
    for key, value in (("user.email", "t@example.test"), ("user.name", "t"), ("commit.gpgsign", "false"),
                       ("tag.gpgsign", "false")):
        _git(repo, "config", key, value)


def _commit(repo: Path, name: str) -> str:
    (repo / name).write_text(name, encoding="utf-8")
    _git(repo, "add", name)
    _git(repo, "commit", "-q", "-m", name)
    return _git(repo, "rev-parse", "HEAD")


class _Repos(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.engine = root / "engine-src"
        _init(self.engine)
        self.v060 = _commit(self.engine, "a")
        _git(self.engine, "tag", "-a", "v0.6.0", "-m", "0.6.0")
        self.v061 = _commit(self.engine, "b")
        _git(self.engine, "tag", "v0.6.1")  # lightweight
        self.untagged = _commit(self.engine, "c")
        _git(self.engine, "checkout", "-q", "-b", "side", self.v060)
        self.side = _commit(self.engine, "side")
        _git(self.engine, "tag", "-a", "v0.7.0-side", "-m", "side")
        _git(self.engine, "checkout", "-q", "-")
        self.app = root / "app"
        _init(self.app)
        _commit(self.app, "README")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def pin_engine(self, sha: str) -> str:
        """Commit the superproject with `engine` pointing at [sha]."""
        _git(self.app, "update-index", "--add", "--cacheinfo", f"160000,{sha},engine")
        _git(self.app, "commit", "-q", "-m", f"engine {sha[:7]}")
        return _git(self.app, "rev-parse", "HEAD")

    def check(self, *extra: str) -> tuple[int, str]:
        proc = subprocess.run([sys.executable, str(_SCRIPT), "--repo", str(self.app),
                               "--engine-git", str(self.engine), *extra],
                              capture_output=True, text=True, check=False)
        return proc.returncode, proc.stdout + proc.stderr


class PinTest(_Repos):
    def test_a_forward_bump_to_a_tag_passes(self):
        self.pin_engine(self.v060)
        self.pin_engine(self.v061)
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn("v0.6.1", out)

    def test_an_untagged_pin_fails(self):
        self.pin_engine(self.v061)
        self.pin_engine(self.untagged)
        code, out = self.check()
        self.assertEqual(code, 1, out)
        self.assertIn("not a tagged engine release", out)

    def test_moving_backwards_fails_unless_allowed(self):
        self.pin_engine(self.v061)
        self.pin_engine(self.v060)
        code, out = self.check()
        self.assertEqual(code, 1, out)
        self.assertIn("moves backwards", out)
        self.assertIn("v0.6.1", out)
        code, out = self.check("--allow-downgrade")
        self.assertEqual(code, 0, out)
        self.assertIn("allowed", out)

    def test_a_pin_off_the_previous_line_fails(self):
        self.pin_engine(self.v061)
        self.pin_engine(self.side)
        code, out = self.check()
        self.assertEqual(code, 1, out)
        self.assertIn("not a descendant", out)

    def test_an_unchanged_or_first_pin_needs_only_the_tag(self):
        self.pin_engine(self.v061)
        code, out = self.check()  # the parent has no engine gitlink
        self.assertEqual(code, 0, out)
        self.assertIn("no engine pin at the base", out)
        _commit(self.app, "unrelated")
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertIn("unchanged", out)

    def test_an_explicit_base_is_compared(self):
        first = self.pin_engine(self.v061)
        _commit(self.app, "x")
        self.pin_engine(self.v060)
        code, out = self.check("--base", first)
        self.assertEqual(code, 1, out)
        self.assertIn("moves backwards", out)

    def test_no_gitlink_at_head_is_an_error(self):
        code, out = self.check()
        self.assertEqual(code, 2, out)
        self.assertIn("no engine gitlink", out)


class WorkflowTest(unittest.TestCase):
    def test_ci_runs_the_check_and_ci_ok_needs_it(self):
        ci = (_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        job = ci[ci.index("\n  engine-pin:"):]
        job = job[:re.search(r"\n  [a-z0-9-]+:\n", job[1:]).start() + 1]
        self.assertIn("fetch-depth: 2", job)
        self.assertIn("scripts/check_engine_pin.py", job)
        self.assertIn("engine-downgrade", job)
        ci_ok = ci[ci.index("\n  ci-ok:"):]
        self.assertIn("      - engine-pin\n", ci_ok)
        self.assertIn("${{ needs.engine-pin.result }}", ci_ok)


def _job(ci: str, name: str) -> str:
    job = ci[ci.index(f"\n  {name}:"):]
    return job[:re.search(r"\n  [a-z0-9-]+:\n", job[1:]).start() + 1]


class EngineHostJobTest(unittest.TestCase):
    """TE2-004: this repo's CI runs the engine's host suite under ASan+UBSan at
    the pinned commit, with system OpenCV (no Android OpenCV build)."""

    def setUp(self) -> None:
        self.ci = (_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")

    def test_the_job_builds_and_runs_the_sanitized_host_suite(self):
        job = _job(self.ci, "engine-host-sanitized")
        self.assertIn("needs: changes", job)
        self.assertIn("if: needs.changes.outputs.engine_host == 'true'", job)
        # Engine, Eigen and OpenCV, through the cached, retried checkout.
        self.assertIn("uses: ./.github/actions/checkout-submodules", job)
        self.assertIn("libopencv-dev", job)
        self.assertIn("working-directory: engine", job)
        for flag in ("-S tests", "-DDIC_REQUIRE_OPENCV=ON", "-DDIC_SANITIZER=address,undefined",
                     "-DCMAKE_BUILD_TYPE=Release"):
            self.assertIn(flag, job)
        self.assertIn("./build/tests/dic_tests", job)
        self.assertNotIn("gradlew", job)
        self.assertNotIn("continue-on-error", job)

    def test_engine_host_follows_the_emulator_tier_and_ci_yml(self):
        changes = _job(self.ci, "changes")
        self.assertIn("engine_host: ${{ steps.flags.outputs.engine_host }}", changes)
        self.assertIn('echo "engine_host=$engine_host"', changes)
        self.assertIn("engine_host=$emulator", changes)
        self.assertRegex(changes, r'FILTER_CI_WORKFLOW" = "true" \]; then engine_host=true')

    def test_ci_ok_needs_it(self):
        ci_ok = self.ci[self.ci.index("\n  ci-ok:"):]
        self.assertIn("      - engine-host-sanitized\n", ci_ok)
        self.assertIn("${{ needs.engine-host-sanitized.result }}", ci_ok)


if __name__ == "__main__":
    unittest.main()
