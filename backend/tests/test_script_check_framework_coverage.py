"""`scripts/check_framework_coverage.py`: every readiness-framework item has an
owned row in the gate's cross-check.

One test runs the real check on this repository; the rest build a small
repository under `tmp_path` and break it one way at a time.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_SCRIPT = _REPO / "scripts" / "check_framework_coverage.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")


@pytest.fixture(scope="module")
def cov():
    spec = importlib.util.spec_from_file_location("check_framework_coverage", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules["check_framework_coverage"] = module
    spec.loader.exec_module(module)
    return module


def _framework(cov, items=("1.1", "1.2"), drop_marker: str | None = None) -> str:
    rows = "\n".join(f"| {i} | requirement {i} | verify {i} |" for i in items)
    markers = "\n".join(m for k, m in cov.PART_KEYS.items() if k != drop_marker)
    return f"# Framework\n\n| # | Item | Verify |\n|---|---|---|\n{rows}\n\n{markers}\n"


def _gate(cov, rows: dict[str, tuple[str, str]] | None = None, extra: str = "") -> str:
    """A gate whose cross-check has a row per key: (status, owner)."""
    rows = rows if rows is not None else {}
    table = ["| Item | Requirement | Status | Evidence | Owner |", "|---|---|---|---|---|"]
    for key, (status, owner) in rows.items():
        table.append(f"| {key} | req | {status} | `backend/app/x.py:1` | {owner} |")
    return ("# Gate\n\n## Earlier section\n\n| 9.9 | not counted | GAP | x | |\n\n"
            "## Framework cross-check (2026-10-18)\n\n" + "\n".join(table) + "\n" + extra
            + "\n## Risk acceptance\n\n| 1.1 | outside the section | GAP | x | |\n")


def _all_pass(cov, items=("1.1", "1.2")) -> dict[str, tuple[str, str]]:
    return {**{i: ("PASS", "—") for i in items}, **{k: ("PASS", "—") for k in cov.PART_KEYS}}


def _repo(tmp_path: Path, cov, *, framework: str | None = None, gate: str | None = None,
          tds=("TD-1", "TD-2"), fis=("FI-1",)) -> Path:
    ops = tmp_path / "docs" / "ops"
    ops.mkdir(parents=True)
    (ops / "READINESS_FRAMEWORK_2026-10.md").write_text(
        framework if framework is not None else _framework(cov), encoding="utf-8")
    (ops / "PRODUCTION_READINESS_GATE.md").write_text(
        gate if gate is not None else _gate(cov, _all_pass(cov)), encoding="utf-8")
    (ops / "TECH_DEBT.md").write_text(
        "| ID | Item |\n|---|---|\n" + "".join(f"| {t} | x |\n" for t in tds), encoding="utf-8")
    (ops / "FUTURE_IMPROVEMENTS.md").write_text(
        "# Future\n\n" + "".join(f"### {f} Something\n\ntext\n\n" for f in fis), encoding="utf-8")
    return tmp_path


def test_the_repository_passes(cov, capsys):
    assert cov.main(["--root", str(_REPO)]) == 0
    assert "framework cross-check complete" in capsys.readouterr().out


def test_the_real_framework_has_every_part_a_item(cov):
    ids = cov.framework_ids((_REPO / cov.FRAMEWORK).read_text(encoding="utf-8"))
    assert ids[0] == "1.1" and ids[-1] == "8.5"
    assert len(ids) == len(set(ids)) == 49


def test_a_complete_synthetic_repository_passes(cov, tmp_path):
    errors, statuses = cov.check(_repo(tmp_path, cov))
    assert errors == []
    assert statuses["PASS"] == 2 + len(cov.PART_KEYS)


def test_a_missing_item_fails(cov, tmp_path):
    rows = _all_pass(cov)
    del rows["1.2"]
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("item 1.2 has no row" in e for e in errors)


def test_a_missing_part_key_fails(cov, tmp_path):
    rows = _all_pass(cov)
    del rows["E-chaos"]
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("E-chaos has no row" in e for e in errors)


def test_a_marker_gone_from_the_framework_fails(cov, tmp_path):
    errors, _ = cov.check(_repo(tmp_path, cov, framework=_framework(cov, drop_marker="D3")))
    assert any("marker for D3" in e for e in errors)


def test_an_item_the_framework_lacks_fails(cov, tmp_path):
    rows = {**_all_pass(cov), "9.1": ("PASS", "—")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("item 9.1 is not in the framework" in e for e in errors)


def test_a_duplicate_row_fails(cov, tmp_path):
    extra = "| 1.1 | again | PASS | x | — |\n"
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, _all_pass(cov), extra)))
    assert any("1.1 has 2 rows" in e for e in errors)


@pytest.mark.parametrize("status", ["PARTIAL", "GAP", "EXTERNAL"])
def test_an_open_row_without_an_owner_fails(cov, tmp_path, status):
    rows = {**_all_pass(cov), "1.1": (status, "—")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any(f"{status} needs an owner" in e for e in errors)


def test_pass_and_na_need_no_owner_but_may_name_one(cov, tmp_path):
    rows = {**_all_pass(cov), "1.1": ("N-A", "—"), "1.2": ("PASS", "TD-1")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert errors == []


def test_an_unknown_td_id_fails(cov, tmp_path):
    rows = {**_all_pass(cov), "1.1": ("GAP", "TD-1, TD-404")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("owner TD-404 is not in" in e for e in errors)
    assert not any("TD-1 " in e or "TD-1 is" in e for e in errors)


def test_an_unknown_fi_id_fails(cov, tmp_path):
    rows = {**_all_pass(cov), "B-model": ("GAP", "FI-9")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("owner FI-9 is not in" in e for e in errors)


def test_known_ids_of_both_kinds_resolve(cov, tmp_path):
    rows = {**_all_pass(cov), "1.1": ("PARTIAL", "TD-2, FI-1")}
    errors, statuses = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert errors == [] and statuses["PARTIAL"] == 1


def test_an_unknown_status_fails(cov, tmp_path):
    rows = {**_all_pass(cov), "1.1": ("DONE", "—")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("status 'DONE'" in e for e in errors)


def test_an_owner_cell_with_prose_fails(cov, tmp_path):
    rows = {**_all_pass(cov), "1.1": ("GAP", "TD-1 once someone has time")}
    errors, _ = cov.check(_repo(tmp_path, cov, gate=_gate(cov, rows)))
    assert any("owner cell holds more than ids" in e for e in errors)


def test_a_short_row_fails(cov, tmp_path):
    gate = _gate(cov, _all_pass(cov)).replace("| 1.2 | req | PASS | `backend/app/x.py:1` | — |",
                                              "| 1.2 | req | PASS |")
    errors, _ = cov.check(_repo(tmp_path, cov, gate=gate))
    assert any("expected 5 columns" in e for e in errors)


def test_no_section_fails(cov, tmp_path):
    errors, _ = cov.check(_repo(tmp_path, cov, gate="# Gate\n\nNothing here.\n"))
    assert any("no '## Framework cross-check' section" in e for e in errors)
    assert any("item 1.1 has no row" in e for e in errors)


def test_rows_outside_the_section_are_not_counted(cov):
    gate = _gate(cov, {"1.1": ("PASS", "—")})
    keys = [row[0] for _, row in cov.crosscheck_rows(gate)]
    assert keys == ["1.1"]


def test_backticked_keys_and_escaped_pipes_parse(cov):
    assert cov.cells(r"| `B-model` | a \| b | PASS | x | — |") == ["`B-model`", "a | b", "PASS", "x", "—"]
    gate = _gate(cov, {"`B-model`": ("PASS", "—")})
    assert [row[0] for _, row in cov.crosscheck_rows(gate)] == ["B-model"]


def test_main_reports_failures_and_exits_one(cov, tmp_path, capsys):
    rows = _all_pass(cov)
    del rows["1.1"]
    root = _repo(tmp_path, cov, gate=_gate(cov, rows))
    assert cov.main(["--root", str(root)]) == 1
    err = capsys.readouterr().err
    assert "item 1.1 has no row" in err and "PASS" in err


def test_main_exits_two_when_a_file_is_missing(cov, tmp_path, capsys):
    assert cov.main(["--root", str(tmp_path)]) == 2
    assert "cannot read" in capsys.readouterr().err
