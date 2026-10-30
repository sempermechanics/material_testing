#!/usr/bin/env python3
"""The QC catalogue tool: check, list, plan and report over the data in `qa/`.

The QC catalogue is data, not prose (ADR-034): scenarios live in
`qa/suites/*.toml`, the phones, builds and backends in `qa/environments.toml`,
the QC accounts in `qa/accounts.toml`, and how a run is split into lanes in
`qa/lanes.toml`. Every field is described in `qa/SCHEMA.md`; how to use this
tool is in `qa/README.md`.

    python scripts/qa/qa.py check [--base REF] [--verbose]
    python scripts/qa/qa.py list [--suite S] [--tag T ...] [--lane X] [--status S]
    python scripts/qa/qa.py plan --run qa/runs/<name> [--lane X ...] [--force]
    python scripts/qa/qa.py report --run qa/runs/<name> [--compare qa/runs/<older>]

`check` runs in CI on every event (the `console-pages` job). `report` exits
non-zero when a result says fail and its note names no TD-, FI- or #number.

Stdlib only (tomllib needs Python 3.11+), so CI installs nothing.
"""
from __future__ import annotations

import argparse
import datetime
import re
import string
import subprocess
import sys
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA = 1

# ---------------------------------------------------------------------------
# Vocabularies. Each is the one place its words are defined; SCHEMA.md lists
# the same words.
# ---------------------------------------------------------------------------

NEEDS = ("emulator", "device", "web", "backend")
PHONE_NEEDS = {"emulator", "device"}
STATUSES = ("active", "deferred", "retired")
DEVICE_KINDS = ("emulator", "real")
RESULTS = ("pass", "fail", "blocked", "skipped")
NO_DEVICE = "-"

# A fail is "explained" when its note names where it is tracked.
EXPLAINED = re.compile(r"\b(?:TD|FI)-\d+\b|#\d+\b")
TAG = re.compile(r"^[a-z0-9][a-z0-9-]*$")
EXTENSION = "x-"

# Automation runners: the prefix of an `automation` reference, and how its
# target is found. "class" looks for <Class>.kt under the directory; "path" is
# a file relative to the repository root. A new runner (maestro:, testlab:)
# is one line here and one row in SCHEMA.md.
RUNNERS: dict[str, tuple[str, str | None]] = {
    "androidTest": ("class", "app/src/androidTest"),
    "unitTest": ("class", "app/src/test"),
    "pytest": ("path", None),
    "node": ("path", None),
    "check": ("path", None),
}
MANUAL = "manual"
PLANNED = "planned:"

# The fields of every table: name -> (type, required). Any other key must
# start with "x-" (SCHEMA.md, "Extending").
_STR, _LIST, _BOOL, _INT, _DICT = str, list, bool, int, dict
FIELDS: dict[str, dict[str, tuple[type, bool]]] = {
    "environments": {"schema": (_INT, True), "device": (_LIST, True), "build": (_LIST, True), "backend": (_LIST, True)},
    "device": {
        "id": (_STR, True), "kind": (_STR, True), "api": (_STR, True), "abi": (_STR, True),
        "page_size": (_STR, True), "tags": (_LIST, True), "use": (_BOOL, True),
        "avd": (_STR, False), "adb_device": (_STR, False), "model": (_STR, False), "note": (_STR, False),
    },
    "build": {
        "id": (_STR, True), "app": (_STR, True), "variant": (_STR, True), "abi": (_STR, True),
        "source": (_STR, True), "recipe": (_STR, True), "env": (_DICT, False), "note": (_STR, False),
    },
    "backend": {
        "id": (_STR, True), "service": (_STR, False), "gateway": (_STR, False), "console": (_STR, False),
        "docs": (_STR, False), "note": (_STR, False),
    },
    "accounts": {"schema": (_INT, True), "domain": (_STR, True), "institution": (_LIST, False), "account": (_LIST, True)},
    "institution": {
        "id": (_STR, True), "title": (_STR, True), "kind": (_STR, True), "domain_lock": (_STR, True),
        "max_seats": (_INT, True), "max_devices": (_INT, False), "apps": (_LIST, True), "admins": (_LIST, True),
        "note": (_STR, False),
    },
    "account": {
        "id": (_STR, True), "address": (_STR, True), "role": (_STR, True), "apps": (_LIST, True),
        "licence": (_STR, True), "created_by": (_STR, True), "used_for": (_STR, True),
        "max_devices": (_INT, False), "institution": (_STR, False), "second_factor": (_STR, False),
        "test_account": (_BOOL, False), "note": (_STR, False),
    },
    "lanes": {"schema": (_INT, True), "lane": (_LIST, True), "gate": (_LIST, False)},
    "lane": {
        "id": (_STR, True), "title": (_STR, True), "devices": (_LIST, True), "builds": (_LIST, True),
        "backend": (_STR, True), "suites": (_LIST, True), "groups": (_LIST, False),
        "scenarios": (_LIST, False), "tags": (_LIST, False), "exclude_tags": (_LIST, False),
        "tools": (_LIST, False), "note": (_STR, False),
    },
    "gate": {"id": (_STR, True), "who": (_STR, True), "ask": (_STR, True)},
    "suite": {
        "schema": (_INT, True), "id": (_STR, True), "title": (_STR, True), "prefix": (_STR, True),
        "since": (_STR, True), "issued": (_DICT, True), "scenario": (_LIST, True), "note": (_STR, False),
    },
    "scenario": {
        "id": (_STR, True), "title": (_STR, True), "area": (_STR, True), "tags": (_LIST, True),
        "accounts": (_LIST, True), "needs": (_LIST, True), "steps": (_LIST, True), "expected": (_STR, True),
        "automation": ((str, list), True), "status": (_STR, True), "since": (_STR, True),
        "human_gate": (_LIST, False), "devices": (_LIST, False), "builds": (_LIST, False),
        "note": (_STR, False), "replaced_by": (_STR, False),
    },
}

# Fields that hold a list of tables ([[device]], [[scenario]], ...), not strings.
TABLE_LISTS = {"device", "build", "backend", "institution", "account", "lane", "gate", "scenario"}

# The one placeholder an account address may hold (accounts.toml).
LANE_PLACEHOLDER = "{lane}"

ACCOUNT_LICENCES = ("individual", "institution", "floating", "none")
INSTITUTION_KINDS = ("assigned", "floating")


class QaError(Exception):
    """A file that cannot be read or parsed; the message names it."""


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

@dataclass
class Suite:
    path: Path
    data: dict

    @property
    def id(self) -> str:
        return self.data.get("id", self.path.stem)

    @property
    def scenarios(self) -> list[dict]:
        return self.data.get("scenario", [])


@dataclass
class Catalogue:
    root: Path
    qa: Path
    environments: dict
    accounts: dict
    lanes_doc: dict
    suites: list[Suite]
    errors: list[str] = field(default_factory=list)

    @property
    def devices(self) -> dict[str, dict]:
        return {d["id"]: d for d in self.environments.get("device", []) if "id" in d}

    @property
    def builds(self) -> dict[str, dict]:
        return {b["id"]: b for b in self.environments.get("build", []) if "id" in b}

    @property
    def backends(self) -> dict[str, dict]:
        return {b["id"]: b for b in self.environments.get("backend", []) if "id" in b}

    @property
    def account_map(self) -> dict[str, dict]:
        return {a["id"]: a for a in self.accounts.get("account", []) if "id" in a}

    @property
    def institutions(self) -> dict[str, dict]:
        return {i["id"]: i for i in self.accounts.get("institution", []) if "id" in i}

    @property
    def lanes(self) -> dict[str, dict]:
        return {lane["id"]: lane for lane in self.lanes_doc.get("lane", []) if "id" in lane}

    @property
    def gates(self) -> dict[str, dict]:
        return {g["id"]: g for g in self.lanes_doc.get("gate", []) if "id" in g}

    def scenario_map(self) -> dict[str, tuple[Suite, dict]]:
        out: dict[str, tuple[Suite, dict]] = {}
        for suite in self.suites:
            for sc in suite.scenarios:
                out.setdefault(sc.get("id", ""), (suite, sc))
        return out

    def suite(self, suite_id: str) -> Suite | None:
        return next((s for s in self.suites if s.id == suite_id), None)


def _read_toml(path: Path) -> dict:
    try:
        with path.open("rb") as fh:
            return tomllib.load(fh)
    except FileNotFoundError as exc:
        raise QaError(f"{path}: missing") from exc
    except tomllib.TOMLDecodeError as exc:
        raise QaError(f"{path}: not valid TOML: {exc}") from exc


def load(root: Path) -> Catalogue:
    qa = root / "qa"
    suites_dir = qa / "suites"
    suites = [Suite(p, _read_toml(p)) for p in sorted(suites_dir.glob("*.toml"))]
    return Catalogue(
        root=root,
        qa=qa,
        environments=_read_toml(qa / "environments.toml"),
        accounts=_read_toml(qa / "accounts.toml"),
        lanes_doc=_read_toml(qa / "lanes.toml"),
        suites=suites,
    )


# ---------------------------------------------------------------------------
# check
# ---------------------------------------------------------------------------

class Findings:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.infos: list[str] = []

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")

    def info(self, where: str, msg: str) -> None:
        self.infos.append(f"{where}: {msg}")


def _check_fields(f: Findings, where: str, kind: str, table: dict) -> None:
    spec = FIELDS[kind]
    for name, (typ, required) in spec.items():
        if name not in table:
            if required:
                f.error(where, f"missing required field `{name}`")
            continue
        value = table[name]
        # bool is an int in Python; keep "use = 1" from passing as a bool.
        bad_bool = typ is _INT and isinstance(value, bool)
        if bad_bool or not isinstance(value, typ):
            want = " or ".join(t.__name__ for t in typ) if isinstance(typ, tuple) else typ.__name__
            f.error(where, f"`{name}` must be {want}, not {type(value).__name__}")
        elif typ is _LIST and name not in TABLE_LISTS and not all(isinstance(v, str) for v in value):
            f.error(where, f"`{name}` must be a list of strings")
    for name in table:
        if name not in spec and not name.startswith(EXTENSION):
            f.error(where, f"unknown field `{name}` (new fields start with `{EXTENSION}` until SCHEMA.md adopts them)")


def _check_schema(f: Findings, where: str, doc: dict) -> None:
    if doc.get("schema") != SCHEMA:
        f.error(where, f"schema = {doc.get('schema')!r}; this tool reads schema = {SCHEMA} (SCHEMA.md, 'Changing the schema')")


def _unique(f: Findings, where: str, kind: str, tables: list[dict]) -> None:
    seen: set[str] = set()
    for t in tables:
        tid = t.get("id")
        if tid in seen:
            f.error(where, f"{kind} id `{tid}` appears twice")
        seen.add(tid)


def resolve_devices(cat: Catalogue, refs: list[str]) -> list[str]:
    """Device ids for a list of `id` / `tag:<tag>` references, in file order."""
    devices = cat.devices
    out: list[str] = []
    for ref in refs:
        if ref.startswith("tag:"):
            tag = ref[4:]
            out += [d for d, dev in devices.items() if tag in dev.get("tags", []) and dev.get("use", True)]
        elif ref in devices:
            out.append(ref)
    return list(dict.fromkeys(out))


def _check_device_refs(f: Findings, cat: Catalogue, where: str, refs: list[str], *, lane: bool) -> None:
    devices = cat.devices
    for ref in refs:
        if ref.startswith("tag:"):
            tag = ref[4:]
            if not any(tag in d.get("tags", []) for d in devices.values()):
                f.error(where, f"device tag `{tag}` matches no device in environments.toml")
        elif ref not in devices:
            f.error(where, f"device `{ref}` is not in environments.toml")
        elif lane and devices[ref].get("use") is False:
            f.error(where, f"device `{ref}` is marked use = false: {devices[ref].get('note', 'no reason given')}")


class _Resolver:
    """Finds automation targets; caches the Kotlin file names per directory."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self._classes: dict[str, set[str]] = {}

    def _class_names(self, rel_dir: str) -> set[str]:
        if rel_dir not in self._classes:
            base = self.root / rel_dir
            self._classes[rel_dir] = {p.stem for p in base.rglob("*.kt")} if base.is_dir() else set()
        return self._classes[rel_dir]

    def parse(self, ref: str) -> tuple[str, str, str | None] | str:
        """(runner, target, error) or an error message for a malformed ref."""
        runner, sep, rest = ref.partition(":")
        if not sep or not rest:
            return f"`{ref}` is not `{MANUAL}`, `{PLANNED}<ref>` or `<runner>:<target>`"
        if runner not in RUNNERS:
            return f"`{ref}`: unknown runner `{runner}` (known: {', '.join(RUNNERS)})"
        mode, _ = RUNNERS[runner]
        if mode == "class":
            target = rest.split("#", 1)[0]
            if not re.fullmatch(r"[A-Z][A-Za-z0-9_]*", target):
                return f"`{ref}`: `{target}` is not a Kotlin class name"
        else:
            target = re.split(r"::|#", rest, maxsplit=1)[0]
            if target.startswith(("/", "\\")) or ".." in Path(target).parts or ":" in target:
                return f"`{ref}`: `{target}` must be a path relative to the repository root"
        return runner, target, None

    def exists(self, runner: str, target: str) -> bool:
        mode, base = RUNNERS[runner]
        if mode == "class":
            return target in self._class_names(base or "")
        return (self.root / target).is_file()


def _check_automation(f: Findings, resolver: _Resolver, where: str, value) -> None:
    refs = [value] if isinstance(value, str) else list(value)
    if not refs:
        f.error(where, "`automation` is empty; use \"manual\"")
    for ref in refs:
        if not isinstance(ref, str):
            f.error(where, "`automation` entries must be strings")
            continue
        if ref == MANUAL:
            continue
        planned = ref.startswith(PLANNED)
        parsed = resolver.parse(ref[len(PLANNED):] if planned else ref)
        if isinstance(parsed, str):
            f.error(where, parsed)
            continue
        runner, target, _ = parsed
        found = resolver.exists(runner, target)
        if planned and found:
            f.info(where, f"`{ref}`: `{target}` exists now; drop `{PLANNED}` once it covers this scenario")
        elif not planned and not found:
            f.error(where, f"`{ref}`: `{target}` not found (mark a test that is not written yet `{PLANNED}{ref}`)")


def _check_environments(f: Findings, cat: Catalogue) -> None:
    where = "qa/environments.toml"
    env = cat.environments
    _check_schema(f, where, env)
    _check_fields(f, where, "environments", env)
    for kind in ("device", "build", "backend"):
        tables = env.get(kind, [])
        _unique(f, where, kind, tables)
        for t in tables:
            _check_fields(f, f"{where} [{kind} {t.get('id', '?')}]", kind, t)
    for d in env.get("device", []):
        w = f"{where} [device {d.get('id', '?')}]"
        if d.get("kind") not in DEVICE_KINDS:
            f.error(w, f"kind must be one of {DEVICE_KINDS}")
        if d.get("kind") == "emulator" and not d.get("avd"):
            f.error(w, "an emulator names its `avd`")
        if d.get("use") is False and not d.get("note"):
            f.error(w, "use = false needs a `note` saying why")
        for tag in d.get("tags", []):
            if isinstance(tag, str) and not TAG.match(tag):
                f.error(w, f"tag `{tag}` must be lower-case words joined by '-'")


def _check_accounts(f: Findings, cat: Catalogue) -> None:
    where = "qa/accounts.toml"
    doc = cat.accounts
    _check_schema(f, where, doc)
    _check_fields(f, where, "accounts", doc)
    _unique(f, where, "institution", doc.get("institution", []))
    _unique(f, where, "account", doc.get("account", []))
    accounts = cat.account_map
    for inst in doc.get("institution", []):
        w = f"{where} [institution {inst.get('id', '?')}]"
        _check_fields(f, w, "institution", inst)
        if inst.get("kind") not in INSTITUTION_KINDS:
            f.error(w, f"kind must be one of {INSTITUTION_KINDS}")
        for admin in inst.get("admins", []):
            if admin not in accounts:
                f.error(w, f"admin `{admin}` is not an account")
    for acc in doc.get("account", []):
        w = f"{where} [account {acc.get('id', '?')}]"
        _check_fields(f, w, "account", acc)
        if acc.get("licence") not in ACCOUNT_LICENCES:
            f.error(w, f"licence must be one of {ACCOUNT_LICENCES}")
        address = str(acc.get("address", ""))
        if re.search(r"[{}]", re.sub(r"\{lane\}|<[^>]*>", "", address)):
            f.error(w, f"address `{address}`: the only placeholder is {LANE_PLACEHOLDER}")
        if acc.get("test_account", True) and not address.endswith("@" + str(doc.get("domain"))):
            f.error(w, f"a test account's address is on `{doc.get('domain')}` (or set test_account = false and say why)")
        if acc.get("test_account") is False and not acc.get("note"):
            f.error(w, "test_account = false needs a `note` saying why")
        inst = acc.get("institution")
        if inst is not None and inst not in cat.institutions:
            f.error(w, f"institution `{inst}` is not in accounts.toml")
        if acc.get("licence") in ("institution", "floating") and inst is None:
            f.error(w, f"a {acc.get('licence')} account names its `institution`")
        if any(word in key.lower() for key in acc for word in ("password", "secret", "seed", "key")):
            f.error(w, "accounts.toml never holds a password, a secret or a key")


def _check_lanes(f: Findings, cat: Catalogue) -> None:
    where = "qa/lanes.toml"
    doc = cat.lanes_doc
    _check_schema(f, where, doc)
    _check_fields(f, where, "lanes", doc)
    _unique(f, where, "lane", doc.get("lane", []))
    _unique(f, where, "gate", doc.get("gate", []))
    for g in doc.get("gate", []):
        _check_fields(f, f"{where} [gate {g.get('id', '?')}]", "gate", g)
    groups = {g for s in cat.suites for g in s.data.get("issued", {})}
    scenarios = cat.scenario_map()
    owner: dict[str, str] = {}
    for lane in doc.get("lane", []):
        lid = lane.get("id", "?")
        w = f"{where} [lane {lid}]"
        _check_fields(f, w, "lane", lane)
        if not re.fullmatch(r"[A-Za-z0-9_-]+", str(lid)):
            f.error(w, "a lane id is letters, digits, '-' or '_' (it names LANE-<id>.md)")
        _check_device_refs(f, cat, w, lane.get("devices", []), lane=True)
        for dev in resolve_devices(cat, lane.get("devices", [])):
            if dev in owner:
                f.error(w, f"device `{dev}` is also in lane {owner[dev]}: no two lanes share a device")
            owner[dev] = lid
        for b in lane.get("builds", []):
            if b not in cat.builds:
                f.error(w, f"build `{b}` is not in environments.toml")
        if lane.get("backend") not in cat.backends:
            f.error(w, f"backend `{lane.get('backend')}` is not in environments.toml")
        for s in lane.get("suites", []):
            if cat.suite(s) is None:
                f.error(w, f"suite `{s}` is not in qa/suites/")
        for g in lane.get("groups", []):
            if g not in groups:
                f.error(w, f"group `{g}` is in no suite's [issued]")
        for sid in lane.get("scenarios", []):
            if sid not in scenarios:
                f.error(w, f"scenario `{sid}` does not exist")


def _check_suite(f: Findings, cat: Catalogue, suite: Suite, resolver: _Resolver) -> None:
    where = f"qa/suites/{suite.path.name}"
    data = suite.data
    _check_schema(f, where, data)
    _check_fields(f, where, "suite", data)
    if data.get("id") != suite.path.stem:
        f.error(where, f"id `{data.get('id')}` must match the file name `{suite.path.stem}`")
    prefix = data.get("prefix", "")
    if not re.fullmatch(r"[A-Z]+", str(prefix)):
        f.error(where, "prefix is one or more capital letters")
    issued = data.get("issued", {}) if isinstance(data.get("issued"), dict) else {}
    id_re = re.compile(rf"^({re.escape(str(prefix))}\d+)\.(\d+)$")
    present: dict[str, set[int]] = {}
    for g, top in issued.items():
        if not id_re.match(f"{g}.1"):
            f.error(where, f"[issued] key `{g}` is not `{prefix}<number>`")
        if not isinstance(top, int) or isinstance(top, bool) or top < 1:
            f.error(where, f"[issued] {g} must be a whole number ≥ 1")
    for sc in suite.scenarios:
        sid = sc.get("id", "?")
        w = f"{where} [{sid}]"
        _check_fields(f, w, "scenario", sc)
        m = id_re.match(str(sid))
        if not m:
            f.error(w, f"id must be {prefix}<group>.<n>")
        else:
            g, n = m.group(1), int(m.group(2))
            top = issued.get(g)
            if top is None:
                f.error(w, f"group `{g}` is not in [issued]")
            elif isinstance(top, int) and n > top:
                f.error(w, f"{sid} is above [issued] {g} = {top}; raise it when you add an id")
            present.setdefault(g, set()).add(n)
        status = sc.get("status")
        if status not in STATUSES:
            f.error(w, f"status must be one of {STATUSES}")
        elif status != "active" and not sc.get("note"):
            f.error(w, f"a {status} scenario says why in `note`")
        if sc.get("replaced_by") and status != "retired":
            f.error(w, "`replaced_by` is for a retired scenario")
        title = str(sc.get("title", ""))
        if "|" in title or "\n" in title:
            f.error(w, "a title has no '|' or line break (it goes in a Markdown table)")
        needs = sc.get("needs", [])
        if isinstance(needs, list):
            if not needs:
                f.error(w, "`needs` names at least one of " + ", ".join(NEEDS))
            for n_ in needs:
                if n_ not in NEEDS:
                    f.error(w, f"need `{n_}` is not one of {NEEDS}")
        for tag in sc.get("tags", []):
            if isinstance(tag, str) and not TAG.match(tag):
                f.error(w, f"tag `{tag}` must be lower-case words joined by '-'")
        if isinstance(sc.get("steps"), list) and not sc["steps"]:
            f.error(w, "`steps` has at least one step")
        for acc in sc.get("accounts", []):
            if acc not in cat.account_map:
                f.error(w, f"account `{acc}` is not in accounts.toml")
        for gate in sc.get("human_gate", []):
            if gate not in cat.gates:
                f.error(w, f"human gate `{gate}` is not a [[gate]] in lanes.toml")
        for b in sc.get("builds", []):
            if b not in cat.builds:
                f.error(w, f"build `{b}` is not in environments.toml")
        _check_device_refs(f, cat, w, sc.get("devices", []), lane=False)
        if "automation" in sc:
            _check_automation(f, resolver, w, sc["automation"])
    for g, top in issued.items():
        if isinstance(top, int):
            missing = sorted(set(range(1, top + 1)) - present.get(g, set()))
            for n in missing:
                f.error(where, f"{g}.{n} was issued and is gone: an id is never deleted; set status = \"retired\" and a note")


def _check_against_base(f: Findings, cat: Catalogue, base: str) -> None:
    """Every scenario id in the suites at `base` must still exist."""
    def git(*args: str) -> str:
        return subprocess.run(["git", *args], cwd=cat.root, check=True, capture_output=True,
                              text=True, encoding="utf-8").stdout

    try:
        git("rev-parse", "--verify", "--quiet", f"{base}^{{commit}}")
        names = git("ls-tree", "--name-only", base, "--", "qa/suites/").split()
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        f.error("--base", f"cannot read `{base}` with git: {(getattr(exc, 'stderr', '') or str(exc)).strip()}")
        return
    now = set(cat.scenario_map())
    for name in names:  # empty when the base has no qa/suites yet
        if not name.endswith(".toml"):
            continue
        try:
            old = tomllib.loads(git("show", f"{base}:{name}"))
        except (subprocess.CalledProcessError, tomllib.TOMLDecodeError):
            f.error(name, f"cannot read it at `{base}`")
            continue
        for sc in old.get("scenario", []):
            sid = sc.get("id")
            if sid and sid not in now:
                f.error(name, f"{sid} exists at {base} and is gone: retire it instead of deleting it")


def lane_rows(cat: Catalogue, lane: dict) -> list[tuple[dict, str]]:
    """(scenario, device id or "-") for every row the lane runs, in suite order.

    A scenario that needs no phone gets one "-" row. One that needs a phone
    gets a row per lane device that can run it: an emulator when it needs
    "emulator"; a real phone for "emulator" or "device". Its `devices`, if
    set, narrow that. A scenario with no row is not in the lane.
    """
    devices = cat.devices
    lane_devices = resolve_devices(cat, lane.get("devices", []))
    groups = set(lane.get("groups", []))
    explicit = set(lane.get("scenarios", []))
    want_tags = set(lane.get("tags", []))
    drop_tags = set(lane.get("exclude_tags", []))
    rows: list[tuple[dict, str]] = []
    for suite_id in lane.get("suites", []):
        suite = cat.suite(suite_id)
        if suite is None:
            continue
        for sc in suite.scenarios:
            if sc.get("status") != "active":
                continue
            sid = sc.get("id", "")
            in_group = sid.split(".", 1)[0] in groups
            if not (in_group or sid in explicit):
                continue
            tags = set(sc.get("tags", []))
            if sid not in explicit and ((want_tags and not tags & want_tags) or tags & drop_tags):
                continue
            needs = set(sc.get("needs", []))
            if not needs & PHONE_NEEDS:
                rows.append((sc, NO_DEVICE))
                continue
            allowed = set(resolve_devices(cat, sc["devices"])) if sc.get("devices") else None
            for dev_id in lane_devices:
                kind = devices[dev_id].get("kind")
                fits = "emulator" in needs if kind == "emulator" else True
                if fits and (allowed is None or dev_id in allowed):
                    rows.append((sc, dev_id))
    return rows


def check(cat: Catalogue, base: str | None = None) -> Findings:
    f = Findings()
    resolver = _Resolver(cat.root)
    _check_environments(f, cat)
    _check_accounts(f, cat)
    _check_lanes(f, cat)
    seen: dict[str, str] = {}
    prefixes: dict[str, str] = {}
    for suite in cat.suites:
        _check_suite(f, cat, suite, resolver)
        prefix = suite.data.get("prefix")
        if prefix in prefixes:
            f.error(f"qa/suites/{suite.path.name}", f"prefix `{prefix}` is also {prefixes[prefix]}'s")
        prefixes.setdefault(prefix, suite.path.name)
        for sc in suite.scenarios:
            sid = sc.get("id")
            if sid in seen:
                f.error(f"qa/suites/{suite.path.name}", f"id `{sid}` appears twice (also in {seen[sid]})")
            seen[sid] = suite.path.name
    if base:
        _check_against_base(f, cat, base)
    if not f.errors:
        routed = {sc.get("id") for lane in cat.lanes.values() for sc, _ in lane_rows(cat, lane)}
        for suite in cat.suites:
            for sc in suite.scenarios:
                if sc.get("status") == "active" and sc.get("id") not in routed:
                    f.warn(f"qa/suites/{suite.path.name} [{sc.get('id')}]", "active but in no lane (lanes.toml)")
    return f


# ---------------------------------------------------------------------------
# list
# ---------------------------------------------------------------------------

def list_scenarios(cat: Catalogue, suite: str | None, tags: list[str], lane: str | None, status: str | None) -> list[dict]:
    if lane is not None:
        if lane not in cat.lanes:
            raise QaError(f"lane `{lane}` is not in qa/lanes.toml")
        ids = {sc["id"] for sc, _ in lane_rows(cat, cat.lanes[lane])}
    out = []
    for s in cat.suites:
        if suite and s.id != suite:
            continue
        for sc in s.scenarios:
            if tags and not set(tags) <= set(sc.get("tags", [])):
                continue
            if status and sc.get("status") != status:
                continue
            if lane is not None and sc.get("id") not in ids:
                continue
            out.append(sc)
    return out


# ---------------------------------------------------------------------------
# plan
# ---------------------------------------------------------------------------

RESULT_COLUMNS = ("id", "title", "device", "build", "result", "evidence", "note")


def _cell(text: str) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def _table(rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(RESULT_COLUMNS) + " |", "|" + "|".join("---" for _ in RESULT_COLUMNS) + "|"]
    lines += ["| " + " | ".join(_cell(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


def _bullets(items: list[str], empty: str) -> str:
    return "\n".join(f"- {i}" for i in items) if items else f"- {empty}"


def _render(template: Path, values: dict[str, str]) -> str:
    try:
        return string.Template(template.read_text(encoding="utf-8")).substitute(values)
    except KeyError as exc:
        raise QaError(f"{template}: unknown placeholder ${exc.args[0]}") from exc
    except ValueError as exc:
        raise QaError(f"{template}: {exc} (write a literal dollar sign as $$)") from exc


def lane_address(account: dict, lane_id: str) -> str:
    """The account's address for one lane: `{lane}` is the lane id, lower case."""
    return str(account.get("address", "")).replace(LANE_PLACEHOLDER, lane_id.lower())


def _display(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def plan(cat: Catalogue, run: Path, lanes: list[str] | None, force: bool, today: str | None = None) -> list[Path]:
    chosen = lanes or list(cat.lanes)
    for lid in chosen:
        if lid not in cat.lanes:
            raise QaError(f"lane `{lid}` is not in qa/lanes.toml")
    today = today or datetime.date.today().isoformat()
    run.mkdir(parents=True, exist_ok=True)
    (run / "evidence").mkdir(exist_ok=True)
    written: list[Path] = []
    for lid in chosen:
        lane = cat.lanes[lid]
        results, brief = run / f"LANE-{lid}.md", run / f"BRIEF-{lid}.md"
        for path in (results, brief):
            if path.exists() and not force:
                raise QaError(f"{path} exists; it may hold results. Pass --force to overwrite it")
        rows = lane_rows(cat, lane)
        lane_builds = lane.get("builds", [])
        table_rows = []
        for sc, dev in rows:
            builds = [b for b in sc.get("builds", lane_builds) if b in lane_builds]
            table_rows.append([sc["id"], sc.get("title", ""), dev, builds[0] if len(builds) == 1 else "", "", "", ""])
        devices = cat.devices
        dev_lines = []
        for d in resolve_devices(cat, lane.get("devices", [])):
            dev = devices[d]
            how = f"AVD `{dev['avd']}`" if dev.get("kind") == "emulator" else f"real phone, `adb devices -l` shows device:{dev.get('adb_device', '?')}"
            dev_lines.append(f"`{d}`: {how}, API {dev.get('api')}, {dev.get('abi')}, {dev.get('page_size')} pages")
        build_lines = []
        for b in lane_builds:
            build = cat.builds[b]
            env = ", ".join(f"{k}={v}" for k, v in build.get("env", {}).items())
            build_lines.append(f"`{b}`: {build.get('recipe')}" + (f" (env: {env})" if env else ""))
        accounts = sorted({a for sc, _ in rows for a in sc.get("accounts", [])})
        acc_map = cat.account_map
        acc_lines = [f"`{a}`: {lane_address(acc_map[a], lid)} ({acc_map[a].get('used_for')})" for a in accounts]
        for iid in sorted({acc_map[a]["institution"] for a in accounts if acc_map[a].get("institution")}):
            inst = cat.institutions[iid]
            admins = ", ".join(f"`{a}`" for a in inst.get("admins", [])) or "none"
            pool = f", pool of {inst['max_devices']} phones" if "max_devices" in inst else ""
            acc_lines.append(f"Institution {iid} for this lane: {inst.get('title')}; {inst.get('kind')}, "
                             f"domain lock {inst.get('domain_lock')}, {inst.get('max_seats')} seats{pool}; IT: {admins}")
        gate_ids = sorted({g for sc, _ in rows for g in sc.get("human_gate", [])})
        gate_lines = []
        for g in gate_ids:
            gate = cat.gates[g]
            used_by = ", ".join(sorted({sc["id"] for sc, _ in rows if g in sc.get("human_gate", [])}, key=_id_key))
            gate_lines.append(f"`{g}` ({gate.get('who')}): {gate.get('ask')} Used by {used_by}.")
        values = {
            "run": _display(run, cat.root),
            "lane_id": lid,
            "lane_title": lane.get("title", ""),
            "devices": _bullets(dev_lines, "none: this lane uses no phone"),
            "builds": _bullets(build_lines, "none"),
            "backend": lane.get("backend", ""),
            "tools": ", ".join(lane.get("tools", [])) or "none",
            "suites": ", ".join(lane.get("suites", [])),
            "accounts": _bullets(acc_lines, "none"),
            "gates": _bullets(gate_lines, "none: nothing in this lane needs a person"),
            "generated": today,
            "count": str(len(table_rows)),
            "results_file": _display(results, cat.root),
            "table": _table(table_rows),
        }
        results.write_text(_render(cat.qa / "templates" / "lane-results.md", values), encoding="utf-8", newline="\n")
        brief.write_text(_render(cat.qa / "templates" / "lane-brief.md", values), encoding="utf-8", newline="\n")
        written += [results, brief]
    return written


# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------

@dataclass
class Row:
    lane: str
    id: str
    device: str
    build: str
    result: str
    evidence: str
    note: str

    @property
    def explained(self) -> bool:
        return bool(EXPLAINED.search(self.note))


def _split_row(line: str) -> list[str]:
    cells = re.split(r"(?<!\\)\|", line.strip())
    return [c.strip().replace("\\|", "|") for c in cells[1:-1]]


def read_lane_file(path: Path) -> list[Row]:
    lane = path.stem.split("-", 1)[1]
    lines = path.read_text(encoding="utf-8").splitlines()
    header_at = next((i for i, ln in enumerate(lines) if ln.strip().startswith("|") and _split_row(ln)[:1] == ["id"]), None)
    if header_at is None:
        raise QaError(f"{path}: no results table (a row starting `| id |`)")
    header = _split_row(lines[header_at])
    if tuple(header) != RESULT_COLUMNS:
        raise QaError(f"{path}: the table's columns must be {' | '.join(RESULT_COLUMNS)}")
    rows = []
    for n, ln in enumerate(lines[header_at + 2:], start=header_at + 3):
        if not ln.strip().startswith("|"):
            break
        cells = _split_row(ln)
        if len(cells) != len(RESULT_COLUMNS):
            raise QaError(f"{path}:{n}: {len(cells)} cells, expected {len(RESULT_COLUMNS)}")
        sid, _title, device, build, result, evidence, note = cells
        result = result.lower()
        if result not in RESULTS and result != "":
            raise QaError(f"{path}:{n}: result `{result}` is not one of {', '.join(RESULTS)} (or blank: not run)")
        rows.append(Row(lane, sid, device or NO_DEVICE, build, result, evidence, note))
    return rows


def read_run(cat: Catalogue, run: Path) -> list[Row]:
    files = sorted(run.glob("LANE-*.md"))
    if not files:
        raise QaError(f"{run}: no LANE-*.md files; run `qa.py plan --run {run.as_posix()}` first")
    rows: list[Row] = []
    known = cat.scenario_map()
    for path in files:
        lane = path.stem.split("-", 1)[1]
        if lane not in cat.lanes:
            raise QaError(f"{path}: lane `{lane}` is not in qa/lanes.toml")
        for row in read_lane_file(path):
            if row.id not in known:
                raise QaError(f"{path}: scenario `{row.id}` is not in the catalogue")
            if row.device != NO_DEVICE and row.device not in cat.devices:
                raise QaError(f"{path}: device `{row.device}` is not in environments.toml")
            rows.append(row)
    return rows


def _id_key(sid: str) -> tuple:
    m = re.match(r"^([A-Z]+)(\d+)\.(\d+)$", sid)
    return (m.group(1), int(m.group(2)), int(m.group(3))) if m else (sid, 0, 0)


def _combine(results: list[str]) -> str:
    for r in ("fail", "pass", "blocked", "skipped"):
        if r in results:
            return r
    return ""


def verdicts(cat: Catalogue, rows: list[Row]) -> dict[str, str]:
    """Per scenario: pass, fail, blocked, skipped, incomplete or not run.

    A scenario passes when, among its rows: every real phone passed, at least
    one emulator passed if it has emulator rows, and at least one row passed
    if it has only no-phone rows. Any fail makes it fail.
    """
    kinds = {d: dev.get("kind") for d, dev in cat.devices.items()}
    by_id: dict[str, list[Row]] = {}
    for r in rows:
        by_id.setdefault(r.id, []).append(r)
    out = {}
    for sid, rs in by_id.items():
        results = [r.result for r in rs]
        if "fail" in results:
            out[sid] = "fail"
            continue
        if all(r == "" for r in results):
            out[sid] = "not run"
            continue
        per_device: dict[str, str] = {}
        for r in rs:
            per_device[r.device] = _combine([per_device.get(r.device, ""), r.result])
        real = [d for d in per_device if kinds.get(d) == "real"]
        emus = [d for d in per_device if kinds.get(d) == "emulator"]
        ok = all(per_device[d] == "pass" for d in real)
        if emus:
            ok = ok and any(per_device[d] == "pass" for d in emus)
        if not real and not emus:
            ok = any(v == "pass" for v in per_device.values())
        if ok:
            out[sid] = "pass"
        elif "blocked" in results:
            out[sid] = "blocked"
        elif "" in results:
            out[sid] = "incomplete"
        else:
            out[sid] = "skipped"
    return out


_SYMBOL = {"pass": "pass", "fail": "FAIL", "blocked": "blocked", "skipped": "skip", "": "·"}


def build_report(cat: Catalogue, run: Path, rows: list[Row], compare: tuple[Path, list[Row]] | None) -> tuple[str, int]:
    verdict = verdicts(cat, rows)
    scenarios = cat.scenario_map()
    ids = sorted(verdict, key=_id_key)
    kinds = {d: dev.get("kind") for d, dev in cat.devices.items()}
    order = list(cat.devices) + [NO_DEVICE]
    devices = [d for d in order if any(r.device == d for r in rows)]
    unexplained = [r for r in rows if r.result == "fail" and not r.explained]

    counts: dict[str, int] = {}
    for v in verdict.values():
        counts[v] = counts.get(v, 0) + 1
    lines = [f"# QC report: {run.name}", ""]
    lines.append(f"Generated by `python scripts/qa/qa.py report --run {run.as_posix()}`"
                 + (f" (compared with {compare[0].as_posix()})" if compare else "") + ". Do not edit by hand; edit the LANE files and run it again.")
    lines += ["", "## Summary", "", "| verdict | scenarios |", "|---|---|"]
    for v in ("pass", "fail", "blocked", "incomplete", "skipped", "not run"):
        lines.append(f"| {v} | {counts.get(v, 0)} |")
    lines += ["", f"Rows: {len(rows)} from {len({r.lane for r in rows})} lane file(s). "
              f"Unexplained fails: {len(unexplained)}."]
    lines += ["", "A scenario passes when every real phone it ran on passed, at least one emulator passed (if it ran on one), "
              "and, for a scenario with no phone, at least one row passed. Any fail makes it fail."]

    lines += ["", "## Scenario × device", "", "Cells: pass, FAIL, blocked, skip, · not run, blank not planned. "
              f"`{NO_DEVICE}` is a row with no phone (web or backend).", ""]
    header = ["scenario", "verdict"] + [f"{d} ({'real' if kinds.get(d) == 'real' else 'emu'})" if d != NO_DEVICE else NO_DEVICE for d in devices]
    lines.append("| " + " | ".join(header) + " |")
    lines.append("|" + "|".join("---" for _ in header) + "|")
    for sid in ids:
        cells = [sid, verdict[sid]]
        for d in devices:
            rs = [r.result for r in rows if r.id == sid and r.device == d]
            cells.append(_SYMBOL[_combine(rs)] if rs else "")
        lines.append("| " + " | ".join(cells) + " |")

    def listing(title: str, picked: list[Row], empty: str) -> None:
        lines.extend(["", f"## {title}", ""])
        if not picked:
            lines.append(empty)
            return
        lines.append("| scenario | lane | device | build | note | evidence |")
        lines.append("|---|---|---|---|---|---|")
        for r in sorted(picked, key=lambda r: (_id_key(r.id), r.device)):
            lines.append(f"| {r.id} {_cell(scenarios[r.id][1].get('title', ''))} | {r.lane} | {r.device} | {_cell(r.build)} | {_cell(r.note)} | {_cell(r.evidence)} |")

    listing("Failures", [r for r in rows if r.result == "fail"], "None.")
    if unexplained:
        lines += ["", "**Unexplained:** " + ", ".join(sorted({r.id for r in unexplained}, key=_id_key))
                  + ". Each needs a TD-, FI- or #number in its note (a TECH_DEBT row, or the PR that fixes it)."]
    listing("Blocked", [r for r in rows if r.result == "blocked"], "None.")

    lines += ["", "## Coverage by kind of device", ""]
    groups = {"emulator and a real phone": [], "emulator only": [], "real phone only": [], "no phone": []}
    for sid in ids:
        passed = {r.device for r in rows if r.id == sid and r.result == "pass"}
        emu = any(kinds.get(d) == "emulator" for d in passed)
        real = any(kinds.get(d) == "real" for d in passed)
        if emu and real:
            groups["emulator and a real phone"].append(sid)
        elif emu:
            groups["emulator only"].append(sid)
        elif real:
            groups["real phone only"].append(sid)
        elif NO_DEVICE in passed:
            groups["no phone"].append(sid)
    for name, sids in groups.items():
        lines.append(f"- **Passed on {name}** ({len(sids)}): " + (", ".join(sids) if sids else "none"))
    not_run = [sid for sid in ids if verdict[sid] in ("not run", "incomplete")]
    lines.append(f"- **Not run or incomplete** ({len(not_run)}): " + (", ".join(not_run) if not_run else "none"))

    if compare:
        before = verdicts(cat, compare[1])
        lines += ["", f"## Compared with {compare[0].name}", ""]
        changed = []
        for sid in sorted(set(before) | set(verdict), key=_id_key):
            a, b = before.get(sid, "absent"), verdict.get(sid, "absent")
            if a != b:
                mark = " **regression**" if a == "pass" and b == "fail" else ""
                changed.append(f"| {sid} | {a} | {b}{mark} |")
        if changed:
            lines += ["| scenario | before | now |", "|---|---|---|", *changed]
        else:
            lines.append("No verdict changed.")
    lines.append("")
    return "\n".join(lines), (1 if unexplained else 0)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(errors="replace")  # titles are UTF-8; a cp1252 console must not crash
    except AttributeError:
        pass
    ap = argparse.ArgumentParser(prog="qa.py", description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", type=Path, default=None, help="repository root (default: this checkout)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("check", help="validate qa/ (CI runs this)")
    p.add_argument("--base", help="also fail when an id present at this git ref is gone (CI passes HEAD^1)")
    p.add_argument("--verbose", action="store_true", help="also print notes, e.g. a planned test that now exists")

    p = sub.add_parser("list", help="print matching scenarios")
    p.add_argument("--suite")
    p.add_argument("--tag", action="append", default=[], help="repeatable; a scenario must carry every tag given")
    p.add_argument("--lane", help="only the scenarios this lane runs")
    p.add_argument("--status", choices=STATUSES)

    p = sub.add_parser("plan", help="write LANE-<X>.md and BRIEF-<X>.md for a run")
    p.add_argument("--run", type=Path, required=True, help="run directory, e.g. qa/runs/2026-11-licence-activation")
    p.add_argument("--lane", action="append", help="only these lanes (repeatable); default every lane")
    p.add_argument("--force", action="store_true", help="overwrite existing lane files (loses their results)")

    p = sub.add_parser("report", help="merge a run's lane files into REPORT.md")
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--compare", type=Path, help="an earlier run directory to diff verdicts against")

    args = ap.parse_args(argv)
    root = (args.root or _repo_root()).resolve()
    try:
        cat = load(root)
        if args.cmd == "check":
            f = check(cat, args.base)
            for e in f.errors:
                print(f"ERROR {e}")
            for w in f.warnings:
                print(f"warning {w}")
            if args.verbose:
                for i in f.infos:
                    print(f"note {i}")
            n = sum(len(s.scenarios) for s in cat.suites)
            if f.errors:
                print(f"qa check: {len(f.errors)} error(s)")
                return 1
            print(f"qa check: OK ({n} scenarios in {len(cat.suites)} suite(s), {len(cat.lanes)} lanes, "
                  f"{len(f.warnings)} warning(s))")
            return 0
        f = check(cat)
        if f.errors:
            for e in f.errors:
                print(f"ERROR {e}")
            print("qa: fix `qa.py check` first")
            return 1
        if args.cmd == "list":
            found = list_scenarios(cat, args.suite, args.tag, args.lane, args.status)
            for sc in found:
                print(f"{sc['id']:<7} {sc['status']:<8} {','.join(sc['needs']):<22} {sc['title']}")
            print(f"{len(found)} scenario(s)")
            return 0
        if args.cmd == "plan":
            for path in plan(cat, args.run, args.lane, args.force):
                print(f"wrote {path.as_posix()}")
            return 0
        if args.cmd == "report":
            rows = read_run(cat, args.run)
            compare = (args.compare, read_run(cat, args.compare)) if args.compare else None
            text, code = build_report(cat, args.run, rows, compare)
            out = args.run / "REPORT.md"
            out.write_text(text, encoding="utf-8", newline="\n")
            v = verdicts(cat, rows)
            summary = ", ".join(f"{k} {sum(1 for x in v.values() if x == k)}" for k in ("pass", "fail", "blocked", "incomplete", "not run"))
            print(f"wrote {out.as_posix()} ({summary})")
            if code:
                print("qa report: a fail has no TD-, FI- or #number in its note")
            return code
    except QaError as exc:
        print(f"ERROR {exc}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
