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
import sys
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
    # The interpreter running the tests, not `env python3`: a test below
    # shadows python3 on PATH.
    body = stub.read_text(encoding="utf-8").split("\n", 1)[1]
    for name in ("gcloud", "curl"):
        target = bin_dir / name
        target.write_text(f"#!{sys.executable}\n{body}", encoding="utf-8")
        target.chmod(0o755)
    return bin_dir


def _run(tmp_path: Path, script: Path, scenario: str | Path, *args: str, state: str = "state",
         ) -> tuple[subprocess.CompletedProcess, list[str]]:
    """Run [script] against the stub. Runs that share [state] share the stub's
    dashboards, so a rerun sees what an earlier run created."""
    bin_dir = _stub_path(tmp_path)
    # One log per run, so a second run in the same test sees only its own calls.
    log = tmp_path / f"calls-{len(list(tmp_path.glob('calls-*.log')))}.log"
    log.touch()
    env = {
        "PATH": f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}",
        "STUB_FIXTURES": str(scenario if isinstance(scenario, Path) else _FIX / scenario),
        "STUB_LOG": str(log),
        "STUB_STATE": str(tmp_path / state),
        "HOME": str(tmp_path),
    }
    proc = subprocess.run(["bash", str(script), *args], capture_output=True, text=True, env=env, timeout=120)  # noqa: S603,S607 - fixed bash and this repo's own script, no shell
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


# The command paths check_gcp_posture.sh's gc() lets through, word for word
# from the start of the call (TE6-010): the verb is matched where gcloud reads
# it, not anywhere in the arguments.
_READ_COMMANDS = (
    "projects describe ", "projects get-iam-policy ", "alpha resource-manager liens list ",
    "resource-manager tags bindings list ", "services api-keys list ", "auth print-access-token ",
    "billing projects describe ", "billing budgets list ", "run services describe ",
    "firestore databases describe ", "firestore backups schedules list ", "storage ls ",
    "iam roles describe ",
)


def test_posture_only_reads(tmp_path):
    """Every gcloud call starts with one of the read-only command paths, and
    curl is only ever a GET."""
    _, calls = _run(tmp_path, _POSTURE, "posture_pass", *_ARGS)
    assert any(c.startswith("gcloud ") for c in calls)
    for call in calls:
        words = call.split()
        if words[0] == "gcloud":
            assert (call[len("gcloud "):] + " ").startswith(_READ_COMMANDS), call
        else:
            assert words[:2] == ["curl", "GET"], call


@pytest.mark.parametrize("call", [
    "run services update semper-api --update-env-vars APP_CHECK_MODE=off list",
    "projects delete describe",
    "projects delete demo-app-prod --format=json describe",
    "--project demo-app-prod projects describe",
    "storage rm gs://demo-backups/firestore/ ls",
    "services api-keys delete key-1 list",
])
def test_posture_wrapper_matches_the_verb_position(tmp_path, call):
    """TE6-010: a read verb anywhere in the arguments used to let a write
    through (`gc projects delete describe` ran)."""
    script = _POSTURE.read_text(encoding="utf-8")
    probe = tmp_path / "probe.sh"
    start = script.index("gc() {")
    end = script.index("\n}\n", start) + 3
    probe.write_text("set -euo pipefail\nWORK=$(mktemp -d)\n" + script[start:end]
                     + f'gc {call}\necho "ran"\n', encoding="utf-8")
    proc, calls = _run(tmp_path, probe, "posture_pass")
    assert proc.returncode == 3, proc.stdout + proc.stderr
    assert "refusing non-read-only gcloud call" in proc.stderr
    assert calls == [] and "ran" not in proc.stdout


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


def test_posture_runs_where_python3_is_only_the_store_alias(tmp_path):
    """TE6-017: under Git Bash on Windows `python3` is the Microsoft Store
    alias (prints "Python was not found", exits 49) and the interpreter is
    `python`; native argv conversion there is no place for multi-line `-c`
    programs. Both are emulated: python3 fails, and python refuses any
    argument holding a newline."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    (bin_dir / "python3").write_text("#!/bin/sh\necho 'Python was not found; run without arguments to install"
                                     " from the Microsoft Store' >&2\nexit 49\n", encoding="utf-8")
    (bin_dir / "python").write_text(
        "#!/bin/sh\nnl='\n'\nfor a in \"$@\"; do\n  case \"$a\" in *\"$nl\"*)\n"
        "    echo 'multi-line argument refused' >&2; exit 97 ;;\n  esac\ndone\n"
        f"exec {sys.executable} \"$@\"\n", encoding="utf-8")
    for name in ("python3", "python"):
        (bin_dir / name).chmod(0o755)
    stamp = (datetime.now(timezone.utc) - timedelta(hours=5)).strftime("%Y%m%dT%H%M%SZ")
    scenario = _variant(tmp_path, {"schedules.json": []}, [{
        "match": ["storage", "ls", "gs://demo-backups/firestore/demo-app-prod/"],
        "stdout": f"gs://demo-backups/firestore/demo-app-prod/{stamp}/\n",
    }])
    proc, _ = _run(tmp_path, _POSTURE, scenario, *_ARGS, "--backup-bucket", "demo-backups")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    lines = _lines(proc.stdout)
    assert {s for s, _ in lines.values()} == {"PASS"}, proc.stdout
    assert "5 h old" in lines[("2.7-backup", "demo-app-prod")][1]
    assert "refused" not in proc.stderr and "not found" not in proc.stderr


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
    assert len(names) == len(_POLICIES), "display names identify policies on update, so they must be unique"
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
    assert text.count("logging metrics create") == len(_METRICS)
    assert "curl POST https://monitoring.googleapis.com/v3/projects/demo-app-prod/uptimeCheckConfigs" in text
    assert "services?serviceId=semper-api" in text
    assert "serviceLevelObjectives?serviceLevelObjectiveId=availability-995" in text
    assert text.count("policies create") == len(_POLICIES)
    assert text.count("curl POST https://monitoring.googleapis.com/v3/projects/demo-app-prod/uptimeCheckConfigs") \
        == len(_UPTIME)
    assert text.count("monitoring dashboards create") == len(_DASHBOARDS)
    assert "monitoring dashboards update" not in text
    uptime = json.loads((tmp_path / "a" / "policies" / "uptime-readyz.json").read_text(encoding="utf-8"))
    assert 'check_id="semper-api-readyz-abc123"' in uptime["conditions"][0]["conditionThreshold"]["filter"]
    burn = json.loads((tmp_path / "a" / "policies" / "slo-burn-fast.json").read_text(encoding="utf-8"))
    assert "projects/111/services/semper-api/serviceLevelObjectives/availability-995" in \
        burn["conditions"][0]["conditionThreshold"]["filter"]
    check = json.loads((tmp_path / "a" / "uptime" / "readyz.json").read_text(encoding="utf-8"))
    assert check["monitoredResource"]["labels"]["host"] == "semper-api-abc-el.a.run.app"

    again, calls = _run(tmp_path, _APPLY, "monitoring_existing", "--project", "demo-app-prod",
                        "--notification-channel", "123", "--apply", "--out", str(tmp_path / "b"), state="state-b")
    assert again.returncode == 0, again.stdout + again.stderr
    text = "\n".join(calls)
    assert "metrics create" not in text and text.count("logging metrics update") == len(_METRICS)
    assert "policies create" not in text and text.count("policies update") == len(_POLICIES)
    assert "curl PATCH" in text and "curl POST" not in text
    assert "dashboards create" not in text and text.count("monitoring dashboards update") == len(_DASHBOARDS)
    # Each existing dashboard was updated by its own name with the etag it was
    # listed with; the look-alike "semper-slo (copy)" was left alone.
    boards = {b["name"]: b for b in json.loads((tmp_path / "state-b" / "dashboards.json").read_text())}
    assert boards["projects/111/dashboards/other"]["etag"] == "etag-1"
    assert "tiles" in boards["projects/111/dashboards/other"]["mosaicLayout"]
    assert not boards["projects/111/dashboards/other"]["mosaicLayout"]["tiles"]
    for i in range(4):
        board = boards[f"projects/111/dashboards/existing-{i}"]
        assert board["etag"] == f"etag-{8 + i}", board["displayName"]
        assert board["mosaicLayout"]["tiles"], board["displayName"]


def _policy_texts() -> dict[str, str]:
    return {p.stem: p.read_text(encoding="utf-8") for p in sorted((_MON / "policies").glob("*.json"))}


@pytest.mark.parametrize(("metric", "label_filter"), [
    ("semper_revocation_check_failed", None),
    ("semper_auth_denied", 'metric.label.stage=\\"app_check\\"'),
    ("semper_devlock_drop", 'metric.label.stage=\\"mismatch\\"'),
    ("semper_activation", 'metric.label.outcome=\\"refused\\"'),
    ("semper_checkout", 'metric.label.outcome=\\"refused\\"'),
])
def test_halt_and_runbook_signals_have_an_alert(metric, label_filter):
    """TE6-002: RELEASING's halt criteria and INCIDENT_RESPONSE name these
    signals; each had a dashboard and no alert policy."""
    hits = [name for name, text in _policy_texts().items()
            for f in _filters(json.loads(text))
            if f'logging.googleapis.com/user/{metric}\\"' in json.dumps(f["filter"])
            and (label_filter is None or label_filter in json.dumps(f["filter"]))]
    assert hits, f"no policy alerts on {metric} {label_filter or ''}"


def test_checkout_refusal_alert_leaves_out_a_full_pool():
    """`no_floating_seat` is a full pool, not an error (RELEASING halt criteria)."""
    text = _policy_texts()["licensing-refusals"]
    assert 'metric.label.errorCode!=\\"no_floating_seat\\"' in text


def _halt_criteria() -> str:
    text = (_REPO / "docs" / "ops" / "RELEASING.md").read_text(encoding="utf-8")
    start = text.index("**Halt criteria.**")
    return text[start:text.index("\n## ", start)]


def test_halt_criteria_read_metrics_that_carry_the_build():
    """TE6-007: a per-version criterion needs a metric labelled with appId and
    appVersion (Material Testing numbers its builds from its own counter, so
    a bare appVersion matches both apps); semper_auth_denied has stage only."""
    criteria = _halt_criteria()
    named = set(re.findall(r"`(semper_[a-z_]+)`", criteria))
    assert {"semper_activation", "semper_checkout", "semper_devlock_drop", "semper_app_check_failed"} <= named
    labels = _metric_labels()
    for metric in named:
        assert {"appId", "appVersion"} <= labels[metric], metric
    assert "rising" not in criteria
    assert "(appId, appVersion)" in criteria


def test_devlock_halt_criterion_is_a_number():
    criteria = _halt_criteria()
    line = criteria[criteria.index("**Device-lock drops:**"):]
    line = line[:line.index("\n- ")]
    assert re.search(r"\*\*\d+\*\*", line), line
    assert "7-day" in line


def test_licensing_charts_the_refused_share_by_build():
    """The activation / checkout criteria compare refused shares; the board
    plots them per (appId, appVersion), checkout without a full pool."""
    board = json.loads((_MON / "dashboards" / "licensing.json").read_text(encoding="utf-8"))
    ratios = {}
    for tile in board["mosaicLayout"]["tiles"]:
        for ds in tile["widget"].get("xyChart", {}).get("dataSets", []):
            ratio = ds["timeSeriesQuery"].get("timeSeriesFilterRatio")
            if ratio:
                ratios[_USER_METRIC.search(ratio["denominator"]["filter"]).group(1)] = ratio
    assert set(ratios) == {"semper_activation", "semper_checkout"}
    for metric, ratio in ratios.items():
        assert 'metric.label.outcome="refused"' in ratio["numerator"]["filter"], metric
        assert "metric.label.outcome" not in ratio["denominator"]["filter"], metric
        for part in ("numerator", "denominator"):
            assert ratio[part]["aggregation"]["groupByFields"] == ["metric.label.appId", "metric.label.appVersion"]
            assert _USER_METRIC.search(ratio[part]["filter"]).group(1) == metric
    for part in ("numerator", "denominator"):
        assert 'metric.label.errorCode!="no_floating_seat"' in ratios["semper_checkout"][part]["filter"]


def test_every_policy_runbook_anchor_exists():
    """Each policy's documentation names a README section; it must exist."""
    readme = (_MON / "README.md").read_text(encoding="utf-8")
    headings = set(re.findall(r"^### ([a-z0-9-]+)$", readme, re.MULTILINE))
    for name, text in _policy_texts().items():
        anchors = re.findall(r"ops/monitoring/README\.md#([a-z0-9-]+)", text)
        assert anchors, f"{name}: no runbook link"
        assert set(anchors) <= headings, f"{name}: {set(anchors) - headings} not a README heading"


def test_gateway_is_probed_from_outside(tmp_path):
    """TE6-003: uptime and SLO watched the private run.app service only; the
    path users take is API Gateway. /healthz (no JWT, security: []) goes
    through to the backend with the gateway's SA; /v1/config without a token
    is ESPv2's 401, and a 404 there is a route the config dropped."""
    out = _dry_render(tmp_path, "--gateway-host", "semper-gw-x.an.gateway.dev")
    healthz = json.loads((out / "uptime" / "gateway-healthz.json").read_text(encoding="utf-8"))
    config = json.loads((out / "uptime" / "gateway-config.json").read_text(encoding="utf-8"))
    for check, path in ((healthz, "/healthz"), (config, "/v1/config")):
        assert check["monitoredResource"]["labels"]["host"] == "semper-gw-x.an.gateway.dev"
        assert check["httpCheck"]["path"] == path and check["httpCheck"]["useSsl"] is True
        # The gateway is public: no OIDC token, unlike the run.app check.
        assert "serviceAgentAuthentication" not in check["httpCheck"]
    assert healthz["httpCheck"]["acceptedResponseStatusCodes"] == [{"statusClass": "STATUS_CLASS_2XX"}]
    assert config["httpCheck"]["acceptedResponseStatusCodes"] == [{"statusValue": 401}]
    policy = json.loads((out / "policies" / "uptime-gateway.json").read_text(encoding="utf-8"))
    filters = [c["conditionThreshold"]["filter"] for c in policy["conditions"]]
    assert len(filters) == 2 and all("uptime_check/check_passed" in f for f in filters)
    assert policy["severity"] == "CRITICAL"


def test_monitoring_apply_looks_up_the_gateway_host(tmp_path):
    proc, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                       "--notification-channel", "123", "--apply", "--out", str(tmp_path / "a"))
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert any(c.startswith("gcloud api-gateway gateways describe semper-gw --location asia-northeast1")
               for c in calls), calls
    check = json.loads((tmp_path / "a" / "uptime" / "gateway-config.json").read_text(encoding="utf-8"))
    assert check["monitoredResource"]["labels"]["host"] == "semper-gw-abc.an.gateway.dev"


def test_monitoring_new_thresholds_come_from_flags(tmp_path):
    out = _dry_render(tmp_path, "--devlock-mismatches-per-licence-per-hour", "9",
                      "--licensing-refusals-per-hour", "33")
    devlock = json.loads((out / "policies" / "devlock-mismatch.json").read_text(encoding="utf-8"))
    assert devlock["conditions"][0]["conditionThreshold"]["thresholdValue"] == 9
    assert devlock["conditions"][0]["conditionThreshold"]["aggregations"][0]["groupByFields"] == \
        ["metric.label.licenseId"]
    refusals = json.loads((out / "policies" / "licensing-refusals.json").read_text(encoding="utf-8"))
    assert [c["conditionThreshold"]["thresholdValue"] for c in refusals["conditions"]] == [33, 33]
    proc, _ = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod", "--notification-channel", "1",
                   "--licensing-refusals-per-hour", "many")
    assert proc.returncode == 2


# --- dashboards -------------------------------------------------------------

_METRICS = sorted(p.stem for p in (_MON / "metrics").glob("*.yaml"))
_POLICIES = sorted((_MON / "policies").glob("*.json"))
_UPTIME = sorted((_MON / "uptime").glob("*.json"))
_DASHBOARDS = sorted((_MON / "dashboards").glob("*.json"))
_USER_METRIC = re.compile(r'logging\.googleapis\.com/user/([A-Za-z0-9_]+)')


def _dry_render(tmp_path: Path, *extra: str) -> Path:
    out = tmp_path / "out"
    proc, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                       "--notification-channel", "123", "--out", str(out), *extra)
    assert proc.returncode == 0, proc.stderr
    assert calls == []
    return out


def _filters(node) -> list[dict]:
    """Every timeSeriesFilter-shaped dict ({filter, aggregation}) under [node]."""
    found = []
    if isinstance(node, dict):
        if isinstance(node.get("filter"), str):
            found.append(node)
        for value in node.values():
            found += _filters(value)
    elif isinstance(node, list):
        for value in node:
            found += _filters(value)
    return found


def _metric_labels() -> dict[str, set[str]]:
    yaml = pytest.importorskip("yaml")
    return {p.stem: {lab["key"] for lab in yaml.safe_load(p.read_text(encoding="utf-8"))
                     ["metricDescriptor"].get("labels", [])}
            for p in (_MON / "metrics").glob("*.yaml")}


def test_every_referenced_log_metric_is_defined():
    """A dashboard or policy naming a log-based metric that ops/monitoring does
    not create would chart nothing, or alert on nothing, without an error."""
    defined = set(_METRICS)
    referenced = {}
    for path in sorted((_MON / "policies").glob("*.json")) + _DASHBOARDS:
        for name in _USER_METRIC.findall(path.read_text(encoding="utf-8")):
            referenced.setdefault(name, []).append(path.name)
    assert referenced, "no log-based metric referenced at all"
    missing = {name: files for name, files in referenced.items() if name not in defined}
    assert not missing, f"not in ops/monitoring/metrics/: {missing}"


def test_log_metric_labels_used_exist():
    """A group-by or filter on metric.label.X of a log-based metric needs X
    among that metric's labels. Dashboards never split by licenseId: a label
    per licence is the anomaly alert's business, not a chart's."""
    labels = _metric_labels()
    for path in sorted((_MON / "policies").glob("*.json")) + _DASHBOARDS:
        for f in _filters(json.loads(path.read_text(encoding="utf-8"))):
            m = _USER_METRIC.search(f["filter"])
            if not m:
                continue
            used = set(re.findall(r"metric\.label\.(\w+)", f["filter"]))
            # A chart's `aggregation`, or a policy condition's `aggregations`.
            for agg in [f.get("aggregation") or {}, *f.get("aggregations", [])]:
                used |= {g.split(".")[-1] for g in agg.get("groupByFields", []) if g.startswith("metric.label.")}
            assert used <= labels[m.group(1)], f"{path.name}: {m.group(1)} has no {used - labels[m.group(1)]}"
            if path.parent.name == "dashboards":
                assert "licenseId" not in used, path.name


def test_dashboards_are_well_formed(tmp_path):
    out = _dry_render(tmp_path)
    names = set()
    for path in sorted((out / "dashboards").glob("*.json")):
        board = json.loads(path.read_text(encoding="utf-8"))
        names.add(board["displayName"])
        assert set(board) <= {"displayName", "mosaicLayout", "labels"}, path.name
        assert board["labels"] == {"app": "semper", "managed_by": "ops-monitoring"}
        layout = board["mosaicLayout"]
        columns = layout["columns"]
        cells = set()
        for tile in layout["tiles"]:
            assert 0 <= tile["xPos"] and tile["xPos"] + tile["width"] <= columns, (path.name, tile)
            mine = {(x, y) for x in range(tile["xPos"], tile["xPos"] + tile["width"])
                    for y in range(tile.get("yPos", 0), tile.get("yPos", 0) + tile["height"])}
            assert not mine & cells, f"{path.name}: tiles overlap at {tile.get('widget', {}).get('title')}"
            cells |= mine
            kinds = set(tile["widget"]) - {"title"}
            assert len(kinds) == 1 and kinds <= {"xyChart", "scorecard", "text"}, (path.name, kinds)
            if "text" not in kinds:
                assert tile["widget"]["title"], path.name
        for f in _filters(board):
            assert f["filter"].startswith(("metric.type=", "select_slo_")), (path.name, f["filter"])
    assert names == {"semper-service-health", "semper-licensing", "semper-security", "semper-slo"}


def test_dashboards_cover_both_services_and_the_slo(tmp_path):
    out = _dry_render(tmp_path)
    health = (out / "dashboards" / "service-health.json").read_text(encoding="utf-8")
    assert 'service_name=one_of(\\"semper-api\\", \\"semper-api-staging\\")' in health
    for metric in ("request_count", "request_latencies", "container/instance_count",
                   "container/cpu/utilizations", "container/memory/utilizations"):
        assert f"run.googleapis.com/{metric}" in health, metric
    assert "timeSeriesFilterRatio" in health and "REDUCE_PERCENTILE_99" in health
    slo = (out / "dashboards" / "slo.json").read_text(encoding="utf-8")
    name = "projects/<project-number>/services/semper-api/serviceLevelObjectives/availability-995"
    for selector in ("select_slo_health", "select_slo_budget_fraction", "select_slo_burn_rate"):
        assert f'{selector}(\\"{name}\\"' in slo, selector
    security = json.loads((out / "dashboards" / "security.json").read_text(encoding="utf-8"))
    thresholds = [t["value"] for tile in security["mosaicLayout"]["tiles"]
                  for t in tile["widget"].get("xyChart", {}).get("thresholds", [])]
    assert thresholds == [50], "the AUTH_DENIED threshold is the alert's, as a number"

    (tmp_path / "other").mkdir()
    out = _dry_render(tmp_path / "other", "--service", "api-b", "--staging-service", "api-b-stg")
    health = (out / "dashboards" / "service-health.json").read_text(encoding="utf-8")
    assert 'one_of(\\"api-b\\", \\"api-b-stg\\")' in health


def test_dashboards_dry_run_names_each_one(tmp_path):
    out = tmp_path / "out"
    proc, _ = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                   "--notification-channel", "123", "--out", str(out))
    lines = [ln for ln in proc.stdout.splitlines() if "gcloud monitoring dashboards create|update" in ln]
    assert len(lines) == len(_DASHBOARDS)
    assert all("--project demo-app-prod --config-from-file=" in ln for ln in lines)


def test_dashboards_apply_converges(tmp_path):
    """Create on the first run; every later run updates the same four, by name
    and current etag, and never creates a second copy."""
    def apply(n):
        proc, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod",
                           "--notification-channel", "123", "--apply", "--out", str(tmp_path / f"o{n}"))
        assert proc.returncode == 0, proc.stdout + proc.stderr
        boards = json.loads((tmp_path / "state" / "dashboards.json").read_text(encoding="utf-8"))
        return [c for c in calls if "monitoring dashboards" in c], boards

    calls, first = apply(1)
    assert sum("dashboards create" in c for c in calls) == len(_DASHBOARDS)
    assert not any("dashboards update" in c for c in calls)
    assert {b["etag"] for b in first} == {"etag-1"}

    for n, etag in ((2, "etag-2"), (3, "etag-3")):
        calls, boards = apply(n)
        assert not any("dashboards create" in c for c in calls), calls
        updates = [c for c in calls if "dashboards update" in c]
        assert len(updates) == len(_DASHBOARDS)
        assert sorted(b["name"] for b in boards) == sorted(b["name"] for b in first)
        assert {b["etag"] for b in boards} == {etag}
        # What was sent is the rendered dashboard plus the etag that was read.
        for call in updates:
            sent = Path(re.search(r"--config-from-file=(\S+)", call).group(1))
            body = json.loads(sent.read_text(encoding="utf-8"))
            rendered = json.loads((tmp_path / f"o{n}" / "dashboards" / sent.name).read_text(encoding="utf-8"))
            assert body.pop("etag") == f"etag-{n - 1}"
            assert body == rendered


def test_monitoring_refuses_bad_input(tmp_path):
    proc, calls = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "Not A Project",
                       "--notification-channel", "1")
    assert proc.returncode == 2 and calls == []
    proc, _ = _run(tmp_path, _APPLY, "monitoring_fresh", "--project", "demo-app-prod", "--notification-channel", "1",
                   "--activations-per-licence-per-day", "lots")
    assert proc.returncode == 2


# ---------------------------------------------- secret access pre-check (TD-269)

_SECRET_CHECK = _REPO / "scripts" / "check_secret_access.sh"
_RUNTIME_SA = "api@demo-app-prod.iam.gserviceaccount.com"


def _iam_scenario(tmp_path: Path, secret_policy, project_policy) -> Path:
    """Answers for the two policy reads; None leaves a read unanswered (the
    stub's NOT_FOUND, as for a caller without getIamPolicy)."""
    root = tmp_path / "iam"
    root.mkdir(exist_ok=True)
    rules = []
    for words, policy in ((["secrets", "get-iam-policy"], secret_policy),
                          (["projects", "get-iam-policy"], project_policy)):
        if policy is not None:
            rules.append({"match": words, "stdout": json.dumps(policy)})
    (root / "gcloud.json").write_text(json.dumps(rules), encoding="utf-8")
    return root


def _grant(role: str, member: str = f"serviceAccount:{_RUNTIME_SA}") -> dict:
    return {"bindings": [{"role": role, "members": [member]}]}


@pytest.mark.parametrize(("secret_policy", "project_policy", "where"), [
    (_grant("roles/secretmanager.secretAccessor"), {}, "secret policy"),
    ({}, _grant("roles/secretmanager.secretAccessor"), "project policy"),
    ({}, _grant("roles/owner"), "project policy"),
])
def test_secret_check_passes_on_a_grant(tmp_path, secret_policy, project_policy, where):
    proc, calls = _run(tmp_path, _SECRET_CHECK, _iam_scenario(tmp_path, secret_policy, project_policy),
                       "demo-app-prod", "entitlement-key", _RUNTIME_SA)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.startswith("OK:") and where in proc.stdout
    assert all("add-iam-policy-binding" not in c and "set-iam-policy" not in c for c in calls)


def test_secret_check_fails_with_the_grant_to_make(tmp_path):
    other = _grant("roles/secretmanager.secretAccessor", "serviceAccount:someone@else.com")
    viewer = _grant("roles/secretmanager.viewer")
    proc, _ = _run(tmp_path, _SECRET_CHECK, _iam_scenario(tmp_path, other, viewer),
                   "demo-app-prod", "entitlement-key", _RUNTIME_SA)
    assert proc.returncode == 1
    assert proc.stdout.startswith("FAIL:")
    assert "gcloud secrets add-iam-policy-binding entitlement-key --project demo-app-prod" in proc.stdout
    assert f"--member=serviceAccount:{_RUNTIME_SA} --role=roles/secretmanager.secretAccessor" in proc.stdout


def test_secret_check_only_warns_when_a_policy_is_unreadable(tmp_path):
    proc, _ = _run(tmp_path, _SECRET_CHECK, _iam_scenario(tmp_path, {}, None),
                   "demo-app-prod", "entitlement-key", _RUNTIME_SA)
    assert proc.returncode == 0
    assert proc.stdout.startswith("WARN:") and "project demo-app-prod" in proc.stdout


def test_secret_check_requires_its_arguments(tmp_path):
    proc, calls = _run(tmp_path, _SECRET_CHECK, _iam_scenario(tmp_path, {}, {}), "demo-app-prod", "")
    assert proc.returncode == 2 and "usage:" in proc.stderr
    assert calls == []


def _deploy_steps() -> list[dict]:
    yaml = pytest.importorskip("yaml")
    workflow = yaml.safe_load((_REPO / ".github" / "workflows" / "deploy-backend.yml").read_text(encoding="utf-8"))
    return workflow["jobs"]["deploy"]["steps"]


def _step(steps: list[dict], step_id: str) -> tuple[int, dict]:
    return next((i, s) for i, s in enumerate(steps) if s.get("id") == step_id)


def test_the_candidate_smoke_loads_an_app_route():
    """TE6-004 (a): /readyz alone proves the container answers, not that the
    routers and auth stack loaded. GET /v1/config with a non-Bearer forwarded
    header is the app's own JSON 401 missing_bearer (no token verified, no
    audit row); Cloud Run's IAM answers HTML, a missing route 404."""
    _, smoke = _step(_deploy_steps(), "smoke")
    run = smoke["run"]
    assert '"${URL}/v1/config"' in run and "X-Forwarded-Authorization: smoke" in run
    assert '.detail == "missing_bearer"' in run and '"401"' in run


def test_a_post_promote_health_check_can_trigger_the_rollback():
    """TE6-004 (b): after the promote nothing called the live service, so the
    rollback could fire only when a gcloud describe failed."""
    steps = _deploy_steps()
    promote_at, _ = _step(steps, "promote")
    health_at, health = _step(steps, "post_promote_health")
    rollback_at = next(i for i, s in enumerate(steps) if s.get("name") == "Rollback traffic on post-promote failure")
    assert promote_at < health_at < rollback_at
    assert "continue-on-error" not in health
    assert "steps.service_url.outputs.url" in health["env"]["URL"]
    run = health["run"]
    assert '"${URL}/readyz"' in run and '"${URL}/v1/config"' in run and "exit 1" in run
    assert "failure() && steps.promote.outcome == 'success'" in steps[rollback_at]["if"]
    # Every step between the promote and the health check must not hide a
    # failure either, or the rollback never runs for it.
    assert all("continue-on-error" not in s for s in steps[promote_at:health_at])


def test_the_rollback_drill_fails_on_purpose_only_on_staging():
    """TE6-004 (c): the D5 revision-rollback drill (TD-220) needs a deploy whose
    post-promote check fails; never production."""
    yaml = pytest.importorskip("yaml")
    workflow = yaml.safe_load((_REPO / ".github" / "workflows" / "deploy-backend.yml").read_text(encoding="utf-8"))
    on = workflow.get("on") or workflow.get(True)
    drill = on["workflow_dispatch"]["inputs"]["rollback_drill"]
    assert drill["type"] == "boolean" and drill["default"] is False
    _, health = _step(_deploy_steps(), "post_promote_health")
    assert "github.event.inputs.rollback_drill" in health["env"]["DRILL"]
    assert '[ "$ENVIRONMENT" = "staging" ]' in health["run"]


def test_the_deploy_runs_the_secret_check_before_mounting():
    workflow = (_REPO / ".github" / "workflows" / "deploy-backend.yml").read_text(encoding="utf-8")
    check = workflow.index("scripts/check_secret_access.sh")
    assert check < workflow.index('ENTITLEMENT_SIGNING_KEY=${ENTITLEMENT_SECRET}:latest')
    assert "RUNTIME_SA: ${{ vars.SERVICE_ACCOUNT_EMAIL }}" in workflow
