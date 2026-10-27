"""`scripts/check_release_apk.py`: the release APK hardening gate (readiness A1.5).

The parsers run on aapt2 output fixtures under `scripts/tests/fixtures/apk/`;
`main()` runs end to end on a small zip standing in for an APK, with a fake
`aapt2` that prints those fixtures, so no Android SDK is needed.
"""
from __future__ import annotations

import importlib.util
import json
import os
import stat
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_SCRIPT = _REPO / "scripts" / "check_release_apk.py"
_FIX = _REPO / "scripts" / "tests" / "fixtures" / "apk"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

_PIN = "sha256/" + "A" * 43 + "="


@pytest.fixture(scope="module")
def apk():
    spec = importlib.util.spec_from_file_location("check_release_apk", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules["check_release_apk"] = module
    spec.loader.exec_module(module)
    return module


def _fixture(name: str) -> str:
    return (_FIX / name).read_text(encoding="utf-8")


# --- parsers -----------------------------------------------------------------


def test_xmltree_reads_application_attributes_and_components(apk):
    tree = apk.parse_xmltree(_fixture("manifest_release.txt"))
    app = tree.find("application")
    assert app.attrs["android:name"] == "com.sempermechanics.semper.SemperApp"
    assert app.attrs["android:networkSecurityConfig"] == "@0x7f160004"
    assert "android:debuggable" not in app.attrs
    names = [el.attrs["android:name"] for el in app.iter(*apk.COMPONENT_TAGS)]
    assert "com.sempermechanics.semper.ui.splash.SplashActivity" in names
    assert "com.sempermechanics.semper.LauncherAlias" in names
    assert "androidx.core.content.FileProvider" in names
    # Attributes of a nested element stay on it, not on its parent.
    assert "android:minSdkVersion" not in tree.find("manifest").attrs
    assert apk.as_int(tree.find("uses-sdk").attrs["android:minSdkVersion"]) == 30
    assert tree.find("manifest").attrs["package"] == "com.sempermechanics.semper"


def test_xmltree_reads_aapt1_typed_values(apk):
    tree = apk.parse_xmltree(_fixture("manifest_aapt1_typed.txt"))
    app = tree.find("application")
    assert apk.as_bool(app.attrs["android:debuggable"]) is False
    assert apk.as_bool(app.attrs["android:usesCleartextTraffic"]) is True
    assert apk.as_int(tree.find("uses-sdk").attrs["android:minSdkVersion"]) == 30


@pytest.mark.parametrize("raw, want", [
    ("true", True), ("false", False), ("0xffffffff", True), ("0x0", False), ("@0x7f01", None), (None, None),
])
def test_as_bool(apk, raw, want):
    assert apk.as_bool(raw) is want


def test_badging(apk):
    rel = apk.parse_badging(_fixture("badging_release.txt"))
    assert rel == {"debuggable": False, "test_only": False,
                   "package": "com.sempermechanics.semper", "min_sdk": 30, "target_sdk": 36}
    dbg = apk.parse_badging(_fixture("badging_debug.txt"))
    assert dbg["debuggable"] is True and dbg["test_only"] is True and dbg["min_sdk"] == 24


def test_resource_file_resolves_a_shortened_path(apk):
    dump = _fixture("resources.txt")
    assert apk.resource_file(dump, 0x7F160004) == "res/Zn.xml"
    assert apk.resource_file(dump, 0x7F160001) == "res/x1.xml"
    assert apk.resource_file(dump, 0x7F13003C) is None  # a string, no file
    assert apk.resource_file(dump, 0x7F999999) is None


def test_gradle_readers(apk):
    text = 'android {\n    namespace = "com.x.app"\n    defaultConfig {\n        minSdk = 30\n        targetSdk = 36\n'
    assert apk.gradle_min_sdk(text) == 30
    assert apk.gradle_namespace(text) == "com.x.app"
    assert apk.gradle_min_sdk("minSdkVersion 26\n") == 26
    assert apk.gradle_min_sdk("// no sdk here\n") is None
    assert apk.gradle_target_sdk(text) == 36
    assert apk.gradle_target_sdk("targetSdkVersion(35)\n") == 35
    assert apk.gradle_target_sdk("    // targetSdk 36 is deliberate\n") is None


def test_gradle_min_sdk_matches_the_real_build_script(apk):
    """The regex still reads the repo's own app/build.gradle.kts."""
    text = (_REPO / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert isinstance(apk.gradle_min_sdk(text), int)
    assert apk.gradle_target_sdk(text) >= apk.PLAY_MIN_TARGET_SDK
    assert apk.gradle_namespace(text) == "com.sempermechanics.semper"


# --- native libraries ----------------------------------------------------------


def _elf(aligns, bits=64, little=True):
    """A minimal ELF: the header, one PT_LOAD per alignment and a PT_DYNAMIC aligned to 8."""
    end = "<" if little else ">"
    segments = [(1, a) for a in aligns] + [(2, 8)]
    ident = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1 if little else 2, 1]) + bytes(9)
    if bits == 64:
        phoff, phentsize = 64, 56
        header = ident + struct.pack(end + "HHIQQQIHHHHHH", 3, 183, 1, 0, phoff, 0, 0, 64, phentsize,
                                     len(segments), 0, 0, 0)
        table = b"".join(struct.pack(end + "IIQQQQQQ", t, 5, 0, 0, 0, 0, 0, a) for t, a in segments)
    else:
        phoff, phentsize = 52, 32
        header = ident + struct.pack(end + "HHIIIIIHHHHHH", 3, 40, 1, 0, phoff, 0, 0, 52, phentsize,
                                     len(segments), 0, 0, 0)
        table = b"".join(struct.pack(end + "IIIIIIII", t, 0, 0, 0, 0, 0, 5, a) for t, a in segments)
    return header + table


@pytest.mark.parametrize("bits, little", [(64, True), (64, False), (32, True)])
def test_elf_load_aligns_reads_only_load_segments(apk, bits, little):
    assert apk.elf_load_aligns(_elf([16384, 65536], bits, little)) == [16384, 65536]


@pytest.mark.parametrize("data", [b"", b"PK\x03\x04" + bytes(80), b"\x7fELF" + bytes(80),
                                  _elf([16384])[:70]])
def test_elf_load_aligns_refuses_what_is_not_elf(apk, data):
    assert apk.elf_load_aligns(data) is None


def _lib(apk, aligns=(16384,), stored=True, offset=4 * 16384, name="lib/arm64-v8a/libsemper_core.so"):
    return apk.NativeLib(name, list(aligns) if aligns is not None else None, stored, offset)


def _native(apk, *libs):
    facts = _facts(apk)
    facts.native_libs = list(libs)
    res = _by_check(apk.evaluate(facts, expected_min_sdk=30, expected_target_sdk=36,
                                 debug_names=_DEBUG, mapping=None))
    return res["native-16kb"]


def test_native_libs_aligned_to_16kb_pass(apk):
    r = _native(apk, _lib(apk), _lib(apk, stored=False, offset=7, name="lib/arm64-v8a/libx.so"))
    assert r.status == "PASS"
    assert "2 libraries" in r.detail and "1 stored" in r.detail and "1 compressed" in r.detail


def test_a_4kb_load_segment_fails_naming_the_library(apk):
    r = _native(apk, _lib(apk), _lib(apk, aligns=(16384, 4096), name="lib/arm64-v8a/libold.so"))
    assert r.status == "FAIL"
    assert r.detail == "lib/arm64-v8a/libold.so: LOAD p_align 4096"


def test_a_stored_library_off_a_16kb_offset_fails(apk):
    r = _native(apk, _lib(apk, offset=16384 + 4096))
    assert r.status == "FAIL" and "zip offset 20480" in r.detail


@pytest.mark.parametrize("lib, want", [
    (dict(aligns=None), "not an ELF file"),
    (dict(aligns=()), "no LOAD segment"),
])
def test_unreadable_libraries_fail(apk, lib, want):
    r = _native(apk, _lib(apk, **lib))
    assert r.status == "FAIL" and want in r.detail


def test_no_native_library_fails(apk):
    r = _native(apk)
    assert r.status == "FAIL" and "engine must ship" in r.detail


def _zip_lib(zf, name, data, *, stored=True, aligned=True):
    """Write `data` as `name`; a stored entry is padded through the extra field
    (Android's alignment record, 0xD935) so its bytes start on a 16 KB boundary, or one byte past it."""
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    if stored:
        start = zf.fp.tell() + 30 + len(name.encode()) + 4
        pad = -start % 16384 + (0 if aligned else 1)
        info.extra = struct.pack("<HH", 0xD935, pad) + bytes(pad)
    zf.writestr(info, data)


@pytest.mark.parametrize("stored, aligned, want", [
    (True, True, "PASS"), (True, False, "FAIL"), (False, True, "PASS"),
])
def test_native_libs_read_from_a_real_zip(apk, tmp_path, stored, aligned, want):
    path = tmp_path / "app.apk"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("classes.dex", b"dex")
        _zip_lib(zf, "lib/arm64-v8a/libsemper_core.so", _elf([16384]), stored=stored, aligned=aligned)
        zf.writestr("lib/arm64-v8a/README.txt", b"not a library")
    with zipfile.ZipFile(path) as zf, path.open("rb") as fh:
        libs = apk.native_libs(zf, fh)
    assert [lib.name for lib in libs] == ["lib/arm64-v8a/libsemper_core.so"]
    assert libs[0].load_aligns == [16384]
    assert _native(apk, *libs).status == want


def test_debug_classes_from_the_real_debug_source_set(apk):
    names = apk.debug_classes(_REPO / "app" / "src" / "debug", "com.sempermechanics.semper")
    assert "com.sempermechanics.semper.debug.DebugViewerSeedActivity" in names


def test_debug_classes_resolves_relative_names(apk, tmp_path):
    (tmp_path / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
        '<activity android:name=".dbg.Seed"/><provider android:name="Probe"/>'
        '</application></manifest>', encoding="utf-8")
    (tmp_path / "kotlin" / "com" / "x").mkdir(parents=True)
    (tmp_path / "kotlin" / "com" / "x" / "Tool.kt").write_text("class Tool", encoding="utf-8")
    assert apk.debug_classes(tmp_path, "com.x") == {"com.x.dbg.Seed", "com.x.Probe", "com.x.Tool"}


# --- evaluate ------------------------------------------------------------------


def _facts(apk, manifest="manifest_release.txt", badging="badging_release.txt", dex=None,
           entries=None, nsc="nsc_strict.txt"):
    return apk.ApkFacts(
        manifest=apk.parse_xmltree(_fixture(manifest)),
        badging=apk.parse_badging(_fixture(badging)),
        dex=dex if dex is not None else {"classes.dex": b"dex\n035\x00" + _PIN.encode() + b"\x00"},
        entries=entries or ["AndroidManifest.xml", "classes.dex"],
        nsc_tree=apk.parse_xmltree(_fixture(nsc)) if nsc else None,
        native_libs=[apk.NativeLib("lib/arm64-v8a/libsemper_core.so", [16384, 16384], True, 16384)],
    )


_DEBUG = {"com.sempermechanics.semper.debug.DebugViewerSeedActivity"}


def _by_check(results):
    return {r.check: r for r in results}


def test_a_hardened_release_passes(apk, tmp_path):
    mapping = tmp_path / "mapping.txt"
    mapping.write_text("# compiler: R8\ncom.x.A -> a.a:\n    void f() -> a\n", encoding="utf-8")
    res = _by_check(apk.evaluate(_facts(apk, dex=_dex_with(_PIN, f"ent-1:{_ENT_SPKI}")), expected_min_sdk=30,
                                 expected_target_sdk=36, debug_names=_DEBUG, mapping=mapping,
                                 require_entitlement_pins=True,
                                 signer_sha256=_RELEASE_CERT, expected_signer_sha256=_RELEASE_CERT_COLONS))
    assert {k: r.status for k, r in res.items()} == {
        "debuggable": "PASS", "debug-components": "PASS", "appcheck-debug": "PASS",
        "cleartext": "PASS", "min-sdk": "PASS", "target-sdk": "PASS", "native-16kb": "PASS",
        "r8-mapping": "PASS", "cert-pins": "PASS",
        "example-assets": "SKIP", "apk-size": "SKIP",
        "entitlement-pins": "PASS", "signing-key": "PASS", "consent-defaults": "PASS",
    }


@pytest.mark.parametrize("gradle, apk_target, status, want", [
    (36, 36, "PASS", "targetSdk 36 (Play floor 36)"),
    (37, 36, "FAIL", "!= build.gradle.kts targetSdk 37"),
    (35, 35, "FAIL", "below Play's floor 36"),
    (None, 36, "FAIL", "could not read targetSdk"),
    (36, None, "FAIL", "declares no targetSdkVersion"),
])
def test_target_sdk(apk, gradle, apk_target, status, want):
    facts = _facts(apk)
    facts.badging["target_sdk"] = apk_target
    facts.manifest.find("uses-sdk").attrs.pop("android:targetSdkVersion")
    res = _by_check(apk.evaluate(facts, expected_min_sdk=30, expected_target_sdk=gradle,
                                 debug_names=_DEBUG, mapping=None))["target-sdk"]
    assert res.status == status
    assert want in res.detail


def test_a_debug_build_fails_every_check_it_should(apk, tmp_path):
    dex = {"classes.dex": b"Lcom/google/firebase/appcheck/debug/DebugAppCheckProviderFactory;",
           "classes2.dex": b"Lcom/sempermechanics/semper/debug/DebugViewerSeedActivity;"}
    empty = tmp_path / "mapping.txt"
    empty.write_text("", encoding="utf-8")
    res = _by_check(apk.evaluate(
        _facts(apk, "manifest_debug.txt", "badging_debug.txt", dex=dex, nsc=None,
               entries=["classes.dex", "META-INF/com.google.firebase_firebase-appcheck-debug.version"]),
        expected_min_sdk=30, expected_target_sdk=36, debug_names=_DEBUG, mapping=empty))
    assert res["debuggable"].status == "FAIL"
    assert "testOnly" in res["debuggable"].detail
    assert res["debug-components"].status == "FAIL"
    assert "manifest:" in res["debug-components"].detail and "dex:" in res["debug-components"].detail
    assert res["appcheck-debug"].status == "FAIL"
    assert "classes.dex" in res["appcheck-debug"].detail and "META-INF" in res["appcheck-debug"].detail
    assert res["cleartext"].status == "FAIL"
    assert res["min-sdk"].status == "FAIL" and "24" in res["min-sdk"].detail
    assert res["r8-mapping"].status == "FAIL"
    assert res["cert-pins"].status == "WARN"
    # No meta-data at all: the SDKs' own default is collection ON.
    assert res["consent-defaults"].status == "FAIL"
    assert "firebase_crashlytics_collection_enabled missing" in res["consent-defaults"].detail


def test_consent_defaults_fail_on_collection_on_or_an_ad_id_permission(apk):
    """TE5-005: a manifest merge (a new Firebase / Play services artifact) can
    flip a collection default or re-add the advertising-ID permissions; the
    Play Data safety answers and the consent flow rely on both staying off."""
    res = _by_check(apk.evaluate(_facts(apk, manifest="manifest_consent_broken.txt"),
                                 expected_min_sdk=30, debug_names=_DEBUG, mapping=None))
    detail = res["consent-defaults"].detail
    assert res["consent-defaults"].status == "FAIL"
    assert "firebase_crashlytics_collection_enabled=true" in detail
    # Only <application>'s own meta-data counts; one inside an activity does not.
    assert "firebase_analytics_collection_enabled missing" in detail
    assert "google_analytics_adid_collection_enabled" not in detail
    assert "com.google.android.gms.permission.AD_ID" in detail
    assert "android.permission.ACCESS_ADSERVICES_ATTRIBUTION" in detail
    assert "android.permission.INTERNET" not in detail


def test_network_security_config_permitting_cleartext_fails(apk):
    res = _by_check(apk.evaluate(_facts(apk, nsc="nsc_cleartext.txt"),
                                 expected_min_sdk=30, expected_target_sdk=36, debug_names=_DEBUG,
                                 mapping=None))
    assert res["cleartext"].status == "FAIL"
    assert "domain-config" in res["cleartext"].detail
    assert res["r8-mapping"].status == "SKIP"


def test_unresolved_network_security_config_warns(apk):
    facts = _facts(apk, nsc=None)
    facts.nsc_note = "network security config @0x7f160004 not resolved to a file"
    res = _by_check(apk.evaluate(facts, expected_min_sdk=30, expected_target_sdk=36,
                                 debug_names=_DEBUG, mapping=None))
    assert res["cleartext"].status == "WARN"


def test_missing_mapping_and_unreadable_min_sdk_fail(apk, tmp_path):
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=None, expected_target_sdk=36,
                                 debug_names=_DEBUG, mapping=tmp_path / "absent.txt"))
    assert res["min-sdk"].status == "FAIL"
    assert res["r8-mapping"].status == "FAIL"


_APP = "com.sempermechanics.semper"
_OBFUSCATED = (
    "# compiler: R8\n"
    "com.sempermechanics.semper.SemperApp -> com.sempermechanics.semper.SemperApp:\n"
    "com.sempermechanics.semper.data.Store -> a.b:\n"
    "    void f() -> a\n"
    "com.sempermechanics.semper.debug.DebugViewerSeedActivity -> R8$$REMOVED$$CLASS$$12:\n"
)


def _mapping(tmp_path, text):
    path = tmp_path / "mapping.txt"
    path.write_text(text, encoding="utf-8")
    return path


def test_r8_mapping_that_renames_no_app_class_fails(apk, tmp_path):
    """TE1-002: `-dontobfuscate` still writes `a -> a` lines; any ' -> ' line
    passed. Evidence of obfuscation is an app class under a new name."""
    identity = ("com.sempermechanics.semper.SemperApp -> com.sempermechanics.semper.SemperApp:\n"
                "com.sempermechanics.semper.data.Store -> com.sempermechanics.semper.data.Store:\n"
                "okhttp3.OkHttpClient -> a.a:\n")
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=30, debug_names=_DEBUG,
                                 mapping=_mapping(tmp_path, identity), app_package=_APP))
    assert res["r8-mapping"].status == "FAIL"
    assert "no com.sempermechanics.semper class renamed" in res["r8-mapping"].detail
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=30, debug_names=_DEBUG,
                                 mapping=_mapping(tmp_path, _OBFUSCATED), app_package=_APP))
    assert res["r8-mapping"].status == "PASS", res["r8-mapping"].detail
    assert "1 of 2 com.sempermechanics.semper class(es) renamed" in res["r8-mapping"].detail


def test_debug_classes_are_found_under_their_obfuscated_names(apk, tmp_path):
    """TE1-002: R8 renames a class that is not kept, so a dex search by the
    original name misses it; the mapping's left-hand side does not. A class R8
    removed (R8$$REMOVED$$) is not shipped."""
    text = _OBFUSCATED + (
        "com.sempermechanics.semper.debug.DebugTool -> b.c:\n"
        "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory -> d.e:\n"
    )
    res = _by_check(apk.evaluate(
        _facts(apk), expected_min_sdk=30, mapping=_mapping(tmp_path, text), app_package=_APP,
        debug_names=_DEBUG | {"com.sempermechanics.semper.debug.DebugTool"}))
    assert res["debug-components"].status == "FAIL"
    assert res["debug-components"].detail == "mapping: com.sempermechanics.semper.debug.DebugTool -> b.c"
    assert res["appcheck-debug"].status == "FAIL"
    assert "mapping: com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory -> d.e" \
        in res["appcheck-debug"].detail
    # The removed seed activity alone is no finding.
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=30, debug_names=_DEBUG,
                                 mapping=_mapping(tmp_path, _OBFUSCATED), app_package=_APP))
    assert res["debug-components"].status == "PASS"
    assert res["appcheck-debug"].status == "PASS"


def test_main_requires_a_mapping(apk, tmp_path, capsys):
    """TE1-002: without --mapping r8-mapping was a SKIP, so a release could
    pass with no evidence R8 ran. Both workflows pass it."""
    with pytest.raises(SystemExit) as exc:
        apk.main([str(tmp_path / "app.apk"), "--aapt2", "aapt2"])
    assert exc.value.code == 2
    assert "--mapping" in capsys.readouterr().err


# An EC P-256 SubjectPublicKeyInfo, base64: the format SEMPER_ENTITLEMENT_PUBKEYS
# pins (`kid:SPKI`, EntitlementPins.kt). 36 fixed header characters, 88 of key.
_ENT_SPKI = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+Pw=="


def _dex_with(*strings: str) -> dict[str, bytes]:
    return {"classes.dex": b"dex\n035\x00" + b"\x00".join(s.encode() for s in strings) + b"\x00"}


def test_entitlement_pins_warn_while_none_is_compiled_in(apk):
    """TE4-001: with SEMPER_ENTITLEMENT_PUBKEYS empty the app trusts its
    unsigned /v1/config cache with no offline bound (TD-215). WARN today, like
    cert-pins; a release built without the key after it is live must not go
    out unnoticed."""
    res = _by_check(apk.evaluate(_facts(apk, dex=_dex_with(_PIN)), expected_min_sdk=30,
                                 debug_names=_DEBUG, mapping=None))
    assert res["entitlement-pins"].status == "WARN"
    assert "SEMPER_ENTITLEMENT_PUBKEYS" in res["entitlement-pins"].detail
    res = _by_check(apk.evaluate(_facts(apk, dex=_dex_with(_PIN, f"ent-2026:{_ENT_SPKI},ent-2027:{_ENT_SPKI}")),
                                 expected_min_sdk=30, debug_names=_DEBUG, mapping=None))
    assert res["entitlement-pins"].status == "PASS"
    assert res["entitlement-pins"].detail.startswith("2 entitlement key(s)")


def test_entitlement_pins_fail_when_required(apk):
    res = _by_check(apk.evaluate(_facts(apk, dex=_dex_with(_PIN)), expected_min_sdk=30, debug_names=_DEBUG,
                                 mapping=None, require_entitlement_pins=True))
    assert res["entitlement-pins"].status == "FAIL"
    # A bare SPKI with no kid is not a pin EntitlementPins accepts.
    res = _by_check(apk.evaluate(_facts(apk, dex=_dex_with(_ENT_SPKI)), expected_min_sdk=30, debug_names=_DEBUG,
                                 mapping=None, require_entitlement_pins=True))
    assert res["entitlement-pins"].status == "FAIL"
    res = _by_check(apk.evaluate(_facts(apk, dex=_dex_with(f"k1:{_ENT_SPKI}")), expected_min_sdk=30,
                                 debug_names=_DEBUG, mapping=None, require_entitlement_pins=True))
    assert res["entitlement-pins"].status == "PASS"


def test_release_workflow_requires_entitlement_pins_only_once_the_key_is_live():
    """The flag is passed from repo variables, never unconditionally, so no
    release fails while TD-215 is open (Admin decision 2026-10-24)."""
    workflow = (_REPO / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
    step = workflow[workflow.index("- name: Inspect release APK hardening"):]
    step = step[:step.index("\n      - ")]
    assert "--require-entitlement-pins" in step
    assert "vars.SEMPER_ENTITLEMENT_PUBKEYS" in step and "vars.SEMPER_REQUIRE_ENTITLEMENT_PINS" in step
    assert '${REQUIRE_ENTITLEMENT_PINS:+--require-entitlement-pins}' in step


# The certificate every Semper release so far was signed with (release runs
# 31565590316 to 39064532280, v1.2-beta.4 = versionCode 1036), as apksigner prints it.
_RELEASE_CERT = "ff72a8b08f02a05d65b53d91250bef7e3b0ff7538af2b0168ee52d5f36b15c9a"
_RELEASE_CERT_COLONS = ":".join(_RELEASE_CERT[i:i + 2] for i in range(0, 64, 2)).upper()


@pytest.mark.parametrize(("signer", "expected", "status"), [
    (_RELEASE_CERT, _RELEASE_CERT, "PASS"),
    (_RELEASE_CERT, _RELEASE_CERT_COLONS, "PASS"),   # assetlinks' form
    (_RELEASE_CERT.upper(), " " + _RELEASE_CERT_COLONS.lower() + "\n", "PASS"),
    ("39" + _RELEASE_CERT[2:], _RELEASE_CERT, "FAIL"),
    (_RELEASE_CERT, None, "WARN"),
    (None, _RELEASE_CERT, "SKIP"),                   # unsigned (CI tier 5 without the keystore)
    ("", _RELEASE_CERT, "SKIP"),
    ("not-a-digest", _RELEASE_CERT, "FAIL"),
])
def test_signing_key_continuity(apk, signer, expected, status):
    """TE1-007: the release gate only grepped assetlinks.json, which lists two
    Semper certificates; a release signed with the other key would refuse to
    install over 1036, and uninstalling deletes local analyses."""
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=30, debug_names=_DEBUG, mapping=None,
                                 signer_sha256=signer, expected_signer_sha256=expected))
    assert res["signing-key"].status == status, res["signing-key"].detail


def test_release_workflow_pins_the_release_certificate():
    workflow = (_REPO / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
    step = workflow[workflow.index("- name: Inspect release APK hardening"):]
    step = step[:step.index("\n      - ")]
    assert "vars.SEMPER_RELEASE_CERT_SHA256" in step
    # The upstream repository's default is the certificate of every release so
    # far; a fork (material_testing signs with its own key) gets no default.
    assert f"github.repository == 'sempermechanics/semperdic-app' && '{_RELEASE_CERT}'" in step
    assert '--signer-sha256 "$SIGNER"' in step
    assert '${EXPECTED_SIGNER:+--expected-signer-sha256 "$EXPECTED_SIGNER"}' in step


def test_no_dex_is_a_failure_not_a_pass(apk):
    res = _by_check(apk.evaluate(_facts(apk, dex={}), expected_min_sdk=30, expected_target_sdk=36,
                                 debug_names=_DEBUG, mapping=None))
    assert res["appcheck-debug"].status == "FAIL"


def test_render_is_a_table(apk):
    out = apk.render([apk.Result("min-sdk", "PASS", "minSdk 30"), apk.Result("cert-pins", "WARN", "none")])
    lines = out.splitlines()
    assert lines[0].split() == ["check", "status", "detail"]
    assert "min-sdk" in lines[2] and "PASS" in lines[2]


# --- main(), end to end with a fake aapt2 --------------------------------------


def _fake_aapt2(tmp_path: Path, manifest: str, badging: str) -> Path:
    tool = tmp_path / "aapt2"
    tool.write_text(
        "#!/usr/bin/env python3\n"
        "import sys, pathlib\n"
        f"fix = pathlib.Path({str(_FIX)!r})\n"
        "a = sys.argv[1:]\n"
        "if a[:2] == ['dump', 'badging']: name = %r\n"
        "elif a[:2] == ['dump', 'resources']: name = 'resources.txt'\n"
        "elif a[:4] == ['dump', 'xmltree', '--file', 'AndroidManifest.xml']: name = %r\n"
        "elif a[:4] == ['dump', 'xmltree', '--file', 'res/Zn.xml']: name = 'nsc_strict.txt'\n"
        "else: sys.exit('unexpected ' + ' '.join(a))\n"
        "sys.stdout.write((fix / name).read_text())\n" % (badging, manifest),
        encoding="utf-8",
    )
    tool.chmod(tool.stat().st_mode | stat.S_IEXEC)
    return tool


_EXAMPLE = {
    "id": "pair", "reference": "ref.png", "deformed": ["def.png"],
    "load_log": {"file": "loads.csv", "unit": "N"}, "truth_file": "truth.csv",
    "credit": {"licence_file": "LICENSE.x"},
}


def _zip(tmp_path: Path, dex: bytes) -> Path:
    path = tmp_path / "app-release.apk"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
        zf.writestr("classes.dex", dex)
        _zip_lib(zf, "lib/arm64-v8a/libsemper_core.so", _elf([16384, 16384]))
        _write_example(zf, "pair")
    return path


def _write_example(zf: zipfile.ZipFile, set_id: str, skip: str = "", deflate: str = "") -> None:
    """One bundled example set (ADR-031), stored as the app stores them; `skip` left out, `deflate` compressed."""
    base = f"assets/examples/{set_id}/"
    zf.writestr(base + "example.json", json.dumps(_EXAMPLE))
    for name in ("README.md", "CREDIT.txt", "LICENSE.x", "ref.png", "def.png", "loads.csv", "truth.csv"):
        if name != skip:
            kind = zipfile.ZIP_DEFLATED if name == deflate else zipfile.ZIP_STORED
            zf.writestr(zipfile.ZipInfo(base + name), b"x" * 64, compress_type=kind)


def _example_facts(apk, tmp_path: Path, **kw):
    path = tmp_path / "ex.apk"
    with zipfile.ZipFile(path, "w") as zf:
        _write_example(zf, "pair", **kw)
    with zipfile.ZipFile(path) as zf:
        entries = zf.namelist()
        stored = {i.filename for i in zf.infolist() if i.compress_type == zipfile.ZIP_STORED}
    facts = _facts(apk, entries=entries)
    facts.examples = {"pair": apk.example_files(_EXAMPLE)}
    facts.stored = stored
    return facts


def test_example_files_lists_what_the_app_lists(apk):
    listed, opened = apk.example_files(_EXAMPLE)
    assert listed == ["README.md", "CREDIT.txt", "LICENSE.x", "truth.csv", "ref.png", "def.png",
                      "loads.csv", "example.json"]
    assert opened == ["ref.png", "def.png", "loads.csv"]


def test_bundled_examples_pass_when_whole_and_stored(apk, tmp_path):
    res = _by_check(apk.evaluate(_example_facts(apk, tmp_path), expected_min_sdk=30, expected_target_sdk=36, debug_names=_DEBUG,
                                 mapping=None))
    assert res["example-assets"].status == "PASS"
    assert "1 sets, 8 files" in res["example-assets"].detail


def test_a_missing_example_file_fails(apk, tmp_path):
    res = _by_check(apk.evaluate(_example_facts(apk, tmp_path, skip="CREDIT.txt"), expected_min_sdk=30, expected_target_sdk=36,
                                 debug_names=_DEBUG, mapping=None))
    assert res["example-assets"].status == "FAIL"
    assert "pair/CREDIT.txt" in res["example-assets"].detail


def test_a_compressed_load_log_fails(apk, tmp_path):
    res = _by_check(apk.evaluate(_example_facts(apk, tmp_path, deflate="loads.csv"), expected_min_sdk=30, expected_target_sdk=36,
                                 debug_names=_DEBUG, mapping=None))
    assert res["example-assets"].status == "FAIL"
    assert "compressed" in res["example-assets"].detail and "pair/loads.csv" in res["example-assets"].detail


def test_no_examples_at_all_fails(apk):
    facts = _facts(apk)
    facts.examples = {}
    res = _by_check(apk.evaluate(facts, expected_min_sdk=30, expected_target_sdk=36, debug_names=_DEBUG, mapping=None))
    assert res["example-assets"].status == "FAIL"


def test_an_apk_over_budget_fails(apk):
    facts = _facts(apk)
    facts.size = (apk.APK_BUDGET_MB + 1) * 1_000_000
    over = _by_check(apk.evaluate(facts, expected_min_sdk=30, expected_target_sdk=36, debug_names=_DEBUG, mapping=None))["apk-size"]
    assert over.status == "FAIL"
    facts.size = 99 * 1_000_000
    within = _by_check(apk.evaluate(facts, expected_min_sdk=30, expected_target_sdk=36, debug_names=_DEBUG, mapping=None))["apk-size"]
    assert within.status == "PASS" and "99.0 MB" in within.detail


def _gradle(tmp_path: Path, min_sdk: int) -> Path:
    path = tmp_path / "build.gradle.kts"
    path.write_text(f'android {{\n    namespace = "com.sempermechanics.semper"\n    defaultConfig {{\n'
                    f'        minSdk = {min_sdk}\n        targetSdk = 36\n    }}\n}}\n', encoding="utf-8")
    return path


@pytest.mark.skipif(os.name == "nt", reason="fake aapt2 is a shebang script")
def test_main_passes_a_release_apk(apk, tmp_path, capsys, monkeypatch):
    monkeypatch.delenv("GITHUB_ACTIONS", raising=False)
    mapping = _mapping(tmp_path, _OBFUSCATED)
    code = apk.main([
        str(_zip(tmp_path, b"dex\n035\x00" + _PIN.encode())),
        "--aapt2", str(_fake_aapt2(tmp_path, "manifest_release.txt", "badging_release.txt")),
        "--gradle", str(_gradle(tmp_path, 30)),
        "--debug-src", str(_REPO / "app" / "src" / "debug"),
        "--mapping", str(mapping),
    ])
    out = capsys.readouterr().out
    assert code == 0, out
    assert "All release hardening checks passed." in out
    assert "network security config forbids it" in out


@pytest.mark.skipif(os.name == "nt", reason="fake aapt2 is a shebang script")
def test_main_fails_a_debug_apk_with_annotations(apk, tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("GITHUB_ACTIONS", "true")
    code = apk.main([
        str(_zip(tmp_path, b"Lcom/google/firebase/appcheck/debug/DebugAppCheckProviderFactory;")),
        "--aapt2", str(_fake_aapt2(tmp_path, "manifest_debug.txt", "badging_debug.txt")),
        "--gradle", str(_gradle(tmp_path, 30)),
        "--debug-src", str(_REPO / "app" / "src" / "debug"),
        "--mapping", str(_mapping(tmp_path, "")),
    ])
    captured = capsys.readouterr()
    assert code == 1
    assert "::error title=Release APK debuggable::" in captured.out
    assert "::warning title=Release APK cert-pins::" in captured.out
    assert "check(s) failed" in captured.err


@pytest.mark.skipif(os.name == "nt", reason="fake aapt2 is a shebang script")
def test_main_require_entitlement_pins_fails_a_release_without_them(apk, tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("GITHUB_ACTIONS", "true")
    args = [
        str(_zip(tmp_path, b"dex\n035\x00" + _PIN.encode())),
        "--aapt2", str(_fake_aapt2(tmp_path, "manifest_release.txt", "badging_release.txt")),
        "--gradle", str(_gradle(tmp_path, 30)),
        "--debug-src", str(_REPO / "app" / "src" / "debug"),
        "--mapping", str(_mapping(tmp_path, _OBFUSCATED)),
    ]
    assert apk.main(args) == 0
    assert "::warning title=Release APK entitlement-pins::" in capsys.readouterr().out
    assert apk.main([*args, "--require-entitlement-pins"]) == 1
    assert "::error title=Release APK entitlement-pins::" in capsys.readouterr().out


def test_aapt2_output_is_read_as_utf8_whatever_the_locale(tmp_path):
    """aapt2 prints UTF-8 (a label or string in any language). Read with the
    platform codec, Windows' cp1252 raised UnicodeDecodeError on byte 0x81 and
    the next parser then met None (readiness baseline 2026-10-24). PYTHONUTF8=0
    and the C locale give the child a non-UTF-8 codec on any OS."""
    fake = tmp_path / "fake_aapt2.py"
    fake.write_text("import sys\nsys.stdout.buffer.write(b'label=\\xd1\\x81\\xe2\\x9c\\x93')\n", encoding="utf-8")
    probe = tmp_path / "probe.py"
    probe.write_text(
        "import sys\n"
        f"sys.path.insert(0, {str(_SCRIPT.parent)!r})\n"
        "import check_release_apk as m\n"
        f"print(ascii(m._run([sys.executable, {str(fake)!r}])))\n",
        encoding="utf-8",
    )
    env = dict(os.environ, PYTHONUTF8="0", PYTHONCOERCECLOCALE="0", LC_ALL="C", LANG="C")
    env.pop("PYTHONIOENCODING", None)
    proc = subprocess.run([sys.executable, str(probe)], capture_output=True, text=True, env=env, check=False)  # noqa: S603 - argv is sys.executable and a probe this test wrote, no shell
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.strip() == ascii("label=с✓")


def test_main_refuses_a_missing_apk(apk, tmp_path):
    assert apk.main([str(tmp_path / "nope.apk"), "--aapt2", "/bin/true",
                     "--mapping", str(tmp_path / "mapping.txt")]) == 2


def test_find_aapt2_prefers_the_newest_build_tools(apk, tmp_path, monkeypatch):
    for ver in ("34.0.0", "36.1.0", "36.0.0"):
        d = tmp_path / "build-tools" / ver
        d.mkdir(parents=True)
        (d / "aapt2").write_text("", encoding="utf-8")
    monkeypatch.delenv("AAPT2", raising=False)
    monkeypatch.setenv("ANDROID_HOME", str(tmp_path))
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    assert apk.find_aapt2(None) == str(tmp_path / "build-tools" / "36.1.0" / "aapt2")
