#!/usr/bin/env python3
"""Print what ended a unit-test JVM into the CI log.

Gradle reports a test worker that exits on its own as only

    Process 'Gradle Test Executor 1' finished with non-zero exit value 1

with no failing test (TD-199, TD-312). The worker leaves its evidence in
``app/build/test-crash/`` (see ``app/build.gradle.kts``): the JVM's error file
on a crash, a heap dump on an OutOfMemoryError, and a GC log. Tier 1 uploads
that folder, but an artifact is not always reachable from where the failure is
read, so this prints the parts that name a cause where the job log shows them:

- each ``hs_err_pid*.log``: its first lines (the signal, the problematic frame,
  the Java frames of the crashing thread);
- each ``*.hprof``: its size, which says the heap ran out;
- the GC log's last lines: how full the heap was when the worker stopped;
- the test classes that finished last (``TEST-*.xml`` by time written): the
  worker died in the class after them;
- the end of the worker's captured output (Gradle's ``binary/output.bin``),
  which holds what a test or a background thread printed last, an uncaught
  exception included.

Usage: python scripts/ci_test_crash_report.py CRASH_DIR RESULTS_DIR
The exit status is always 0: the Gradle step already failed the job.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HS_ERR_LINES = 60
GC_TAIL_LINES = 25
LAST_CLASSES = 8
OUTPUT_TAIL_LINES = 80
# Gradle's output.bin interleaves the text with binary record headers; runs of
# at least this many printable characters are the text.
MIN_TEXT_RUN = 4
_TEXT_RUN = re.compile(rb"[\x09\x0a\x0d\x20-\x7e]{%d,}" % MIN_TEXT_RUN)


def _head(path: Path, lines: int) -> list[str]:
    with path.open(encoding="utf-8", errors="replace") as handle:
        return [line.rstrip("\n") for _, line in zip(range(lines), handle)]


def _tail(path: Path, lines: int) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return text[-lines:]


def binary_output_text(data: bytes) -> list[str]:
    """The readable lines of a Gradle ``output.bin``, in order."""
    lines: list[str] = []
    for run in _TEXT_RUN.findall(data):
        lines.extend(part for part in run.decode("ascii").splitlines() if part.strip())
    return lines


def report(crash_dir: Path, results_dir: Path) -> list[str]:
    out: list[str] = []
    found_cause = False

    for hs_err in sorted(crash_dir.glob("hs_err_pid*.log")):
        found_cause = True
        out.append(f"::error::The unit-test JVM crashed: {hs_err.name}")
        out.extend(_head(hs_err, HS_ERR_LINES))

    for dump in sorted(crash_dir.glob("*.hprof")):
        found_cause = True
        size_mb = dump.stat().st_size / (1024 * 1024)
        out.append(
            f"::error::The unit-test JVM ran out of heap: {dump.name} ({size_mb:.0f} MB) "
            "is in the app-unit-tests-and-coverage artifact"
        )

    gc_logs = sorted(crash_dir.glob("gc.log*"))
    if gc_logs:
        newest = max(gc_logs, key=lambda path: path.stat().st_mtime)
        out.append(f"Last {GC_TAIL_LINES} lines of {newest.name}:")
        out.extend(_tail(newest, GC_TAIL_LINES))

    if not found_cause:
        out.append(
            "No JVM error file and no heap dump: the worker did not crash and did not "
            "run out of heap, so something ended it (System.exit, halt, or a kill)."
        )

    xml_reports = sorted(results_dir.glob("TEST-*.xml"), key=lambda path: path.stat().st_mtime)
    if xml_reports:
        out.append(f"{len(xml_reports)} test classes finished; the last {LAST_CLASSES}:")
        for xml_report in xml_reports[-LAST_CLASSES:]:
            out.append("  " + xml_report.name.removeprefix("TEST-").removesuffix(".xml"))

    output_bin = results_dir / "binary" / "output.bin"
    if output_bin.is_file():
        lines = binary_output_text(output_bin.read_bytes())
        out.append(f"Last {OUTPUT_TAIL_LINES} lines the test worker printed:")
        out.extend(lines[-OUTPUT_TAIL_LINES:])

    return out


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 0
    crash_dir, results_dir = Path(argv[1]), Path(argv[2])
    if not crash_dir.is_dir() and not results_dir.is_dir():
        print(f"{crash_dir} and {results_dir}: not found")
        return 0
    for line in report(crash_dir, results_dir):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
