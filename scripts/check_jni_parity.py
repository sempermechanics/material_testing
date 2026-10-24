#!/usr/bin/env python3
"""Fail when a Kotlin `external fun` has no JNI export in the pinned engine.

`SemperNativeLib` declares the engine's JNI calls; the engine submodule's
Android adapter (`engine/adapters/android/**/*.cpp`) exports them as
`Java_<package>_SemperNativeLib_<name>`. A missing export links fine and
throws `UnsatisfiedLinkError` the first time the app calls it. That is how a
sync merge that moved the `engine` gitlink back to an older engine (MT-TE2-001:
v0.6.1 -> 0.2.3, which has no incremental calls) got through: no test calls
the incremental solve. This compares the two sources, so it needs the engine
submodule checked out.

Exit 0 when every declaration has its export, 1 when one is missing, 2 when
the Kotlin file or the adapter sources are not there. Stdlib only.

Usage: python scripts/check_jni_parity.py [--root DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

KOTLIN = Path("app/src/main/java/com/sempermechanics/semper/SemperNativeLib.kt")
ADAPTER = Path("engine/adapters/android")

_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
_OBJECT = re.compile(r"^\s*(?:internal\s+)?object\s+(\w+)", re.MULTILINE)
_EXTERNAL = re.compile(r"^\s*(?:(?:public|internal|private)\s+)?external\s+fun\s+(\w+)", re.MULTILINE)
_EXPORT = re.compile(r"\bJNICALL\s+(Java_\w+)\s*\(")


def _mangle(part: str) -> str:
    """JNI short-name escaping for one identifier (only `_` occurs in ours)."""
    return part.replace("_", "_1")


def expected_symbols(kotlin_source: str) -> dict[str, str]:
    """`external fun` name -> the JNI symbol the JVM looks up for it."""
    package = _PACKAGE.search(kotlin_source)
    owner = _OBJECT.search(kotlin_source)
    if not package or not owner:
        raise ValueError("no package or object declaration")
    prefix = "Java_" + "_".join(_mangle(p) for p in package.group(1).split(".")) + "_" + _mangle(owner.group(1))
    return {name: f"{prefix}_{_mangle(name)}" for name in _EXTERNAL.findall(kotlin_source)}


def exported_symbols(adapter: Path) -> set[str]:
    """Every `JNICALL Java_*(` definition in the adapter's C++ sources (not calls)."""
    found: set[str] = set()
    for path in sorted(adapter.rglob("*.cpp")):
        found.update(_EXPORT.findall(path.read_text(encoding="utf-8", errors="replace")))
    return found


def check(root: Path) -> int:
    kotlin = root / KOTLIN
    adapter = root / ADAPTER
    if not kotlin.is_file():
        print(f"check_jni_parity: {KOTLIN} not found", file=sys.stderr)
        return 2
    if not adapter.is_dir() or not any(adapter.rglob("*.cpp")):
        print(
            f"check_jni_parity: no C++ sources under {ADAPTER.as_posix()} "
            "(git submodule update --init engine)",
            file=sys.stderr,
        )
        return 2
    expected = expected_symbols(kotlin.read_text(encoding="utf-8"))
    exported = exported_symbols(adapter)
    missing = {name: sym for name, sym in expected.items() if sym not in exported}
    for name, sym in missing.items():
        print(f"{KOTLIN.as_posix()}: external fun {name} has no {sym} in {ADAPTER.as_posix()}")
    if missing:
        print(
            f"{len(missing)} of {len(expected)} JNI declarations missing from the pinned engine; "
            "check the engine gitlink (git ls-tree HEAD engine)."
        )
        return 1
    print(f"check_jni_parity: all {len(expected)} external funs exported by the engine adapter")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return check(parser.parse_args(argv).root)


if __name__ == "__main__":
    sys.exit(main())
