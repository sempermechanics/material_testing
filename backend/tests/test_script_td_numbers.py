"""`scripts/td_numbers.py`: semperdic-app's TD numbers turned into this repo's (ADR-032).

`translate` is tested on a small map, and the real map is checked to be the one
the 2026-10-27 restart wrote: the register's rows came first, TD-1..TD-61, in old order.
"""
from __future__ import annotations

import csv
import importlib.util
import io
import re
import sys
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_SCRIPT = _REPO / "scripts" / "td_numbers.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

_MAP = {214: 9, 215: 10, 260: 198, 312: 54, 139: 141}
# The register rows on 2026-10-27, which took TD-1..TD-61.
_ROWS = 61


@pytest.fixture(scope="module")
def td():
    spec = importlib.util.spec_from_file_location("td_numbers", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_a_mapped_number_is_rewritten(td):
    out, unknown = td.translate("closes TD-312 (see TD-214).", _MAP)
    assert out == "closes TD-54 (see TD-9)."
    assert unknown == []


def test_a_number_inside_another_is_left_alone(td):
    out, _ = td.translate("XTD-312, TD-3120 and TD-31", _MAP)
    assert out == "XTD-312, semperdic-app's TD-3120 and semperdic-app's TD-31"


def test_an_unknown_number_is_named_as_upstream_and_listed(td):
    out, unknown = td.translate("TD-351 is new upstream", _MAP)
    assert out == "semperdic-app's TD-351 is new upstream"
    assert unknown == [351]


def test_a_range_becomes_the_runs_its_members_took(td):
    out, _ = td.translate("TD-214–TD-260 by the cross-check", _MAP)
    assert out == "TD-9–TD-10, TD-198 by the cross-check"


def test_a_range_with_an_unknown_end_maps_each_end(td):
    out, unknown = td.translate("TD-214 to TD-400", _MAP)
    assert out == "TD-9 to semperdic-app's TD-400"
    assert unknown == [400]


def test_a_heading_anchor_follows_its_number(td):
    out, _ = td.translate("[ADR-011](ADR-011.md#as-built-td-139-2026-10-13)", _MAP)
    assert out == "[ADR-011](ADR-011.md#as-built-td-141-2026-10-13)"


def test_the_map_puts_the_register_rows_first(td):
    mapping = td.load_map(td.MAP)
    assert sorted(mapping.values()) == list(range(1, len(mapping) + 1))
    olds = list(mapping)
    with td.MAP.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    assert [int(r["old"][3:]) for r in rows] == olds
    # New numbers rise with the old ones within each block: rows, then the rest.
    first, rest = olds[:_ROWS], olds[_ROWS:]
    assert first == sorted(first) and rest == sorted(rest)
    # Rows close and leave gaps, but every open one is from that first block or later.
    register = (_REPO / "docs" / "ops" / "TECH_DEBT.md").read_text(encoding="utf-8")
    row_ids = [int(n) for n in re.findall(r"^\|\s*TD-(\d+)\s*\|", register, re.M)]
    assert row_ids == sorted(row_ids)
    assert all(n <= _ROWS or n > len(mapping) for n in row_ids)


def test_translate_reads_stdin_and_reports_on_stderr(td, tmp_path, monkeypatch, capsys):
    path = tmp_path / "map.csv"
    path.write_text("old,new\nTD-312,TD-54\n", encoding="utf-8")
    stdin = io.TextIOWrapper(io.BytesIO("TD-312 and TD-9".encode("utf-8")), encoding="utf-8")
    monkeypatch.setattr(sys, "stdin", stdin)
    stdout = io.BytesIO()
    monkeypatch.setattr(sys, "stdout", io.TextIOWrapper(stdout, encoding="utf-8"))
    assert td.main(["translate", "--map", str(path)]) == 0
    sys.stdout.flush()
    assert stdout.getvalue().decode("utf-8") == "TD-54 and semperdic-app's TD-9"
    assert "semperdic-app's TD-9" in capsys.readouterr().err
