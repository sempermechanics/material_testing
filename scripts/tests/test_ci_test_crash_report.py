"""Tests for scripts/ci_test_crash_report.py."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import ci_test_crash_report as crash  # noqa: E402


class CrashReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.crash_dir = root / "test-crash"
        self.results_dir = root / "results"
        self.crash_dir.mkdir()
        (self.results_dir / "binary").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_crash_file_is_printed_as_an_error(self) -> None:
        (self.crash_dir / "hs_err_pid42.log").write_text("# SIGSEGV (0xb)\n# Problematic frame:\n")
        lines = crash.report(self.crash_dir, self.results_dir)
        self.assertIn("::error::The unit-test JVM crashed: hs_err_pid42.log", lines)
        self.assertIn("# SIGSEGV (0xb)", lines)
        self.assertFalse(any(line.startswith("No JVM error file") for line in lines))

    def test_heap_dump_says_the_heap_ran_out(self) -> None:
        (self.crash_dir / "java_pid42.hprof").write_bytes(b"\0" * 1024)
        lines = crash.report(self.crash_dir, self.results_dir)
        self.assertTrue(any("ran out of heap: java_pid42.hprof" in line for line in lines))

    def test_neither_means_something_ended_the_worker(self) -> None:
        (self.crash_dir / "gc.log").write_text("[1.0s] GC(1) Pause Young 900M->300M(2048M)\n")
        lines = crash.report(self.crash_dir, self.results_dir)
        self.assertTrue(any(line.startswith("No JVM error file") for line in lines))
        self.assertIn("[1.0s] GC(1) Pause Young 900M->300M(2048M)", lines)

    def test_every_worker_gc_log_is_printed(self) -> None:
        (self.crash_dir / "gc-101.log").write_text("[2.0s] GC(4) Pause Full 1900M->1890M(2048M)\n")
        (self.crash_dir / "gc-202.log").write_text("[2.0s] GC(3) Pause Young 400M->200M(2048M)\n")
        (self.crash_dir / "gc-202.log.0").write_text("[0.5s] older rotation\n")
        lines = crash.report(self.crash_dir, self.results_dir)
        self.assertIn("Last 25 lines of gc-101.log:", lines)
        self.assertIn("Last 25 lines of gc-202.log:", lines)
        self.assertIn("[2.0s] GC(4) Pause Full 1900M->1890M(2048M)", lines)
        self.assertNotIn("[0.5s] older rotation", lines)

    def test_last_classes_are_listed_oldest_first(self) -> None:
        for index, name in enumerate(["a.FirstTest", "b.SecondTest"]):
            path = self.results_dir / f"TEST-{name}.xml"
            path.write_text("<testsuite/>")
            os.utime(path, (1000 + index, 1000 + index))
        lines = crash.report(self.crash_dir, self.results_dir)
        self.assertEqual(lines[-2:], ["  a.FirstTest", "  b.SecondTest"])

    def test_worker_output_is_read_under_either_gradle_name(self) -> None:
        for name in ("output.bin", "output-events.bin"):
            with self.subTest(name=name):
                for old in (self.results_dir / "binary").iterdir():
                    old.unlink()
                (self.results_dir / "binary" / name).write_bytes(b"\x01\x00\x07Exception in thread \"main\"\x00")
                lines = crash.report(self.crash_dir, self.results_dir)
                self.assertIn(f"Last 80 lines the test worker printed ({name}):", lines)
                self.assertIn('Exception in thread "main"', lines)

    def test_binary_output_keeps_the_text_in_order(self) -> None:
        data = b"\x01\x00\x07Exception in thread \"x\"\x00\x02\x05    at a.B.c(B.kt:3)\n"
        self.assertEqual(
            crash.binary_output_text(data),
            ['Exception in thread "x"', "    at a.B.c(B.kt:3)"],
        )

    def test_missing_folders_exit_zero(self) -> None:
        self.assertEqual(crash.main(["x", "/nonexistent/a", "/nonexistent/b"]), 0)


if __name__ == "__main__":
    unittest.main()
