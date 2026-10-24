"""`scripts/check_jni_parity.py`: every `external fun` has its engine export (MT-TE2-001).

Each test writes a small `SemperNativeLib.kt` and an engine adapter under a
temporary directory and runs the check against it with `check(root)`.
Stdlib only: `python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[1] / "check_jni_parity.py"
_spec = importlib.util.spec_from_file_location("check_jni_parity", _SCRIPT)
parity = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(parity)

_KOTLIN = """package com.sempermechanics.semper

object SemperNativeLib {
    external fun initializeReference(refBytes: ByteArray): Int

    /** Opens a session. */
    external fun initializeIncremental(
        refBytes: ByteArray,
    ): Int

    external fun releaseIncremental()

    fun notNative(): Int = 0
}
"""

_PREFIX = "Java_com_sempermechanics_semper_SemperNativeLib_"


def _cpp(*names: str, call: str = "") -> str:
    defs = "\n".join(f"extern \"C\" JNIEXPORT jint JNICALL\n{_PREFIX}{n}(JNIEnv* env, jobject) {{ return 0; }}\n" for n in names)
    return defs + call


class CheckJniParityTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        kt = self.root / parity.KOTLIN
        kt.parent.mkdir(parents=True)
        kt.write_text(_KOTLIN, encoding="utf-8")
        (self.root / parity.ADAPTER / "jni").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _adapter(self, text: str) -> None:
        (self.root / parity.ADAPTER / "jni" / "SemperJNI.cpp").write_text(text, encoding="utf-8")

    def _run(self) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = parity.check(self.root)
        return code, out.getvalue()

    def test_expected_symbols_follow_jni_naming(self) -> None:
        self.assertEqual(
            parity.expected_symbols(_KOTLIN),
            {
                "initializeReference": _PREFIX + "initializeReference",
                "initializeIncremental": _PREFIX + "initializeIncremental",
                "releaseIncremental": _PREFIX + "releaseIncremental",
            },
        )

    def test_underscore_is_escaped(self) -> None:
        src = "package a.b_c\nobject Lib {\n    external fun do_it()\n}\n"
        self.assertEqual(parity.expected_symbols(src), {"do_it": "Java_a_b_1c_Lib_do_1it"})

    def test_passes_when_every_declaration_is_exported(self) -> None:
        self._adapter(_cpp("initializeReference", "initializeIncremental", "releaseIncremental"))
        code, out = self._run()
        self.assertEqual(code, 0, out)
        self.assertIn("all 3 external funs", out)

    def test_fails_naming_each_missing_export(self) -> None:
        # The 0.2.3 adapter: no incremental calls (MT-TE2-001).
        self._adapter(_cpp("initializeReference"))
        code, out = self._run()
        self.assertEqual(code, 1)
        self.assertIn("external fun initializeIncremental", out)
        self.assertIn("external fun releaseIncremental", out)
        self.assertIn("2 of 3", out)

    def test_a_call_is_not_an_export(self) -> None:
        self._adapter(
            _cpp("initializeReference", "initializeIncremental", call=f"void f() {{ {_PREFIX}releaseIncremental(0, 0); }}\n")
        )
        code, out = self._run()
        self.assertEqual(code, 1)
        self.assertIn("external fun releaseIncremental", out)

    def test_old_package_export_does_not_count(self) -> None:
        old = "Java_com_indicvision_semper_SemperNativeLib_"
        self._adapter(
            _cpp("initializeReference", "initializeIncremental")
            + f"JNIEXPORT void JNICALL\n{old}releaseIncremental(JNIEnv*, jobject) {{}}\n"
        )
        code, _ = self._run()
        self.assertEqual(code, 1)

    def test_missing_submodule_is_an_error_not_a_pass(self) -> None:
        code, out = self._run()  # adapter dir exists but holds no .cpp
        self.assertEqual(code, 2)
        self.assertIn("git submodule update", out)


if __name__ == "__main__":
    unittest.main()
