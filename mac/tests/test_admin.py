from session_cases import credential_log_guard as credential_log_guard

import logging
import secrets
from unittest.mock import patch

import pytest

from atomic_relay.config import ConfigError, load_config
from atomic_relay.sessions.admin import AdminCredential


@pytest.fixture
def settings(tmp_path):
    return {
        "ATOMIC_RELAY_SESSIONS_FILE": "", "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
        "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(tmp_path / "admin.secret"),
    }


def test_private_secret_load_and_constant_time_compare(tmp_path, settings, caplog):
    secret = secrets.token_urlsafe(32)
    path = tmp_path / "admin.secret"
    path.write_text(secret + "\n")
    path.chmod(0o600)
    before = path.stat().st_mtime_ns
    config = load_config(environ=settings)
    with patch("atomic_relay.sessions.admin.secrets.compare_digest", wraps=secrets.compare_digest) as compare:
        accepted = config.admin_secret.compare(secret)
        assert accepted
        assert not config.admin_secret.compare(secrets.token_urlsafe(32))
        assert compare.call_count == 2
    header_matches = config.admin_secret.authorization() == "Bearer " + secret
    assert header_matches
    assert path.stat().st_mtime_ns == before
    assert secret not in repr(config) + caplog.text


@pytest.mark.parametrize("failure", ["missing", "directory", "group-readable", "other-readable", "empty", "short", "encoding", "multiline"])
def test_secret_startup_failures_are_safe_config_errors(tmp_path, settings, failure, caplog):
    path = tmp_path / "admin.secret"
    content = secrets.token_urlsafe(32)
    if failure == "directory":
        path.mkdir()
    elif failure != "missing":
        value = {"empty": " \n", "short": secrets.token_hex(5), "multiline": content + "\n" + content}.get(failure, content)
        path.write_bytes(b"\xff" * 32 if failure == "encoding" else value.encode())
        path.chmod({"group-readable": 0o640, "other-readable": 0o604}.get(failure, 0o600))
    with pytest.raises(ConfigError, match="component=config operation=admin-secret") as refused:
        load_config(environ=settings)
    assert content not in str(refused.value) + caplog.text
    assert content not in repr(refused.value)


def test_empty_setting_is_loud_and_never_authorizes(settings, caplog, monkeypatch):
    generated = secrets.token_urlsafe(32)
    monkeypatch.setattr("atomic_relay.sessions.admin.secrets.token_urlsafe", lambda size: generated)
    with caplog.at_level(logging.WARNING):
        config = load_config(environ={**settings, "ATOMIC_RELAY_ADMIN_SECRET_FILE": ""})
    assert [row.message for row in caplog.records] == [
        "component=config operation=admin-secret ephemeral; admin routes unreachable (isolated health/tests only)",
    ]
    accepted = config.admin_secret.compare(generated)
    assert not accepted
    assert not config.admin_secret.compare("")
    with pytest.raises(ValueError, match="isolated credential"):
        config.admin_secret.authorization()
    assert generated not in caplog.text


def test_secret_file_read_failure_preserves_cause_without_fallback(tmp_path, settings, monkeypatch):
    def denied(*args):
        raise PermissionError("synthetic file access refused")
    monkeypatch.setattr("atomic_relay.sessions.admin.os.open", denied)
    with pytest.raises(ConfigError, match="file unavailable") as error:
        load_config(environ=settings)
    assert isinstance(error.value.__cause__.__cause__, PermissionError)


def test_non_ascii_comparisons_are_safe_and_count_characters(tmp_path, settings):
    path = tmp_path / "admin.secret"
    path.write_text("é" * 16)
    path.chmod(0o600)
    with pytest.raises(ConfigError, match="32 characters"):
        load_config(environ=settings)
    assert not AdminCredential(secrets.token_urlsafe(32)).compare("é" * 32)
