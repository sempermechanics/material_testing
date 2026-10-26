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


def test_gradle_min_sdk_matches_the_real_build_script(apk):
    """The regex still reads the repo's own app/build.gradle.kts."""
    text = (_REPO / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert isinstance(apk.gradle_min_sdk(text), int)
    assert apk.gradle_namespace(text) == "com.sempermechanics.semper"


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
    )


_DEBUG = {"com.sempermechanics.semper.debug.DebugViewerSeedActivity"}


def _by_check(results):
    return {r.check: r for r in results}


def test_a_hardened_release_passes(apk, tmp_path):
    mapping = tmp_path / "mapping.txt"
    mapping.write_text("# compiler: R8\ncom.x.A -> a.a:\n    void f() -> a\n", encoding="utf-8")
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=30, debug_names=_DEBUG, mapping=mapping))
    assert {k: r.status for k, r in res.items()} == {
        "debuggable": "PASS", "debug-components": "PASS", "appcheck-debug": "PASS",
        "cleartext": "PASS", "min-sdk": "PASS", "r8-mapping": "PASS", "cert-pins": "PASS",
        "example-assets": "SKIP", "apk-size": "SKIP",
    }


def test_a_debug_build_fails_every_check_it_should(apk, tmp_path):
    dex = {"classes.dex": b"Lcom/google/firebase/appcheck/debug/DebugAppCheckProviderFactory;",
           "classes2.dex": b"Lcom/sempermechanics/semper/debug/DebugViewerSeedActivity;"}
    empty = tmp_path / "mapping.txt"
    empty.write_text("", encoding="utf-8")
    res = _by_check(apk.evaluate(
        _facts(apk, "manifest_debug.txt", "badging_debug.txt", dex=dex, nsc=None,
               entries=["classes.dex", "META-INF/com.google.firebase_firebase-appcheck-debug.version"]),
        expected_min_sdk=30, debug_names=_DEBUG, mapping=empty))
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


def test_network_security_config_permitting_cleartext_fails(apk):
    res = _by_check(apk.evaluate(_facts(apk, nsc="nsc_cleartext.txt"),
                                 expected_min_sdk=30, debug_names=_DEBUG, mapping=None))
    assert res["cleartext"].status == "FAIL"
    assert "domain-config" in res["cleartext"].detail
    assert res["r8-mapping"].status == "SKIP"


def test_unresolved_network_security_config_warns(apk):
    facts = _facts(apk, nsc=None)
    facts.nsc_note = "network security config @0x7f160004 not resolved to a file"
    res = _by_check(apk.evaluate(facts, expected_min_sdk=30, debug_names=_DEBUG, mapping=None))
    assert res["cleartext"].status == "WARN"


def test_missing_mapping_and_unreadable_min_sdk_fail(apk, tmp_path):
    res = _by_check(apk.evaluate(_facts(apk), expected_min_sdk=None, debug_names=_DEBUG,
                                 mapping=tmp_path / "absent.txt"))
    assert res["min-sdk"].status == "FAIL"
    assert res["r8-mapping"].status == "FAIL"


def test_no_dex_is_a_failure_not_a_pass(apk):
    res = _by_check(apk.evaluate(_facts(apk, dex={}), expected_min_sdk=30, debug_names=_DEBUG, mapping=None))
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
    res = _by_check(apk.evaluate(_example_facts(apk, tmp_path), expected_min_sdk=30, debug_names=_DEBUG,
                                 mapping=None))
    assert res["example-assets"].status == "PASS"
    assert "1 sets, 8 files" in res["example-assets"].detail


def test_a_missing_example_file_fails(apk, tmp_path):
    res = _by_check(apk.evaluate(_example_facts(apk, tmp_path, skip="CREDIT.txt"), expected_min_sdk=30,
                                 debug_names=_DEBUG, mapping=None))
    assert res["example-assets"].status == "FAIL"
    assert "pair/CREDIT.txt" in res["example-assets"].detail


def test_a_compressed_load_log_fails(apk, tmp_path):
    res = _by_check(apk.evaluate(_example_facts(apk, tmp_path, deflate="loads.csv"), expected_min_sdk=30,
                                 debug_names=_DEBUG, mapping=None))
    assert res["example-assets"].status == "FAIL"
    assert "compressed" in res["example-assets"].detail and "pair/loads.csv" in res["example-assets"].detail


def test_no_examples_at_all_fails(apk):
    facts = _facts(apk)
    facts.examples = {}
    res = _by_check(apk.evaluate(facts, expected_min_sdk=30, debug_names=_DEBUG, mapping=None))
    assert res["example-assets"].status == "FAIL"


def test_an_apk_over_budget_fails(apk):
    facts = _facts(apk)
    facts.size = (apk.APK_BUDGET_MB + 1) * 1_000_000
    over = _by_check(apk.evaluate(facts, expected_min_sdk=30, debug_names=_DEBUG, mapping=None))["apk-size"]
    assert over.status == "FAIL"
    facts.size = 99 * 1_000_000
    within = _by_check(apk.evaluate(facts, expected_min_sdk=30, debug_names=_DEBUG, mapping=None))["apk-size"]
    assert within.status == "PASS" and "99.0 MB" in within.detail


def _gradle(tmp_path: Path, min_sdk: int) -> Path:
    path = tmp_path / "build.gradle.kts"
    path.write_text(f'android {{\n    namespace = "com.sempermechanics.semper"\n    defaultConfig {{\n'
                    f'        minSdk = {min_sdk}\n    }}\n}}\n', encoding="utf-8")
    return path


@pytest.mark.skipif(os.name == "nt", reason="fake aapt2 is a shebang script")
def test_main_passes_a_release_apk(apk, tmp_path, capsys, monkeypatch):
    monkeypatch.delenv("GITHUB_ACTIONS", raising=False)
    mapping = tmp_path / "mapping.txt"
    mapping.write_text("com.x.A -> a.a:\n", encoding="utf-8")
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
    ])
    captured = capsys.readouterr()
    assert code == 1
    assert "::error title=Release APK debuggable::" in captured.out
    assert "::warning title=Release APK cert-pins::" in captured.out
    assert "check(s) failed" in captured.err


def test_main_refuses_a_missing_apk(apk, tmp_path):
    assert apk.main([str(tmp_path / "nope.apk"), "--aapt2", "/bin/true"]) == 2


def test_find_aapt2_prefers_the_newest_build_tools(apk, tmp_path, monkeypatch):
    for ver in ("34.0.0", "36.1.0", "36.0.0"):
        d = tmp_path / "build-tools" / ver
        d.mkdir(parents=True)
        (d / "aapt2").write_text("", encoding="utf-8")
    monkeypatch.delenv("AAPT2", raising=False)
    monkeypatch.setenv("ANDROID_HOME", str(tmp_path))
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    assert apk.find_aapt2(None) == str(tmp_path / "build-tools" / "36.1.0" / "aapt2")
