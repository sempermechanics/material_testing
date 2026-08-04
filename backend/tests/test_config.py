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
