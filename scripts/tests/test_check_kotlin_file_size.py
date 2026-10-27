"""`scripts/check_kotlin_file_size.py`: ADR-015's file-size budget (TD-229).

Each test builds a small repository under a temporary directory, with Kotlin
files of known length, and runs the check against it through `--root` (or its
`check` function). Stdlib only: `python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[1] / "check_kotlin_file_size.py"
_spec = importlib.util.spec_from_file_location("check_kotlin_file_size", _SCRIPT)
size = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(size)

_PKG = "app/src/main/java/com/sempermechanics/semper"


def _kotlin(lines: int, extra: str = "") -> str:
    """A Kotlin file of exactly `lines` lines (plus whatever `extra` adds)."""
    body = ["package com.example", ""] + [f"val v{k} = {k}" for k in range(lines - 2)]
    return "\n".join(body) + "\n" + extra


def _hot_loop(name: str, body_lines: int, receiver: str = "") -> str:
    """A block-bodied function of `body_lines + 2` lines, with braces in strings."""
    inner = [f'        val s{k} = "{{ not a brace {k} }}" // }} nor this' for k in range(body_lines)]
    return "\n".join([f"internal fun {receiver}{name}(a: Int, f: (Int) -> Unit) {{"] + inner + ["}"]) + "\n"


class _Repo(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        # A repo that matches HOT_LOOPS, each loop small, so the default list holds.
        for path, names in size.HOT_LOOPS.items():
            text = "package com.example\n\n" + "".join(_hot_loop(n, 3) for n in names)
            self.write(path, text)
        self.allow("")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, rel: str, text: str) -> Path:
        path = self.root / _PKG / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def allow(self, body: str) -> None:
        path = self.root / size.ALLOWLIST
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# header\n" + body, encoding="utf-8")

    def failures(self) -> list[str]:
        return size.check(self.root)[0]


class BudgetTest(_Repo):
    def test_a_file_at_the_budget_passes(self) -> None:
        self.write("data/AtBudget.kt", _kotlin(500))
        self.assertEqual(self.failures(), [])

    def test_a_file_one_line_over_fails_with_its_path_and_count(self) -> None:
        self.write("data/Over.kt", _kotlin(501))
        [failure] = self.failures()
        self.assertIn("data/Over.kt: 501 lines", failure)
        self.assertIn("ADR-015", failure)

    def test_files_outside_the_package_root_are_checked_too(self) -> None:
        stray = self.root / "app/src/main/kotlin/Stray.kt"
        stray.parent.mkdir(parents=True)
        stray.write_text(_kotlin(600), encoding="utf-8")
        [failure] = self.failures()
        self.assertIn("app/src/main/kotlin/Stray.kt: 600 lines", failure)

    def test_test_sources_are_not_checked(self) -> None:
        big = self.root / "app/src/test/java/BigTest.kt"
        big.parent.mkdir(parents=True)
        big.write_text(_kotlin(900), encoding="utf-8")
        self.assertEqual(self.failures(), [])


class HotLoopTest(_Repo):
    def test_a_hot_loop_does_not_count_against_its_file(self) -> None:
        # 120 lines around a 400-line loop: 520 in all, 120 counted.
        loop = _hot_loop("runBatchAnalysisBody", 398, receiver="AnalysisViewModel.")
        self.write("ui/analysis/run/BatchAnalysis.kt", _kotlin(120, loop))
        self.assertEqual(self.failures(), [])

    def test_the_code_around_a_hot_loop_still_counts(self) -> None:
        loop = _hot_loop("runBatchAnalysisBody", 98, receiver="AnalysisViewModel.")
        self.write("ui/analysis/run/BatchAnalysis.kt", _kotlin(501, loop))
        [failure] = self.failures()
        self.assertIn("ui/analysis/run/BatchAnalysis.kt: 501 lines (100 more in hot loops, exempt)", failure)

    def test_the_same_function_name_elsewhere_is_not_exempt(self) -> None:
        self.write("data/Elsewhere.kt", _kotlin(120, _hot_loop("prefetchAround", 398)))
        [failure] = self.failures()
        self.assertIn("data/Elsewhere.kt: 520 lines", failure)

    def test_an_expression_bodied_overload_is_not_the_loop(self) -> None:
        text = (
            "package com.example\n"
            "fun buildReport(p: Int): Int = buildReport(p) {}\n"
            + _hot_loop("buildReport", 5)
        )
        code = size.strip_code(text)
        self.assertEqual(size.function_spans(code, "buildReport"), [(3, 9)])

    def test_a_hot_loop_that_is_gone_fails(self) -> None:
        self.write("report/GifEncoder.kt", _kotlin(50, _hot_loop("compressRenamed", 3)))
        [failure] = self.failures()
        self.assertIn("report/GifEncoder.kt: hot loop `compress` not found", failure)

    def test_a_hot_loop_file_that_is_gone_fails(self) -> None:
        (self.root / _PKG / "field/DatDecoder.kt").unlink()
        [failure] = self.failures()
        self.assertIn("field/DatDecoder.kt: hot-loop file not found", failure)


class AllowlistTest(_Repo):
    def test_an_allowlisted_file_may_sit_at_its_ceiling(self) -> None:
        self.write("data/cloud/Big.kt", _kotlin(506))
        self.allow("data/cloud/Big.kt | 506 | split pending, TD-229\n")
        self.assertEqual(self.failures(), [])

    def test_an_allowlisted_file_that_grows_fails(self) -> None:
        self.write("data/cloud/Big.kt", _kotlin(507))
        self.allow("data/cloud/Big.kt | 506 | split pending, TD-229\n")
        [failure] = self.failures()
        self.assertIn("past its allowlisted ceiling of 506", failure)

    def test_an_entry_back_within_the_budget_must_go(self) -> None:
        self.write("data/cloud/Big.kt", _kotlin(480))
        self.allow("data/cloud/Big.kt | 506 | split pending, TD-229\n")
        [failure] = self.failures()
        self.assertIn("within the budget; remove its entry", failure)

    def test_an_entry_for_a_missing_file_must_go(self) -> None:
        self.allow("data/cloud/Gone.kt | 506 | split pending, TD-229\n")
        [failure] = self.failures()
        self.assertIn("data/cloud/Gone.kt does not exist", failure)

    def test_an_entry_needs_a_td_or_adr_pointer(self) -> None:
        self.write("data/cloud/Big.kt", _kotlin(506))
        self.allow("data/cloud/Big.kt | 506 | because\n")
        [failure] = self.failures()
        self.assertIn("names no TD- or ADR- pointer", failure)

    def test_a_malformed_entry_fails(self) -> None:
        self.allow("data/cloud/Big.kt 506 TD-229\n")
        [failure] = self.failures()
        self.assertIn("expected `path | max lines | reason`", failure)


class StripTest(unittest.TestCase):
    def test_comments_strings_and_chars_are_blanked_but_lines_kept(self) -> None:
        text = 'val a = "}" // }\n/* { /* } */ { */ val b = \'{\'\nval c = """\n}\n"""\n'
        code = size.strip_code(text)
        self.assertEqual(code.count("\n"), text.count("\n"))
        self.assertNotIn("{", code)
        self.assertNotIn("}", code)
        self.assertIn("val b", code)

    def test_a_template_with_braces_stays_inside_its_string(self) -> None:
        code = size.strip_code('val s = "${map["k"]} {"\nval t = 1\n')
        self.assertNotIn("{", code)
        self.assertIn("val t = 1", code)


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

    def test_an_oversized_file_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / _PKG / "Big.kt"
            path.parent.mkdir(parents=True)
            path.write_text(_kotlin(600), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, "-I", str(_SCRIPT), "--root", tmp],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("Big.kt: 600 lines", result.stderr)


if __name__ == "__main__":
    unittest.main()
