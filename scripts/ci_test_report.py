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

With ``--gates FILE`` (``benchmark/gates.json``) it also checks each result from
a listed device (the JSON's ``context.build.device``) against that device's
reference times (1 + margin), and exits 1 if any is over. Results from a device
the file does not list, CI's emulator included, are reported and never gated.

A startup time moves 30-40 % with heat and the charger (TD-135), so a result is
gated only in the state its reference was taken in. The benchmarks write that
state (``*deviceState.json``, ``DeviceStateRule``) next to their results; a
result whose test ran above the file's ``state.maxThermalStatus`` or off the
charger (``state.requirePlugged``), at its start or end, is reported as not
gated. A result with no recorded state is gated as before, with a note.

Usage: python scripts/ci_test_report.py [--gates FILE] DIR [DIR ...]
Without ``--gates`` the exit status is always 0: the Gradle step already
failed or passed the job.
"""

from __future__ import annotations

import argparse
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


def report_benchmarks(root: Path, measured: dict[str, dict[str, float]]) -> int:
    """Print every result; add each value to ``measured[device]`` by gate key."""
    count = 0
    for data_file in sorted(root.rglob("*benchmarkData.json")):
        try:
            data = json.loads(data_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"::warning::{data_file}: unreadable benchmark data ({error})")
            continue
        device = str(data.get("context", {}).get("build", {}).get("device", "?"))
        values_by_key = measured.setdefault(device, {})
        for bench in data.get("benchmarks", []):
            count += 1
            name = f"{bench.get('className', '?').rsplit('.', 1)[-1]}.{bench.get('name', '?')}"
            for metric, values in sorted(bench.get("metrics", {}).items()):
                print(
                    f"BENCH {name} {metric}: min {_fmt(values.get('minimum'))} "
                    f"median {_fmt(values.get('median'))} max {_fmt(values.get('maximum'))}"
                )
                if isinstance(values.get("median"), (int, float)):
                    values_by_key[f"{name} {metric}"] = float(values["median"])
            for metric, values in sorted(bench.get("sampledMetrics", {}).items()):
                print(
                    f"BENCH {name} {metric}: P50 {_fmt(values.get('P50'))} "
                    f"P90 {_fmt(values.get('P90'))} P99 {_fmt(values.get('P99'))}"
                )
                for pct in ("P50", "P90", "P99"):
                    if isinstance(values.get(pct), (int, float)):
                        values_by_key[f"{name} {metric} {pct}"] = float(values[pct])
    return count


def read_device_states(root: Path, states: dict[str, dict[str, dict]]) -> None:
    """Add each ``*deviceState.json`` under ``root`` to ``states[device][test]``."""
    for state_file in sorted(root.rglob("*deviceState.json")):
        try:
            data = json.loads(state_file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"::warning::{state_file}: unreadable device state ({error})")
            continue
        states.setdefault(str(data.get("device", "?")), {}).update(data.get("tests", {}))


def state_problems(test_state: dict, rules: dict) -> list[str]:
    """Why a test's recorded state is not the one the references were taken in."""
    max_thermal = int(rules.get("maxThermalStatus", 0))
    problems = []
    for phase in ("start", "end"):
        snap = test_state.get(phase)
        if not isinstance(snap, dict):
            problems.append(f"no {phase} state")
            continue
        thermal = snap.get("thermalStatus")
        if isinstance(thermal, int) and thermal > max_thermal:
            problems.append(f"thermal status {thermal} at {phase}")
        if rules.get("requirePlugged", False) and snap.get("plugged") == "none":
            problems.append(f"on battery at {phase}")
    return problems


def check_gates(
    gates: dict,
    measured: dict[str, dict[str, float]],
    states: dict[str, dict[str, dict]] | None = None,
) -> tuple[int, int]:
    """Compare each gated device's results with its references.

    Returns (breaches, not gated because of the phone's state).
    """
    margin = float(gates.get("margin", 0.0))
    rules = gates.get("state", {})
    states = states or {}
    breaches = 0
    skipped = 0
    for device, values in sorted(measured.items()):
        spec = gates.get("devices", {}).get(device)
        if spec is None:
            if values:
                print(f"GATE {device}: no reference, report only")
            continue
        label = spec.get("label", device)
        device_states = states.get(device, {})
        for key, reference in sorted(spec.get("reference", {}).items()):
            limit = float(reference) * (1 + margin)
            value = values.get(key)
            test_state = device_states.get(key.split(" ", 1)[0])
            problems = state_problems(test_state, rules) if test_state is not None else []
            if value is None:
                print(f"GATE {label} {key}: not measured")
            elif problems:
                skipped += 1
                print(
                    f"GATE not gated {label} {key}: {_fmt(value)} (limit {_fmt(limit)}): "
                    + "; ".join(problems)
                )
            elif value > limit:
                breaches += 1
                print(
                    f"::error title=Benchmark gate::{label} {key}: {_fmt(value)} > {_fmt(limit)} "
                    f"({_fmt(float(reference))} + {margin:.0%})"
                )
            else:
                note = "" if test_state is not None else " (device state not recorded)"
                print(f"GATE ok {label} {key}: {_fmt(value)} <= {_fmt(limit)}{note}")
    return breaches, skipped


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--gates", type=Path, help="benchmark gates JSON (benchmark/gates.json)")
    parser.add_argument("dirs", nargs="*", type=Path)
    args = parser.parse_args(argv[1:])
    failures = 0
    benchmarks = 0
    measured: dict[str, dict[str, float]] = {}
    states: dict[str, dict[str, dict]] = {}
    for root in args.dirs:
        if not root.exists():
            print(f"{root}: not found")
            continue
        failures += report_failures(root)
        benchmarks += report_benchmarks(root, measured)
        read_device_states(root, states)
    print(f"{failures} failing test case(s), {benchmarks} benchmark result(s)")
    if args.gates is None:
        return 0
    breaches, skipped = check_gates(json.loads(args.gates.read_text(encoding="utf-8")), measured, states)
    print(f"{breaches} benchmark gate(s) exceeded, {skipped} not gated (phone state)")
    return 1 if breaches else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
