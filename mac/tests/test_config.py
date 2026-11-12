from session_cases import credential_log_guard as credential_log_guard

import json
import stat
from pathlib import Path

import pytest

from atomic_relay.config import ConfigError, load_config


@pytest.fixture
def settings(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    return {
        "ATOMIC_RELAY_SESSIONS_FILE": "",
        "ATOMIC_RELAY_ADMIN_SECRET_FILE": "",
        "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "logs" / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
    }


def test_defaults_and_private_storage(settings):
    config = load_config(environ=settings)
    assert config.hosts == ("127.0.0.1",)
    assert config.phone_url is None
    assert config.port == 8767
    assert config.sessions_file is None
    assert config.trusted_proxies == frozenset()
    assert config.notification_allow == config.notification_deny == ()
    assert config.sessions_count == 0
    assert config.log_level == "INFO"
    assert config.media_max_bytes == 256 * 1024 * 1024
    assert config.media_ttl_seconds == 86400
    assert config.server_version == "2.3.0"
    assert stat.S_IMODE(config.media_dir.stat().st_mode) == 0o700
    assert config.log_file.is_file()


def test_env_file_and_process_precedence(settings, tmp_path):
    path = tmp_path / "relay.env"
    path.write_text('# comment\nATOMIC_RELAY_PORT="0"\nATOMIC_RELAY_HOST=::1\n'
                    'ATOMIC_RELAY_SERVER_VERSION=custom\nANDROID_BRIDGE_TIMEOUT=30\n')
    config = load_config(environ={**settings, "ATOMIC_RELAY_HOST": "127.0.0.1"})
    assert config.port == 0
    assert config.hosts == ("127.0.0.1",)
    assert config.server_version == "custom"


def test_explicit_env_file_and_unknown_settings(settings, tmp_path):
    with pytest.raises(FileNotFoundError):
        load_config(tmp_path / "missing.env", environ=settings)
    for environment in ({**settings, "ATOMIC_RELAY_UNKNOWN": "synthetic"},):
        with pytest.raises(ConfigError, match="unknown"):
            load_config(environ=environment)
    path = tmp_path / "explicit.env"
    path.write_text("ATOMIC_RELAY_UNKNOWN=synthetic\n")
    with pytest.raises(ConfigError, match="unknown"):
        load_config(path, environ=settings)


@pytest.mark.parametrize("line", ["no assignment", "BAD KEY=value", "ATOMIC_RELAY_HOST='unclosed"])
def test_invalid_env_file_reports_line_without_contents(settings, tmp_path, line):
    path = tmp_path / "explicit.env"
    path.write_text(line)
    with pytest.raises(ConfigError, match="line 1") as error:
        load_config(path, environ=settings)
    assert line not in str(error.value)


@pytest.mark.parametrize(("name", "value"), [
    ("PORT", "-1"), ("PORT", "65536"), ("PORT", "invalid"), ("PORT", ""),
    ("PORT", "１２"), ("PORT", "9" * 13), ("MEDIA_MAX_MB", "0"),
    ("MEDIA_TTL_SECONDS", "0"), ("LOG_LEVEL", "invalid"), ("LOG_FILE", ""),
    ("MEDIA_DIR", ""), ("MEDIA_ALLOWED_ROOTS", ":"), ("HOST", ""), ("SERVER_VERSION", ""),
])
def test_invalid_settings_fail(settings, name, value):
    with pytest.raises(ConfigError):
        load_config(environ={**settings, f"ATOMIC_RELAY_{name}": value})


def test_existing_sessions_are_counted_and_never_rewritten(settings, tmp_path):
    path = tmp_path / "sessions.json"
    contents = json.dumps({"version": 1, "sessions": [{}], "trusted_devices": []})
    path.write_text(contents)
    before = path.stat().st_mtime_ns
    config = load_config(environ={**settings, "ATOMIC_RELAY_SESSIONS_FILE": str(path)})
    assert config.sessions_count == 1
    assert path.read_text() == contents
    assert path.stat().st_mtime_ns == before


@pytest.mark.parametrize("contents", ["{invalid", "[]", '{"version":1,"sessions":[]}'])
def test_invalid_sessions_fail_without_rewriting(settings, tmp_path, contents):
    path = tmp_path / "sessions.json"
    path.write_text(contents)
    with pytest.raises((ConfigError, json.JSONDecodeError)):
        load_config(environ={**settings, "ATOMIC_RELAY_SESSIONS_FILE": str(path)})
    assert path.read_text() == contents


def test_missing_sessions_file_is_not_created(settings, tmp_path):
    path = tmp_path / "new-sessions.json"
    config = load_config(environ={**settings, "ATOMIC_RELAY_SESSIONS_FILE": str(path)})
    assert config.sessions_count == 0
    assert not path.exists()


def test_unreadable_env_file_propagates(settings, tmp_path, monkeypatch):
    path = tmp_path / "unreadable.env"
    def denied_read(self, **kwargs):
        raise PermissionError("synthetic env-file permission failure")
    monkeypatch.setattr(Path, "read_text", denied_read)
    with pytest.raises(PermissionError, match="synthetic"):
        load_config(path, environ=settings)


def test_unwritable_log_file_propagates(settings, monkeypatch):
    original = Path.open
    def denied_log(self, *args, **kwargs):
        if str(self) == settings["ATOMIC_RELAY_LOG_FILE"]:
            raise PermissionError("synthetic log permission failure")
        return original(self, *args, **kwargs)
    monkeypatch.setattr(Path, "open", denied_log)
    with pytest.raises(PermissionError, match="synthetic"):
        load_config(environ=settings)


def test_unusable_media_directory_fails(settings, tmp_path):
    path = tmp_path / "not-a-directory"
    path.write_text("synthetic")
    with pytest.raises(FileExistsError):
        load_config(environ={**settings, "ATOMIC_RELAY_MEDIA_DIR": str(path)})


def test_all_overrides_and_path_expansion(settings, tmp_path, monkeypatch):
    def expand(self):
        return tmp_path / str(self)[2:] if str(self).startswith("~/") else self
    monkeypatch.setattr(Path, "expanduser", expand)
    config = load_config(environ={**settings, "ATOMIC_RELAY_LOG_LEVEL": "debug",
        "ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS": "~/one:~/two", "ATOMIC_RELAY_MEDIA_MAX_MB": "1",
        "ATOMIC_RELAY_MEDIA_TTL_SECONDS": "2", "ATOMIC_RELAY_SESSIONS_FILE": "~/absent.json"})
    assert config.log_level == "DEBUG"
    assert config.media_allowed_roots == (tmp_path / "one", tmp_path / "two")
    assert config.sessions_file == tmp_path / "absent.json"
    assert config.media_max_bytes == 1024 * 1024
    assert config.media_ttl_seconds == 2


def test_trusted_proxies_parse_env_file_and_normalize_addresses(settings, tmp_path):
    path = tmp_path / "proxies.env"
    path.write_text("ATOMIC_RELAY_TRUSTED_PROXIES=127.0.0.1, 2001:0db8::1, ::1,127.0.0.1\n")
    assert load_config(path, environ=settings).trusted_proxies == frozenset({"127.0.0.1", "2001:db8::1", "::1"})
    assert load_config(path, environ={**settings, "ATOMIC_RELAY_TRUSTED_PROXIES": ""}).trusted_proxies == frozenset()


@pytest.mark.parametrize("value", ["private-invalid-ip", "127.0.0.1/8", "127.0.0.1,", ",::1", "localhost"])
def test_invalid_trusted_proxy_text_is_config_error_without_value(settings, value):
    with pytest.raises(ConfigError, match="ATOMIC_RELAY_TRUSTED_PROXIES") as error:
        load_config(environ={**settings, "ATOMIC_RELAY_TRUSTED_PROXIES": value})
    assert value not in str(error.value)


def test_notification_patterns_parse_env_file_whitespace_and_process_override(settings, tmp_path):
    path = tmp_path / "notifications.env"
    path.write_text("ATOMIC_RELAY_NOTIFY_ALLOW=com.example, org.allowed.*\nATOMIC_RELAY_NOTIFY_DENY=com.example.bank\n")
    config = load_config(path, environ=settings)
    assert config.notification_allow == ("com.example", "org.allowed.*")
    assert config.notification_deny == ("com.example.bank",)
    config = load_config(path, environ={**settings, "ATOMIC_RELAY_NOTIFY_ALLOW": " ", "ATOMIC_RELAY_NOTIFY_DENY": "other.*"})
    assert config.notification_allow == () and config.notification_deny == ("other.*",)


@pytest.mark.parametrize("name", ["ATOMIC_RELAY_NOTIFY_ALLOW", "ATOMIC_RELAY_NOTIFY_DENY"])
@pytest.mark.parametrize("value", ["*", ".*", "com.*.bank", "com.example*", "com/example", "com.example-1",
                                 "com.example?", "com example", "com.é", "com.example,", ",com.example", "com.example,,other"])
def test_invalid_notification_pattern_is_config_error_without_setting_value(settings, name, value):
    with pytest.raises(ConfigError, match=name) as error:
        load_config(environ={**settings, name: value})
    private_absent = value not in str(error.value) and value not in str(error.value.__cause__)
    assert private_absent


def test_explicit_host_list_is_normalized_deduplicated_and_ordered(settings):
    config = load_config(environ={**settings, "ATOMIC_RELAY_HOST": "127.0.0.1, 100.64.1.2, ::1,127.0.0.1"})
    assert config.hosts == ("127.0.0.1", "100.64.1.2", "::1")
    assert not hasattr(config, "host")


@pytest.mark.parametrize("value", ["0.0.0.0", "::", "100.64.1.2", "relay.local", "127.0.0.1,0.0.0.0",
                                 "127.0.0.1,", ",::1", "127.0.0.1,relay.local"])
def test_explicit_host_list_refuses_invalid_destinations(settings, value):
    with pytest.raises(ConfigError, match="ATOMIC_RELAY_HOST") as error:
        load_config(environ={**settings, "ATOMIC_RELAY_HOST": value})
    assert value not in str(error.value)


@pytest.mark.parametrize("value,expected", [
    ("wss://relay.example.com", "wss://relay.example.com"),
    ("ws://192.168.1.5:8767", "ws://192.168.1.5:8767"),
    ("HTTPS://Relay.Example.com:443", "wss://relay.example.com:443"),
    ("http://100.64.1.2", "ws://100.64.1.2"),
    ("ws://[::1]:8767", "ws://[::1]:8767"),
    ("ws://[fd00::1]", "ws://[fd00::1]"),
    ("ws://169.254.1.2", "ws://169.254.1.2"),
    ("", None),
])
def test_phone_url_accepts_and_normalizes_supported_addresses(settings, value, expected):
    config = load_config(environ={**settings, "ATOMIC_RELAY_PHONE_URL": value})
    assert config.phone_url == expected


@pytest.mark.parametrize("value", [
    "ws://relay.local", "ws://localhost", "ws://8.8.8.8", "ws://192.0.2.1", "ws://[2001:db8::1]",
    "wss://relay.example.com/path", "wss://relay.example.com/", "wss://relay.example.com?query",
    "wss://relay.example.com#fragment", "wss://user:password@relay", "ftp://relay.example.com",
    "wss://relay.example.com:0", "wss://relay.example.com:65536", "wss://relay.example.com:",
    "wss://relay.example.com:invalid", "wss://relay.example.com\\evil", "wss://relay.example.com\n",
])
def test_phone_url_refuses_unsafe_or_non_authority_urls(settings, value):
    with pytest.raises(ConfigError, match="ATOMIC_RELAY_PHONE_URL") as error:
        load_config(environ={**settings, "ATOMIC_RELAY_PHONE_URL": value})
    assert value not in str(error.value) + str(error.value.__cause__)
