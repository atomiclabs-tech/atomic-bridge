"""Load relay settings and validate startup storage without changing sessions."""

import ipaddress
import json
import os
import re
import tempfile
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from atomic_relay import __version__
from atomic_relay.config_network import parse_hosts, parse_phone_url
from atomic_relay.notifications.filter import validate_patterns
from atomic_relay.sessions.admin import AdminCredential, AdminSecretError

DEFAULTS = {
    "ATOMIC_RELAY_HOST": "127.0.0.1",
    "ATOMIC_RELAY_PHONE_URL": "",
    "ATOMIC_RELAY_PORT": "8767",
    "ATOMIC_RELAY_SESSIONS_FILE": "~/.hermes/hermes-relay-sessions.json",
    "ATOMIC_RELAY_ADMIN_SECRET_FILE": "~/services/atomic-bridge/admin.secret",
    "ATOMIC_RELAY_TRUSTED_PROXIES": "",
    "ATOMIC_RELAY_NOTIFY_ALLOW": "",
    "ATOMIC_RELAY_NOTIFY_DENY": "",
    "ATOMIC_RELAY_LOG_FILE": "~/services/atomic-bridge/logs/relay.log",
    "ATOMIC_RELAY_LOG_LEVEL": "INFO",
    "ATOMIC_RELAY_MEDIA_DIR": "~/services/atomic-bridge/media",
    "ATOMIC_RELAY_MEDIA_MAX_MB": "256",
    "ATOMIC_RELAY_MEDIA_TTL_SECONDS": "86400",
    "ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS": "~/.hermes/workspace",
    "ATOMIC_RELAY_SERVER_VERSION": __version__,
}


class ConfigError(ValueError):
    """A relay setting or startup resource is invalid."""


@dataclass(frozen=True)
class Config:
    hosts: tuple[str, ...]
    phone_url: str | None
    port: int
    sessions_file: Path | None
    log_file: Path
    log_level: str
    media_dir: Path
    media_max_bytes: int
    media_ttl_seconds: int
    media_allowed_roots: tuple[Path, ...]
    server_version: str
    sessions_count: int
    admin_secret: AdminCredential
    trusted_proxies: frozenset[str]
    notification_allow: tuple[str, ...]
    notification_deny: tuple[str, ...]


def _read_env(path: Path) -> dict[str, str]:
    settings = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ConfigError(f"component=config operation=env-file invalid assignment at line {number}")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ConfigError(f"component=config operation=env-file invalid quoting at line {number}")
            value = value[1:-1]
        if key.startswith("ATOMIC_RELAY_"):
            settings[key] = value
    return settings


def _number(settings: Mapping[str, str], key: str, minimum: int) -> int:
    value = settings[key]
    if not value.isascii() or not value.isdecimal() or len(value) > 12:
        raise ConfigError(f"component=config operation=parse {key} must be an integer")
    parsed = int(value)
    if parsed < minimum:
        raise ConfigError(f"component=config operation=parse {key} must be at least {minimum}")
    return parsed


def _path(settings: Mapping[str, str], key: str) -> Path:
    if not settings[key].strip():
        raise ConfigError(f"component=config operation=parse {key} must not be empty")
    return Path(settings[key]).expanduser().absolute()


def _session_count(path: Path | None) -> int:
    if path is None or not path.exists():
        return 0
    with path.open(encoding="utf-8") as source:
        data = json.load(source)
    if (not isinstance(data, dict) or "version" not in data
            or not isinstance(data.get("sessions"), list)
            or not isinstance(data.get("trusted_devices"), list)):
        raise ConfigError("component=config operation=sessions invalid sessions file structure")
    return len(data["sessions"])


def _validate_storage(config: Config) -> None:
    config.log_file.parent.mkdir(parents=True, exist_ok=True)
    with config.log_file.open("a", encoding="utf-8"):
        pass
    config.media_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    config.media_dir.chmod(0o700)
    with tempfile.TemporaryFile(dir=config.media_dir):
        pass


def _admin_secret(settings: Mapping[str, str]) -> AdminCredential:
    try:
        return (AdminCredential.load(_path(settings, "ATOMIC_RELAY_ADMIN_SECRET_FILE"))
                if settings["ATOMIC_RELAY_ADMIN_SECRET_FILE"] else AdminCredential.ephemeral())
    except AdminSecretError as error:
        raise ConfigError(str(error)) from error


def _trusted_proxies(value: str) -> frozenset[str]:
    if not value.strip():
        return frozenset()
    try:
        return frozenset(str(ipaddress.ip_address(item.strip())) for item in value.split(","))
    except ValueError as error:
        raise ConfigError("component=config operation=parse ATOMIC_RELAY_TRUSTED_PROXIES must contain IP addresses") from error


def _notification_patterns(value: str, key: str) -> tuple[str, ...]:
    patterns = tuple(item.strip() for item in value.split(",")) if value.strip() else ()
    try:
        validate_patterns(patterns)
    except ValueError as error:
        raise ConfigError(f"component=config operation=parse {key} must contain valid notification package patterns") from error
    return patterns


def load_config(env_file: Path | None = None, *, environ: Mapping[str, str] | None = None) -> Config:
    """Read relay.env when present, then apply process overrides and validate storage."""
    path = Path("relay.env") if env_file is None else Path(env_file)
    from_file = _read_env(path) if env_file is not None or path.exists() else {}
    environment = os.environ if environ is None else environ
    supplied = {**from_file, **{k: v for k, v in environment.items() if k.startswith("ATOMIC_RELAY_")}}
    unknown = supplied.keys() - DEFAULTS.keys()
    if unknown:
        raise ConfigError("component=config operation=load unknown ATOMIC_RELAY_* setting")
    settings = {**DEFAULTS, **supplied}
    port = _number(settings, "ATOMIC_RELAY_PORT", 0)
    if port > 65535:
        raise ConfigError("component=config operation=parse ATOMIC_RELAY_PORT must be at most 65535")
    level = settings["ATOMIC_RELAY_LOG_LEVEL"].upper()
    if level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
        raise ConfigError("component=config operation=parse ATOMIC_RELAY_LOG_LEVEL is invalid")
    if not settings["ATOMIC_RELAY_SERVER_VERSION"].strip():
        raise ConfigError("component=config operation=parse server version must not be empty")
    try:
        hosts = parse_hosts(settings["ATOMIC_RELAY_HOST"])
        phone_url = parse_phone_url(settings["ATOMIC_RELAY_PHONE_URL"])
    except ValueError as error:
        raise ConfigError(str(error)) from error
    roots = settings["ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS"].split(os.pathsep)
    if any(not root.strip() for root in roots):
        raise ConfigError("component=config operation=parse media allowed roots must not be empty")
    sessions = (_path(settings, "ATOMIC_RELAY_SESSIONS_FILE")
                if settings["ATOMIC_RELAY_SESSIONS_FILE"] else None)
    config = Config(
        hosts=hosts, phone_url=phone_url, port=port, sessions_file=sessions,
        log_file=_path(settings, "ATOMIC_RELAY_LOG_FILE"), log_level=level,
        media_dir=_path(settings, "ATOMIC_RELAY_MEDIA_DIR"),
        media_max_bytes=_number(settings, "ATOMIC_RELAY_MEDIA_MAX_MB", 1) * 1024 * 1024,
        media_ttl_seconds=_number(settings, "ATOMIC_RELAY_MEDIA_TTL_SECONDS", 1),
        media_allowed_roots=tuple(Path(root).expanduser().absolute() for root in roots),
        server_version=settings["ATOMIC_RELAY_SERVER_VERSION"], sessions_count=_session_count(sessions),
        admin_secret=_admin_secret(settings),
        trusted_proxies=_trusted_proxies(settings["ATOMIC_RELAY_TRUSTED_PROXIES"]),
        notification_allow=_notification_patterns(settings["ATOMIC_RELAY_NOTIFY_ALLOW"], "ATOMIC_RELAY_NOTIFY_ALLOW"),
        notification_deny=_notification_patterns(settings["ATOMIC_RELAY_NOTIFY_DENY"], "ATOMIC_RELAY_NOTIFY_DENY"),
    )
    _validate_storage(config)
    return config
