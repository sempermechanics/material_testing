"""Config parsing + required-var validation (added with the startup-safety fix)."""
import importlib

import pytest

from app import config as config_module


def test_env_int_parses_valid(monkeypatch):
    monkeypatch.setenv("SOME_INT", "42")
    assert config_module._env_int("SOME_INT", "1") == 42


def test_env_int_uses_default_when_unset(monkeypatch):
    monkeypatch.delenv("SOME_INT", raising=False)
    assert config_module._env_int("SOME_INT", "7") == 7


def test_env_int_raises_clear_error_on_non_numeric(monkeypatch):
    monkeypatch.setenv("SOME_INT", "not-a-number")
    with pytest.raises(RuntimeError) as e:
        config_module._env_int("SOME_INT", "1")
    assert "SOME_INT" in str(e.value)


def test_missing_required_reports_absent_vars(monkeypatch):
    for var in ("GOOGLE_CLOUD_PROJECT", "SERVICE_ACCOUNT_EMAIL", "SHARED_DRIVE_ID"):
        monkeypatch.delenv(var, raising=False)
    # Re-import so the class body re-reads the (now-cleared) environment.
    reloaded = importlib.reload(config_module)
    missing = reloaded.settings.missing_required()
    assert set(missing) == {"GCP_PROJECT", "SERVICE_ACCOUNT_EMAIL", "SHARED_DRIVE_ID"}


def test_missing_required_empty_when_all_present(monkeypatch):
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "p")
    monkeypatch.setenv("SERVICE_ACCOUNT_EMAIL", "sa@p.iam.gserviceaccount.com")
    monkeypatch.setenv("SHARED_DRIVE_ID", "drive")
    reloaded = importlib.reload(config_module)
    assert reloaded.settings.missing_required() == []


def test_auto_approve_hd_is_lowercased(monkeypatch):
    monkeypatch.setenv("AUTO_APPROVE_HD", "Corp.COM")
    reloaded = importlib.reload(config_module)
    assert reloaded.settings.AUTO_APPROVE_HD == "corp.com"


@pytest.mark.parametrize(
    "raw",
    [
        "https://a.example https://b.example",
        "https://a.example,https://b.example",
        "https://a.example; https://b.example/",
        "  https://a.example\nhttps://b.example  ",
    ],
)
def test_console_origins_accept_any_delimiter(monkeypatch, raw):
    # The deploy action splits env_vars on commas, so the value is shipped
    # space-separated; commas and semicolons still parse for hand-set envs.
    monkeypatch.setenv("CONSOLE_ORIGINS", raw)
    reloaded = importlib.reload(config_module)
    assert reloaded.settings.CONSOLE_ORIGINS == ["https://a.example", "https://b.example"]


@pytest.mark.parametrize(
    "raw",
    [
        "ops@corp.com staff@corp.com",
        "ops@corp.com,staff@corp.com",
        "ops@corp.com; staff@corp.com",
        "  Ops@Corp.com\nstaff@corp.com  ",
    ],
)
def test_admin_emails_accept_any_delimiter(monkeypatch, raw):
    # Same reason as CONSOLE_ORIGINS: shipped space-separated because the
    # deploy action splits env_vars on commas, and a truncated operator list
    # locks the second operator out of the desk.
    monkeypatch.setenv("ADMIN_EMAILS", raw)
    reloaded = importlib.reload(config_module)
    assert reloaded.settings.ADMIN_EMAILS == {"ops@corp.com", "staff@corp.com"}


def test_admin_emails_default_to_nobody(monkeypatch):
    monkeypatch.delenv("ADMIN_EMAILS", raising=False)
    reloaded = importlib.reload(config_module)
    assert reloaded.settings.ADMIN_EMAILS == set()


def test_console_origins_default_lists_both_hosts(monkeypatch):
    monkeypatch.delenv("CONSOLE_ORIGINS", raising=False)
    reloaded = importlib.reload(config_module)
    assert reloaded.settings.CONSOLE_ORIGINS == [
        "https://app.sempermechanics.com",
        "https://indicvision-dic-app-auth.firebaseapp.com",
    ]
