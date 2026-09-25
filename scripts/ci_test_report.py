#!/usr/bin/env python3
"""Print what a device test run found, into the CI log.

Gradle's connected tasks only say "There were failing tests" and leave the
names in an HTML report inside the build directory; the Macrobenchmark numbers
sit in JSON files uploaded as an artifact. This prints both where the job log
shows them:

- every failing or erroring JUnit test case under the given directories
  (``TEST-*.xml``), as a GitHub ``::error`` annotation plus the first lines of
  its message;
- every ``*benchmarkData.json`` result as one line per metric: min / median /
  max for single-value metrics, P50 / P90 / P99 for sampled ones.

Usage: python scripts/ci_test_report.py DIR [DIR ...]
Exit status is always 0: the Gradle step already failed or passed the job.
"""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MESSAGE_LINES = 40


def report_failures(root: Path) -> int:
    failures = 0
    for xml_file in sorted(root.rglob("TEST-*.xml")):
        try:
            tree = ET.parse(xml_file)
        except ET.ParseError as error:
            print(f"::warning::{xml_file}: unreadable test report ({error})")
            continue
        for case in tree.getroot().iter("testcase"):
            for kind in ("failure", "error"):
                problem = case.find(kind)
                if problem is None:
                    continue
                name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                body = (problem.get("message") or "") + (problem.text or "")
                if "AssumptionViolatedException" in body:
                    # An assumption that did not hold is a skip, not a failure.
                    print(f"skipped: {name}")
                    continue
                failures += 1
                headline = (problem.get("message") or "").splitlines()[:1]
                print(f"::error title=Failed test::{name}: {headline[0] if headline else kind}")
                print(f"::group::{name}")
                text = (problem.text or problem.get("message") or "").splitlines()
                print("\n".join(text[:MESSAGE_LINES]))
                print("::endgroup::")
    return failures


def _fmt(value: object) -> str:
    return f"{value:.1f}" if isinstance(value, (int, float)) else str(value)


def report_benchmarks(root: Path) -> int:
    count = 0
    for data_file in sorted(root.rglob("*benchmarkData.json")):
        try:
            data = json.loads(data_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"::warning::{data_file}: unreadable benchmark data ({error})")
            continue
        for bench in data.get("benchmarks", []):
            count += 1
            name = f"{bench.get('className', '?').rsplit('.', 1)[-1]}.{bench.get('name', '?')}"
            for metric, values in sorted(bench.get("metrics", {}).items()):
                print(
                    f"BENCH {name} {metric}: min {_fmt(values.get('minimum'))} "
                    f"median {_fmt(values.get('median'))} max {_fmt(values.get('maximum'))}"
                )
            for metric, values in sorted(bench.get("sampledMetrics", {}).items()):
                print(
                    f"BENCH {name} {metric}: P50 {_fmt(values.get('P50'))} "
                    f"P90 {_fmt(values.get('P90'))} P99 {_fmt(values.get('P99'))}"
                )
    return count


def main(argv: list[str]) -> int:
    failures = 0
    benchmarks = 0
    for arg in argv[1:]:
        root = Path(arg)
        if not root.exists():
            print(f"{root}: not found")
            continue
        failures += report_failures(root)
        benchmarks += report_benchmarks(root)
    print(f"{failures} failing test case(s), {benchmarks} benchmark result(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
