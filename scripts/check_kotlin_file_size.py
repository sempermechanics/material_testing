#!/usr/bin/env python3
"""Fail when a main Kotlin source file outgrows ADR-015's size budget.

ADR-015 (docs/adr/ADR-015-package-layout.md, "File size") says a file that
grows past about 500 lines is split. Until 2026-10-19 nothing checked it, and
`data/cloud/SessionMetadataDoc.kt` reached 506 lines with five more files just
under the line (TD-292). This is that check: every `*.kt` under
`app/src/main/` is counted, and one over the budget fails CI.

Two things are not counted against the budget:

* **Fused hot loops.** ADR-015 exempts them: the function that holds one keeps
  its body whole however long it is, and only the code around it moves out.
  `HOT_LOOPS` lists them as ADR-015's table does; their lines are subtracted
  from their file's count. A listed function that is no longer found fails the
  check, so the list cannot drift from the code.
* **Allowlisted files.** `scripts/kotlin_file_size_allowlist.txt` names each
  file allowed over the budget today, the most lines it may have, and why
  (with a TD- or ADR- pointer). A file that grows past its ceiling fails; an
  entry whose file is gone or back within the budget fails too, so the list
  only ever shrinks.

Stdlib only. Usage: python scripts/check_kotlin_file_size.py [--root DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NamedTuple

#: ADR-015's guide: "a file that grows past about 500 lines is split".
BUDGET = 500

SOURCES = Path("app/src/main")
#: Allowlist and hot-loop paths are relative to the app's package root, as
#: ADR-015 and TECH_DEBT write them (`data/cloud/SessionMetadataDoc.kt`).
PACKAGE_ROOT = Path("app/src/main/java/com/sempermechanics/semper")
ALLOWLIST = Path("scripts/kotlin_file_size_allowlist.txt")

#: ADR-015's hot-loop table (and CLAUDE.md's guardrail): file -> functions.
HOT_LOOPS: dict[str, tuple[str, ...]] = {
    "ui/analysis/run/BatchAnalysis.kt": ("runBatchAnalysisBody",),
    "report/ReportBuilder.kt": ("buildReport",),
    "report/HeatmapRenderer.kt": ("generateHeatmapIndices",),
    "report/DeformedHeatmap.kt": ("generateDeformedHeatmapIndices",),
    "report/GifEncoder.kt": ("compress",),
    "field/DatDecoder.kt": ("decodeInto",),
    "ui/viewer/ViewerFrameLoader.kt": ("prefetchAround",),
    "ui/viewer/inspect/PointSpatialIndex.kt": ("build",),
}

_POINTER = re.compile(r"\b(?:TD|ADR)-\d+\b")


def strip_code(text: str) -> str:
    """Blank comments, string and char literals; every offset and newline stays.

    Only braces and parentheses in code are matched against each other, so a
    `"{"` in a string or a `}` in a comment must not count.
    """
    out = list(text)
    n = len(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
        elif text.startswith("/*", i):
            depth, end = 1, i + 2
            while end < n and depth:
                if text.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif text.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            while end < n and text[end] == '"':
                end += 1
        elif text[i] == '"':
            end = _string_end(text, i + 1)
        elif text[i] == "'":
            end = i + 1
            if end < n and text[end] == "\\":
                end += 2
            close = text.find("'", end)
            end = n if close < 0 else close + 1
        else:
            i += 1
            continue
        blank(i, end)
        i = end
    return "".join(out)


def _string_end(text: str, j: int) -> int:
    """The offset just past a one-line string literal opened before `j`."""
    n = len(text)
    while j < n:
        c = text[j]
        if c == "\\":
            j += 2
        elif text.startswith("${", j):
            depth, j = 1, j + 2
            while j < n and depth:
                depth += {"{": 1, "}": -1}.get(text[j], 0)
                j += 1
        elif c == '"':
            return j + 1
        elif c == "\n":
            return j
        else:
            j += 1
    return n


def matching(code: str, start: int) -> int:
    """Offset of the bracket closing the one at `start`, or -1."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    opener = code[start]
    closer = pairs[opener]
    depth = 0
    for k in range(start, len(code)):
        if code[k] == opener:
            depth += 1
        elif code[k] == closer:
            depth -= 1
            if depth == 0:
                return k
    return -1


def function_spans(code: str, name: str) -> list[tuple[int, int]]:
    """(first line, last line) of each block-bodied `fun name(...)` in `code`.

    Extension receivers (`fun AnalysisViewModel.runBatchAnalysisBody(`) and
    type parameters are allowed. An expression-bodied overload
    (`fun buildReport(p) = buildReport(p) {}`) is a one-line delegate, not the
    loop, and is skipped.
    """
    declaration = re.compile(
        r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?" + re.escape(name) + r"\s*\("
    )
    spans = []
    for match in declaration.finditer(code):
        close = matching(code, match.end() - 1)
        if close < 0:
            continue
        brace = code.find("{", close)
        equals = code.find("=", close)
        if brace < 0 or (0 <= equals < brace):
            continue
        end = matching(code, brace)
        if end < 0:
            continue
        spans.append((code.count("\n", 0, match.start()) + 1, code.count("\n", 0, end) + 1))
    return spans


class Entry(NamedTuple):
    path: str
    ceiling: int
    reason: str
    line: int


def read_allowlist(path: Path) -> tuple[dict[str, Entry], list[str]]:
    """Entries by package-relative path, and any problems with the file itself."""
    entries: dict[str, Entry] = {}
    problems: list[str] = []
    if not path.is_file():
        return entries, problems
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        where = f"{ALLOWLIST}:{number}"
        if len(parts) != 3 or not parts[1].isdigit():
            problems.append(f"{where}: expected `path | max lines | reason`, got {line!r}")
            continue
        file, ceiling, reason = parts[0], int(parts[1]), parts[2]
        if not _POINTER.search(reason):
            problems.append(f"{where}: the reason for {file} names no TD- or ADR- pointer")
        if file in entries:
            problems.append(f"{where}: {file} is listed twice")
        entries[file] = Entry(file, ceiling, reason, number)
    return entries, problems


def counted_lines(text: str, hot_loops: tuple[str, ...]) -> tuple[int, int, list[str]]:
    """(lines counted against the budget, lines exempted, hot loops not found)."""
    total = len(text.splitlines())
    if not hot_loops:
        return total, 0, []
    code = strip_code(text)
    exempt, missing = 0, []
    for name in hot_loops:
        spans = function_spans(code, name)
        if not spans:
            missing.append(name)
        exempt += sum(last - first + 1 for first, last in spans)
    return total - exempt, exempt, missing


def check(root: Path, budget: int = BUDGET) -> tuple[list[str], int, int]:
    """Failures, files checked, and files allowed over the budget."""
    package_root = root / PACKAGE_ROOT
    allowlist, failures = read_allowlist(root / ALLOWLIST)
    seen: set[str] = set()
    checked = 0
    for source in sorted((root / SOURCES).rglob("*.kt")):
        checked += 1
        try:
            key = source.relative_to(package_root).as_posix()
        except ValueError:
            key = source.relative_to(root).as_posix()
        text = source.read_text(encoding="utf-8")
        count, exempt, missing = counted_lines(text, HOT_LOOPS.get(key, ()))
        for name in missing:
            failures.append(
                f"{key}: hot loop `{name}` not found; update HOT_LOOPS here and ADR-015's table"
            )
        shown = f"{count} lines" + (f" ({exempt} more in hot loops, exempt)" if exempt else "")
        entry = allowlist.get(key)
        if entry is None:
            if count > budget:
                failures.append(f"{key}: {shown}, over the {budget}-line budget (ADR-015); split it")
            continue
        seen.add(key)
        if count > entry.ceiling:
            failures.append(
                f"{key}: {shown}, past its allowlisted ceiling of {entry.ceiling}; split it"
            )
        elif count <= budget:
            failures.append(
                f"{ALLOWLIST}:{entry.line}: {key} is {count} lines, within the budget; "
                "remove its entry"
            )
    for key, entry in allowlist.items():
        if key not in seen:
            failures.append(f"{ALLOWLIST}:{entry.line}: {key} does not exist; remove its entry")
    for key in HOT_LOOPS:
        if not (package_root / key).is_file():
            failures.append(f"{key}: hot-loop file not found; update HOT_LOOPS and ADR-015")
    return failures, checked, len(seen)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root (default: cwd)")
    parser.add_argument("--budget", type=int, default=BUDGET, help=f"lines (default {BUDGET})")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    failures, checked, allowed = check(root, args.budget)
    print(f"checked {checked} Kotlin files under {SOURCES}; budget {args.budget} lines, "
          f"{allowed} allowlisted")
    if failures:
        print("\nADR-015 file-size budget:\n", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(
            "\nSplit the file as ADR-015's split rule says (a pure move into the same "
            "package). An exception goes in scripts/kotlin_file_size_allowlist.txt with "
            "its reason and a TD- or ADR- pointer.",
            file=sys.stderr,
        )
        return 1
    print("every main Kotlin file is within the budget or its allowlisted ceiling")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
