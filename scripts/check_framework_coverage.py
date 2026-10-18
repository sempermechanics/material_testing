#!/usr/bin/env python3
"""Fail when the readiness framework's cross-check leaves an item unrecorded.

docs/ops/READINESS_FRAMEWORK_2026-10.md is an external checklist (items 1.1-8.5
in its Part A tables, plus Parts B-E). docs/ops/PRODUCTION_READINESS_GATE.md maps
every one of them in its "Framework cross-check" section. A row that is not
PASS or N-A must name its owner: a TECH_DEBT row (TD-nnn) or a
FUTURE_IMPROVEMENTS entry (FI-nn). This check keeps that true:

* every Part A id in the framework has exactly one row in the gate, and the
  gate names no id the framework lacks;
* every Part B-E key below has exactly one row, and each key's marker text is
  still in the framework (so the key list cannot drift from the source);
* every status is one of PASS, PARTIAL, GAP, EXTERNAL, N-A;
* every row that is not PASS or N-A names at least one owner id, and the owner
  cell holds nothing but ids (or "—");
* every owner id exists: a `| TD-nnn |` row in TECH_DEBT.md, an `FI-nn`
  heading in FUTURE_IMPROVEMENTS.md.

Stdlib only. Usage: python scripts/check_framework_coverage.py [--root DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

FRAMEWORK = Path("docs/ops/READINESS_FRAMEWORK_2026-10.md")
GATE = Path("docs/ops/PRODUCTION_READINESS_GATE.md")
TECH_DEBT = Path("docs/ops/TECH_DEBT.md")
FUTURE = Path("docs/ops/FUTURE_IMPROVEMENTS.md")

SECTION = "## Framework cross-check"
STATUSES = ("PASS", "PARTIAL", "GAP", "EXTERNAL", "N-A")
NEEDS_NO_OWNER = {"PASS", "N-A"}

#: Each Part B-E row key, and text that marks its section or bullet group in
#: the framework.
PART_KEYS: dict[str, str] = {
    "B-model": "**Model.**",
    "B-first-activation": "**First activation (online, required).**",
    "B-offline": "**Offline operation.**",
    "B-clock": "**Clock-tampering resistance",
    "B-refresh": "**Refresh and check-in.**",
    "B-revocation": "**Revocation, reassignment, replacement.**",
    "B-resilience": "**Resilience controls (MASVS-RESILIENCE).**",
    "B-distribution": "**Distribution caveat.**",
    "B-build-vs-buy": "#### Build vs. buy",
    "C-tenancy": "- **Tenancy.**",
    "C-provisioning": "- **Provisioning.**",
    "C-console": "- **Console essentials.**",
    "C-emm": "- **EMM.**",
    "C-update-control": "- **Update control for IT.**",
    "D1": "1. **Versioning:**",
    "D2": "2. **Firestore schema evolution:**",
    "D3": "3. **Rules and functions deployment:**",
    "D4": "4. **Forced update and gating:**",
    "D5": "5. **Rollback:**",
    "D6": "6. **Support/SLA (B2B):**",
    "E-rules": "- **Security rules tests:**",
    "E-load": "- **Load testing:**",
    "E-pentest": "- **Penetration test scope:**",
    "E-tamper": "- **License bypass and tamper tests:**",
    "E-offline": "- **Offline and grace-period tests:**",
    "E-chaos": "- **Chaos/failure tests:**",
    "E-gonogo": "- **Go/no-go review template",
}

_ITEM = re.compile(r"^\d\.\d+$")
_FRAMEWORK_ROW = re.compile(r"^\|\s*(\d\.\d+)\s*\|", re.MULTILINE)
_TD_ROW = re.compile(r"^\|\s*(TD-\d+)\s*\|", re.MULTILINE)
_FI_HEADING = re.compile(r"^#{2,4}\s+(FI-\d+)\b", re.MULTILINE)
_OWNER_ID = re.compile(r"\b(?:TD|FI)-\d+\b")
_OWNER_REST = re.compile(r"^[\s,;—-]*$")


def cells(line: str) -> list[str]:
    """A Markdown table row's cells, trimmed. `\\|` inside a cell is kept."""
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", body)]


def framework_ids(text: str) -> list[str]:
    """Part A item ids, in order, from the framework's table rows."""
    return _FRAMEWORK_ROW.findall(text)


def crosscheck_rows(gate_text: str) -> list[tuple[int, list[str]]]:
    """(line number, cells) for each table row of the cross-check section whose
    first cell is a Part A id or a Part B-E key."""
    lines = gate_text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.startswith(SECTION)), None)
    if start is None:
        return []
    rows = []
    for i in range(start + 1, len(lines)):
        line = lines[i]
        if line.startswith("## "):
            break
        if not line.lstrip().startswith("|"):
            continue
        row = cells(line)
        key = row[0].strip("`")
        if _ITEM.match(key) or key in PART_KEYS:
            rows.append((i + 1, [key, *row[1:]]))
    return rows


def check(root: Path) -> tuple[list[str], Counter]:
    """Every problem found, and the count of each status."""
    errors: list[str] = []
    framework = (root / FRAMEWORK).read_text(encoding="utf-8")
    gate = (root / GATE).read_text(encoding="utf-8")
    tds = set(_TD_ROW.findall((root / TECH_DEBT).read_text(encoding="utf-8")))
    fis = set(_FI_HEADING.findall((root / FUTURE).read_text(encoding="utf-8")))

    wanted = framework_ids(framework)
    if not wanted:
        errors.append(f"{FRAMEWORK}: no Part A item rows found")
    for key, marker in PART_KEYS.items():
        if marker not in framework:
            errors.append(f"{FRAMEWORK}: marker for {key} not found ({marker!r})")

    rows = crosscheck_rows(gate)
    if not rows:
        errors.append(f"{GATE}: no '{SECTION}' section with item rows")
    seen = Counter(r[1][0] for r in rows)
    statuses: Counter = Counter()

    for item in wanted:
        if seen[item] == 0:
            errors.append(f"{GATE}: item {item} has no row")
    for key in PART_KEYS:
        if seen[key] == 0:
            errors.append(f"{GATE}: Part B-E key {key} has no row")
    for key, n in seen.items():
        if n > 1:
            errors.append(f"{GATE}: {key} has {n} rows")
        if _ITEM.match(key) and key not in wanted:
            errors.append(f"{GATE}: item {key} is not in the framework")

    for lineno, row in rows:
        where = f"{GATE}:{lineno} ({row[0]})"
        if len(row) < 5:
            errors.append(f"{where}: expected 5 columns, found {len(row)}")
            continue
        status, owner = row[2].strip("*` "), row[4]
        statuses[status] += 1
        if status not in STATUSES:
            errors.append(f"{where}: status {status!r} is not one of {', '.join(STATUSES)}")
            continue
        ids = _OWNER_ID.findall(owner)
        if not _OWNER_REST.match(_OWNER_ID.sub("", owner)):
            errors.append(f"{where}: owner cell holds more than ids: {owner!r}")
        if status not in NEEDS_NO_OWNER and not ids:
            errors.append(f"{where}: {status} needs an owner (TD-nnn or FI-nn)")
        for oid in ids:
            known = tds if oid.startswith("TD-") else fis
            if oid not in known:
                where_known = TECH_DEBT if oid.startswith("TD-") else FUTURE
                errors.append(f"{where}: owner {oid} is not in {where_known}")
    return errors, statuses


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root (default: this script's repository)")
    args = parser.parse_args(argv)
    try:
        errors, statuses = check(args.root)
    except OSError as e:
        print(f"cannot read: {e}", file=sys.stderr)
        return 2
    summary = ", ".join(f"{s} {statuses.get(s, 0)}" for s in STATUSES)
    if errors:
        print("framework cross-check incomplete:\n", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        print(f"\n({summary})", file=sys.stderr)
        return 1
    print(f"framework cross-check complete: {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
