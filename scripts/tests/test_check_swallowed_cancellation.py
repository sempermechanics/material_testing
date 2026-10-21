"""`scripts/check_swallowed_cancellation.py`: ADR-018's cancellation rule as a CI check (TD-171).

Each test copies Kotlin fixtures (`fixtures/cancellation/`) into a small
repository under a temporary directory and runs the check against it: a broad
catch or `runCatching` around a suspend call fails unless it rethrows
cancellation, is `suspendRunCatching`, or runs under `NonCancellable`. Stdlib
only: `python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SCRIPT = _HERE.parent / "check_swallowed_cancellation.py"
_FIXTURES = _HERE / "fixtures" / "cancellation"
_spec = importlib.util.spec_from_file_location("check_swallowed_cancellation", _SCRIPT)
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)

_PKG = "app/src/main/java/com/sempermechanics/semper"


class _Repo(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / _PKG).mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def place(self, fixture: str, rel: str) -> None:
        target = self.root / _PKG / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(_FIXTURES / fixture, target)

    def write(self, rel: str, text: str) -> None:
        target = self.root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def allow(self, body: str) -> None:
        self.write(str(check.ALLOWLIST), "# header\n" + body)

    def failures(self) -> list[str]:
        return check.check(self.root)[0]


class FlaggedTest(_Repo):
    def test_each_way_of_swallowing_cancellation_fails(self) -> None:
        self.place("SwallowedCancellation.kt", "data/AccountLoader.kt")
        failures = self.failures()
        self.assertEqual(len(failures), 3, failures)
        self.assertIn("data/AccountLoader.kt:8: `runCatching` around the suspend call `fetchMe`", failures[0])
        self.assertIn("in `load`", failures[0])
        self.assertIn("data/AccountLoader.kt:14: `catch Exception`", failures[1])
        self.assertIn("in `loadOrNull`", failures[1])
        self.assertIn("data/AccountLoader.kt:24: `catch Throwable` around the suspend call `delay`", failures[2])

    def test_a_suspend_lambda_parameter_counts(self) -> None:
        self.write(
            f"{_PKG}/data/Flight.kt",
            "class Flight {\n"
            "    fun start(scope: CoroutineScope, op: suspend () -> Unit) {\n"
            "        scope.launch { runCatching { op() } }\n"
            "    }\n"
            "}\n",
        )
        failures = self.failures()
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("`runCatching` around the suspend call `op`", failures[0])

    def test_a_private_suspend_fun_counts_only_in_its_file(self) -> None:
        self.write(
            f"{_PKG}/a/A.kt",
            "class A {\n"
            "    private suspend fun ping() = delay(1)\n"
            "    suspend fun go() = runCatching { ping() }\n"
            "}\n",
        )
        self.write(f"{_PKG}/b/B.kt", "class B {\n    fun go() = runCatching { other.ping() }\n}\n")
        failures = self.failures()
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("a/A.kt:3:", failures[0])


class PassingTest(_Repo):
    def test_every_handled_form_passes(self) -> None:
        self.place("HandledCancellation.kt", "data/HandledLoader.kt")
        self.assertEqual(self.failures(), [])

    def test_an_ambiguous_name_counts_only_where_it_can_suspend(self) -> None:
        self.place("AmbiguousNames.kt", "ui/Screen.kt")
        self.place("PlainWriter.kt", "data/DicFieldIo.kt")
        failures = self.failures()
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("ui/Screen.kt:24: `runCatching` around the suspend call `write`", failures[0])
        self.assertIn("in `save`", failures[0])


class AllowlistTest(_Repo):
    def setUp(self) -> None:
        super().setUp()
        self.place("SwallowedCancellation.kt", "data/AccountLoader.kt")

    def test_listed_findings_pass(self) -> None:
        self.allow(
            "data/AccountLoader.kt | load | runCatching | fetchMe | never-cancelled scope (ADR-018)\n"
            "data/AccountLoader.kt | loadOrNull | catch Exception | fetchMe | reason (TD-171)\n"
            "data/AccountLoader.kt | waitThenLoad | catch Throwable | delay | reason (TD-171)\n"
        )
        failures, _, covered = check.check(self.root)
        self.assertEqual(failures, [])
        self.assertEqual(covered, 3)

    def test_an_entry_that_matches_nothing_must_go(self) -> None:
        self.allow(
            "data/AccountLoader.kt | load | runCatching | fetchMe | reason (TD-171)\n"
            "data/AccountLoader.kt | loadOrNull | catch Exception | fetchMe | reason (TD-171)\n"
            "data/AccountLoader.kt | waitThenLoad | catch Throwable | delay | reason (TD-171)\n"
            "data/Gone.kt | gone | runCatching | fetchMe | reason (TD-171)\n"
        )
        failures = self.failures()
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("data/Gone.kt | gone | runCatching | fetchMe matches nothing", failures[0])

    def test_an_entry_needs_a_pointer_and_five_fields(self) -> None:
        self.allow(
            "data/AccountLoader.kt | load | runCatching | fetchMe | no pointer here\n"
            "data/AccountLoader.kt | loadOrNull | catch Exception\n"
        )
        failures = self.failures()
        self.assertTrue(any("names no TD- or ADR- pointer" in f for f in failures), failures)
        self.assertTrue(any("expected `path | function | construct | call | reason`" in f for f in failures))

    def test_a_reason_may_hold_a_pipe(self) -> None:
        entries, problems = check.read_allowlist(self.root / check.ALLOWLIST)
        self.assertEqual((entries, problems), ([], []))
        self.allow("a.kt | f | runCatching | g | one | two (TD-171)\n")
        entries, problems = check.read_allowlist(self.root / check.ALLOWLIST)
        self.assertEqual(problems, [])
        self.assertEqual(entries[0].reason, "one | two (TD-171)")


class CommandLineTest(unittest.TestCase):
    def test_the_real_tree_passes(self) -> None:
        repo = _SCRIPT.parents[1]
        if not (repo / _PKG).is_dir():
            self.skipTest("app sources not present")
        result = subprocess.run(
            [sys.executable, "-I", str(_SCRIPT), "--root", str(repo)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_violation_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / _PKG / "data/AccountLoader.kt"
            target.parent.mkdir(parents=True)
            shutil.copyfile(_FIXTURES / "SwallowedCancellation.kt", target)
            result = subprocess.run(
                [sys.executable, "-I", str(_SCRIPT), "--root", tmp],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("swallows CancellationException", result.stderr)


if __name__ == "__main__":
    unittest.main()
