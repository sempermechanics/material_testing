#!/usr/bin/env python3
"""Inspect a release APK for the hardening a release must have (readiness A1.5).

The release build type sets these in `app/build.gradle.kts`, but nothing proved
the APK that comes out actually has them: a manifest merge, a stray
`debugImplementation` moved to `implementation`, or a build-type edit could
ship a debuggable or debug-provider build that still signs and installs.
MASWE-0067 (debuggable flag) and MASWE-0089 (no obfuscation) are the two this
gate is named after.

Checks (FAIL exits 1; WARN is reported and does not fail):

  debuggable        application android:debuggable is not true (manifest and
                    `aapt2 dump badging`); android:testOnly is not true
  debug-components  no activity / alias / provider / receiver / service from the
                    debug source set (app/src/debug: its manifest and its
                    classes) is in the manifest or in any classes*.dex
  appcheck-debug    no `DebugAppCheckProviderFactory` in any classes*.dex, and no
                    firebase-appcheck-debug version file in META-INF
  cleartext         android:usesCleartextTraffic is not true, and the network
                    security config (when one is set and resolvable) does not
                    permit cleartext in base-config or a domain-config
  min-sdk           the APK's minSdkVersion equals `minSdk` in app/build.gradle.kts
  target-sdk        the APK's targetSdkVersion equals `targetSdk` in
                    app/build.gradle.kts and is at least Play's floor (36)
  native-16kb       every lib/<abi>/*.so is ELF with each LOAD segment aligned to
                    16 KB or more, and a .so stored uncompressed starts at a 16 KB
                    offset in the zip (Play: 16 KB page sizes); the APK has one
  r8-mapping        with --mapping: the file exists, is non-empty and holds
                    `a -> b` lines (evidence R8 ran)
  cert-pins         WARN when no `sha256/<base64>` pin string is in the dex,
                    i.e. SEMPER_API_CERT_PINS (BuildConfig) was empty at build

aapt2 comes from --aapt2, $AAPT2, PATH, or the newest
$ANDROID_HOME/build-tools/*/aapt2 (the same lookup the workflows use for
apksigner). Standard library only.

Usage:
  python3 scripts/check_release_apk.py APP.apk [--mapping mapping.txt]
      [--gradle app/build.gradle.kts] [--debug-src app/src/debug] [--aapt2 PATH]
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"
COMPONENT_TAGS = ("activity", "activity-alias", "provider", "receiver", "service")
DEBUG_APPCHECK = b"DebugAppCheckProviderFactory"
PIN_RE = re.compile(rb"sha256/[A-Za-z0-9+/]{43}=")
# Play refuses new apps and updates that target less, from 2026-08-31.
PLAY_MIN_TARGET_SDK = 36
# Android 15+ devices may run with 16 KB pages; Play requires native code to load there.
PAGE_16K = 16384
NATIVE_LIB_RE = re.compile(r"lib/[^/]+/[^/]+\.so")


# --- aapt2 output parsers ----------------------------------------------------


@dataclass
class Element:
    """One element of `aapt2 dump xmltree` output."""

    name: str
    indent: int
    attrs: dict[str, str] = field(default_factory=dict)
    children: list["Element"] = field(default_factory=list)

    def iter(self, *names: str):
        """This element and every descendant, filtered by name when given."""
        if not names or self.name in names:
            yield self
        for child in self.children:
            yield from child.iter(*names)

    def find(self, name: str) -> "Element | None":
        return next(self.iter(name), None)


_ELEMENT_LINE = re.compile(r"^(\s*)E: (\S+)")
_ATTR_LINE = re.compile(r"^(\s*)A: (.+?)=(.*)$")
_ATTR_NAME = re.compile(r"^(?P<local>[A-Za-z_][\w.-]*)(?:\(0x[0-9a-fA-F]+\))?$")
_QUOTED = re.compile(r'^"(.*?)"(?: \(Raw: ".*"\))?$')
_TYPED = re.compile(r"^\(type 0x[0-9a-fA-F]+\)(\S+)$")


def _attr_name(raw: str) -> str:
    """`http://…/android:debuggable(0x0101000f)` -> `android:debuggable`."""
    # The namespace is a URI with colons of its own, so split on the last ':'
    # that precedes the local name (and its optional resource id).
    m = re.match(r"^(?P<ns>.*):(?P<local>[A-Za-z_][\w.-]*)(?:\(0x[0-9a-fA-F]+\))?$", raw)
    if m and m.group("ns"):
        prefix = "android" if m.group("ns") == ANDROID_NS else m.group("ns")
        return f"{prefix}:{m.group('local')}"
    m = _ATTR_NAME.match(raw)
    return m.group("local") if m else raw


def _attr_value(raw: str) -> str:
    """Strip aapt2's quoting (`"x" (Raw: "x")`) and aapt's `(type 0x..)` tag."""
    raw = raw.strip()
    m = _QUOTED.match(raw)
    if m:
        return m.group(1)
    m = _TYPED.match(raw)
    if m:
        return m.group(1)
    return raw


def parse_xmltree(text: str) -> Element:
    """Parse `aapt2 dump xmltree` output into an Element tree under a synthetic root."""
    root = Element("#root", -1)
    stack = [root]
    for line in text.splitlines():
        m = _ELEMENT_LINE.match(line)
        if m:
            indent = len(m.group(1))
            while stack[-1].indent >= indent:
                stack.pop()
            el = Element(m.group(2), indent)
            stack[-1].children.append(el)
            stack.append(el)
            continue
        m = _ATTR_LINE.match(line)
        if m:
            indent = len(m.group(1))
            while len(stack) > 1 and stack[-1].indent >= indent:
                stack.pop()
            stack[-1].attrs[_attr_name(m.group(2))] = _attr_value(m.group(3))
    return root


def as_bool(value: str | None) -> bool | None:
    """aapt2 prints `true`/`false`; aapt and older dumps print 0xffffffff / 0x0."""
    if value is None:
        return None
    v = value.strip().lower()
    if v in ("true", "0xffffffff", "-1", "1"):
        return True
    if v in ("false", "0x0", "0x00000000", "0"):
        return False
    return None


def as_int(value: str | None) -> int | None:
    if value is None:
        return None
    v = _attr_value(value)
    try:
        return int(v, 16) if v.lower().startswith("0x") else int(v)
    except ValueError:
        return None


def parse_badging(text: str) -> dict:
    """The few `aapt2 dump badging` facts this gate reads."""
    out: dict = {"debuggable": False, "test_only": False}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            m = re.search(r"name='([^']*)'", line)
            out["package"] = m.group(1) if m else None
        elif line.startswith("sdkVersion:") or line.startswith("minSdkVersion:"):
            out["min_sdk"] = as_int(line.split(":", 1)[1].strip().strip("'"))
        elif line.startswith("targetSdkVersion:"):
            out["target_sdk"] = as_int(line.split(":", 1)[1].strip().strip("'"))
        elif line == "application-debuggable":
            out["debuggable"] = True
        elif line.startswith("testOnly="):
            out["test_only"] = as_bool(line.split("=", 1)[1].strip("'")) is True
    return out


def resource_file(dump: str, res_id: int) -> str | None:
    """The file path of resource `res_id` in `aapt2 dump resources` output.

    The dump lists `resource 0x7f150004 xml/network_security_config` followed
    by one or more `(<config>) (file) res/…xml type=XML` lines.
    """
    want = f"0x{res_id:08x}"
    lines = dump.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^\s*resource (0x[0-9a-fA-F]{8}) ", line)
        if not m or m.group(1).lower() != want:
            continue
        for follow in lines[i + 1:]:
            if re.match(r"^\s*resource 0x", follow) or re.match(r"^\s*type ", follow):
                break
            f = re.search(r"\(file\) (\S+)", follow)
            if f:
                return f.group(1)
        return None
    return None


# --- native libraries --------------------------------------------------------


def elf_load_aligns(data: bytes) -> list[int] | None:
    """`p_align` of every PT_LOAD segment of an ELF file, or None when `data` is not ELF.

    Reads ELF32 and ELF64 in either byte order: only the header's program-header
    table fields and each entry's type and alignment.
    """
    if len(data) < 52 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        return None
    is64 = data[4] == 2
    end = "<" if data[5] == 1 else ">"
    if is64:
        if len(data) < 64:
            return None
        (phoff,) = struct.unpack_from(end + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
        align_at, align_fmt = 48, "Q"
    else:
        (phoff,) = struct.unpack_from(end + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
        align_at, align_fmt = 28, "I"
    if phentsize < align_at + struct.calcsize(align_fmt) or phoff + phnum * phentsize > len(data):
        return None
    aligns = []
    for i in range(phnum):
        entry = phoff + i * phentsize
        (p_type,) = struct.unpack_from(end + "I", data, entry)
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from(end + align_fmt, data, entry + align_at)[0])
    return aligns


def zip_data_offset(fh, info: zipfile.ZipInfo) -> int:
    """Where an entry's bytes start: after its local header, name and extra field."""
    fh.seek(info.header_offset)
    header = fh.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


# --- inputs from the repo ----------------------------------------------------


def gradle_min_sdk(text: str) -> int | None:
    """`minSdk = 30` (or `minSdk(30)` / `minSdkVersion 30`) from a Gradle script."""
    m = re.search(r"^\s*minSdk(?:Version)?\s*(?:=|\(|\s)\s*(\d+)", text, re.MULTILINE)
    return int(m.group(1)) if m else None


def gradle_target_sdk(text: str) -> int | None:
    """`targetSdk = 36` (or `targetSdk(36)` / `targetSdkVersion 36`) from a Gradle script."""
    m = re.search(r"^\s*targetSdk(?:Version)?\s*(?:=|\(|\s)\s*(\d+)", text, re.MULTILINE)
    return int(m.group(1)) if m else None


def gradle_namespace(text: str) -> str | None:
    m = re.search(r'^\s*namespace\s*=\s*"([^"]+)"', text, re.MULTILINE)
    return m.group(1) if m else None


def debug_classes(debug_src: Path, namespace: str | None) -> set[str]:
    """Fully qualified names the debug source set adds: its manifest's
    components and every class file under java/ or kotlin/."""
    names: set[str] = set()
    manifest = debug_src / "AndroidManifest.xml"
    if manifest.is_file():
        tree = ET.parse(manifest)
        for tag in COMPONENT_TAGS:
            for el in tree.iter(tag):
                name = el.get(f"{{{ANDROID_NS}}}name")
                if not name:
                    continue
                if name.startswith(".") and namespace:
                    name = namespace + name
                elif "." not in name and namespace:
                    name = f"{namespace}.{name}"
                names.add(name)
    for sub in ("java", "kotlin"):
        base = debug_src / sub
        if not base.is_dir():
            continue
        for src in sorted(base.rglob("*")):
            if src.suffix in (".kt", ".java"):
                rel = src.relative_to(base).with_suffix("")
                names.add(".".join(rel.parts))
    return names


def dex_descriptor(fqcn: str) -> bytes:
    return ("L" + fqcn.replace(".", "/") + ";").encode()


# --- the checks --------------------------------------------------------------


@dataclass
class Result:
    check: str
    status: str  # PASS | FAIL | WARN | SKIP
    detail: str


@dataclass
class NativeLib:
    """One lib/<abi>/*.so: its LOAD alignments (None when not ELF) and how it sits in the zip."""

    name: str
    load_aligns: list[int] | None
    stored: bool
    data_offset: int


@dataclass
class ApkFacts:
    manifest: Element
    badging: dict
    dex: dict[str, bytes]
    entries: list[str]
    nsc_tree: Element | None = None
    nsc_note: str = ""
    native_libs: list[NativeLib] = field(default_factory=list)


def _native_problems(lib: NativeLib) -> list[str]:
    if lib.load_aligns is None:
        return [f"{lib.name}: not an ELF file"]
    problems = [f"{lib.name}: LOAD p_align {a}" for a in lib.load_aligns if a < PAGE_16K]
    if not lib.load_aligns:
        problems.append(f"{lib.name}: no LOAD segment")
    if lib.stored and lib.data_offset % PAGE_16K:
        problems.append(f"{lib.name}: stored at zip offset {lib.data_offset}, not a multiple of {PAGE_16K}")
    return problems


def evaluate(facts: ApkFacts, *, expected_min_sdk: int | None, expected_target_sdk: int | None,
             debug_names: set[str], mapping: Path | None) -> list[Result]:
    results: list[Result] = []
    app = facts.manifest.find("application") or Element("application", 0)

    # debuggable / testOnly
    dbg_manifest = as_bool(app.attrs.get("android:debuggable")) is True
    dbg_badging = bool(facts.badging.get("debuggable"))
    test_only = as_bool(app.attrs.get("android:testOnly")) is True or bool(facts.badging.get("test_only"))
    if dbg_manifest or dbg_badging or test_only:
        why = []
        if dbg_manifest:
            why.append("manifest android:debuggable=true")
        if dbg_badging:
            why.append("badging says application-debuggable")
        if test_only:
            why.append("android:testOnly=true")
        results.append(Result("debuggable", "FAIL", "; ".join(why)))
    else:
        results.append(Result("debuggable", "PASS", "not debuggable, not testOnly"))

    # debug-source-set components
    shipped = {
        el.attrs.get("android:name", ""): el.name
        for el in app.iter(*COMPONENT_TAGS)
    }
    in_manifest = sorted(n for n in shipped if n in debug_names)
    in_dex = sorted(
        n for n in debug_names
        if any(dex_descriptor(n) in data for data in facts.dex.values())
    )
    if in_manifest or in_dex:
        parts = [f"manifest: {', '.join(in_manifest)}"] if in_manifest else []
        if in_dex:
            parts.append(f"dex: {', '.join(in_dex)}")
        results.append(Result("debug-components", "FAIL", "; ".join(parts)))
    elif not debug_names:
        results.append(Result("debug-components", "WARN", "no debug source set names found to check against"))
    else:
        results.append(Result("debug-components", "PASS",
                              f"none of {len(debug_names)} debug-only names shipped"))

    # App Check debug provider
    hits = sorted(name for name, data in facts.dex.items() if DEBUG_APPCHECK in data)
    meta = sorted(e for e in facts.entries
                  if e.startswith("META-INF/") and "firebase-appcheck-debug" in e)
    if hits or meta:
        results.append(Result("appcheck-debug", "FAIL",
                              "; ".join(filter(None, [
                                  f"DebugAppCheckProviderFactory in {', '.join(hits)}" if hits else "",
                                  f"{', '.join(meta)}" if meta else "",
                              ]))))
    elif not facts.dex:
        results.append(Result("appcheck-debug", "FAIL", "no classes*.dex in the APK"))
    else:
        results.append(Result("appcheck-debug", "PASS", f"absent from {len(facts.dex)} dex file(s)"))

    # cleartext
    cleartext = as_bool(app.attrs.get("android:usesCleartextTraffic"))
    nsc_bad: list[str] = []
    if facts.nsc_tree is not None:
        for el in facts.nsc_tree.iter("base-config", "domain-config"):
            if as_bool(el.attrs.get("cleartextTrafficPermitted")) is True:
                nsc_bad.append(el.name)
    if cleartext is True or nsc_bad:
        detail = []
        if cleartext is True:
            detail.append("android:usesCleartextTraffic=true")
        if nsc_bad:
            detail.append(f"network security config permits cleartext in {', '.join(nsc_bad)}")
        results.append(Result("cleartext", "FAIL", "; ".join(detail)))
    elif facts.nsc_note:
        results.append(Result("cleartext", "WARN", f"usesCleartextTraffic not true; {facts.nsc_note}"))
    else:
        results.append(Result("cleartext", "PASS",
                              "usesCleartextTraffic not true"
                              + ("; network security config forbids it" if facts.nsc_tree is not None else "")))

    # minSdk
    uses_sdk = facts.manifest.find("uses-sdk")
    apk_min = as_int(uses_sdk.attrs.get("android:minSdkVersion")) if uses_sdk else None
    badging_min = facts.badging.get("min_sdk")
    seen = {v for v in (apk_min, badging_min) if v is not None}
    if expected_min_sdk is None:
        results.append(Result("min-sdk", "FAIL", "could not read minSdk from app/build.gradle.kts"))
    elif not seen:
        results.append(Result("min-sdk", "FAIL", "the APK declares no minSdkVersion"))
    elif seen != {expected_min_sdk}:
        results.append(Result("min-sdk", "FAIL",
                              f"APK minSdk {sorted(seen)} != build.gradle.kts minSdk {expected_min_sdk}"))
    else:
        results.append(Result("min-sdk", "PASS", f"minSdk {expected_min_sdk}"))

    # targetSdk
    apk_target = as_int(uses_sdk.attrs.get("android:targetSdkVersion")) if uses_sdk else None
    seen = {v for v in (apk_target, facts.badging.get("target_sdk")) if v is not None}
    if expected_target_sdk is None:
        results.append(Result("target-sdk", "FAIL", "could not read targetSdk from app/build.gradle.kts"))
    elif not seen:
        results.append(Result("target-sdk", "FAIL", "the APK declares no targetSdkVersion"))
    elif seen != {expected_target_sdk}:
        results.append(Result("target-sdk", "FAIL",
                              f"APK targetSdk {sorted(seen)} != build.gradle.kts targetSdk {expected_target_sdk}"))
    elif expected_target_sdk < PLAY_MIN_TARGET_SDK:
        results.append(Result("target-sdk", "FAIL",
                              f"targetSdk {expected_target_sdk} is below Play's floor {PLAY_MIN_TARGET_SDK}"))
    else:
        results.append(Result("target-sdk", "PASS",
                              f"targetSdk {expected_target_sdk} (Play floor {PLAY_MIN_TARGET_SDK})"))

    # 16 KB pages
    libs = facts.native_libs
    problems = [p for lib in libs for p in _native_problems(lib)]
    if not libs:
        results.append(Result("native-16kb", "FAIL", "no lib/<abi>/*.so in the APK: the engine must ship"))
    elif problems:
        results.append(Result("native-16kb", "FAIL", "; ".join(problems)))
    else:
        stored = sum(lib.stored for lib in libs)
        where = [f"{stored} stored at 16 KB offsets"] if stored else []
        if stored < len(libs):
            where.append(f"{len(libs) - stored} compressed (extracted at install)")
        results.append(Result("native-16kb", "PASS",
                              f"{len(libs)} librar{'y' if len(libs) == 1 else 'ies'}, every LOAD segment "
                              f">= 16 KB; {', '.join(where)}"))

    # R8 mapping
    if mapping is None:
        results.append(Result("r8-mapping", "SKIP", "no --mapping given"))
    elif not mapping.is_file() or mapping.stat().st_size == 0:
        results.append(Result("r8-mapping", "FAIL", f"{mapping} missing or empty: did R8 run?"))
    else:
        with mapping.open(encoding="utf-8", errors="replace") as fh:
            classes = sum(1 for line in fh if " -> " in line and not line.startswith((" ", "#")))
        if classes == 0:
            results.append(Result("r8-mapping", "FAIL", f"{mapping} holds no class mappings"))
        else:
            results.append(Result("r8-mapping", "PASS", f"{classes} class mappings"))

    # cert pins
    pins = sorted({m.group(0).decode() for data in facts.dex.values() for m in PIN_RE.finditer(data)})
    if pins:
        results.append(Result("cert-pins", "PASS", f"{len(pins)} sha256/ pin(s) compiled in"))
    else:
        results.append(Result("cert-pins", "WARN",
                              "no sha256/ pin in the dex: SEMPER_API_CERT_PINS was empty at build "
                              "(system trust store only)"))
    return results


def render(results: list[Result]) -> str:
    width = max(len(r.check) for r in results)
    lines = [f"{'check'.ljust(width)}  status  detail", f"{'-' * width}  ------  {'-' * 40}"]
    lines += [f"{r.check.ljust(width)}  {r.status.ljust(6)}  {r.detail}" for r in results]
    return "\n".join(lines)


# --- gathering facts from a real APK ----------------------------------------


def find_aapt2(explicit: str | None) -> str:
    for candidate in (explicit, os.environ.get("AAPT2"), shutil.which("aapt2")):
        if candidate:
            return candidate
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        tools = sorted(
            (p for p in Path(sdk, "build-tools").glob("*") if (p / "aapt2").is_file()),
            key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", p.name)],
        )
        if tools:
            return str(tools[-1] / "aapt2")
    raise SystemExit("aapt2 not found: pass --aapt2, set AAPT2, or set ANDROID_HOME")


def _run(cmd: list[str]) -> str:
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} exited {proc.returncode}: {proc.stderr.strip()[:500]}")
    return proc.stdout


def native_libs(zf: zipfile.ZipFile, fh) -> list[NativeLib]:
    """Every lib/<abi>/*.so in the APK; `fh` is the same file opened for raw reads."""
    return [
        NativeLib(info.filename, elf_load_aligns(zf.read(info)),
                  info.compress_type == zipfile.ZIP_STORED, zip_data_offset(fh, info))
        for info in zf.infolist() if NATIVE_LIB_RE.fullmatch(info.filename)
    ]


def gather(apk: Path, aapt2: str) -> ApkFacts:
    manifest = parse_xmltree(_run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)]))
    badging = parse_badging(_run([aapt2, "dump", "badging", str(apk)]))
    with zipfile.ZipFile(apk) as zf, apk.open("rb") as fh:
        entries = zf.namelist()
        dex = {n: zf.read(n) for n in entries if re.fullmatch(r"classes\d*\.dex", n)}
        libs = native_libs(zf, fh)
    facts = ApkFacts(manifest=manifest, badging=badging, dex=dex, entries=entries, native_libs=libs)
    app = manifest.find("application")
    ref = app.attrs.get("android:networkSecurityConfig") if app else None
    if ref:
        res_id = as_int(ref.lstrip("@")) if ref.startswith("@0x") else None
        try:
            path = resource_file(_run([aapt2, "dump", "resources", str(apk)]), res_id) if res_id else None
            if path:
                facts.nsc_tree = parse_xmltree(_run([aapt2, "dump", "xmltree", "--file", path, str(apk)]))
            else:
                facts.nsc_note = f"network security config {ref} not resolved to a file"
        except RuntimeError as e:
            facts.nsc_note = f"network security config not read ({e})"
    return facts


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("apk", type=Path)
    ap.add_argument("--mapping", type=Path, help="R8 mapping.txt (required to exist and be non-empty when given)")
    ap.add_argument("--gradle", type=Path, default=REPO / "app" / "build.gradle.kts")
    ap.add_argument("--debug-src", type=Path, default=REPO / "app" / "src" / "debug")
    ap.add_argument("--aapt2", help="path to aapt2 (default: $AAPT2, PATH, newest build-tools)")
    args = ap.parse_args(argv)

    if not args.apk.is_file():
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 2
    gradle_text = args.gradle.read_text(encoding="utf-8")
    facts = gather(args.apk, find_aapt2(args.aapt2))
    results = evaluate(
        facts,
        expected_min_sdk=gradle_min_sdk(gradle_text),
        expected_target_sdk=gradle_target_sdk(gradle_text),
        debug_names=debug_classes(args.debug_src, gradle_namespace(gradle_text)),
        mapping=args.mapping,
    )
    print(f"Release APK inspection: {args.apk}")
    print(render(results))
    failed = [r for r in results if r.status == "FAIL"]
    for r in results:
        if r.status == "WARN" and os.environ.get("GITHUB_ACTIONS") == "true":
            print(f"::warning title=Release APK {r.check}::{r.detail}")
    if failed:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            for r in failed:
                print(f"::error title=Release APK {r.check}::{r.detail}")
        print(f"\n{len(failed)} check(s) failed.", file=sys.stderr)
        return 1
    print("\nAll release hardening checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
