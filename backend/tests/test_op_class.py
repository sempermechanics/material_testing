"""Unit tests for route → opClass metering helpers."""
from app import observability as obs


def test_classify_health():
    assert obs.classify_route("GET", "/healthz") == ("health", "/healthz")
    assert obs.classify_route("GET", "/readyz") == ("health", "/readyz")


def test_classify_attest_and_login():
    assert obs.classify_route("POST", "/v1/challenge")[0] == "attest"
    assert obs.classify_route("GET", "/v1/me")[0] == "login"
    assert obs.classify_route("POST", "/v1/devices/register")[0] == "login"
    assert obs.classify_route("DELETE", "/v1/me")[0] == "account"
    assert obs.classify_route("GET", "/v1/me/export")[0] == "account"
    assert obs.classify_route("GET", "/v1/config")[0] == "config"


def test_classify_backup_sync_restore():
    assert obs.classify_route("POST", "/v1/sessions")[0] == "backup"
    assert obs.classify_route("GET", "/v1/sessions")[0] == "sync"
    assert obs.classify_route("DELETE", "/v1/sessions/abcdef0123456789") == (
        "backup",
        "/v1/sessions/{id}",
    )
    assert obs.classify_route("GET", "/v1/sessions/abcdef0123456789/uploads") == (
        "backup",
        "/v1/sessions/{id}/uploads",
    )
    assert obs.classify_route("POST", "/v1/files/abcdef0123456789/complete") == (
        "backup",
        "/v1/files/{id}/complete",
    )
    assert obs.classify_route("GET", "/v1/sessions/abcdef0123456789/files") == (
        "restore",
        "/v1/sessions/{id}/files",
    )
    assert obs.classify_route("GET", "/v1/files/abcdef0123456789/content") == (
        "restore",
        "/v1/files/{id}/content",
    )
    assert obs.classify_route("POST", "/v1/tasks/provision-session")[0] == "backup"


def test_classify_admin_and_other():
    assert obs.classify_route("GET", "/v1/admin/users")[0] == "admin"
    assert obs.classify_route("GET", "/unknown")[0] == "other"


def test_metrics_counts():
    assert obs.metrics_counts({"frameCount": 12.0}, file_count=2) == {
        "fileCount": 2,
        "frameCount": 12,
    }
    assert obs.metrics_counts({"frameCount": True}, file_count=1) == {"fileCount": 1}
    assert obs.metrics_counts(None, file_count=3) == {"fileCount": 3}
