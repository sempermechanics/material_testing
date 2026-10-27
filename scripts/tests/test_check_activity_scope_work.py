"""`scripts/check_activity_scope_work.py`: ADR-016 rule 1 as a CI check (TD-228).

Each test copies Kotlin fixtures (`fixtures/activity_scope/`) into a small
repository under a temporary directory and runs the check against it. The
acceptance TD-228 set: a restore enqueued from an Activity's `lifecycleScope`
fails, and the same restore from `viewModelScope` passes. Stdlib only:
`python -m unittest discover -s scripts/tests`.
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
_SCRIPT = _HERE.parent / "check_activity_scope_work.py"
_FIXTURES = _HERE / "fixtures" / "activity_scope"
_spec = importlib.util.spec_from_file_location("check_activity_scope_work", _SCRIPT)
scope = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scope)

_PKG = "app/src/main/java/com/sempermechanics/semper"


class _Repo(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / _PKG / "ui").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def place(self, fixture: str, rel: str) -> None:
        target = self.root / _PKG / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(_FIXTURES / fixture, target)

    def write(self, rel: str, text: str) -> None:
        target = self.root / _PKG / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def allow(self, body: str) -> None:
        path = self.root / scope.ALLOWLIST
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# header\n" + body, encoding="utf-8")

    def failures(self) -> list[str]:
        return scope.check(self.root)[0]


class AcceptanceTest(_Repo):
    def test_a_restore_enqueued_from_lifecycle_scope_fails(self) -> None:
        self.place("RestoreFromLifecycleScopeActivity.kt", "ui/home/HomeActivity.kt")
        failures = self.failures()
        self.assertEqual(len(failures), 2, failures)
        self.assertIn("ui/home/HomeActivity.kt:17: `RestoreStart.startAll`", failures[0])
        self.assertIn("in `queueRestores`", failures[0])
        # The network lookup is in a function of the same file the block calls.
        self.assertIn("ui/home/HomeActivity.kt:23: `CloudSync.resolveCloudIdFor`", failures[1])

    def test_the_same_restore_from_view_model_scope_passes(self) -> None:
        self.place("RestoreFromViewModel.kt", "ui/home/HomeViewModel.kt")
        self.assertEqual(self.failures(), [])


class LaunchSiteTest(_Repo):
    def test_a_lambda_handed_to_a_launcher_runs_on_its_scope(self) -> None:
        self.place("SignInThroughRunAuthActivity.kt", "ui/auth/AuthActivity.kt")
        self.place("SignInTotpPart.kt", "ui/auth/AuthTotp.kt")
        failures = self.failures()
        self.assertEqual(len(failures), 2, failures)
        self.assertIn("ui/auth/AuthActivity.kt:9: `signInWithPassword`", failures[0])
        self.assertIn("through `runAuth`, in `onMainAction`", failures[0])
        self.assertIn("ui/auth/AuthTotp.kt:7: `completeTotpChallenge`", failures[1])

    def test_a_screen_part_launching_on_the_activitys_scope_counts(self) -> None:
        self.place("SettingsPartSection.kt", "ui/settings/SettingsPartSection.kt")
        calls = [f.split("`")[1] for f in self.failures()]
        self.assertEqual(
            calls, ["api.listSessions", "enqueueUniqueWork", "oneTimeWork", "api.authed"],
        )

    def test_not_a_screen_nor_a_namesake_of_a_launcher(self) -> None:
        self.place("NotAScreen.kt", "ui/common/NotAScreen.kt")
        self.assertEqual(self.failures(), [])

    def test_files_outside_ui_are_not_read(self) -> None:
        self.place("RestoreFromLifecycleScopeActivity.kt", "data/HomeActivity.kt")
        self.assertEqual(self.failures(), [])

    def test_a_fragment_counts_as_a_screen(self) -> None:
        self.write(
            "ui/x/ListFragment.kt",
            "class ListFragment : Fragment() {\n"
            "    fun go() { lifecycleScope.launch { SessionDeletes.enqueue(ctx, items) } }\n}\n",
        )
        [failure] = self.failures()
        self.assertIn("ui/x/ListFragment.kt:2: `SessionDeletes.enqueue`", failure)

    def test_a_constructor_parameter_of_activity_type_is_not_a_supertype(self) -> None:
        self.write(
            "ui/x/Helper.kt",
            "class Helper(private val activity: HomeActivity) : Base() {\n"
            "    fun go() { lifecycleScope.launch { CloudSync.reconcile(ctx) } }\n}\n",
        )
        self.assertEqual(self.failures(), [])

    def test_phone_only_members_of_a_client_pass(self) -> None:
        self.write(
            "ui/x/LocalActivity.kt",
            "class LocalActivity : AppCompatActivity() {\n"
            "    fun go() { lifecycleScope.launch {\n"
            "        SessionDeletes.cancel(this@LocalActivity, id)\n"
            "        CloudRestore.cancelBundleDownload(this@LocalActivity, id)\n"
            "        CloudSync.uploadsEnabled(this@LocalActivity)\n"
            "    } }\n}\n",
        )
        self.assertEqual(self.failures(), [])


class AllowlistTest(_Repo):
    def setUp(self) -> None:
        super().setUp()
        self.place("RestoreFromLifecycleScopeActivity.kt", "ui/home/HomeActivity.kt")

    def test_listed_findings_pass(self) -> None:
        self.allow(
            "ui/home/HomeActivity.kt | queueRestores | RestoreStart.startAll | test, TD-228\n"
            "ui/home/HomeActivity.kt | queueRestores | CloudSync.resolveCloudIdFor | test, ADR-016\n"
        )
        self.assertEqual(self.failures(), [])
        self.assertEqual(scope.check(self.root)[2], 2)

    def test_an_entry_that_matches_nothing_must_go(self) -> None:
        self.allow(
            "ui/home/HomeActivity.kt | queueRestores | RestoreStart.startAll | test, TD-228\n"
            "ui/home/HomeActivity.kt | queueRestores | CloudSync.resolveCloudIdFor | test, ADR-016\n"
            "ui/home/HomeActivity.kt | elsewhere | CloudSync.reconcile | test, TD-228\n"
        )
        [failure] = self.failures()
        self.assertIn("elsewhere | CloudSync.reconcile matches nothing", failure)

    def test_an_entry_needs_a_pointer_and_four_fields(self) -> None:
        self.allow(
            "ui/home/HomeActivity.kt | queueRestores | RestoreStart.startAll | because\n"
            "ui/home/HomeActivity.kt | queueRestores | CloudSync.resolveCloudIdFor\n"
        )
        failures = self.failures()
        self.assertTrue(any("names no TD- or ADR- pointer" in f for f in failures), failures)
        self.assertTrue(any("expected `path | function | call | reason`" in f for f in failures))


class StripTest(unittest.TestCase):
    def test_comments_and_strings_do_not_count(self) -> None:
        code = scope.strip_code('x("CloudSync.reconcile(") // enqueue(\n/* api.listSessions( */ y\n')
        self.assertEqual(scope.calls_in(code, 0, len(code)), [])
        self.assertEqual(code.count("\n"), 2)


class CommandLineTest(unittest.TestCase):
    def test_the_real_tree_passes(self) -> None:
        repo = _SCRIPT.parents[1]
        if not (repo / _PKG / "ui").is_dir():
            self.skipTest("app sources not present")
        result = subprocess.run(
            [sys.executable, "-I", str(_SCRIPT), "--root", str(repo)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_violation_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / _PKG / "ui/home/HomeActivity.kt"
            target.parent.mkdir(parents=True)
            shutil.copyfile(_FIXTURES / "RestoreFromLifecycleScopeActivity.kt", target)
            result = subprocess.run(
                [sys.executable, "-I", str(_SCRIPT), "--root", tmp],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("RestoreStart.startAll", result.stderr)


if __name__ == "__main__":
    unittest.main()
