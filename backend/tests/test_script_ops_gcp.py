"""The GCP ops scripts, run against a stub `gcloud` / `curl` only.

`scripts/check_gcp_posture.sh` (read-only posture verifier) and
`scripts/apply-monitoring.sh` (monitoring as code, ops/monitoring/) are run
with `scripts/tests/fixtures/gcp/stub_gcloud.py` first on PATH under both
names, answering from fixture files. Nothing here reaches Google: the stub
has no network code, and the real tools are shadowed.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_POSTURE = _REPO / "scripts" / "check_gcp_posture.sh"
_APPLY = _REPO / "scripts" / "apply-monitoring.sh"
_FIX = _REPO / "scripts" / "tests" / "fixtures" / "gcp"
_MON = _REPO / "ops" / "monitoring"

pytestmark = [
    pytest.mark.skipif(not _POSTURE.is_file(), reason="scripts/ not present (backend-only checkout)"),
    pytest.mark.skipif(os.name == "nt" or shutil.which("bash") is None, reason="needs bash"),
]

_ARGS = ["--app-project", "demo-app-prod", "--auth-project", "demo-auth-prod",
         "--api-sa", "api@demo-app-prod.iam.gserviceaccount.com"]


def _stub_path(tmp_path: Path) -> Path:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir(exist_ok=True)
    stub = _FIX / "stub_gcloud.py"
    for name in ("gcloud", "curl"):
        target = bin_dir / name
        target.write_text(stub.read_text(encoding="utf-8"), encoding="utf-8")
        target.chmod(0o755)
    return bin_dir


def _run(tmp_path: Path, script: Path, scenario: str | Path, *args: str,
         ) -> tuple[subprocess.CompletedProcess, list[str]]:
    bin_dir = _stub_path(tmp_path)
    # One log per run, so a second run in the same test sees only its own calls.
    log = tmp_path / f"calls-{len(list(tmp_path.glob('calls-*.log')))}.log"
    log.touch()
    env = {
        "PATH": f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}",
        "STUB_FIXTURES": str(scenario if isinstance(scenario, Path) else _FIX / scenario),
        "STUB_LOG": str(log),
        "HOME": str(tmp_path),
    }
    proc = subprocess.run(["bash", str(script), *args], capture_output=True, text=True, env=env, timeout=120)
    calls = log.read_text(encoding="utf-8").splitlines()
    return proc, calls


def _variant(tmp_path: Path, files: dict[str, object], rules: list[dict] | None = None) -> Path:
    """posture_pass, copied, with `files` replaced (JSON-encoded) and `rules`
    put ahead of its gcloud.json rules (the first match wins)."""
    root = tmp_path / "scenario"
    shutil.copytree(_FIX / "posture_pass", root)
    for name, value in files.items():
        (root / name).write_text(json.dumps(value), encoding="utf-8")
    if rules:
        existing = json.loads((root / "gcloud.json").read_text(encoding="utf-8"))
        (root / "gcloud.json").write_text(json.dumps(rules + existing), encoding="utf-8")
    return root


def _auth_iam(role: str) -> dict:
    return {"bindings": [
        {"role": "roles/owner", "members": ["group:owners@example.test", "group:breakglass@example.test"]},
        {"role": role, "members": ["serviceAccount:api@demo-app-prod.iam.gserviceaccount.com"]},
    ], "auditConfigs": [{"service": "allServices", "auditLogConfigs": [
        {"logType": "DATA_READ"}, {"logType": "DATA_WRITE"}]}]}


def _lines(stdout: str) -> dict[tuple[str, str], tuple[str, str]]:
    """(item, scope) -> (status, detail) from the posture table."""
    out = {}
    for line in stdout.splitlines():
        m = re.match(r"^(\d\.\d+-[\w-]+)\s+(PASS|FAIL|UNKNOWN)\s+(\S+)\s+(.*)$", line)
        if m:
            out[(m.group(1), m.group(3))] = (m.group(2), m.group(4))
    return out


# --- check_gcp_posture.sh ------------------------------------------------------


def test_posture_all_pass(tmp_path):
    proc, calls = _run(tmp_path, _POSTURE, "posture_pass", *_ARGS)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    lines = _lines(proc.stdout)
    assert {s for s, _ in lines.values()} == {"PASS"}, proc.stdout
    items = {item for item, _ in lines}
    for want in ("2.1-lien", "2.1-tag", "2.1-owners", "2.1-groups", "2.4-api-keys", "2.5-auth-providers",
                 "2.6-budget", "2.6-max-instances", "2.7-pitr", "2.7-delete-protection",
                 "2.7-backup", "3.1-app-check", "3.4-check-revoked", "3.4-iam",
                 "3.5-entitlement-key", "3.7-audit-logs"):
        assert want in items, f"{want} missing from:\n{proc.stdout}"
    assert lines[("2.7-backup", "demo-app-prod")][1].startswith("daily schedule")
    assert "roles/firebaseauth.admin" in lines[("3.4-iam", "demo-auth-prod")][1]
    assert lines[("3.5-entitlement-key", "semper-api")][1].startswith("secret entitlement-signing-key")
    assert "FAIL: 0  UNKNOWN: 0" in proc.stdout
    assert calls, "the stub was never called"


def test_posture_reports_each_failure(tmp_path):
    proc, _ = _run(tmp_path, _POSTURE, "posture_fail", *_ARGS, "--backup-bucket", "gs://demo-backups")
    assert proc.returncode == 1
    lines = _lines(proc.stdout)
    fail = {item for (item, _), (status, _) in lines.items() if status == "FAIL"}
    assert fail >= {"2.1-lien", "2.1-tag", "2.1-owners", "2.1-groups", "2.4-api-keys", "2.5-auth-providers",
                    "2.6-budget", "2.6-max-instances", "2.7-pitr", "2.7-delete-protection",
                    "2.7-backup", "3.1-app-check", "3.4-check-revoked", "3.4-iam",
                    "3.5-entitlement-key", "3.7-audit-logs"}, proc.stdout
    # The newest export is from 2024: stale, not missing.
    assert "latest export 20240102T000000Z" in lines[("2.7-backup", "demo-app-prod")][1]
    # Reading users is not enough: revoke and delete need update and delete.
    status, detail = lines[("3.4-iam", "demo-auth-prod")]
    assert status == "FAIL" and "roles/firebaseauth.viewer" in detail and "firebaseauth.admin" in detail
    assert "anonymous sign-in is enabled" in lines[("2.5-auth-providers", "demo-auth-prod")][1]
    assert "Browser key" in lines[("2.4-api-keys", "demo-app-prod")][1]
    assert "plain env value" in lines[("3.5-entitlement-key", "semper-api")][1]
    # Staging could not be read: UNKNOWN, with gcloud's reason.
    status, detail = lines[("3.1-app-check", "semper-api-staging")]
    assert status == "UNKNOWN" and "PERMISSION_DENIED" in detail


def test_posture_only_reads(tmp_path):
    """Every gcloud call is a describe / list / get-iam-policy / token print,
    and curl is only ever a GET."""
    _, calls = _run(tmp_path, _POSTURE, "posture_pass", *_ARGS)
    read_verbs = {"describe", "list", "ls", "get-iam-policy", "print-access-token"}
    for call in calls:
        words = call.split()
        if words[0] == "gcloud":
            assert read_verbs & set(words), call
            assert not {"create", "update", "delete", "set-iam-policy", "add-iam-policy-binding",
                        "remove-iam-policy-binding", "patch"} & set(words), call
        else:
            assert words[:2] == ["curl", "GET"], call


def test_posture_backups_pass_on_a_recent_export_without_a_schedule(tmp_path):
    """Semper's backups are the daily export workflow, not a managed schedule."""
    stamp = (datetime.now(timezone.utc) - timedelta(hours=3)).strftime("%Y%m%dT%H%M%SZ")
    scenario = _variant(tmp_path, {"schedules.json": []}, [{
        "match": ["storage", "ls", "gs://demo-backups/firestore/demo-app-prod/"],
        "stdout": f"gs://demo-backups/firestore/demo-app-prod/20240101T000000Z/\n"
                  f"gs://demo-backups/firestore/demo-app-prod/{stamp}/\n",
    }])
    proc, calls = _run(tmp_path, _POSTURE, scenario, *_ARGS, "--backup-bucket", "demo-backups")
    status, detail = _lines(proc.stdout)[("2.7-backup", "demo-app-prod")]
    assert status == "PASS" and stamp in detail and "3 h old" in detail, proc.stdout
    assert proc.returncode == 0
    assert any(c.startswith("gcloud storage ls") for c in calls)


def test_posture_backups_unread_are_unknown_not_fail(tmp_path):
    scenario = _variant(tmp_path, {"schedules.json": []})
    # No bucket named: the export cannot be looked at.
    proc, calls = _run(tmp_path, _POSTURE, scenario, *_ARGS)
    status, detail = _lines(proc.stdout)[("2.7-backup", "demo-app-prod")]
    assert status == "UNKNOWN" and "firestore-backup.yml" in detail and "--backup-bucket" in detail
    assert proc.returncode == 0
    assert not any("storage" in c for c in calls)
    # A bucket that cannot be listed (the stub has no answer for it).
    proc, _ = _run(tmp_path, _POSTURE, scenario, *_ARGS, "--backup-bucket", "demo-backups")
    status, detail = _lines(proc.stdout)[("2.7-backup", "demo-app-prod")]
    assert status == "UNKNOWN" and "export listing failed" in detail and "firestore-backup.yml" in detail


@pytest.mark.parametrize(("permissions", "status"), [
    (["firebaseauth.users.get", "firebaseauth.users.update", "firebaseauth.users.delete"], "PASS"),
    (["firebaseauth.users.get", "firebaseauth.users.update"], "FAIL"),
    (None, "UNKNOWN"),
])
def test_posture_iam_reads_a_custom_role(tmp_path, permissions, status):
    role = "projects/demo-auth-prod/roles/semperAuthUsers"
    rule = {"match": ["iam", "roles", "describe", "semperAuthUsers", "--project", "demo-auth-prod"]}
    if permissions is None:
        rule.update(stderr="ERROR: PERMISSION_DENIED\n", exit=1)
    else:
        rule["stdout"] = json.dumps({"name": role, "includedPermissions": permissions})
    scenario = _variant(tmp_path, {"iam_auth.json": _auth_iam(role)}, [rule])
    proc, _ = _run(tmp_path, _POSTURE, scenario, *_ARGS)
    got, detail = _lines(proc.stdout)[("3.4-iam", "demo-auth-prod")]
    assert got == status, proc.stdout
    if status == "UNKNOWN":
        assert "PERMISSION_DENIED" in detail


def test_posture_wrapper_refuses_a_write(tmp_path):
    """The gc() guard stops a non-read gcloud verb before it runs."""
    script = _POSTURE.read_text(encoding="utf-8")
    probe = tmp_path / "probe.sh"
    start = script.index("gc() {")
    end = script.index("\n}\n", start) + 3
    probe.write_text("set -euo pipefail\nWORK=$(mktemp -d)\n" + script[start:end]
                     + 'gc projects delete demo-app-prod\necho "ran"\n', encoding="utf-8")
    proc, calls = _run(tmp_path, probe, "posture_pass")
    assert proc.returncode == 3
    assert "refusing non-read-only gcloud call" in proc.stderr
    assert calls == [] and "ran" not in proc.stdout


def test_posture_requires_its_arguments(tmp_path):
    proc, calls = _run(tmp_path, _POSTURE, "posture_pass", "--app-project", "demo-app-prod")
    assert proc.returncode == 2
    assert calls == []


# --- apply-monitoring.sh --------------------------------------------------------


def _rendered(out: Path) -> dict[str, str]:
    return {str(p.relative_to(out)): p.read_text(encoding="utf-8") for p in sorted(out.rglob("*")) if p.is_file()
            and p.name != "response.json"}


def test_monitoring_dry_run_calls_nothing_and_renders_everything(tmp_path):
    out = tmp_path / "out"
    proc, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                       "--notification-channel", "123", "--out", str(out))
    assert proc.returncode == 0, proc.stderr
    assert calls == [], "a dry run must not call gcloud or curl"
    assert "DRY RUN" in proc.stdout
    files = _rendered(out)
    sources = sorted(str(p.relative_to(_MON)) for p in _MON.rglob("*") if p.suffix in (".json", ".yaml"))
    assert sorted(files) == sources
    for name, text in files.items():
        assert not re.search(r"__[A-Z_]+__", text), name
        if name.endswith(".json"):
            json.loads(text)


def test_monitoring_policies_are_well_formed(tmp_path):
    out = tmp_path / "out"
    proc, _ = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                   "--notification-channel", "projects/demo-app-prod/notificationChannels/9",
                   "--activations-per-licence-per-day", "7", "--auth-denied-per-5min", "40", "--out", str(out))
    assert proc.returncode == 0, proc.stderr
    names = set()
    for path in sorted((out / "policies").glob("*.json")):
        policy = json.loads(path.read_text(encoding="utf-8"))
        names.add(policy["displayName"])
        assert policy["notificationChannels"] == ["projects/demo-app-prod/notificationChannels/9"], path.name
        assert policy["conditions"] and policy["combiner"] == "OR"
        assert policy["documentation"]["content"], path.name
        for cond in policy["conditions"]:
            threshold = cond["conditionThreshold"]["thresholdValue"]
            assert isinstance(threshold, (int, float)), path.name
    assert len(names) == 8, "display names identify policies on update, so they must be unique"
    activation = json.loads((out / "policies" / "activation-anomaly.json").read_text(encoding="utf-8"))
    assert activation["conditions"][0]["conditionThreshold"]["thresholdValue"] == 7
    auth = json.loads((out / "policies" / "auth-denied-spike.json").read_text(encoding="utf-8"))
    assert auth["conditions"][0]["conditionThreshold"]["thresholdValue"] == 40


def test_monitoring_metric_files_parse(tmp_path):
    yaml = pytest.importorskip("yaml")
    for path in sorted((_MON / "metrics").glob("*.yaml")):
        metric = yaml.safe_load(path.read_text(encoding="utf-8"))
        assert metric["filter"] and metric["description"], path.name
        assert 'resource.type="cloud_run_revision"' in metric["filter"]
        labels = {lab["key"] for lab in metric["metricDescriptor"].get("labels", [])}
        assert labels == set(metric.get("labelExtractors", {})), path.name


def test_monitoring_apply_creates_then_updates(tmp_path):
    fresh, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                        "--notification-channel", "123", "--apply", "--out", str(tmp_path / "a"))
    assert fresh.returncode == 0, fresh.stdout + fresh.stderr
    text = "\n".join(calls)
    assert text.count("logging metrics create") == 6
    assert "curl POST https://monitoring.googleapis.com/v3/projects/demo-app-prod/uptimeCheckConfigs" in text
    assert "services?serviceId=semper-api" in text
    assert "serviceLevelObjectives?serviceLevelObjectiveId=availability-995" in text
    assert text.count("policies create") == 8
    uptime = json.loads((tmp_path / "a" / "policies" / "uptime-readyz.json").read_text(encoding="utf-8"))
    assert 'check_id="semper-api-readyz-abc123"' in uptime["conditions"][0]["conditionThreshold"]["filter"]
    burn = json.loads((tmp_path / "a" / "policies" / "slo-burn-fast.json").read_text(encoding="utf-8"))
    assert "projects/111/services/semper-api/serviceLevelObjectives/availability-995" in \
        burn["conditions"][0]["conditionThreshold"]["filter"]
    check = json.loads((tmp_path / "a" / "uptime" / "readyz.json").read_text(encoding="utf-8"))
    assert check["monitoredResource"]["labels"]["host"] == "semper-api-abc-el.a.run.app"

    again, calls = _run(tmp_path, _APPLY, "monitoring_existing", "--project", "demo-app-prod",
                        "--notification-channel", "123", "--apply", "--out", str(tmp_path / "b"))
    assert again.returncode == 0, again.stdout + again.stderr
    text = "\n".join(calls)
    assert "metrics create" not in text and text.count("logging metrics update") == 6
    assert "policies create" not in text and text.count("policies update") == 8
    assert "curl PATCH" in text and "curl POST" not in text


def test_monitoring_refuses_bad_input(tmp_path):
    proc, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "Not A Project",
                       "--notification-channel", "1")
    assert proc.returncode == 2 and calls == []
    proc, _ = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod", "--notification-channel", "1",
                   "--activations-per-licence-per-day", "lots")
    assert proc.returncode == 2
