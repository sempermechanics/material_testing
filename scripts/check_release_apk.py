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
                    classes) is in the manifest, in any classes*.dex, or kept
                    (under any name) in the R8 mapping
  appcheck-debug    no `DebugAppCheckProviderFactory` in any classes*.dex or
                    kept in the R8 mapping, and no firebase-appcheck-debug
                    version file in META-INF
  cleartext         android:usesCleartextTraffic is not true, and the network
                    security config (when one is set and resolvable) does not
                    permit cleartext in base-config or a domain-config
  min-sdk           the APK's minSdkVersion equals `minSdk` in app/build.gradle.kts
  r8-mapping        --mapping (required): the file exists, holds class lines,
                    and at least one class of the app's namespace maps to a
                    new name (evidence R8 obfuscated; -dontobfuscate writes
                    `a -> a`)
  cert-pins         WARN when no `sha256/<base64>` pin string is in the dex,
                    i.e. SEMPER_API_CERT_PINS (BuildConfig) was empty at build
  example-assets    every file each assets/examples/*/example.json lists is in
                    the APK (ADR-031), and the ones the wizard opens through
                    ExampleAssetProvider (photos, load log) are stored
                    uncompressed, as openFd needs
  apk-size          the APK is at most APK_BUDGET_MB (the examples are ~80 MB)
  entitlement-pins  WARN (FAIL with --require-entitlement-pins) when no
                    `kid:<P-256 SPKI base64>` entitlement key is in the dex,
                    i.e. SEMPER_ENTITLEMENT_PUBKEYS was empty at build: the
                    app then trusts its unsigned /v1/config cache with no
                    offline bound (ADR-023, TD-215)
  signing-key       with --signer-sha256: the signer's certificate equals
                    --expected-signer-sha256 (FAIL otherwise: the build would
                    not install over the released ones); WARN with no pin,
                    SKIP for an unsigned build
  consent-defaults  <application> meta-data firebase_crashlytics_collection_enabled,
                    firebase_analytics_collection_enabled and
                    google_analytics_adid_collection_enabled are present and
                    false; no AD_ID or ACCESS_ADSERVICES_* uses-permission

aapt2 comes from --aapt2, $AAPT2, PATH, or the newest
$ANDROID_HOME/build-tools/*/aapt2 (the same lookup the workflows use for
apksigner). Standard library only.

Usage:
  python3 scripts/check_release_apk.py APP.apk --mapping mapping.txt
      [--gradle app/build.gradle.kts] [--debug-src app/src/debug] [--aapt2 PATH]
      [--require-entitlement-pins] [--signer-sha256 HEX [--expected-signer-sha256 HEX]]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
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
R8_REMOVED = "R8$$REMOVED$$"
PIN_RE = re.compile(rb"sha256/[A-Za-z0-9+/]{43}=")
EXAMPLES = "assets/examples/"
APK_BUDGET_MB = 150
# `kid:SPKI` (EntitlementPins.parse): an EC P-256 SubjectPublicKeyInfo is 91
# bytes, 124 base64 characters, and starts with the same 36 for every key.
ENTITLEMENT_PIN_RE = re.compile(rb"[A-Za-z0-9._-]+: *(MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE[A-Za-z0-9+/]{86}==)")
CONSENT_OFF_META = (
    "firebase_crashlytics_collection_enabled",
    "firebase_analytics_collection_enabled",
    "google_analytics_adid_collection_enabled",
)
AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID"
ADSERVICES_PREFIX = "android.permission.ACCESS_ADSERVICES_"


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


# --- inputs from the repo ----------------------------------------------------


def gradle_min_sdk(text: str) -> int | None:
    """`minSdk = 30` (or `minSdk(30)` / `minSdkVersion 30`) from a Gradle script."""
    m = re.search(r"^\s*minSdk(?:Version)?\s*(?:=|\(|\s)\s*(\d+)", text, re.MULTILINE)
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
class ApkFacts:
    manifest: Element
    badging: dict
    dex: dict[str, bytes]
    entries: list[str]
    nsc_tree: Element | None = None
    nsc_note: str = ""
    size: int = 0
    # Set id -> (every file its example.json lists, the ones the wizard opens); None when not read.
    examples: dict[str, tuple[list[str], list[str]]] | None = None
    stored: set[str] = field(default_factory=set)


def example_files(doc: dict) -> tuple[list[str], list[str]]:
    """Every file an example.json lists (as `ExampleManifest.files`), and those Try it opens."""
    opened = [doc["reference"], *doc["deformed"]] + ([doc["load_log"]["file"]] if doc.get("load_log") else [])
    listed = ["README.md", "CREDIT.txt"]
    listed += [f for f in (doc.get("credit", {}).get("licence_file"), doc.get("truth_file")) if f]
    listed += opened + doc.get("extra_files", []) + ["example.json"]
    return list(dict.fromkeys(listed)), opened


def cert_digest(text: str | None) -> str | None:
    """A SHA-256 certificate digest as lowercase hex, from apksigner's bare hex
    or assetlinks' colon-separated uppercase form; None if it is neither."""
    hexed = re.sub(r"[\s:]", "", text or "").lower()
    return hexed if re.fullmatch(r"[0-9a-f]{64}", hexed) else None


def parse_mapping(lines) -> dict[str, str]:
    """Class lines of an R8 `mapping.txt`: original name -> name in the dex.
    Member lines are indented; `#` lines are comments."""
    out: dict[str, str] = {}
    for line in lines:
        if line.startswith((" ", "\t", "#")) or " -> " not in line:
            continue
        line = line.rstrip("\r\n")
        if line.endswith(":"):
            orig, new = line[:-1].split(" -> ", 1)
            out[orig.strip()] = new.strip()
    return out


def kept_in(class_map: dict[str, str], name: str) -> bool:
    """[name] is in the mapping and R8 kept it (under any name)."""
    new = class_map.get(name)
    return new is not None and not new.startswith(R8_REMOVED)


def evaluate(facts: ApkFacts, *, expected_min_sdk: int | None, debug_names: set[str],
             mapping: Path | None, app_package: str | None = None,
             require_entitlement_pins: bool = False, signer_sha256: str | None = None,
             expected_signer_sha256: str | None = None) -> list[Result]:
    results: list[Result] = []
    app = facts.manifest.find("application") or Element("application", 0)
    # R8 renames every class that is not kept, so the dex alone cannot show a
    # debug class under its original name; the mapping can (TE1-002).
    class_map: dict[str, str] = {}
    if mapping is not None and mapping.is_file():
        with mapping.open(encoding="utf-8", errors="replace") as fh:
            class_map = parse_mapping(fh)

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
    in_mapping = sorted(f"{n} -> {class_map[n]}" for n in debug_names
                        if n not in in_dex and kept_in(class_map, n))
    if in_manifest or in_dex or in_mapping:
        parts = [f"manifest: {', '.join(in_manifest)}"] if in_manifest else []
        if in_dex:
            parts.append(f"dex: {', '.join(in_dex)}")
        if in_mapping:
            parts.append(f"mapping: {', '.join(in_mapping)}")
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
    mapped = sorted(f"{n} -> {class_map[n]}" for n in class_map
                    if n.rsplit(".", 1)[-1] == DEBUG_APPCHECK.decode() and kept_in(class_map, n))
    if hits or meta or mapped:
        results.append(Result("appcheck-debug", "FAIL",
                              "; ".join(filter(None, [
                                  f"DebugAppCheckProviderFactory in {', '.join(hits)}" if hits else "",
                                  f"{', '.join(meta)}" if meta else "",
                                  f"mapping: {', '.join(mapped)}" if mapped else "",
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

    # R8 mapping
    if mapping is None:
        results.append(Result("r8-mapping", "SKIP", "no --mapping given"))
    elif not mapping.is_file() or mapping.stat().st_size == 0:
        results.append(Result("r8-mapping", "FAIL", f"{mapping} missing or empty: did R8 run?"))
    elif not class_map:
        results.append(Result("r8-mapping", "FAIL", f"{mapping} holds no class mappings"))
    else:
        # -dontobfuscate still writes `a -> a` lines: the evidence is an app
        # class (any class, without a package to look for) under a new name.
        mine = {o: n for o, n in class_map.items()
                if (app_package is None or o.startswith(app_package + ".")) and not n.startswith(R8_REMOVED)}
        renamed = sum(1 for o, n in mine.items() if o != n)
        scope = f"{app_package} " if app_package else ""
        if renamed == 0:
            results.append(Result("r8-mapping", "FAIL",
                                  f"{len(class_map)} class mappings but no {scope}class renamed: "
                                  "not obfuscated (-dontobfuscate or a blanket -keep?)"))
        else:
            results.append(Result("r8-mapping", "PASS",
                                  f"{renamed} of {len(mine)} {scope}class(es) renamed; "
                                  f"{len(class_map)} class mappings"))

    # cert pins
    pins = sorted({m.group(0).decode() for data in facts.dex.values() for m in PIN_RE.finditer(data)})
    if pins:
        results.append(Result("cert-pins", "PASS", f"{len(pins)} sha256/ pin(s) compiled in"))
    else:
        results.append(Result("cert-pins", "WARN",
                              "no sha256/ pin in the dex: SEMPER_API_CERT_PINS was empty at build "
                              "(system trust store only)"))

    # bundled example sets (ADR-031)
    if facts.examples is None:
        results.append(Result("example-assets", "SKIP", "examples not read"))
    elif not facts.examples:
        results.append(Result("example-assets", "FAIL", f"no {EXAMPLES}*/example.json in the APK"))
    else:
        entries = set(facts.entries)
        missing, compressed, total = [], [], 0
        for set_id, (listed, opened) in sorted(facts.examples.items()):
            total += len(listed)
            missing += [f"{set_id}/{f}" for f in listed if f"{EXAMPLES}{set_id}/{f}" not in entries]
            compressed += [f"{set_id}/{f}" for f in opened
                           if f"{EXAMPLES}{set_id}/{f}" in entries and f"{EXAMPLES}{set_id}/{f}" not in facts.stored]
        if missing or compressed:
            detail = []
            if missing:
                detail.append(f"missing: {', '.join(missing[:8])}")
            if compressed:
                detail.append(f"compressed (noCompress?): {', '.join(compressed[:8])}")
            results.append(Result("example-assets", "FAIL", "; ".join(detail)))
        else:
            results.append(Result("example-assets", "PASS", f"{len(facts.examples)} sets, {total} files"))

    # size
    if not facts.size:
        results.append(Result("apk-size", "SKIP", "size not read"))
    else:
        mb = facts.size / 1_000_000
        status = "FAIL" if mb > APK_BUDGET_MB else "PASS"
        results.append(Result("apk-size", status, f"{mb:.1f} MB (budget {APK_BUDGET_MB} MB)"))
    # entitlement pins (TE4-001): WARN while TD-215 is open, FAIL once the
    # release workflow asks for them (the key is live).
    entries = {m.group(0) for data in facts.dex.values() for m in ENTITLEMENT_PIN_RE.finditer(data)}
    if entries:
        results.append(Result("entitlement-pins", "PASS",
                              f"{len(entries)} entitlement key(s) compiled in (kid:SPKI)"))
    else:
        results.append(Result("entitlement-pins", "FAIL" if require_entitlement_pins else "WARN",
                              "no kid:SPKI entitlement key in the dex: SEMPER_ENTITLEMENT_PUBKEYS was empty "
                              "at build, so signed tokens are ignored and the cached licence has no offline "
                              "bound (TD-215)"))

    # Signing-key continuity (TE1-007): an update installs only over a build
    # signed with the same certificate. assetlinks.json lists two for Semper,
    # so the grep there passes either; this compares with the one pinned.
    if not signer_sha256:
        results.append(Result("signing-key", "SKIP", "no --signer-sha256 given (unsigned build)"))
    elif not cert_digest(signer_sha256):
        results.append(Result("signing-key", "FAIL", f"--signer-sha256 {signer_sha256!r} is not a SHA-256 digest"))
    elif not expected_signer_sha256 or not expected_signer_sha256.strip():
        results.append(Result("signing-key", "WARN",
                              f"signed with {cert_digest(signer_sha256)}; no pinned release certificate to compare "
                              "(SEMPER_RELEASE_CERT_SHA256)"))
    elif cert_digest(signer_sha256) != cert_digest(expected_signer_sha256):
        results.append(Result("signing-key", "FAIL",
                              f"signed with {cert_digest(signer_sha256)}, not the release certificate "
                              f"{cert_digest(expected_signer_sha256) or expected_signer_sha256!r}: it would not "
                              "install over the released builds (INSTALL_FAILED_UPDATE_INCOMPATIBLE)"))
    else:
        results.append(Result("signing-key", "PASS", f"signed with the release certificate {cert_digest(signer_sha256)}"))

    # Consent defaults (TE5-005): diagnostics stay off until the user opts in
    # (AppSettings.diagnosticsEnabled), the advertising ID is never collected,
    # and its permissions stay removed (TD-232). A manifest merge can undo any
    # of these silently; an absent flag means the SDK's default, which is on.
    meta = {el.attrs.get("android:name"): el.attrs.get("android:value")
            for el in app.children if el.name == "meta-data"}
    bad: list[str] = []
    for name in CONSENT_OFF_META:
        if name not in meta:
            bad.append(f"{name} missing")
        elif as_bool(meta[name]) is not False:
            bad.append(f"{name}={meta[name]} (want false)")
    ad_perms = sorted(
        name for el in facts.manifest.iter("uses-permission", "uses-permission-sdk-23")
        if (name := el.attrs.get("android:name", "")) == AD_ID_PERMISSION or name.startswith(ADSERVICES_PREFIX)
    )
    bad += [f"uses-permission {name}" for name in ad_perms]
    if bad:
        results.append(Result("consent-defaults", "FAIL", "; ".join(bad)))
    else:
        results.append(Result("consent-defaults", "PASS",
                              "Crashlytics / Analytics / ad-ID collection off by default; "
                              "no AD_ID or AdServices permission"))
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
    # aapt2 writes UTF-8 whatever the locale; the platform codec (cp1252 on
    # Windows) failed on it and left stdout None for the parsers.
    proc = subprocess.run(cmd, capture_output=True, encoding="utf-8", errors="replace", check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} exited {proc.returncode}: {proc.stderr.strip()[:500]}")
    return proc.stdout


def gather(apk: Path, aapt2: str) -> ApkFacts:
    manifest = parse_xmltree(_run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)]))
    badging = parse_badging(_run([aapt2, "dump", "badging", str(apk)]))
    with zipfile.ZipFile(apk) as zf:
        entries = zf.namelist()
        dex = {n: zf.read(n) for n in entries if re.fullmatch(r"classes\d*\.dex", n)}
        examples = {
            n[len(EXAMPLES):].split("/")[0]: example_files(json.loads(zf.read(n)))
            for n in entries if re.fullmatch(re.escape(EXAMPLES) + r"[^/]+/example\.json", n)
        }
        stored = {i.filename for i in zf.infolist() if i.compress_type == zipfile.ZIP_STORED}
    facts = ApkFacts(manifest=manifest, badging=badging, dex=dex, entries=entries,
                     size=apk.stat().st_size, examples=examples, stored=stored)
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
    ap.add_argument("--mapping", type=Path, required=True,
                    help="R8 mapping.txt: must exist, and rename at least one class of the app's namespace")
    ap.add_argument("--gradle", type=Path, default=REPO / "app" / "build.gradle.kts")
    ap.add_argument("--debug-src", type=Path, default=REPO / "app" / "src" / "debug")
    ap.add_argument("--aapt2", help="path to aapt2 (default: $AAPT2, PATH, newest build-tools)")
    ap.add_argument("--require-entitlement-pins", action="store_true",
                    help="entitlement-pins FAILs instead of WARNs when no key is compiled in (once TD-215 is live)")
    ap.add_argument("--signer-sha256", help="the APK signer's certificate SHA-256 (apksigner --print-certs)")
    ap.add_argument("--expected-signer-sha256",
                    help="the release certificate's SHA-256 (hex or colon form); a different signer FAILs")
    args = ap.parse_args(argv)

    if not args.apk.is_file():
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 2
    gradle_text = args.gradle.read_text(encoding="utf-8")
    facts = gather(args.apk, find_aapt2(args.aapt2))
    results = evaluate(
        facts,
        expected_min_sdk=gradle_min_sdk(gradle_text),
        debug_names=debug_classes(args.debug_src, gradle_namespace(gradle_text)),
        mapping=args.mapping,
        app_package=gradle_namespace(gradle_text),
        require_entitlement_pins=args.require_entitlement_pins,
        signer_sha256=args.signer_sha256,
        expected_signer_sha256=args.expected_signer_sha256,
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
