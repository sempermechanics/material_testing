"""Tests of scripts/qa/qa.py: check, list, plan and report over small fixture trees.

Written as stdlib unittest so CI runs them with nothing installed
(`python -m unittest discover -s scripts/qa/tests`); pytest runs them too.
Each test builds a throwaway repository root holding a `qa/` tree, using the
real templates from this checkout so a broken placeholder fails here.
"""
from __future__ import annotations

import contextlib
import io
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE.parent))

import qa  # noqa: E402

ENVIRONMENTS = """
schema = 1

[[device]]
id = "Emu_A"
kind = "emulator"
avd = "Emu_A"
api = "34"
abi = "x86_64"
page_size = "4k"
tags = ["api34"]
use = true

[[device]]
id = "Emu_B"
kind = "emulator"
avd = "Emu_B"
api = "30"
abi = "x86_64"
page_size = "16k"
tags = ["api30", "16k"]
use = true

[[device]]
id = "Phone"
kind = "real"
adb_device = "phone"
api = "36"
abi = "arm64-v8a"
page_size = "4k"
tags = ["real"]
use = true

[[device]]
id = "Owner_Phone"
kind = "real"
api = "36"
abi = "arm64-v8a"
page_size = "4k"
tags = ["real"]
use = false
note = "Never used."

[[build]]
id = "debug"
app = "semper"
variant = "debug"
abi = "x86_64"
source = "this repository"
recipe = "gradlew assembleDebug"
env = { SEMPER_DEV_AUTH_BYPASS = "false" }

[[build]]
id = "release"
app = "semper"
variant = "release"
abi = "arm64-v8a"
source = "this repository"
recipe = "gradlew assembleRelease"

[[backend]]
id = "staging"
"""

ACCOUNTS = """
schema = 1
domain = "qa.example.com"

[[institution]]
id = "A"
title = "Institution A"
kind = "assigned"
domain_lock = "qa.example.com"
max_seats = 2
max_devices = 2
apps = ["semper"]
admins = ["it"]

[[account]]
id = "holder"
address = "holder-{lane}@qa.example.com"
role = "holder"
apps = ["semper"]
licence = "individual"
max_devices = 1
created_by = "staff"
used_for = "Holder rows."

[[account]]
id = "it"
address = "it-{lane}@qa.example.com"
role = "it-admin"
apps = ["semper"]
licence = "none"
institution = "A"
created_by = "staff"
used_for = "IT rows."
"""

LANES = """
schema = 1

[[lane]]
id = "A"
title = "Emulators"
devices = ["Emu_A", "Emu_B"]
builds = ["debug"]
backend = "staging"
suites = ["demo"]
groups = ["D1"]

[[lane]]
id = "B"
title = "Real phone and web"
devices = ["Phone"]
builds = ["release"]
backend = "staging"
suites = ["demo"]
groups = ["D1", "D2"]
exclude_tags = ["emu-only"]

[[gate]]
id = "staff-step-up"
who = "owner"
ask = "Do the staff action."
"""

SUITE = """
schema = 1
id = "demo"
title = "Demo suite"
prefix = "D"
since = "ADR-000"

[issued]
D1 = 4
D2 = 2

[[scenario]]
id = "D1.1"
title = "Runs on an emulator or a phone"
area = "a"
tags = ["holder"]
accounts = ["holder"]
needs = ["emulator"]
steps = ["Do it"]
expected = "It works"
automation = "androidTest:WidgetTest#works"
status = "active"
since = "ADR-000"

[[scenario]]
id = "D1.2"
title = "Real phone only"
area = "a"
tags = ["holder"]
accounts = ["holder"]
needs = ["device"]
steps = ["Do it"]
expected = "It works"
automation = "planned:androidTest:NotWrittenYetTest"
status = "active"
since = "ADR-000"

[[scenario]]
id = "D1.3"
title = "Only on a 16k emulator"
area = "a"
tags = ["holder", "emu-only"]
accounts = ["holder"]
needs = ["emulator"]
devices = ["tag:16k"]
steps = ["Do it"]
expected = "It works"
automation = ["pytest:backend/tests/test_thing.py::test_it", "manual"]
status = "active"
since = "ADR-000"
x-owner = "qa"

[[scenario]]
id = "D1.4"
title = "Retired"
area = "a"
tags = ["holder"]
accounts = []
needs = ["emulator"]
steps = ["Old"]
expected = "Old"
automation = "manual"
status = "retired"
note = "Replaced by D1.1."
since = "ADR-000"

[[scenario]]
id = "D2.1"
title = "Web, with a staff gate"
area = "b"
tags = ["it", "web"]
accounts = ["it"]
needs = ["web"]
human_gate = ["staff-step-up"]
steps = ["Click"]
expected = "Shown"
automation = "manual"
status = "active"
since = "ADR-000"

[[scenario]]
id = "D2.2"
title = "Deferred"
area = "b"
tags = ["it"]
accounts = []
needs = ["backend"]
steps = ["Later"]
expected = "Later"
automation = "manual"
status = "deferred"
note = "Waits on TD-1."
since = "ADR-000"
"""


class Tree:
    """A throwaway repository root with a qa/ tree and two test files."""

    def __init__(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        qa_dir = self.root / "qa"
        (qa_dir / "suites").mkdir(parents=True)
        shutil.copytree(REPO / "qa" / "templates", qa_dir / "templates")
        self.write("qa/environments.toml", ENVIRONMENTS)
        self.write("qa/accounts.toml", ACCOUNTS)
        self.write("qa/lanes.toml", LANES)
        self.write("qa/suites/demo.toml", SUITE)
        self.write("app/src/androidTest/java/com/example/WidgetTest.kt", "class WidgetTest\n")
        self.write("backend/tests/test_thing.py", "def test_it(): pass\n")

    def write(self, rel: str, text: str) -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text), encoding="utf-8")

    def edit(self, rel: str, old: str, new: str) -> None:
        path = self.root / rel
        text = path.read_text(encoding="utf-8")
        assert old in text, f"{old!r} not in {rel}"
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def cat(self) -> qa.Catalogue:
        return qa.load(self.root)

    def run(self, *argv: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = qa.main(["--root", str(self.root), *argv])
        return code, out.getvalue()

    def close(self) -> None:
        self._tmp.cleanup()


class TreeCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tree = Tree()
        self.addCleanup(self.tree.close)

    def errors(self) -> list[str]:
        return qa.check(self.tree.cat()).errors

    def assertError(self, pattern: str) -> None:
        errors = self.errors()
        self.assertTrue(any(re.search(pattern, e) for e in errors), f"no error matching {pattern!r} in {errors}")


class RealCatalogueTest(unittest.TestCase):
    """The data committed in qa/ passes; this is what CI's step runs."""

    def test_repository_catalogue_passes(self) -> None:
        findings = qa.check(qa.load(REPO))
        self.assertEqual(findings.errors, [])
        self.assertEqual(findings.warnings, [])

    def test_every_licence_group_is_routed_and_ids_are_stable(self) -> None:
        cat = qa.load(REPO)
        suite = cat.suite("licence-activation")
        self.assertIsNotNone(suite)
        groups = {sc["id"].split(".")[0] for sc in suite.scenarios}
        self.assertEqual(groups, {f"L{n}" for n in range(1, 16)})
        self.assertTrue(all(sc["since"] == "ADR-033" for sc in suite.scenarios))


class CheckTest(TreeCase):
    def test_fixture_passes(self) -> None:
        findings = qa.check(self.tree.cat())
        self.assertEqual(findings.errors, [])
        code, out = self.tree.run("check")
        self.assertEqual(code, 0, out)
        self.assertIn("qa check: OK (6 scenarios", out)

    def test_duplicate_id(self) -> None:
        self.tree.edit("qa/suites/demo.toml", 'id = "D1.2"', 'id = "D1.1"')
        self.assertError(r"D1\.1` appears twice")

    def test_deleted_id_fails(self) -> None:
        text = (self.tree.root / "qa/suites/demo.toml").read_text(encoding="utf-8")
        start = text.index('[[scenario]]\nid = "D1.4"')
        end = text.index("[[scenario]]", start + 1)
        self.tree.write("qa/suites/demo.toml", text[:start] + text[end:])
        self.assertError(r"D1\.4 was issued and is gone")

    def test_id_above_issued(self) -> None:
        self.tree.edit("qa/suites/demo.toml", "D1 = 4", "D1 = 3")
        self.assertError(r"D1\.4 is above \[issued\] D1 = 3")

    def test_retired_without_note(self) -> None:
        self.tree.edit("qa/suites/demo.toml", 'note = "Replaced by D1.1."\n', "")
        self.assertError(r"retired scenario says why")

    def test_unknown_references(self) -> None:
        self.tree.edit("qa/suites/demo.toml", 'accounts = ["holder"]\nneeds = ["device"]', 'accounts = ["nobody"]\nneeds = ["device"]')
        self.tree.edit("qa/suites/demo.toml", 'human_gate = ["staff-step-up"]', 'human_gate = ["no-such-gate"]')
        self.tree.edit("qa/suites/demo.toml", 'devices = ["tag:16k"]', 'devices = ["Ghost"]')
        self.tree.edit("qa/lanes.toml", 'builds = ["release"]', 'builds = ["nightly"]')
        self.tree.edit("qa/lanes.toml", 'groups = ["D1", "D2"]', 'groups = ["D1", "D9"]')
        self.assertError(r"account `nobody` is not in accounts.toml")
        self.assertError(r"human gate `no-such-gate`")
        self.assertError(r"device `Ghost` is not in environments.toml")
        self.assertError(r"build `nightly` is not in environments.toml")
        self.assertError(r"group `D9` is in no suite")

    def test_automation_refs(self) -> None:
        self.tree.edit("qa/suites/demo.toml", "androidTest:WidgetTest#works", "androidTest:MissingTest#works")
        self.assertError(r"`MissingTest` not found")
        self.tree.edit("qa/suites/demo.toml", "androidTest:MissingTest#works", "maestro:flows/x.yaml")
        self.assertError(r"unknown runner `maestro`")
        self.tree.edit("qa/suites/demo.toml", "maestro:flows/x.yaml", "pytest:../outside.py")
        self.assertError(r"relative to the repository root")

    def test_planned_ref_may_be_missing_and_notes_when_it_exists(self) -> None:
        self.assertEqual(self.errors(), [])
        self.tree.write("app/src/androidTest/java/com/example/NotWrittenYetTest.kt", "class NotWrittenYetTest\n")
        findings = qa.check(self.tree.cat())
        self.assertEqual(findings.errors, [])
        self.assertTrue(any("NotWrittenYetTest` exists now" in i for i in findings.infos))

    def test_unknown_field_needs_x_prefix(self) -> None:
        self.tree.edit("qa/suites/demo.toml", 'x-owner = "qa"', 'owner = "qa"')
        self.assertError(r"unknown field `owner`")

    def test_vocabularies(self) -> None:
        self.tree.edit("qa/suites/demo.toml", 'needs = ["web"]', 'needs = ["laptop"]')
        self.tree.edit("qa/suites/demo.toml", 'status = "active"', 'status = "maybe"')
        self.tree.edit("qa/suites/demo.toml", 'tags = ["it", "web"]', 'tags = ["It Admin"]')
        self.assertError(r"need `laptop`")
        self.assertError(r"status must be one of")
        self.assertError(r"tag `It Admin`")

    def test_schema_version(self) -> None:
        self.tree.edit("qa/lanes.toml", "schema = 1", "schema = 2")
        self.assertError(r"qa/lanes.toml: schema = 2")

    def test_lane_may_not_use_a_retired_device(self) -> None:
        self.tree.edit("qa/lanes.toml", 'devices = ["Phone"]', 'devices = ["Phone", "Owner_Phone"]')
        self.assertError(r"`Owner_Phone` is marked use = false: Never used")

    def test_two_lanes_may_not_share_a_device(self) -> None:
        self.tree.edit("qa/lanes.toml", 'devices = ["Phone"]', 'devices = ["Phone", "tag:api30"]')
        self.assertError(r"device `Emu_B` is also in lane A")

    def test_account_address_rules(self) -> None:
        self.tree.edit("qa/accounts.toml", "holder-{lane}@qa.example.com", "holder@elsewhere.com")
        self.tree.edit("qa/accounts.toml", "it-{lane}@qa.example.com", "it-{run}@qa.example.com")
        self.assertError(r"test account's address is on `qa.example.com`")
        self.assertError(r"the only placeholder is \{lane\}")

    def test_accounts_hold_no_secret(self) -> None:
        self.tree.edit("qa/accounts.toml", 'used_for = "Holder rows."', 'used_for = "Holder rows."\nx-password = "hunter2"')
        self.assertError(r"never holds a password")

    def test_unrouted_active_scenario_warns(self) -> None:
        self.tree.edit("qa/lanes.toml", 'groups = ["D1", "D2"]', 'groups = ["D1"]')
        findings = qa.check(self.tree.cat())
        self.assertEqual(findings.errors, [])
        self.assertTrue(any("D2.1" in w and "in no lane" in w for w in findings.warnings), findings.warnings)

    def test_bad_toml_is_reported(self) -> None:
        self.tree.write("qa/suites/demo.toml", "schema = = 1\n")
        code, out = self.tree.run("check")
        self.assertEqual(code, 2)
        self.assertIn("not valid TOML", out)


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class CheckBaseTest(TreeCase):
    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], cwd=self.tree.root, check=True, capture_output=True)

    def test_id_deleted_since_base_fails(self) -> None:
        self.git("init", "-q")
        self.git("-c", "user.email=t@example.com", "-c", "user.name=t", "add", ".")
        self.git("-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "base")
        # Delete D1.4 and lower the counter too: only --base can see it.
        text = (self.tree.root / "qa/suites/demo.toml").read_text(encoding="utf-8")
        start = text.index('[[scenario]]\nid = "D1.4"')
        end = text.index("[[scenario]]", start + 1)
        self.tree.write("qa/suites/demo.toml", (text[:start] + text[end:]).replace("D1 = 4", "D1 = 3"))
        self.assertEqual(qa.check(self.tree.cat()).errors, [])
        errors = qa.check(self.tree.cat(), base="HEAD").errors
        self.assertTrue(any("D1.4 exists at HEAD and is gone" in e for e in errors), errors)

    def test_base_without_suites_is_fine_and_a_bad_ref_fails(self) -> None:
        self.git("init", "-q")
        self.git("-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "empty")
        self.assertEqual(qa.check(self.tree.cat(), base="HEAD").errors, [])
        errors = qa.check(self.tree.cat(), base="no-such-ref").errors
        self.assertTrue(any(e.startswith("--base") for e in errors), errors)


class ListTest(TreeCase):
    def ids(self, **kw) -> list[str]:
        args = {"suite": None, "tags": [], "lane": None, "status": None} | kw
        return [sc["id"] for sc in qa.list_scenarios(self.tree.cat(), **args)]

    def test_filters(self) -> None:
        self.assertEqual(self.ids(), ["D1.1", "D1.2", "D1.3", "D1.4", "D2.1", "D2.2"])
        self.assertEqual(self.ids(tags=["holder", "emu-only"]), ["D1.3"])
        self.assertEqual(self.ids(status="deferred"), ["D2.2"])
        self.assertEqual(self.ids(suite="other"), [])
        self.assertEqual(self.ids(lane="A"), ["D1.1", "D1.3"])
        self.assertEqual(self.ids(lane="B"), ["D1.1", "D1.2", "D2.1"])

    def test_cli(self) -> None:
        code, out = self.tree.run("list", "--tag", "web")
        self.assertEqual(code, 0)
        self.assertIn("D2.1", out)
        self.assertIn("1 scenario(s)", out)
        code, out = self.tree.run("list", "--lane", "Z")
        self.assertEqual(code, 2)
        self.assertIn("lane `Z`", out)

    def test_refuses_while_check_fails(self) -> None:
        self.tree.edit("qa/suites/demo.toml", 'id = "D1.2"', 'id = "D1.1"')
        code, out = self.tree.run("list")
        self.assertEqual(code, 1)
        self.assertIn("fix `qa.py check` first", out)


class PlanTest(TreeCase):
    def plan(self, *extra: str) -> Path:
        run = self.tree.root / "qa" / "runs" / "r1"
        code, out = self.tree.run("plan", "--run", str(run), *extra)
        self.assertEqual(code, 0, out)
        return run

    def rows(self, path: Path) -> list[tuple[str, str, str]]:
        return [(r.id, r.device, r.build) for r in qa.read_lane_file(path)]

    def test_rows_per_lane(self) -> None:
        run = self.plan()
        self.assertTrue((run / "evidence").is_dir())
        # Lane A: D1.1 on both emulators; D1.2 needs a real phone (none here);
        # D1.3 only on the 16k emulator.
        self.assertEqual(self.rows(run / "LANE-A.md"),
                         [("D1.1", "Emu_A", "debug"), ("D1.1", "Emu_B", "debug"), ("D1.3", "Emu_B", "debug")])
        # Lane B: a real phone runs emulator and device rows; D1.3 is excluded
        # by tag; D2.1 needs no phone; the deferred D2.2 is never planned.
        self.assertEqual(self.rows(run / "LANE-B.md"),
                         [("D1.1", "Phone", "release"), ("D1.2", "Phone", "release"), ("D2.1", "-", "release")])

    def test_templates_are_filled(self) -> None:
        run = self.plan()
        for name in ("LANE-A.md", "BRIEF-A.md", "LANE-B.md", "BRIEF-B.md"):
            text = (run / name).read_text(encoding="utf-8")
            self.assertIsNone(re.search(r"\$[a-z_]+", text), f"{name} kept a placeholder")
        brief = (run / "BRIEF-B.md").read_text(encoding="utf-8")
        self.assertIn("it-b@qa.example.com", brief)
        self.assertIn("Institution A for this lane", brief)
        self.assertIn("`staff-step-up` (owner): Do the staff action. Used by D2.1.", brief)
        self.assertIn("qa/runs/r1/LANE-B.md", brief)
        self.assertIn("ANDROID_SERIAL", brief)
        self.assertIn("gradlew --stop", brief)

    def test_one_lane_and_no_overwrite(self) -> None:
        run = self.plan("--lane", "A")
        self.assertFalse((run / "LANE-B.md").exists())
        code, out = self.tree.run("plan", "--run", str(run), "--lane", "A")
        self.assertEqual(code, 2)
        self.assertIn("exists; it may hold results", out)
        self.plan("--lane", "A", "--force")

    def test_unknown_lane(self) -> None:
        code, out = self.tree.run("plan", "--run", str(self.tree.root / "r"), "--lane", "Q")
        self.assertEqual(code, 2)
        self.assertIn("lane `Q`", out)


class ReportTest(TreeCase):
    def setUp(self) -> None:
        super().setUp()
        self.run_dir = self.tree.root / "qa" / "runs" / "r1"
        code, out = self.tree.run("plan", "--run", str(self.run_dir))
        self.assertEqual(code, 0, out)

    def fill(self, lane: str, results: dict[tuple[str, str], tuple[str, str]], run: Path | None = None) -> None:
        """results: (id, device) -> (result, note)."""
        path = (run or self.run_dir) / f"LANE-{lane}.md"
        out = []
        for line in path.read_text(encoding="utf-8").splitlines():
            cells = qa._split_row(line) if line.startswith("| D") else None
            if cells and (cells[0], cells[2]) in results:
                result, note = results[(cells[0], cells[2])]
                cells[4], cells[6] = result, note
                line = "| " + " | ".join(qa._cell(c) for c in cells) + " |"
            out.append(line)
        path.write_text("\n".join(out) + "\n", encoding="utf-8")

    def report(self, *extra: str) -> tuple[int, str, str]:
        code, out = self.tree.run("report", "--run", str(self.run_dir), *extra)
        text = (self.run_dir / "REPORT.md").read_text(encoding="utf-8") if (self.run_dir / "REPORT.md").exists() else ""
        return code, out, text

    def verdicts(self) -> dict[str, str]:
        cat = self.tree.cat()
        return qa.verdicts(cat, qa.read_run(cat, self.run_dir))

    def test_verdict_rules(self) -> None:
        self.fill("A", {("D1.1", "Emu_A"): ("pass", ""), ("D1.3", "Emu_B"): ("blocked", "staging down")})
        self.fill("B", {("D1.1", "Phone"): ("pass", ""), ("D2.1", "-"): ("pass", "")})
        v = self.verdicts()
        # D1.1: one emulator and the real phone passed (Emu_B not run is fine).
        self.assertEqual(v["D1.1"], "pass")
        self.assertEqual(v["D1.2"], "not run")
        self.assertEqual(v["D1.3"], "blocked")
        self.assertEqual(v["D2.1"], "pass")

    def test_real_phone_must_pass(self) -> None:
        self.fill("A", {("D1.1", "Emu_A"): ("pass", ""), ("D1.1", "Emu_B"): ("pass", "")})
        self.assertEqual(self.verdicts()["D1.1"], "incomplete")
        self.fill("B", {("D1.1", "Phone"): ("skipped", "no cable")})
        self.assertEqual(self.verdicts()["D1.1"], "skipped")

    def test_unexplained_fail_exits_non_zero(self) -> None:
        self.fill("A", {("D1.1", "Emu_A"): ("fail", "crashed on tap")})
        code, out, text = self.report()
        self.assertEqual(code, 1)
        self.assertIn("a fail has no TD-", out)
        self.assertIn("**Unexplained:** D1.1", text)
        self.assertIn("| D1.1 | fail | FAIL |", text)

    def test_explained_fail_exits_zero(self) -> None:
        self.fill("A", {("D1.1", "Emu_A"): ("fail", "crashed on tap, TD-412")})
        self.fill("B", {("D1.2", "Phone"): ("fail", "fixed by #612")})
        code, out, text = self.report()
        self.assertEqual(code, 0, out)
        self.assertIn("Unexplained fails: 0", text)

    def test_report_sections(self) -> None:
        self.fill("A", {("D1.1", "Emu_A"): ("pass", ""), ("D1.3", "Emu_B"): ("pass", "")})
        self.fill("B", {("D1.1", "Phone"): ("pass", ""), ("D2.1", "-"): ("blocked", "waiting on the owner")})
        code, out, text = self.report()
        self.assertEqual(code, 0, out)
        self.assertIn("| scenario | verdict | Emu_A (emu) | Emu_B (emu) | Phone (real) | - |", text)
        self.assertIn("| D1.1 | pass | pass | · | pass |  |", text)
        self.assertIn("**Passed on emulator and a real phone** (1): D1.1", text)
        self.assertIn("**Passed on emulator only** (1): D1.3", text)
        self.assertIn("waiting on the owner", text.split("## Blocked")[1])

    def test_compare_marks_regressions(self) -> None:
        old = self.tree.root / "qa" / "runs" / "r0"
        self.tree.run("plan", "--run", str(old))
        self.fill("A", {("D1.3", "Emu_B"): ("pass", "")}, run=old)
        self.fill("A", {("D1.3", "Emu_B"): ("fail", "TD-9")})
        code, out, text = self.report("--compare", str(old))
        self.assertEqual(code, 0, out)
        self.assertIn("## Compared with r0", text)
        self.assertIn("| D1.3 | pass | fail **regression** |", text)

    def test_bad_lane_file(self) -> None:
        self.fill("A", {("D1.1", "Emu_A"): ("passed", "")})
        code, out, _ = self.report()
        self.assertEqual(code, 2)
        self.assertIn("result `passed`", out)

    def test_unknown_scenario_or_device_or_lane(self) -> None:
        path = self.run_dir / "LANE-A.md"
        path.write_text(path.read_text(encoding="utf-8").replace("| D1.3 |", "| D9.9 |"), encoding="utf-8")
        code, out, _ = self.report()
        self.assertEqual(code, 2)
        self.assertIn("scenario `D9.9`", out)
        path.write_text(path.read_text(encoding="utf-8").replace("| D9.9 |", "| D1.3 |").replace("| Emu_B |", "| Ghost |"), encoding="utf-8")
        code, out, _ = self.report()
        self.assertIn("device `Ghost`", out)
        shutil.copy(path, self.run_dir / "LANE-Z.md")
        path.write_text(path.read_text(encoding="utf-8").replace("| Ghost |", "| Emu_B |"), encoding="utf-8")
        code, out, _ = self.report()
        self.assertIn("lane `Z`", out)

    def test_empty_run(self) -> None:
        code, out = self.tree.run("report", "--run", str(self.tree.root / "nothing"))
        self.assertEqual(code, 2)
        self.assertIn("no LANE-*.md", out)


if __name__ == "__main__":
    unittest.main()
