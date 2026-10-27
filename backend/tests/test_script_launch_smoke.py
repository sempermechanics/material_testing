"""`scripts/launch_smoke.py`: cold-start the minified benchmark build and fail on a
crash or an R8 symptom. Driven through a fake `subprocess.run`, so nothing here
touches a device.
"""
from __future__ import annotations

import importlib.util
import subprocess
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "launch_smoke.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

PKG = "org.example.app"
COMPONENT = f"{PKG}/com.sempermechanics.semper.LauncherAlias"


@pytest.fixture(scope="module")
def smoke():
    spec = importlib.util.spec_from_file_location("launch_smoke", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _line(pid: int, level: str, text: str) -> str:
    return f"10-26 11:44:24.408  {pid}  {pid + 7} {level} Tag     : {text}"


class FakeDevice:
    """Stands in for `subprocess.run`: answers the adb calls the smoke makes."""

    def __init__(self, *, status="Status: ok\nTotalTime: 812\n", pids=None, logcat="", crash="",
                 resolved=f"priority=0 preferredOrder=0\n{COMPONENT}\n"):
        self.calls: list[list[str]] = []
        self.status, self.logcat, self.crash, self.resolved = status, logcat, crash, resolved
        self.pids = list(pids if pids is not None else ["4242"] * 30)

    def __call__(self, cmd, **kwargs):
        self.calls.append(cmd)
        args = cmd[cmd.index("adb") + 1:]
        if args[:2] == ["-s", "S"]:
            args = args[2:]
        out = ""
        if args[:2] == ["shell", "cmd"]:
            out = self.resolved
        elif args[:3] == ["shell", "am", "start"]:
            out = self.status
        elif args[:2] == ["shell", "pidof"]:
            out = self.pids.pop(0) + "\n" if self.pids else ""
        elif args[:2] == ["logcat", "-d"]:
            out = self.logcat
        elif args[:4] == ["logcat", "-b", "crash", "-d"]:
            out = self.crash
        return subprocess.CompletedProcess(cmd, 0, stdout=out, stderr="")


def _run(smoke, monkeypatch, tmp_path, device, settle=3.0, out=None):
    monkeypatch.setattr(smoke.subprocess, "run", device)
    apk = tmp_path / "app-benchmark.apk"
    apk.write_bytes(b"apk")
    return smoke.smoke(["adb"], apk, PKG, settle, out, sleep=lambda _s: None)


def test_a_clean_cold_start_passes(smoke, monkeypatch, tmp_path):
    device = FakeDevice(logcat=_line(4242, "I", "ActivityTaskManager: Displayed"))
    out = tmp_path / "out"
    assert _run(smoke, monkeypatch, tmp_path, device, out=out) == []
    steps = [c[1:] for c in device.calls]
    assert steps[0][:3] == ["install", "-r", "-t"]
    assert ["shell", "am", "start", "-W", "-n", COMPONENT] in steps
    assert sum(1 for s in steps if s[:2] == ["shell", "pidof"]) == 3
    assert (out / "am-start.txt").read_text(encoding="utf-8").startswith("Status: ok")
    assert {p.name for p in out.iterdir()} == {"am-start.txt", "logcat.txt", "crash.txt"}


def test_a_start_that_is_not_ok_fails(smoke, monkeypatch, tmp_path):
    failures = _run(smoke, monkeypatch, tmp_path, FakeDevice(status="Status: timeout\n"))
    assert failures == [f"am start -W {COMPONENT} did not report Status: ok"]


def test_a_process_that_dies_fails(smoke, monkeypatch, tmp_path):
    failures = _run(smoke, monkeypatch, tmp_path, FakeDevice(pids=["4242", "4242"]), settle=10.0)
    assert failures == [f"{PKG} was not running 2 s after launch"]


def test_a_java_crash_in_the_crash_buffer_fails(smoke, monkeypatch, tmp_path):
    crash = (
        "E AndroidRuntime: FATAL EXCEPTION: main\n"
        f"E AndroidRuntime: Process: {PKG}, PID: 4242\n"
        "E AndroidRuntime: Process: com.other.app, PID: 77\n"
    )
    failures = _run(smoke, monkeypatch, tmp_path, FakeDevice(crash=crash))
    assert failures == [f"crash buffer: E AndroidRuntime: Process: {PKG}, PID: 4242"]


def test_a_native_crash_of_the_app_fails(smoke, monkeypatch, tmp_path):
    crash = f"F DEBUG   : pid: 4242, tid: 4242, name: main  >>> {PKG} <<<\n"
    assert len(_run(smoke, monkeypatch, tmp_path, FakeDevice(crash=crash))) == 1


def test_an_r8_symptom_at_error_level_fails_and_a_warning_only_warns(smoke, monkeypatch, tmp_path, capsys):
    logcat = "\n".join([
        _line(4242, "E", "Sync: kotlinx.serialization.SerializationException: Serializer for class 'X' is not found"),
        _line(4242, "W", "Probe: java.lang.ClassNotFoundException: org.conscrypt.Conscrypt"),
        _line(77, "E", "Other: java.lang.NoSuchMethodError: not ours"),
    ])
    failures = _run(smoke, monkeypatch, tmp_path, FakeDevice(logcat=logcat))
    assert len(failures) == 1 and "SerializationException" in failures[0]
    out = capsys.readouterr().out
    assert "::warning title=Launch smoke::" in out and "Conscrypt" in out


def test_no_launcher_activity_fails_before_starting(smoke, monkeypatch, tmp_path):
    device = FakeDevice(resolved="No activity found\n")
    assert _run(smoke, monkeypatch, tmp_path, device) == [f"{PKG} has no launcher activity"]
    assert not any(c[1:4] == ["shell", "am", "start"] for c in device.calls)


@pytest.mark.parametrize("resolved, want", [
    (f"priority=0\n{COMPONENT}\n", COMPONENT),
    ("No activity found\n", None),
    ("com.other/.Main\n", None),
])
def test_launcher_component(smoke, resolved, want):
    assert smoke.launcher_component(resolved, PKG) == want


def test_main_exit_codes(smoke, monkeypatch, tmp_path, capsys):
    apk = tmp_path / "app.apk"
    assert smoke.main(["launch_smoke.py", str(apk), "--package", PKG]) == 2
    apk.write_bytes(b"apk")
    monkeypatch.setattr(smoke.time, "sleep", lambda _s: None)
    monkeypatch.setattr(smoke.subprocess, "run", FakeDevice())
    assert smoke.main(["launch_smoke.py", str(apk), "--package", PKG, "--serial", "S", "--settle", "2"]) == 0
    assert "Launch smoke passed" in capsys.readouterr().out
    monkeypatch.setattr(smoke.subprocess, "run", FakeDevice(status="Status: timeout\n"))
    assert smoke.main(["launch_smoke.py", str(apk), "--package", PKG, "--settle", "2"]) == 1


def test_an_install_that_fails_exits_2(smoke, monkeypatch, tmp_path, capsys):
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"apk")
    monkeypatch.setattr(smoke.subprocess, "run",
                        lambda cmd, **k: subprocess.CompletedProcess(cmd, 1, "", "INSTALL_FAILED_NO_MATCHING_ABIS"))
    assert smoke.main(["launch_smoke.py", str(apk), "--package", PKG]) == 2
    assert "INSTALL_FAILED_NO_MATCHING_ABIS" in capsys.readouterr().out
