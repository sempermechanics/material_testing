#!/usr/bin/env python3
"""Cold-start a minified build on a device and fail on a crash or an R8 symptom.

Nothing in CI launched an R8 build before a user did: Tier 5 builds the release
APK but has no device, and Tier 3 and the micro A/B run the debug build. A keep
rule that is missing (``app/proguard-rules.pro``: the serialization models, the
JNI callback resolved by name) fails only at run time. The benchmark job builds
the ``benchmark`` type, which is R8-minified as release and debug-signed, and
runs this on its emulator before the Macrobenchmarks.

It installs the APK, resolves its launcher activity, cold-starts it with
``am start -W``, watches the process for ``--settle`` seconds and fails when:

- ``am start -W`` does not report ``Status: ok``;
- the process is gone before the settle time is up;
- the crash buffer holds a crash of the app (a Java ``Process: <id>`` or a
  native ``>>> <id> <<<`` entry);
- an error line of the app's process names an R8 symptom (``R8_SYMPTOMS``).

A warning line that names one is reported, not failed: libraries probe for
optional classes and log what they did not find.

The engine library loads on first use (``SemperNativeLib``), not at launch, so
a launch does not reach the JNI callback; the emulator tier's
``EnginePipelineSmokeTest`` does, on the debug build.

Usage:
  python scripts/launch_smoke.py APK --package ID [--serial S] [--settle 15] [--out DIR]
With ``--out`` the ``am start`` output, the app's logcat and the crash buffer
are written there.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

R8_SYMPTOMS = (
    "ClassNotFoundException",
    "NoClassDefFoundError",
    "NoSuchMethodError",
    "NoSuchMethodException",
    "NoSuchFieldError",
    "NoSuchFieldException",
    "AbstractMethodError",
    "UnsatisfiedLinkError",
    "SerializationException",
    "VerifyError",
)
# `logcat -v threadtime`: date time pid tid level tag: message
_LINE = re.compile(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+([VDIWEF])\s+(.*)$")


def adb(prefix: list[str], *args: str, check: bool = True) -> str:
    result = subprocess.run(prefix + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def launcher_component(resolved: str, package: str) -> str | None:
    """The `pkg/.Activity` line of `cmd package resolve-activity --brief`."""
    lines = [line.strip() for line in resolved.splitlines() if line.strip().startswith(f"{package}/")]
    return lines[-1] if lines else None


def crashes_of(crash_buffer: str, package: str) -> list[str]:
    """The crash buffer's lines that name the app: a Java crash's `Process:` line
    or a native tombstone's `>>> pkg <<<` line."""
    marks = (f"Process: {package},", f">>> {package} <<<")
    return [line for line in crash_buffer.splitlines() if any(mark in line for mark in marks)]


def symptoms(logcat: str, pid: str | None) -> tuple[list[str], list[str]]:
    """(error lines, warning lines) of process `pid` naming an R8 symptom; a
    `FATAL EXCEPTION` error line counts whatever it names."""
    errors: list[str] = []
    warnings: list[str] = []
    for line in logcat.splitlines():
        m = _LINE.match(line)
        if not m or (pid and m.group(1) != pid):
            continue
        level, text = m.group(2), m.group(3)
        named = any(symptom in text for symptom in R8_SYMPTOMS)
        if level in "EF" and (named or "FATAL EXCEPTION" in text):
            errors.append(line)
        elif level == "W" and named:
            warnings.append(line)
    return errors, warnings


def smoke(prefix: list[str], apk: Path, package: str, settle: float, out: Path | None,
          sleep=None) -> list[str]:
    """Run the smoke; the failures found, empty when it passed."""
    sleep = sleep or time.sleep
    adb(prefix, "install", "-r", "-t", str(apk))
    adb(prefix, "shell", "am", "force-stop", package, check=False)
    adb(prefix, "logcat", "-c", check=False)
    adb(prefix, "logcat", "-b", "crash", "-c", check=False)
    component = launcher_component(
        adb(prefix, "shell", "cmd", "package", "resolve-activity", "--brief",
            "-c", "android.intent.category.LAUNCHER", package),
        package,
    )
    if component is None:
        return [f"{package} has no launcher activity"]
    started = adb(prefix, "shell", "am", "start", "-W", "-n", component, check=False)
    failures = [] if re.search(r"^Status: ok", started, re.M) else [f"am start -W {component} did not report Status: ok"]
    pid = None
    waited = 0.0
    while waited < settle:
        pid = adb(prefix, "shell", "pidof", package, check=False).strip() or None
        if pid is None:
            break
        sleep(1.0)
        waited += 1.0
    if pid is None:
        failures.append(f"{package} was not running {waited:.0f} s after launch")
    logcat = adb(prefix, "logcat", "-d", "-v", "threadtime", check=False)
    crash = adb(prefix, "logcat", "-b", "crash", "-d", check=False)
    failures += [f"crash buffer: {line.strip()}" for line in crashes_of(crash, package)]
    errors, warnings = symptoms(logcat, pid)
    failures += [f"logcat: {line.strip()}" for line in errors]
    for line in warnings:
        print(f"::warning title=Launch smoke::{line.strip()}")
    if out is not None:
        out.mkdir(parents=True, exist_ok=True)
        (out / "am-start.txt").write_text(started, encoding="utf-8")
        (out / "logcat.txt").write_text(logcat, encoding="utf-8")
        (out / "crash.txt").write_text(crash, encoding="utf-8")
    return failures


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", type=Path, help="the APK to install and launch")
    parser.add_argument("--package", required=True, help="its applicationId")
    parser.add_argument("--serial", help="adb serial, if more than one device is attached")
    parser.add_argument("--settle", type=float, default=15.0, help="seconds the process must stay up (default 15)")
    parser.add_argument("--out", type=Path, help="where to write am start, logcat and the crash buffer")
    args = parser.parse_args(argv[1:])
    if not args.apk.is_file():
        print(f"::error title=Launch smoke::{args.apk} not found")
        return 2
    prefix = ["adb"] + (["-s", args.serial] if args.serial else [])
    try:
        failures = smoke(prefix, args.apk, args.package, args.settle, args.out)
    except RuntimeError as error:
        print(f"::error title=Launch smoke::{error}")
        return 2
    for failure in failures:
        print(f"::error title=Launch smoke::{failure}")
    if failures:
        return 1
    print(f"Launch smoke passed: {args.package} up {args.settle:.0f} s after a cold start, no crash or R8 symptom")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
