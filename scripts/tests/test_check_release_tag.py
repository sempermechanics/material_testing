"""`scripts/check_release_tag.py`: the released tag names the commit the
Release workflow built and signed (TE6-008).

Each test builds a small git repository with tags under a temporary directory.
Stdlib only (and git): `python -m unittest discover -s scripts/tests`.
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
_SCRIPT = _ROOT / "scripts" / "check_release_tag.py"
_spec = importlib.util.spec_from_file_location("check_release_tag", _SCRIPT)
tag = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tag)


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


class _Repo(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        _git(self.repo, "init", "-q")
        for key, value in (("user.email", "t@example.test"), ("user.name", "t"), ("commit.gpgsign", "false"),
                           ("tag.gpgsign", "false")):
            _git(self.repo, "config", key, value)
        self.first = self._commit("one")
        _git(self.repo, "tag", "-a", "v1.2-beta.4", "-m", "annotated")
        _git(self.repo, "tag", "v1.2-beta.3")  # lightweight
        self.head = self._commit("two")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _commit(self, name: str) -> str:
        (self.repo / name).write_text(name, encoding="utf-8")
        _git(self.repo, "add", name)
        _git(self.repo, "commit", "-q", "-m", name)
        return _git(self.repo, "rev-parse", "HEAD")


class CheckTest(_Repo):
    def test_a_tag_on_another_commit_fails(self):
        ok, why = tag.check("v1.2-beta.4", self.head, repo=self.repo)
        self.assertFalse(ok)
        self.assertIn(self.first[:12], why)
        ok, _ = tag.check("v1.2-beta.3", self.head, repo=self.repo)
        self.assertFalse(ok, "a lightweight tag is checked too")

    def test_a_tag_on_the_built_commit_passes(self):
        ok, why = tag.check("v1.2-beta.4", self.first, repo=self.repo)
        self.assertTrue(ok, why)

    def test_a_new_tag_is_left_to_publish(self):
        ok, why = tag.check("v1.2-beta.5", self.head, repo=self.repo)
        self.assertTrue(ok, why)
        self.assertIn("target_commitish", why)

    def test_the_version_format_is_checked_first(self):
        for bad in ("1.2-beta.5", "v1.2-beta", "v1.2-rc.1", "v1.2-beta.5; rm -rf /", "v1.2-beta.5\n", "", "v1"):
            ok, why = tag.check(bad, self.head, repo=self.repo)
            self.assertFalse(ok, bad)
            self.assertIn("not a release version", why)
        for good in ("v1.2", "v1.2.3", "v1.0-beta.1", "v10.20.30-beta.40"):
            self.assertTrue(tag.VERSION_RE.fullmatch(good), good)

    def test_main_exit_codes(self):
        def run(*args):
            return subprocess.run([sys.executable, str(_SCRIPT), *args, "--repo", str(self.repo)],
                                  capture_output=True, text=True, check=False)
        self.assertEqual(run("--version", "v1.2-beta.4", "--sha", self.first).returncode, 0)
        bad = run("--version", "v1.2-beta.4", "--sha", self.head)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("v1.2-beta.4", bad.stderr)


class WorkflowTest(unittest.TestCase):
    def test_release_checks_the_tag_before_signing_and_publishes_at_the_sha(self):
        release = (_ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        job = release[release.index("\n  verify-tag:"):]
        job = job[:job.index("\n  verify-ci:")] if "\n  verify-ci:" in job else job
        self.assertIn("fetch-depth: 0", job)
        self.assertIn("scripts/check_release_tag.py", job)
        self.assertIn('--sha "$GITHUB_SHA"', job)
        needs = re.search(r"\n  build-release:.*?\n    needs: \[([^\]]*)\]", release, re.DOTALL).group(1)
        self.assertIn("verify-tag", [n.strip() for n in needs.split(",")])
        publish = release[release.index("\n  publish:"):]
        self.assertIn("target_commitish: ${{ github.sha }}", publish)


if __name__ == "__main__":
    unittest.main()
