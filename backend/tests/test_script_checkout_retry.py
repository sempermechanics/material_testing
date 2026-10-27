"""`.github/actions/checkout-submodules/retry.sh`, which retries the CI's submodule fetches.

Each test runs the script under bash with no waits (`RETRY_DELAYS`) on a
command that counts its calls in a file and fails until a given call.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_RETRY = _REPO / ".github" / "actions" / "checkout-submodules" / "retry.sh"

pytestmark = [
    pytest.mark.skipif(not _RETRY.is_file(), reason=".github/ not present (backend-only checkout)"),
    pytest.mark.skipif(os.name == "nt" or shutil.which("bash") is None, reason="needs bash"),
]

# Fails (exit 3) on every call before call number $1, then succeeds.
_FLAKY = 'n=$(( $(cat calls 2>/dev/null || echo 0) + 1 )); echo "$n" > calls; [ "$n" -ge "$1" ] || exit 3'


def _run(tmp_path: Path, succeed_on: int) -> subprocess.CompletedProcess[str]:
    env = {**os.environ, "RETRY_DELAYS": "0 0 0 0"}
    return subprocess.run(  # noqa: S603 - this repo's own script and a fixed command, no shell
        ["bash", _RETRY.as_posix(), "bash", "-c", _FLAKY, "flaky", str(succeed_on)],  # noqa: S607 - fixed bash on PATH
        capture_output=True, text=True, env=env, cwd=tmp_path, timeout=60,
    )


def _calls(tmp_path: Path) -> int:
    return int((tmp_path / "calls").read_text().strip())


def test_a_command_that_succeeds_runs_once(tmp_path):
    proc = _run(tmp_path, succeed_on=1)
    assert proc.returncode == 0
    assert _calls(tmp_path) == 1
    assert "::warning::" not in proc.stdout


def test_a_failure_is_retried_until_it_passes(tmp_path):
    proc = _run(tmp_path, succeed_on=3)
    assert proc.returncode == 0
    assert _calls(tmp_path) == 3
    assert proc.stdout.count("::warning::") == 2
    assert "try 1 of 5" in proc.stdout and "try 2 of 5" in proc.stdout


def test_five_failures_fail_with_the_command_status(tmp_path):
    proc = _run(tmp_path, succeed_on=99)
    assert proc.returncode == 3
    assert _calls(tmp_path) == 5
    assert "::error::" in proc.stdout and "failed 5 times (exit 3)" in proc.stdout
